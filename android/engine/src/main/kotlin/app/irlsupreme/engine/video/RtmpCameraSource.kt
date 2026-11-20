package app.irlsupreme.engine.video

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import app.irlsupreme.engine.gl.GlUtil
import app.irlsupreme.engine.ingest.IngestClock
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * A camera that streams to the phone over RTMP (DJI Pocket 3, or anything else): H.264 from [app.irlsupreme.engine.ingest.RtmpIngestServer]
 * -> hardware decoder -> SurfaceTexture (OES), latched by the render thread exactly like [CameraSource].
 *
 * Decoded frames are released on [clock]'s schedule (camera time mapped to phone time plus a jitter allowance), so
 * Wi-Fi bursts don't turn into stutter and the audio (same clock) stays in sync. The decoder is rebuilt for every new
 * camera session or parameter-set change; a failure is a "camera lost" state, not an end.
 *
 * Construct and [release] on the render thread (it owns the texture); everything else is thread-safe.
 */
class RtmpCameraSource(val clock: IngestClock) {
    val texture = GlUtil.genTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
    private val st = SurfaceTexture(texture)
    private val surface = Surface(st)
    private val frameAvailable = AtomicBoolean(false)
    /** How smoothly decoded frames reach the stream (see [CadenceMeter]); arrivals are decoder releases. */
    val cadence = CadenceMeter(NOMINAL_INTERVAL_NS)
    /** SurfaceTexture transform; decoded frames are already upright. */
    val texMatrix = FloatArray(16)
    private val cbThread = HandlerThread("ingest-video-st").apply { start() }

    @Volatile var lastFrameNs = 0L
        private set
    /** Frames shown since the last session started. */
    @Volatile var framesShown = 0L
        private set
    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set
    /** Decoded frames dropped unshown because they were more than a frame late (this stream). */
    @Volatile var lateDrops = 0L
        private set
    /** Compressed frames waiting for the decoder (for the compositor: more are on their way). */
    @Volatile var queuedFrames = 0
        private set
    /**
     * Hold decoded frames until their presentation time (on by default). Off releases each one as soon as it's
     * decoded, as before, in case a decoder misbehaves with buffers held.
     */
    @Volatile var paceOutput = true
        /** System.nanoTime when [clock] last anchored (0: not since the session started). */
    @Volatile var anchoredAtNs = 0L
        private set

    private val lock = Object()
    @Volatile private var codec: MediaCodec? = null
    private var drain: Thread? = null
    private var session = 0
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    @Volatile private var waitingForKey = true

    private var windows = 0

    init {
        clock.onWindow = { if (windows++ < 15) Log.i(TAG, "camera clock window: tightest slack $it ms") }
        android.opengl.Matrix.setIdentityM(texMatrix, 0)
        st.setOnFrameAvailableListener({ cadence.onArrival(System.nanoTime()); frameAvailable.set(true) }, Handler(cbThread.looper))
    }

    /** Render thread: latch the newest decoded frame. Returns true when a new frame was latched. */
    fun update(nowNs: Long): Boolean {
        if (!frameAvailable.getAndSet(false)) return false
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        lastFrameNs = nowNs
        framesShown++
        cadence.onLatch(nowNs, st.timestamp, cadence.lastArrivalNs) // timestamp: the camera's PTS, in ns
        return true
    }

    fun onSession(newSession: Int): Unit = synchronized(lock) {
        session = newSession
        stopCodec()
        sps = null; pps = null
        clock.reset()
        framesShown = 0
        anchoredAtNs = 0L
        bFramesLogged = false
        oversizeLogged = false
        cadence.restartRun()
    }

    fun onConfig(s: Int, sps: ByteArray, pps: ByteArray): Unit = synchronized(lock) {
        if (s != session) return
        if (codec != null && sps.contentEquals(this.sps) && pps.contentEquals(this.pps)) return
        this.sps = sps; this.pps = pps
        stopCodec()
        startCodec()
    }

    /** A compressed frame waiting for its turn at the decoder: [feedNs] is when it goes in (see [RtmpFeedScheduler]). */
    private class Pending(val annexB: ByteArray, val ptsMs: Long, val key: Boolean, val feedNs: Long)

    private val pending = java.util.ArrayDeque<Pending>()
    private var feeder: Thread? = null

    /** A camera that doesn't send DTS: treated as having no B-frames (DTS = PTS), as before. */
    fun onFrame(s: Int, annexB: ByteArray, ptsMs: Long, keyframe: Boolean) = onFrame(s, annexB, ptsMs, ptsMs, keyframe)

