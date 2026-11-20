package app.irlsupreme.engine.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import app.irlsupreme.engine.ingest.IngestClock
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The RTMP camera's audio (with a DJI Mic paired to the Pocket 3, that's the mic), decoded and played out on the same
 * [IngestClock] as its video, so [AudioPipeline] can take exactly one chunk of it per mic chunk: camera audio when it's
 * there, the phone's mic when it isn't (fallback with no gap, and no separate clock to drift).
 *
 * Decoded PCM is converted to the stream's rate and channel count ([CameraResampler], continuous across blocks) and
 * queued in a [CameraPlayout], which plays it as one continuous stream (a jitter buffer with a tiny drift-correcting
 * resample) rather than placing every decoded block at its own timestamp: that placement skipped or held samples at
 * nearly every block boundary, which crackled.
 *
 * The RTMP read thread never touches the decoder: [onFrame] queues the frame and returns, and a feeder thread hands
 * frames to the decoder as it takes them. A decoder that stalls can then never hold up the camera's video, which
 * arrives on the same connection.
 */
class CameraAudio(private val clock: IngestClock, private val outRate: Int, private val outChannels: Int) {
    private val playout = CameraPlayout(outRate, outChannels)
    private val queue = AacFrameQueue()
    private val lock = Object()
    @Volatile private var codec: MediaCodec? = null
    private var drain: Thread? = null
    private var feeder: Thread? = null
    private var session = 0
    private var inRate = 0
    private var inChannels = 0
    private var scratch = ShortArray(0) // capture thread only

    /**
     * Play the camera's audio during the clock's warm-up, on its provisional mapping, instead of waiting ~8 s for it
     * to anchor (the phone mic fills in meanwhile when this is off).
     */
    @Volatile var playDuringWarmup = true

    /** Times the camera's audio ran dry while playing, each a short faded silence. */
    val underruns: Int get() = playout.underruns
    /** Lost camera audio frames filled with faded silence of the same length. */
    val gapFills: Int get() = playout.gapFills
    /**
     * Compressed frames the decoder never got: it fell ~1 s behind, or refused a frame with an error (not Wi-Fi loss:
     * that's [gapFills]).
     */
    val decoderDrops: Int get() = queue.drops + inputErrors
    @Volatile private var inputErrors = 0
    /** Times the playout aligned to the clock again (the clock or the camera's timeline jumped). */
    val resyncs: Int get() = playout.resyncs
    /** Camera audio in hand at the last chunk (ms). */
    val bufferMs: Int get() = (playout.depthNs / 1_000_000).toInt()
    /** The drift-correcting resample at the last chunk (ppm; +: playing the camera faster). */
    val ratePpm: Int get() = playout.ratePpm
    /** Smoothed audio-vs-picture offset (ms; +: audio early, -: late). */
    val syncErrorMs: Int get() = (playout.syncErrorNs / 1_000_000).toInt()
    /** The camera's announced AAC sample rate (0 before its config): anything but the stream's rate is converted. */
    val cameraRate: Int get() = inRate

    fun onSession(s: Int): Unit = synchronized(lock) {
        session = s
        stopCodec()
        playout.reset()
    }

