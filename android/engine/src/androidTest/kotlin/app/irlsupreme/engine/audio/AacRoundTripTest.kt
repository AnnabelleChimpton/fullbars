package app.irlsupreme.engine.audio

import android.media.MediaCodec
import android.media.MediaFormat
import junit.framework.TestCase
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 10 s of a known tone through the phone's real AAC encoder (the live setup, [AudioPipeline.configureAac]) and back
 * through its AAC decoder: what a viewer would hear, minus the network. The level must come back where it went in
 * (within 0.5 dB RMS) and a hot signal must not clip on the way.
 */
class AacRoundTripTest : TestCase() {
    private val rate = 48_000
    private val channels = 2

    fun testHotToneComesBackAtTheSameLevelWithoutClipping() {
        val amplitude = 0.708 // -3 dBFS: a loud voice right at the mic
        val input = tone(1_000.0, amplitude, seconds = 10)
        val (aac, csd) = encode(input)
        val output = decode(aac, csd)

        // Skip the codec's priming delay and its fade at the end: compare 1 s .. 9 s.
        val from = rate * channels
        val to = 9 * rate * channels
        assertTrue("decoded only ${output.size / channels} frames", output.size >= to)
        val inRms = rms(input, from, to)
        val outRms = rms(output, from, to)
        val diffDb = 20 * log10(outRms / inRms)
        assertTrue("RMS changed by %.2f dB".format(diffDb), abs(diffDb) <= 0.5)
        var peak = 0
        for (i in from until to) peak = maxOf(peak, abs(output[i].toInt()))
        assertTrue("peak $peak reached full scale", peak < Short.MAX_VALUE)
    }

    private fun tone(hz: Double, amplitude: Double, seconds: Int): ShortArray {
        val n = rate * seconds
        return ShortArray(n * channels) { i ->
            (sin(2 * PI * hz * (i / channels) / rate) * amplitude * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun rms(s: ShortArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (i in from until to) sum += s[i].toDouble() * s[i]
        return sqrt(sum / (to - from))
    }

    private fun encode(pcm: ShortArray): Pair<List<ByteArray>, ByteArray> {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            AudioPipeline.configureAac(codec, rate, channels == 2, 128_000)
            codec.start()
            val chunk = AudioPipeline.CHUNK_FRAMES * channels
            val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.nativeOrder())
            bytes.asShortBuffer().put(pcm)
            val inputs = (0 until pcm.size step chunk).map { off ->
                val n = minOf(chunk, pcm.size - off)
                bytes.array().copyOfRange(off * 2, (off + n) * 2) to off.toLong() / channels * 1_000_000 / rate
            }
            var csd: ByteArray? = null
            val out = ArrayList<ByteArray>()
            run(codec, inputs) { buf, info ->
                val data = ByteArray(info.size).also { buf.get(it) }
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) csd = data else out += data
            }
            return out to (csd ?: codec.outputFormat.getByteBuffer("csd-0")!!.let { b -> ByteArray(b.remaining()).also { b.get(it) } })
        } finally {
            codec.stop(); codec.release()
        }
    }

    private fun decode(aac: List<ByteArray>, csd: ByteArray): ShortArray {
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(csd))
                setInteger(MediaFormat.KEY_IS_ADTS, 0)
            }
            codec.configure(format, null, null, 0)
            codec.start()
            val frameUs = AudioPipeline.CHUNK_FRAMES * 1_000_000L / rate
            val pcm = ArrayList<Short>(rate * channels * 11)
            run(codec, aac.mapIndexed { i, a -> a to i * frameUs }) { buf, info ->
                val s = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                repeat(info.size / 2) { pcm += s.get() }
            }
            return pcm.toShortArray()
        } finally {
            codec.stop(); codec.release()
        }
    }

    /** Feeds every input (bytes, PTS in us) then end of stream, handing each output buffer to [onOutput]. */
    private fun run(codec: MediaCodec, inputs: List<Pair<ByteArray, Long>>, onOutput: (ByteBuffer, MediaCodec.BufferInfo) -> Unit) {
        val info = MediaCodec.BufferInfo()
        var next = 0
        var eosSent = false
        val deadline = System.nanoTime() + 60_000_000_000L
        while (System.nanoTime() < deadline) {
            if (!eosSent) {
                val idx = codec.dequeueInputBuffer(5_000)
                if (idx >= 0) {
                    if (next < inputs.size) {
                        val (data, pts) = inputs[next++]
                        codec.getInputBuffer(idx)!!.apply { clear(); put(data) }
                        codec.queueInputBuffer(idx, 0, data.size, pts, 0)
                    } else {
                        codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosSent = true
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 5_000)
            if (o >= 0) {
                val buf = codec.getOutputBuffer(o)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset).limit(info.offset + info.size)
                    onOutput(buf, info)
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
        fail("codec did not finish within 60 s")
    }
}
