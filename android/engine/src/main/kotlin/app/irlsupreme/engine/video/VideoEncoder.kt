package app.irlsupreme.engine.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaFormat
import android.os.Bundle
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

    /**
     * Output bytes per second, measured over a sliding ~2 s window. ABR must steer on this, not the target: CBR
     * undershoots the target on simple scenes.
     */
    @Volatile var measuredBitrate = 0L
        private set
    private var windowStartNs = 0L
    private var windowBytes = 0L

    init {
        // A codec that fails half-way (configure rejects the size, start fails) still holds a hardware slot: give it
        // back before rethrowing, or every failed recovery attempt leaks one until none are left.
        var surface: Surface? = null
        try {
            val cbr = configureAvc(codec, width, height, fps, bitrate, keyframeIntervalSec)
            surface = codec.createInputSurface()
            codec.start()
            inputSurface = surface
            drain = Thread(::drainLoop, "engine-venc-drain").apply { start() }
            Log.i(TAG, "encoder ${codec.name} ${width}x$height@$fps ${bitrate / 1000}kbps cbr=$cbr")
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
                        if (sps != null && pps != null) sink.onVideoConfig(sps, pps)
                    }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (buf != null && !config && info.size > 0) {
                            buf.position(info.offset).limit(info.offset + info.size)
                            sink.onVideoFrame(buf, info)
                            account(info.size)
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

    private fun account(bytes: Int) {
        val now = System.nanoTime()
        lastOutputNs = now
        if (windowStartNs == 0L) windowStartNs = now
        windowBytes += bytes
        val elapsed = now - windowStartNs
        if (elapsed >= 2_000_000_000L) {
            measuredBitrate = windowBytes * 8 * 1_000_000_000L / elapsed
            windowStartNs = now
            windowBytes = 0
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
         * transcoding. Returns whether CBR is in use.
         */
        fun configureAvc(codec: MediaCodec, width: Int, height: Int, fps: Int, bitrate: Int, keyframeIntervalSec: Int): Boolean {
            val caps = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val cbr = caps.encoderCapabilities?.isBitrateModeSupported(EncoderCapabilities.BITRATE_MODE_CBR) == true
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeIntervalSec)
                setInteger(MediaFormat.KEY_BITRATE_MODE, if (cbr) EncoderCapabilities.BITRATE_MODE_CBR else EncoderCapabilities.BITRATE_MODE_VBR)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
                // No KEY_REPEAT_PREVIOUS_FRAME_AFTER: the compositor renders every tick, and encoder-inserted repeats
                // produced a duplicate timestamp in testing.
                setInteger(MediaFormat.KEY_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            }
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                // Some encoders reject profile/level hints; fall back to defaults.
                format.removeKey(MediaFormat.KEY_PROFILE); format.removeKey(MediaFormat.KEY_LEVEL)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            return cbr
        }
    }
}
