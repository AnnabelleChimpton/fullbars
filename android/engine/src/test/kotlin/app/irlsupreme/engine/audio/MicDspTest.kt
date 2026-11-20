package app.irlsupreme.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class MicDspTest {
    private val rate = 48_000
    private val frames = 1024

    /** A stereo sine at [amp] (0..1 of full scale), 16-bit little-endian. */
    private fun sine(amp: Float, hz: Float = 440f, offset: Int = 0): ByteArray {
        val b = ByteArray(frames * 4)
        for (f in 0 until frames) {
            val v = (amp * 32767 * sin(2 * PI * hz * (f + offset) / rate)).toInt()
            for (ch in 0 until 2) {
                b[f * 4 + ch * 2] = (v and 0xff).toByte()
                b[f * 4 + ch * 2 + 1] = (v shr 8).toByte()
            }
        }
        return b
    }

    private fun peak(b: ByteArray): Int {
        var p = 0
        for (i in b.indices step 2) p = maxOf(p, abs(((b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8)).toShort().toInt()))
        return p
    }

    @Test fun `at 0 dB a quiet voice passes unchanged and meters right`() {
        val dsp = MicDsp(rate, 2)
        dsp.process(sine(0.1f), frames) // the first chunk starts with the lookahead's few ms of silence
        val pcm = sine(0.1f, offset = frames)
        val before = peak(pcm)
        val level = dsp.process(pcm, frames)
        assertTrue(abs(peak(pcm) - before) <= 1)
        assertEquals(-20f, level.peakDb, 0.2f) // 0.1 of full scale
        assertEquals(-23f, level.rmsDb, 0.3f) // a sine's RMS is 3 dB under its peak
        assertEquals(0f, level.reductionDb, 0.01f)
    }

    @Test fun `gain raises the level after its glide`() {
        val dsp = MicDsp(rate, 2)
        dsp.gainDb = 6f
        repeat(6) { dsp.process(sine(0.1f, offset = it * frames), frames) } // the ~20 ms glide settles
        val level = dsp.process(sine(0.1f, offset = 6 * frames), frames)
        assertEquals(-14f, level.peakDb, 0.3f)
    }

    @Test fun `a camera's audio gets 0 dB whatever the phone mic's gain`() {
        val dsp = MicDsp(rate, 2)
        dsp.gainDb = 24f
        repeat(6) { dsp.process(sine(0.1f, offset = it * frames), frames, gainOverrideDb = 0f) }
        val level = dsp.process(sine(0.1f, offset = 6 * frames), frames, gainOverrideDb = 0f)
        assertEquals(-20f, level.peakDb, 0.3f)
        assertEquals(0f, level.reductionDb, 0.01f)
        assertEquals(24f, dsp.gainDb, 0f) // the user's setting is kept for when the phone mic is back
    }

    @Test fun `the limiter never lets a boosted shout clip`() {
        val dsp = MicDsp(rate, 2)
        dsp.gainDb = 20f
        repeat(3) { dsp.process(sine(0.5f, offset = it * frames), frames) }
        val pcm = sine(0.5f, offset = 3 * frames)
        val level = dsp.process(pcm, frames)
        assertTrue("peak ${peak(pcm)}", peak(pcm) <= (MicDsp.CEILING * 32768).toInt() + 1)
        assertTrue(level.reductionDb > 10f)
        assertTrue(level.peakDb <= -0.9f)
    }

    @Test fun `output is the input a lookahead later, sample for sample, when nothing is limited`() {
        val dsp = MicDsp(rate, 2)
        val a = sine(0.2f, hz = 1000f)
        val b = sine(0.2f, hz = 1000f, offset = frames)
        val orig = b.copyOf()
        dsp.process(a, frames)
        dsp.process(b, frames)
        val lat = dsp.latencyFrames
        assertEquals(240, lat) // 5 ms at 48 kHz
        for (f in lat until frames) {
            val i = f * 4
            val j = (f - lat) * 4
            assertEquals(orig[j], b[i]); assertEquals(orig[j + 1], b[i + 1])
        }
    }

    /** Mono float view of channel 0. */
    private fun ch0(b: ByteArray): FloatArray = FloatArray(frames) { f ->
        ((b[f * 4].toInt() and 0xff) or (b[f * 4 + 1].toInt() shl 8)).toShort() / 32768f
    }

    /** Everything that isn't the [hz] tone, relative to it (THD+N as a ratio), from a least-squares fit of the tone. */
    private fun distortion(x: FloatArray, hz: Float): Double {
        var ss = 0.0; var cc = 0.0; var sc = 0.0; var xs = 0.0; var xc = 0.0; var mean = 0.0
        for (v in x) mean += v
        mean /= x.size
        for (i in x.indices) {
            val s = sin(2 * PI * hz * i / rate); val c = cos(2 * PI * hz * i / rate)
            ss += s * s; cc += c * c; sc += s * c; xs += (x[i] - mean) * s; xc += (x[i] - mean) * c
        }
        val det = ss * cc - sc * sc
        val a = (xs * cc - xc * sc) / det
        val b = (xc * ss - xs * sc) / det
        var res = 0.0; var fund = 0.0
        for (i in x.indices) {
            val f = a * sin(2 * PI * hz * i / rate) + b * cos(2 * PI * hz * i / rate)
            fund += f * f
            val r = x[i] - mean - f
            res += r * r
        }
        return sqrt(res / fund)
    }

    @Test fun `a sine pushed 8 dB over the ceiling stays under it with far less distortion than an instant attack`() {
        // Whole numbers of cycles per 1024-frame chunk (multiples of 46.875 Hz), so the fit below is exact.
        for (hz in listOf(234.375f, 1031.25f)) {
            val dsp = MicDsp(rate, 2).apply { gainDb = 13f } // 0.5 of full scale +13 dB: 8 dB over -1 dBFS
            val old = InstantAttackLimiter(rate, 2).apply { gainDb = 13f }
            var newOut = FloatArray(0); var oldOut = FloatArray(0)
            var maxPeak = 0
            for (c in 0 until 40) { // ~0.85 s: past the glide and the release's settling
                val a = sine(0.5f, hz, offset = c * frames)
                val b = a.copyOf()
                dsp.process(a, frames); old.process(b, frames)
                maxPeak = maxOf(maxPeak, peak(a))
                newOut = ch0(a); oldOut = ch0(b)
            }
            assertTrue("$hz Hz peak $maxPeak", maxPeak <= (MicDsp.CEILING * 32768).toInt() + 1)
            val dNew = distortion(newOut, hz)
            val dOld = distortion(oldOut, hz)
            assertTrue("$hz Hz: new ${dNew * 100}% vs old ${dOld * 100}%", dNew < dOld / 5)
            assertTrue("$hz Hz: new ${dNew * 100}%", dNew < 0.01)
        }
    }

    @Test fun `a sudden shout is turned down over a ramp, not in a step`() {
        fun maxGainStep(process: (ByteArray) -> Unit, latency: Int): Float {
            repeat(10) { process(ByteArray(frames * 4)) } // silence while the +13 dB glide settles
            val input = sine(0.5f, 1000f)
            val out = input.copyOf()
            process(out)
            val x = ch0(input); val y = ch0(out)
            val gainLin = MicDsp.dbToLinear(13f)
            var prev = Float.NaN; var worst = 0f
            for (n in latency until frames) {
                val xin = x[n - latency] * gainLin
                if (abs(xin) < 0.2f) { prev = Float.NaN; continue } // near a zero crossing: no clean reading
                val g = y[n] / xin
                if (!prev.isNaN()) worst = maxOf(worst, abs(g - prev))
                prev = g
            }
            return worst
        }
        val dsp = MicDsp(rate, 2).apply { gainDb = 13f }
        val old = InstantAttackLimiter(rate, 2).apply { gainDb = 13f }
        val stepNew = maxGainStep({ dsp.process(it, frames) }, dsp.latencyFrames)
        val stepOld = maxGainStep({ old.process(it, frames) }, 0)
        assertTrue("old $stepOld", stepOld > 0.03f)
        assertTrue("new $stepNew", stepNew < 0.01f)
    }

    @Test fun `gain is kept to its range`() {
        val dsp = MicDsp(rate, 2)
        dsp.gainDb = 99f
        assertEquals(MicDsp.MAX_GAIN_DB, dsp.gainDb, 0f)
        dsp.gainDb = -99f
        assertEquals(MicDsp.MIN_GAIN_DB, dsp.gainDb, 0f)
    }

    @Test fun `silence meters as the floor`() {
        val level = MicDsp(rate, 2).process(ByteArray(frames * 4), frames)
        assertEquals(MicLevel.SILENT, level)
    }

    @Test fun `auto level off asks for the raw sources first`() {
        assertEquals(android.media.MediaRecorder.AudioSource.CAMCORDER, micSources(raw = false, unprocessedOk = true)[0])
        assertEquals(android.media.MediaRecorder.AudioSource.UNPROCESSED, micSources(raw = true, unprocessedOk = true)[0])
        assertEquals(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, micSources(raw = true, unprocessedOk = false)[0])
    }
}