    /**
     * Ingest thread: timestamp the frame on arrival (its slack against the clock is the real network slack) and
     * queue it. The jitter buffer holds *compressed* frames: holding decoded ones starved the hardware decoder of
     * output buffers, which stalled its input and dropped frames.
     *
     * The clock schedules by PTS (that's when the frame is shown, and what the camera audio follows); the decoder is
     * fed by DTS on that schedule ([RtmpFeedScheduler.feedDueNs]), so a stream with B-frames decodes in time.
     */
    fun onFrame(s: Int, annexB: ByteArray, dtsMs: Long, ptsMs: Long, keyframe: Boolean) {
        if (synchronized(lock) { s != session || codec == null }) return
        val before = clock.reanchors
        val wasAnchored = clock.isAnchored
        val now = System.nanoTime()
        val due = clock.onFrame(ptsMs, now)
        if (clock.reanchors != before) Log.i(TAG, "camera clock re-anchored (${clock.reanchors}), slack ${clock.lastReanchorSlackMs} ms")
        if (!wasAnchored && clock.isAnchored || clock.reanchors != before) anchoredAtNs = now
        if (ptsMs != dtsMs && !bFramesLogged) {
            bFramesLogged = true
            Log.i(TAG, "camera sends B-frames (composition offset ${ptsMs - dtsMs} ms); decoding by DTS")
        }
        val feedNs = RtmpFeedScheduler.feedDueNs(due, ptsMs, dtsMs, DECODE_LEAD_NS)
        synchronized(pending) {
            if (pending.size >= MAX_PENDING) { // seconds behind: drop the backlog, resume at the next keyframe
                pending.clear()
                waitingForKey = true
            }
            if (waitingForKey && !keyframe) return
            waitingForKey = false
            pending.addLast(Pending(annexB, ptsMs, keyframe, feedNs))
            queuedFrames = pending.size
            (pending as Object).notifyAll()
        }
    }

    @Volatile private var bFramesLogged = false
    @Volatile private var oversizeLogged = false

