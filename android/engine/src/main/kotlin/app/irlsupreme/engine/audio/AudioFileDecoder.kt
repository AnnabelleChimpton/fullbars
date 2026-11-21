package app.irlsupreme.engine.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.Closeable
import java.io.IOException
import java.nio.ByteOrder

/** Stereo float audio at the stream's rate, a block at a time, that can start again from the top. */
internal interface PcmSource {
    /** Up to [maxFrames] frames into [dst] (interleaved stereo, 1.0 = full scale); -1 at the end. Blocks while decoding. */
    fun read(dst: FloatArray, maxFrames: Int): Int

    /** Back to the first frame: the frames that follow are exactly the ones the first pass had. */
    fun rewind()
}

/**
 * Any audio file Android can play (mp3, m4a/aac, ogg, opus, wav, flac), decoded with MediaExtractor and MediaCodec
 * and converted to 48 kHz stereo: one channel goes to both sides, more than two keep the front pair, and other rates
 * go through the camera audio's windowed-sinc converter ([CameraResampler]), run as one continuous stream.
 *
 * Progressive: only a decoder's buffers and one block are held, so a long file never sits in memory. One thread at
 * a time. No allocation per block once the buffers have grown.
 */
internal class AudioFileDecoder(private val path: String) : PcmSource, Closeable {
    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()
    private val converter = StreamConverter()

    /** The file's length, microseconds, or 0 when it doesn't say. */
    var durationUs = 0L
        private set

    private var inRate = 0
    private var inChannels = 0
    private var encoding = AudioFormat.ENCODING_PCM_16BIT
    private var inputDone = false
    private var outputDone = false
    /** Turns in a row with nothing out of the decoder after its input ended: a stuck decoder ends the file. */
    private var idleTurns = 0
    private var block = ShortArray(0)
    private var pending = ShortArray(0)
    private var pendingPos = 0
    private var pendingFrames = 0

    /** Opens the file's first audio track. Throws [IOException] when there's none or it can't be decoded. */
    fun open() {
        extractor.setDataSource(path)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IOException("no audio track")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        readFormat(format)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val c = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) { throw IOException("no decoder for $mime", e) }
        try {
            c.configure(format, null, null, 0)
            c.start()
        } catch (e: Exception) {
            c.release()
            throw IOException("decoder wouldn't start", e)
        }
        codec = c
    }

    private fun readFormat(f: MediaFormat) {
        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) inRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) inChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
        if (inRate > 0 && inChannels > 0) converter.configure(inRate, minOf(inChannels, 2))
    }

    override fun read(dst: FloatArray, maxFrames: Int): Int {
        while (pendingFrames == 0) {
            if (outputDone) {
                if (!converter.finish()) return -1
                takeOutput()
                continue
            }
            decodeSome()
        }
        val n = minOf(maxFrames, pendingFrames)
        val from = pendingPos * 2
        for (i in 0 until n * 2) dst[i] = pending[from + i] / 32768f
        pendingPos += n
        pendingFrames -= n
        return n
    }

    override fun rewind() {
        val c = codec ?: return
        extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        c.flush()
        converter.reset()
        inputDone = false; outputDone = false; idleTurns = 0
        pendingPos = 0; pendingFrames = 0
    }

    private fun decodeSome() {
        val c = codec ?: throw IOException("not open")
        if (!inputDone) {
            val i = c.dequeueInputBuffer(TIMEOUT_US)
            if (i >= 0) {
                val buf = c.getInputBuffer(i)!!
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) {
                    c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    c.queueInputBuffer(i, 0, size, extractor.sampleTime.coerceAtLeast(0), 0)
                    extractor.advance()
                }
            }
        }
        val o = c.dequeueOutputBuffer(info, TIMEOUT_US)
        if (o == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) { if (++idleTurns > MAX_IDLE_TURNS) outputDone = true } else idleTurns = 0
        when {
            o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> readFormat(c.outputFormat)
            o >= 0 -> {
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size > 0 && inRate > 0 && inChannels > 0) {
                    val buf = c.getOutputBuffer(o)!!
                    buf.order(ByteOrder.nativeOrder())
                    val frames = toShorts(buf, info.offset, info.size)
                    c.releaseOutputBuffer(o, false)
                    if (frames > 0) { converter.push(block, frames); takeOutput() }
                } else {
                    c.releaseOutputBuffer(o, false)
                }
                if (eos) outputDone = true
            }
        }
    }

    /** The decoder's output (any PCM encoding, any channel count) as 16-bit, at most two channels, in [block]. */
    private fun toShorts(buf: java.nio.ByteBuffer, offset: Int, size: Int): Int {
        val bytesPer = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
            else -> 2
        }
        val frames = size / (bytesPer * inChannels)
        val keep = minOf(inChannels, 2)
        ensureBlock(frames * keep)
        for (f in 0 until frames) for (ch in 0 until keep) {
            val at = offset + (f * inChannels + ch) * bytesPer
            val v: Int = when (encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> ((buf.get(at).toInt() and 0xff) - 128) shl 8
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                    // Little-endian, the top two bytes are the 16-bit sample.
                    (buf.get(at + 1).toInt() and 0xff) or (buf.get(at + 2).toInt() shl 8)
                }
                AudioFormat.ENCODING_PCM_FLOAT -> (buf.getFloat(at) * 32768f).toInt().coerceIn(-32768, 32767)
                AudioFormat.ENCODING_PCM_32BIT -> buf.getInt(at) shr 16
                else -> buf.getShort(at).toInt()
            }
            block[f * keep + ch] = v.toShort()
        }
        return frames
    }

    private fun takeOutput() {
        val n = converter.outFrames
        if (n <= 0) return
        if (pending.size < n * 2) pending = ShortArray(n * 2)
        System.arraycopy(converter.out, 0, pending, 0, n * 2)
        pendingPos = 0
        pendingFrames = n
    }

    private fun ensureBlock(samples: Int) { if (block.size < samples) block = ShortArray(samples) }

    override fun close() {
        codec?.let { runCatching { it.stop() }; it.release() }
        codec = null
        runCatching { extractor.release() }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_IDLE_TURNS = 300
    }
}