    fun onConfig(s: Int, asc: ByteArray): Unit = synchronized(lock) {
        if (s != session) return
        val cfg = parseAsc(asc)
        if (cfg == null) {
            Log.w(TAG, "camera audio: unreadable AudioSpecificConfig ${hex(asc)}")
            return
        }
        val ch = cfg.channels.coerceIn(1, 2)
        if (codec != null && cfg.rate == inRate && ch == inChannels) return
        stopCodec()
        inRate = cfg.rate; inChannels = ch
        val c = runCatching { MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC) }.getOrNull() ?: return
        try {
            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, inRate, inChannels).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(asc))
                setInteger(MediaFormat.KEY_IS_ADTS, 0)
            }
            c.configure(f, null, null, 0)
            c.start()
        } catch (e: Exception) {
            Log.e(TAG, "camera audio decoder failed", e)
            runCatching { c.release() }
            return
        }
        codec = c
        val r = inRate
        val n = inChannels
        drain = Thread({ drainLoop(c, r, n) }, "ingest-audio-decode").apply { start() }
        feeder = Thread({ feedLoop(c) }, "ingest-audio-feed").apply { start() }
        // What the camera announces (the Pocket 3 is expected to send 48 kHz). Whether conversion runs is the decoder's
        // say, logged when its output format arrives: with HE-AAC (SBR) it decodes at twice the announced rate.
        val sbr = cfg.objectType == AOT_SBR || cfg.objectType == AOT_PS
        Log.i(TAG, "camera audio: announced AAC $inRate Hz, $inChannels ch (object type ${cfg.objectType}" +
            (if (sbr) ", SBR: decodes at twice that" else "") + ", ASC ${hex(asc)})")
    }

    /** RTMP read thread: queue the frame for the decoder and return at once. */
    fun onFrame(s: Int, aac: ByteArray, ptsMs: Long): Unit = synchronized(lock) {
        // Under [lock] so a frame from an old session can't slip in after [onSession] cleared the queue.
        if (s == session && codec != null) queue.offer(aac, ptsMs)
    }

    private fun feedLoop(c: MediaCodec) {
        var errors = 0
        feedDecoder(queue, CodecInput(c), { codec === c }) { e ->
            inputErrors++
            // The first per codec in full, then a count now and then: a codec that keeps failing mustn't flood the log.
            if (errors++ == 0) Log.w(TAG, "camera audio decoder refused a frame", e)
            else if (errors % 100 == 0) Log.w(TAG, "camera audio decoder refused $errors frames")
        }
    }

    /**
     * Decoder thread. [ascRate]/[ascChannels] are what the camera announced; the decoder's own output format wins when
     * it says otherwise (HE-AAC doubles the rate, parametric stereo turns mono into stereo, some decoders upmix), since
     * reading stereo as mono would play everything at half speed.
     */
    private fun drainLoop(c: MediaCodec, ascRate: Int, ascChannels: Int) {
        val info = MediaCodec.BufferInfo()
        // Per codec, so a new codec (new session or format) always starts the conversion afresh.
        val resampler = CameraResampler(outRate, outChannels).apply { configure(ascRate, ascChannels) }
        var pcm = ShortArray(0)
        try {
            while (codec === c) {
                val idx = c.dequeueOutputBuffer(info, 20_000)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat
                    val rate = runCatching { f.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(resampler.inRate)
                    val ch = runCatching { f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(resampler.inChannels)
                    resampler.configure(rate, ch)
                    Log.i(TAG, "camera audio decodes to $rate Hz, ${resampler.inChannels} ch" +
                        if (resampler.converting) ", converting to $outRate Hz" else ", no rate conversion")
                    continue
                }
                if (idx < 0) continue
                val buf = c.getOutputBuffer(idx)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset).limit(info.offset + info.size)
                    val samples = info.size / 2
                    if (pcm.size < samples) pcm = ShortArray(samples)
                    buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm, 0, samples)
                    resampler.process(pcm, samples / resampler.inChannels, info.presentationTimeUs * 1000)
                    if (resampler.outFrames > 0) playout.push(resampler.outPtsNs, resampler.out, resampler.outFrames)
                }
                c.releaseOutputBuffer(idx, false)
            }
        } catch (e: Exception) {
            if (codec === c) Log.w(TAG, "camera audio decoder output failed", e)
        }
    }

    /**
     * Fills [out] (16-bit little-endian interleaved, stream format) with [frames] frames of camera audio for the mic
     * chunk whose first sample is at local time [startNs]. Always written: silence (faded in and out, never a hard cut)
     * where the camera has none. Returns true when the camera covered most of the chunk, for the caller's choice of
     * camera or phone mic. Capture thread.
     */
    fun fill(startNs: Long, frames: Int, out: ByteArray): Boolean {
        if (scratch.size < frames * outChannels) scratch = ShortArray(frames * outChannels)
        val tmp = scratch
        // The camera moment due at the start of this chunk: the clock maps camera ms to local ns with slope 1, so
        // its offset at camera time 0 converts any local time. Null (nothing plays) until the video gives it one.
        val zero = when {
            codec == null -> null
            playDuringWarmup -> clock.toLocalNsProvisional(0)
            else -> clock.toLocalNs(0)
        }
        val played = playout.pull(tmp, frames, zero?.let { startNs - it })
        for (i in 0 until frames * outChannels) {
            out[2 * i] = tmp[i].toByte()
            out[2 * i + 1] = (tmp[i].toInt() shr 8).toByte()
        }
        return played >= frames * MIN_COVERAGE
    }

    private fun stopCodec() {
        val c = codec ?: return
        codec = null
        feeder?.join(300)
        feeder = null
        drain?.join(300)
        drain = null
        queue.clear()
        runCatching { c.stop() }
        runCatching { c.release() }
    }

    fun close(): Unit = synchronized(lock) {
        session = -1
        stopCodec()
        queue.clear()
        playout.reset()
    }

    /** A MediaCodec's input side for the feeder. */
    private class CodecInput(private val c: MediaCodec) : DecoderInput {
        override fun dequeueInputBuffer(timeoutUs: Long): Int = c.dequeueInputBuffer(timeoutUs)
        override fun queue(index: Int, data: ByteArray, ptsUs: Long) {
            val buf = c.getInputBuffer(index)
            if (buf == null || data.size > buf.capacity()) { c.queueInputBuffer(index, 0, 0, ptsUs, 0); return }
            buf.clear()
            buf.put(data)
            c.queueInputBuffer(index, 0, data.size, ptsUs, 0)
        }
    }

    /** What an AAC AudioSpecificConfig says about the stream. */
    internal class Asc(val objectType: Int, val rate: Int, val channels: Int)

    internal companion object {
        const val TAG = "Engine"
        const val MIN_COVERAGE = 0.9
        /** AAC object types that carry SBR (HE-AAC v1) and parametric stereo (HE-AAC v2). */
        const val AOT_SBR = 5
        const val AOT_PS = 29
        val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

        /**
         * Reads the object type, sample rate and channel configuration from an AudioSpecificConfig (ISO 14496-3),
         * including the escaped object type and the explicit 24-bit rate. Null when it can't be read.
         */
        fun parseAsc(asc: ByteArray): Asc? {
            var bit = 0
            fun read(n: Int): Int? {
                var v = 0
                repeat(n) {
                    if (bit >= asc.size * 8) return null
                    v = (v shl 1) or ((asc[bit ushr 3].toInt() ushr (7 - (bit and 7))) and 1)
                    bit++
                }
                return v
            }
            var aot = read(5) ?: return null
            if (aot == 31) aot = 32 + (read(6) ?: return null)
            val freqIdx = read(4) ?: return null
            val rate = if (freqIdx == 15) read(24) ?: return null else RATES.getOrNull(freqIdx) ?: return null
            val ch = read(4) ?: return null
            if (rate <= 0) return null
            return Asc(aot, rate, ch)
        }

        private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    }
}