    /** Hands each frame to the decoder at its feed time ([DECODE_LEAD_NS] ahead of its decode slot). */
    private fun feedLoop(c: MediaCodec) {
        try {
            while (codec === c) {
                val next = synchronized(pending) {
                    var head = pending.peekFirst()
                    while (head == null && codec === c) { (pending as Object).wait(50); head = pending.peekFirst() }
                    head
                } ?: continue
                val wait = next.feedNs - System.nanoTime()
                if (wait > 0) { LockSupport.parkNanos(minOf(wait, 20_000_000L)); continue }
                val idx = c.dequeueInputBuffer(20_000)
                if (idx < 0) continue
                val buf: ByteBuffer = c.getInputBuffer(idx) ?: continue
                buf.clear()
                synchronized(pending) { pending.pollFirst(); queuedFrames = pending.size }
                if (next.annexB.size > buf.capacity()) {
                    // Frames after it reference it: skip to the next keyframe rather than decode garbage.
                    c.queueInputBuffer(idx, 0, 0, 0, 0)
                    synchronized(pending) { waitingForKey = true; pending.clear(); queuedFrames = 0 }
                    if (!oversizeLogged) {
                        oversizeLogged = true
                        Log.w(TAG, "camera frame of ${next.annexB.size} bytes is over the decoder's ${buf.capacity()}; waiting for a keyframe")
                    }
                    continue
                }
                buf.put(next.annexB)
                c.queueInputBuffer(idx, 0, next.annexB.size, next.ptsMs * 1000, if (next.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            }
        } catch (e: Exception) {
            if (codec === c) Log.w(TAG, "camera decoder input failed", e)
        }
    }

    private fun startCodec() {
        val sps = sps ?: return
        val pps = pps ?: return
        val c = runCatching { MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrElse {
            Log.e(TAG, "no H.264 decoder", it); return
        }
        try {
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                // A 1080p keyframe from a high-bitrate camera can exceed some decoders' default input buffer.
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
            }
            c.configure(f, surface, null, 0)
            c.start()
        } catch (e: Exception) {
            Log.e(TAG, "camera decoder configure failed", e)
            runCatching { c.release() }
            return
        }
        codec = c
        waitingForKey = true
        synchronized(pending) { pending.clear(); queuedFrames = 0 }
        drain = Thread({ drainLoop(c) }, "ingest-video-decode").apply { start() }
        feeder = Thread({ feedLoop(c) }, "ingest-video-feed").apply { start() }
        Log.i(TAG, "camera decoder ${c.name} started")
    }

    /**
     * Releases decoded frames at their mapped presentation time: each output buffer is held (at most
     * [OutputPacer.capacity]) until [IngestClock.toLocalNs] of its PTS, then released to the SurfaceTexture. During the
     * clock's warm-up there's no schedule yet, so frames go out as soon as they're decoded. A frame already more than
     * an interval late, with a newer one already held behind it, is dropped unshown and counted in [lateDrops].
     */
    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        val pacer = OutputPacer(MAX_HELD_OUTPUTS)
        var lastPtsUs = Long.MIN_VALUE
        var intervalNs = NOMINAL_INTERVAL_NS
        try {
            while (codec === c) { // not under [lock]: stopCodec holds it while joining this thread
                while (pacer.size > 0) {
                    val action = RtmpFeedScheduler.decide(System.nanoTime(), pacer.headDueNs, intervalNs, newerWaiting = pacer.size > 1)
                    if (action == RtmpFeedScheduler.Action.WAIT) break
                    if (action == RtmpFeedScheduler.Action.DROP) lateDrops++
                    c.releaseOutputBuffer(pacer.headIndex, action == RtmpFeedScheduler.Action.RENDER)
                    pacer.removeHead()
                }
                val untilHeadNs = if (pacer.size > 0) pacer.headDueNs - System.nanoTime() else Long.MAX_VALUE
                if (pacer.isFull) {
                    LockSupport.parkNanos(untilHeadNs.coerceIn(100_000L, 20_000_000L))
                    continue
                }
                val timeoutUs = (untilHeadNs / 1_000).coerceIn(0L, 20_000L)
                val idx = c.dequeueOutputBuffer(info, timeoutUs)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        // The visible (cropped) size: 1920x1080 video decodes into 1920x1088 buffers.
                        val hasCrop = f.containsKey("crop-right") && f.containsKey("crop-bottom")
                        width = if (hasCrop) f.getInteger("crop-right") - f.getInteger("crop-left", 0) + 1 else f.getInteger(MediaFormat.KEY_WIDTH)
                        height = if (hasCrop) f.getInteger("crop-bottom") - f.getInteger("crop-top", 0) + 1 else f.getInteger(MediaFormat.KEY_HEIGHT)
                    }
                    idx >= 0 && info.size <= 0 -> c.releaseOutputBuffer(idx, false)
                    idx >= 0 -> {
                        val pts = info.presentationTimeUs
                        if (lastPtsUs != Long.MIN_VALUE && pts > lastPtsUs) {
                            intervalNs = ((pts - lastPtsUs) * 1_000).coerceIn(MIN_INTERVAL_NS, MAX_INTERVAL_NS)
                        }
                        lastPtsUs = pts
                        val due = if (paceOutput) clock.toLocalNs(pts / 1_000) else null
                        pacer.add(idx, due ?: RtmpFeedScheduler.NOW)
                    }
                }
            }
        } catch (e: Exception) {
            if (codec === c) Log.w(TAG, "camera decoder output failed", e)
        } finally {
            // Hand back whatever was still held (the codec is stopped after this thread ends).
            while (pacer.size > 0) {
                runCatching { c.releaseOutputBuffer(pacer.headIndex, false) }
                pacer.removeHead()
            }
        }
    }

    private fun stopCodec() {
        val c = codec ?: return
        codec = null
        synchronized(pending) { (pending as Object).notifyAll() }
        feeder?.join(500)
        feeder = null
        drain?.join(500)
        drain = null
        runCatching { c.stop() }
        runCatching { c.release() }
    }

    fun close(): Unit = synchronized(lock) {
        session = -1
        stopCodec()
    }

    /** Render thread. */
    fun release() {
        close()
        cbThread.quitSafely()
        surface.release()
        st.release()
        GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
    }

    private companion object {
        const val TAG = "Engine"
        /** Hardware decode latency allowance: frames go in this far ahead of their presentation time. */
        const val DECODE_LEAD_NS = 40_000_000L
        /** ~4 s at 30 fps: beyond that the camera is hopelessly behind; skip to a keyframe. */
        const val MAX_PENDING = 120
        /** Decoded buffers held for their presentation time; more would starve the decoder. */
        const val MAX_HELD_OUTPUTS = 3
        /** 2 MiB, or a whole uncompressed 1080p frame if that's larger. */
        val MAX_INPUT_BYTES = maxOf(2 shl 20, 1920 * 1088 * 3 / 2)
        const val NOMINAL_INTERVAL_NS = 33_333_333L
        const val MIN_INTERVAL_NS = 8_000_000L
        const val MAX_INTERVAL_NS = 100_000_000L
    }
}