/** The limiter MicDsp had before the lookahead (instant attack, 150 ms release), kept here to measure against. */
private class InstantAttackLimiter(sampleRate: Int, private val channels: Int) {
    var gainDb = 0f
    private var gain = 1f
    private var env = 1f
    private val glide = (1.0 - exp(-1.0 / (0.02 * sampleRate))).toFloat()
    private val release = exp(-1.0 / (0.15 * sampleRate)).toFloat()

    fun process(pcm: ByteArray, frames: Int) {
        val target = MicDsp.dbToLinear(gainDb)
        for (f in 0 until frames) {
            gain += (target - gain) * glide
            val base = f * channels * 2
            var framePeak = 0f
            for (ch in 0 until channels) {
                val a = abs(sample(pcm, base + ch * 2) * gain)
                if (a > framePeak) framePeak = a
            }
            env = 1f - (1f - env) * release
            if (framePeak * env > MicDsp.CEILING) env = MicDsp.CEILING / framePeak
            for (ch in 0 until channels) {
                val i = base + ch * 2
                val out = (sample(pcm, i) * gain * env * 32768f).roundToInt().coerceIn(-32768, 32767)
                pcm[i] = (out and 0xff).toByte()
                pcm[i + 1] = (out shr 8).toByte()
            }
        }
    }

    private fun sample(pcm: ByteArray, i: Int): Float =
        ((pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)).toShort() / 32768f
}
