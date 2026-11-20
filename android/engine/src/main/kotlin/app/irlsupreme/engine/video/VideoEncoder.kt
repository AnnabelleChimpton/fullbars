package app.irlsupreme.engine.video

import android.media.MediaCodec
import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaFormat
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/** Receives encoder output. Called on the encoder's drain thread; buffers are only valid during the call. */
interface VideoSink {
    fun onVideoConfig(sps: ByteBuffer, pps: ByteBuffer)
    fun onVideoFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
}

/**
 * One H.264 MediaCodec instance fed through an input Surface, with its own drain thread.
 *
 * Lifecycle rules learned on real phones:
 *  - [release] stops and joins the drain thread BEFORE stopping/releasing the codec (otherwise the drain thread hits
 *    "codec is released already").
 *  - Any drain exception marks [failure]; the compositor then swaps in a new instance (recovery takes ~250 ms).
 *  - A constructor that throws has already released everything it built.
 */
class VideoEncoder(
    val width: Int,
    val height: Int,
    val fps: Int,
    bitrate: Int,
    keyframeIntervalSec: Int,
    private val sink: VideoSink,
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val inputSurface: Surface
    @Volatile private var running = true
    private val drain: Thread

    @Volatile var failure: String? = null
        private set
    @Volatile var lastOutputNs = 0L
        private set
    @Volatile var createdNs = System.nanoTime()
        private set

    /** Output numbers (rate, fps, IDR size and spacing), updated per frame on the drain thread. */
    val meter = EncoderMeter()

    /**
     * Output bits per second over a 1 s sliding window (0 until the first full second). ABR must steer on this, not
     * the target: CBR undershoots the target on simple scenes.
     */
    val measuredBitrate: Long get() = meter.bitsPerSecond

    /**
     * Which encoder this is and what it really produces, e.g. "c2.qti.avc.encoder High@4.1 CBR". The profile and
     * level fill in once the encoder hands over its SPS.
     */
    @Volatile var info: EncoderInfo
        private set

    init {
        // A codec that fails half-way (configure rejects the size, start fails) still holds a hardware slot: give it
        // back before rethrowing, or every failed recovery attempt leaks one until none are left.
        var surface: Surface? = null
        try {
            info = configureAvc(codec, width, height, fps, bitrate, keyframeIntervalSec)
            surface = codec.createInputSurface()
            codec.start()
            inputSurface = surface
            drain = Thread(::drainLoop, "engine-venc-drain").apply { start() }
            Log.i(TAG, "encoder ${codec.name} ${width}x$height@$fps ${bitrate / 1000}kbps cbr=${info.cbr} level=${info.requestedLevel} colour=${info.colour} fallback=${info.fallback}")
        } catch (t: Throwable) {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            surface?.release()
            throw t
        }
    }

    fun setBitrate(bps: Int) = runCatching {
        codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) })
    }

    /** Honoured on the very next frame. */
    fun requestKeyframe() = runCatching {
        codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
    }

    private fun drainLoop() {
        // Above ordinary app threads (the WebView, Compose, GC) so output keeps flowing when the phone is busy; a
        // late drain backs up the encoder and shows as uneven frame timing downstream. Same level as the UI's display
        // work, below audio.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val idx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        val sps = f.getByteBuffer("csd-0")
                        val pps = f.getByteBuffer("csd-1")
                        if (sps != null) {
                            this.info = this.info.copy(sps = SpsInfo.parse(sps))
                            Log.i(TAG, "encoder ${this.info.describe()}")
                        }
                        if (sps != null && pps != null) sink.onVideoConfig(sps, pps)
                    }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (buf != null && !config && info.size > 0) {
                            buf.position(info.offset).limit(info.offset + info.size)
                            sink.onVideoFrame(buf, info)
                            lastOutputNs = System.nanoTime()
                            meter.onFrame(info.presentationTimeUs, info.size, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                        }
                        codec.releaseOutputBuffer(idx, false)
                    }
                }
            }
        } catch (e: Exception) {
            if (running) {
                failure = e.toString()
                Log.e(TAG, "encoder drain failed", e)
            }
        }
    }

    /** Marks the instance failed from outside (watchdog). */
    fun fail(reason: String) {
        if (failure == null) failure = reason
    }

    /** Stop the drain thread first, then the codec (order matters). Safe after a failure. */
    fun release() {
        running = false
        drain.join(1_000)
        runCatching { codec.stop() }
        runCatching { codec.release() }
        inputSurface.release()
    }

    companion object {
        private const val TAG = "Engine"

        /**
         * Configures [codec] for the live stream. The phone's BRB slate is encoded through this too (SlateEncoder):
         * identical settings give byte-identical SPS/PPS, which is what lets the server splice it in without
         * transcoding. The settings themselves are in [AvcSettings]. Returns what was set up; its SPS is still unknown.
         */
        fun configureAvc(codec: MediaCodec, width: Int, height: Int, fps: Int, bitrate: Int, keyframeIntervalSec: Int): EncoderInfo {
            val caps = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val cbr = caps.encoderCapabilities?.isBitrateModeSupported(EncoderCapabilities.BITRATE_MODE_CBR) == true
            val keys = AvcSettings.keys(width, height, fps, bitrate, keyframeIntervalSec, cbr)
            val level = AvcSettings.levelName(AvcSettings.level(width, height, fps))
            // Some encoders reject hints they don't support. Retry first without the colour description (so an encoder
            // that only dislikes that keeps High at the right level), then without profile and level too. The order
            // is fixed, so the slate encoder ends up with the same format and the same SPS as the live one.
            try {
                codec.configure(format(width, height, keys), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return EncoderInfo(codec.name, cbr, fallback = false, requestedLevel = level)
            } catch (e: Exception) {
                Log.w(TAG, "encoder ${codec.name} rejected the format, retrying without the colour description", e)
            }
            try {
                codec.configure(format(width, height, AvcSettings.withoutColour(keys)), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return EncoderInfo(codec.name, cbr, fallback = false, requestedLevel = level, colour = false)
            } catch (e: Exception) {
                // Logged, because the stream then runs at the encoder's default profile, often Baseline.
                Log.w(TAG, "encoder ${codec.name} rejected profile/level hints too, retrying with its defaults", e)
            }
            codec.configure(format(width, height, AvcSettings.fallback(keys)), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            return EncoderInfo(codec.name, cbr, fallback = true, requestedLevel = level)
        }

        private fun format(width: Int, height: Int, keys: List<Pair<String, Int>>): MediaFormat =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                for ((k, v) in keys) setInteger(k, v)
            }
    }
}
