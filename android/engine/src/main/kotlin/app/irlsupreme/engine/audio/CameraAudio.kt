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
 * Decoded PCM is converted to the stream's rate and channel count and queued in a [CameraPlayout], which plays it as
 * one continuous stream (a jitter buffer with a tiny drift-correcting resample) rather than placing every decoded
 * block at its own timestamp: that placement skipped or held samples at nearly every block boundary, which crackled.
 */
class CameraAudio(private val clock: IngestClock, private val outRate: Int, private val outChannels: Int) {
    private val playout = CameraPlayout(outRate, outChannels)
    private val lock = Object()
    @Volatile private var codec: MediaCodec? = null
    private var drain: Thread? = null
    private var session = 0
    private var inRate = 0
    private var inChannels = 0
    private var scratch = ShortArray(0) // capture thread only

    /** Times the camera's audio ran dry while playing, each a short faded silence. */
    val underruns: Int get() = playout.underruns
    /** Lost camera audio frames filled with faded silence of the same length. */
    val gapFills: Int get() = playout.gapFills
    /** Times the playout aligned to the clock again (the clock or the camera's timeline jumped). */
    val resyncs: Int get() = playout.resyncs
    /** Camera audio in hand at the last chunk (ms). */
    val bufferMs: Int get() = (playout.depthNs / 1_000_000).toInt()
    /** The drift-correcting resample at the last chunk (ppm; +: playing the camera faster). */
    val ratePpm: Int get() = playout.ratePpm
    /** Smoothed audio-vs-picture offset (ms; +: audio early, -: late). */
    val syncErrorMs: Int get() = (playout.syncErrorNs / 1_000_000).toInt()

    fun onSession(s: Int): Unit = synchronized(lock) {
        session = s
        stopCodec()
        playout.reset()
    }

    fun onConfig(s: Int, asc: ByteArray): Unit = synchronized(lock) {
        if (s != session || asc.size < 2) return
        val freqIdx = ((asc[0].toInt() and 0x07) shl 1) or ((asc[1].toInt() and 0x80) ushr 7)
        val ch = (asc[1].toInt() and 0x78) ushr 3
        val rate = RATES.getOrNull(freqIdx) ?: return
        if (codec != null && rate == inRate && ch == inChannels) return
        stopCodec()
        inRate = rate; inChannels = ch.coerceIn(1, 2)
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
        Log.i(TAG, "camera audio: AAC $inRate Hz, $inChannels ch")
    }

    fun onFrame(s: Int, aac: ByteArray, ptsMs: Long) {
        val c = synchronized(lock) { if (s == session) codec else null } ?: return
        try {
            val idx = c.dequeueInputBuffer(20_000)
            if (idx < 0) return
            val buf = c.getInputBuffer(idx) ?: return
            buf.clear()
            buf.put(aac)
            c.queueInputBuffer(idx, 0, aac.size, ptsMs * 1000, 0)
        } catch (e: Exception) {
            Log.w(TAG, "camera audio decoder input failed", e)
        }
    }

    /**
     * Decoder thread. [ascRate]/[ascChannels] are what the camera announced; the decoder's own output format wins when
     * it says otherwise (HE-AAC doubles the rate, parametric stereo turns mono into stereo, some decoders upmix), since
     * reading stereo as mono would play everything at half speed.
     */
    private fun drainLoop(c: MediaCodec, ascRate: Int, ascChannels: Int) {
        val info = MediaCodec.BufferInfo()
        var rate = ascRate
        var ch = ascChannels
        try {
            while (codec === c) {
                val idx = c.dequeueOutputBuffer(info, 20_000)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat
                    rate = runCatching { f.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(rate)
                    ch = runCatching { f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(ch).coerceIn(1, 2)
                    Log.i(TAG, "camera audio decodes to $rate Hz, $ch ch")
                    continue
                }
                if (idx < 0) continue
                val buf = c.getOutputBuffer(idx)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset).limit(info.offset + info.size)
                    val pcm = ShortArray(info.size / 2)
                    buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
                    playout.push(info.presentationTimeUs * 1000, convert(pcm, rate, ch))
                }
                c.releaseOutputBuffer(idx, false)
            }
        } catch (e: Exception) {
            if (codec === c) Log.w(TAG, "camera audio decoder output failed", e)
        }
    }

    /** Camera rate/channels -> stream rate/channels (linear resampling; usually both are already 48 kHz). */
    private fun convert(pcm: ShortArray, inRate: Int, inCh: Int): ShortArray {
        val inFrames = pcm.size / inCh
        val outFrames = if (inRate == outRate) inFrames else (inFrames.toLong() * outRate / inRate).toInt()
        val out = ShortArray(outFrames * outChannels)
        for (o in 0 until outFrames) {
            val pos = if (inRate == outRate) o.toDouble() else o.toDouble() * inRate / outRate
            val i0 = pos.toInt().coerceAtMost(inFrames - 1)
            val i1 = (i0 + 1).coerceAtMost(inFrames - 1)
            val frac = pos - i0
            for (ch in 0 until outChannels) {
                val src = if (inCh == 1) 0 else if (outChannels == 1) -1 else ch
                fun sample(i: Int): Double = if (src >= 0) pcm[i * inCh + src].toDouble()
                    else (pcm[i * inCh].toDouble() + pcm[i * inCh + 1]) / 2
                out[o * outChannels + ch] = (sample(i0) * (1 - frac) + sample(i1) * frac).toInt().toShort()
            }
        }
        return out
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
        // its offset at camera time 0 converts any local time. Null (nothing plays) until the video anchors it.
        val target = if (codec == null) null else clock.toLocalNs(0)?.let { startNs - it }
        val played = playout.pull(tmp, frames, target)
        for (i in 0 until frames * outChannels) {
            out[2 * i] = tmp[i].toByte()
            out[2 * i + 1] = (tmp[i].toInt() shr 8).toByte()
        }
        return played >= frames * MIN_COVERAGE
    }

    private fun stopCodec() {
        val c = codec ?: return
        codec = null
        drain?.join(300)
        drain = null
        runCatching { c.stop() }
        runCatching { c.release() }
    }

    fun close(): Unit = synchronized(lock) {
        session = -1
        stopCodec()
        playout.reset()
    }

    private companion object {
        const val TAG = "Engine"
        const val MIN_COVERAGE = 0.9
        val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    }
}