/**
 * A decoded file's PCM, block by block, to 48 kHz stereo with [CameraResampler] run as one continuous stream (each
 * block's timestamps follow on exactly from the last), cut at the end to exactly the frames the file comes to at
 * 48 kHz. Pure, so the conversion an import gets is unit-tested as it is.
 */
internal class StreamConverter(private val outRate: Int = ImportMath.RATE) {
    private val resampler = CameraResampler(outRate, 2)
    private var inRate = 0
    private var inChannels = 0
    private var inFrames = 0L
    private var outTotal = 0L
    private var flushed = false
    private var zeros = ShortArray(0)

    /** The last [push] or [finish]'s output: [outFrames] stereo frames at the start of [out]. */
    val out: ShortArray get() = resampler.out
    var outFrames = 0
        private set

    val converting: Boolean get() = resampler.converting

    fun configure(rate: Int, channels: Int) {
        inRate = rate
        inChannels = channels.coerceIn(1, 2)
        resampler.configure(rate, inChannels)
    }

    fun reset() {
        resampler.reset()
        inFrames = 0; outTotal = 0; flushed = false; outFrames = 0
    }

    /** [frames] frames of [pcm] at the configured format (at most two channels, interleaved). */
    fun push(pcm: ShortArray, frames: Int) {
        resampler.process(pcm, frames, ptsNs())
        inFrames += frames
        take(resampler.outFrames)
    }

    /** The end: whatever the converter held back. False when there's nothing more. */
    fun finish(): Boolean {
        outFrames = 0
        if (flushed || !resampler.converting) return false
        flushed = true
        val n = FLUSH_FRAMES
        if (zeros.size < n * inChannels) zeros = ShortArray(n * inChannels)
        resampler.process(zeros, n, ptsNs())
        take(resampler.outFrames)
        return outFrames > 0
    }

    private fun ptsNs() = inFrames * 1_000_000_000L / inRate.coerceAtLeast(1)

    /** Never past the input's own length at 48 kHz: the flush's silence doesn't add to it. */
    private fun take(n: Int) {
        val expected = if (inRate <= 0) 0L else (inFrames * outRate + inRate - 1) / inRate
        outFrames = if (resampler.converting) minOf(n.toLong(), (expected - outTotal).coerceAtLeast(0)).toInt() else n
        outTotal += outFrames
    }

    private companion object {
        /** Enough silence to push the widest kernel's held-back frames out. */
        const val FLUSH_FRAMES = 16 * CameraResampler.HALF_TAPS
    }
}
