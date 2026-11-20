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
        st.setOnFrameAvailableListener({ frameAvailable.set(true) }, Handler(cbThread.looper))
    }

    /** Render thread: latch the newest decoded frame. Returns true when a new frame was latched. */
    fun update(nowNs: Long): Boolean {
        if (!frameAvailable.getAndSet(false)) return false
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        lastFrameNs = nowNs
        framesShown++
        return true
    }

    fun onSession(newSession: Int): Unit = synchronized(lock) {
        session = newSession
        stopCodec()
        sps = null; pps = null
        clock.reset()
        framesShown = 0
    }

    fun onConfig(s: Int, sps: ByteArray, pps: ByteArray): Unit = synchronized(lock) {
        if (s != session) return
        if (codec != null && sps.contentEquals(this.sps) && pps.contentEquals(this.pps)) return
        this.sps = sps; this.pps = pps
        stopCodec()
        startCodec()
    }

    /** A compressed frame waiting for its turn at the decoder. */
    private class Pending(val annexB: ByteArray, val ptsMs: Long, val key: Boolean, val dueNs: Long)

    private val pending = java.util.ArrayDeque<Pending>()
    private var feeder: Thread? = null

    /**
     * Ingest thread: timestamp the frame on arrival (its slack against the clock is the real network slack) and
     * queue it. The jitter buffer holds *compressed* frames: holding decoded ones starved the hardware decoder of
     * output buffers, which stalled its input and dropped frames.
     */
    fun onFrame(s: Int, annexB: ByteArray, ptsMs: Long, keyframe: Boolean) {
        if (synchronized(lock) { s != session || codec == null }) return
        val before = clock.reanchors
        val due = clock.onFrame(ptsMs, System.nanoTime())
        if (clock.reanchors != before) Log.i(TAG, "camera clock re-anchored (${clock.reanchors}), slack ${clock.lastReanchorSlackMs} ms")
        synchronized(pending) {
            if (pending.size >= MAX_PENDING) { // seconds behind: drop the backlog, resume at the next keyframe
                pending.clear()
                waitingForKey = true
            }
            if (waitingForKey && !keyframe) return
            waitingForKey = false
            pending.addLast(Pending(annexB, ptsMs, keyframe, due))
            (pending as Object).notifyAll()
        }
    }

    /** Hands each frame to the decoder [DECODE_LEAD_NS] before it is due; the decoder then outputs right away. */
    private fun feedLoop(c: MediaCodec) {
        try {
            while (codec === c) {
                val next = synchronized(pending) {
                    var head = pending.peekFirst()
                    while (head == null && codec === c) { (pending as Object).wait(50); head = pending.peekFirst() }
                    head
                } ?: continue
                val wait = next.dueNs - DECODE_LEAD_NS - System.nanoTime()
                if (wait > 0) { LockSupport.parkNanos(minOf(wait, 20_000_000L)); continue }
                val idx = c.dequeueInputBuffer(20_000)
                if (idx < 0) continue
                val buf: ByteBuffer = c.getInputBuffer(idx) ?: continue
                buf.clear()
                synchronized(pending) { pending.pollFirst() }
                if (next.annexB.size > buf.capacity()) { c.queueInputBuffer(idx, 0, 0, 0, 0); continue }
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
        synchronized(pending) { pending.clear() }
        drain = Thread({ drainLoop(c) }, "ingest-video-decode").apply { start() }
        feeder = Thread({ feedLoop(c) }, "ingest-video-feed").apply { start() }
        Log.i(TAG, "camera decoder ${c.name} started")
    }

    /** Releases decoded frames at their mapped presentation time. */
    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (codec === c) { // not under [lock]: stopCodec holds it while joining this thread
                val idx = c.dequeueOutputBuffer(info, 20_000)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        // The visible (cropped) size: 1920x1080 video decodes into 1920x1088 buffers.
                        val hasCrop = f.containsKey("crop-right") && f.containsKey("crop-bottom")
                        width = if (hasCrop) f.getInteger("crop-right") - f.getInteger("crop-left", 0) + 1 else f.getInteger(MediaFormat.KEY_WIDTH)
                        height = if (hasCrop) f.getInteger("crop-bottom") - f.getInteger("crop-top", 0) + 1 else f.getInteger(MediaFormat.KEY_HEIGHT)
                    }
                    idx >= 0 -> c.releaseOutputBuffer(idx, info.size > 0) // already paced at the input
                }
            }
        } catch (e: Exception) {
            if (codec === c) Log.w(TAG, "camera decoder output failed", e)
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
    }
}
