package app.irlsupreme.engine.audio

import app.irlsupreme.engine.audio.testutil.Metrics
import app.irlsupreme.engine.audio.testutil.Signals
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

    @Test fun `a chime over the voice is limited with it but doesn't count as the voice being too hot`() {
        val dsp = MicDsp(rate, 2)
        val voice = FloatArray(frames * 2)
        val chime = FloatArray(frames * 2)
        val out = ByteArray(frames * 4)
        var level = MicLevel.SILENT
        for (c in 0 until 10) {
            for (f in 0 until frames) {
                val t = (c * frames + f).toDouble() / rate
                val v = (0.7 * sin(2 * PI * 300 * t)).toFloat() // -3 dBFS: under the ceiling on its own
                val e = (0.7 * sin(2 * PI * 1500 * t)).toFloat() // the chime pushes the sum well over it
                voice[2 * f] = v; voice[2 * f + 1] = v
                chime[2 * f] = e; chime[2 * f + 1] = e
            }
            level = dsp.limit(voice, chime, frames, out)
            assertTrue("chunk $c peak ${peak(out)}", peak(out) <= (MicDsp.CEILING * 32768).toInt() + 1)
        }
        assertEquals(0f, level.reductionDb, 0f)
        // The same voice pushed over the ceiling on its own does count.
        for (i in voice.indices) voice[i] *= 2f
        repeat(3) { level = dsp.limit(voice, chime, frames, out) }
        assertTrue("reduction ${level.reductionDb}", level.reductionDb > 3f)
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

    // --- Low cut ---

    /** Steady-state gain (dB) of an [hz] high-pass at [fc] on a float sine, from a least-squares fit after settling. */
    private fun hpfGainDb(fc: Double, hz: Double): Double {
        val bq = Biquad(1).apply { setHighPass(rate, fc) }
        val n = rate * 4
        val y = FloatArray(n)
        for (i in 0 until n) y[i] = bq.process(0.5 * sin(2 * PI * hz * i / rate), 0).toFloat()
        val amp = Metrics.toneAmp(y, hz, rate, from = rate * 2, to = n)
        return 20 * kotlin.math.log10(amp / 0.5)
    }

    @Test fun `the low cut is -3 dB at 80 Hz, steep below and flat above`() {
        // -3 dB within 5% of 80 Hz: below at 76 Hz, above at 84 Hz.
        assertTrue(hpfGainDb(80.0, 76.0) < -3.01)
        assertTrue(hpfGainDb(80.0, 84.0) > -3.01)
        assertEquals(-3.01, hpfGainDb(80.0, 80.0), 0.05)
        assertTrue("20 Hz ${hpfGainDb(80.0, 20.0)}", hpfGainDb(80.0, 20.0) <= -24.0)
        for (hz in listOf(300.0, 1000.0, 5000.0, 15000.0)) assertEquals("$hz Hz", 0.0, hpfGainDb(80.0, hz), 0.2)
        assertEquals(-3.01, hpfGainDb(120.0, 120.0), 0.05) // the wind setting
    }

    @Test fun `rumble no longer drives the limiter once it's cut`() {
        fun run(lowCut: Int): Float {
            val dsp = MicDsp(rate, 2).apply { gainDb = 10f }
            var worst = 0f
            for (c in 0 until 40) {
                val x = Signals.sine(30.0, 0.251, frames, rate, from = c.toLong() * frames)
                val y = Signals.sine(1000.0, 0.0501, frames, rate, from = c.toLong() * frames)
                for (i in x.indices) x[i] += y[i]
                val level = dsp.process(Signals.toPcm(x), frames, lowCutHz = lowCut)
                if (c >= 10) worst = maxOf(worst, level.reductionDb)
            }
            return worst
        }
        assertEquals(0f, run(80), 0f)
        assertTrue(run(0) > 0f)
    }

    @Test fun `the low cut takes out a DC offset`() {
        val dsp = MicDsp(rate, 2)
        val dc = ByteArray(frames * 4).also { b -> for (i in 0 until frames * 2) { b[2 * i] = (328 and 0xff).toByte(); b[2 * i + 1] = (328 shr 8).toByte() } }
        var mean = 0.0
        for (c in 0 until 10) { // ~210 ms
            val pcm = dc.copyOf()
            dsp.process(pcm, frames, lowCutHz = 80)
            if (c >= 5) mean = Signals.fromPcm(pcm).average() * 32768
        }
        assertTrue("mean $mean LSB", kotlin.math.abs(mean) < 1.0)
    }

    @Test fun `the low cut carries its state across chunk boundaries, so a split changes nothing`() {
        val x = Signals.toPcm(Signals.sine(140.0, 0.3, 2048, rate))
        val whole = x.copyOf()
        MicDsp(rate, 2).process(whole, 2048, lowCutHz = 80)
        val split = x.copyOf()
        val dsp = MicDsp(rate, 2)
        val a = split.copyOfRange(0, 700 * 4); val b = split.copyOfRange(700 * 4, split.size)
        dsp.process(a, 700, lowCutHz = 80); dsp.process(b, 2048 - 700, lowCutHz = 80)
        assertTrue((a + b).contentEquals(whole))
    }

    @Test fun `stereo channels stay independent through the low cut and gain`() {
        val dsp = MicDsp(rate, 2).apply { gainDb = 6f }
        for (c in 0 until 10) {
            val x = Signals.sine(1000.0, 0.1, frames, rate, from = c.toLong() * frames)
            for (f in 0 until frames) x[2 * f + 1] = 0f
            val pcm = Signals.toPcm(x)
            dsp.process(pcm, frames, lowCutHz = 80)
            val y = Signals.fromPcm(pcm)
            for (f in 0 until frames) assertEquals(0f, y[2 * f + 1], 0f)
            if (c > 2) assertTrue(Metrics.peak(y, ch = 0) > 0.19)
        }
    }

    // --- Input metering ---

    @Test fun `audio that clipped before Fullbars is spotted even when the limiter has nothing to do`() {
        for (gain in listOf(-10f, 0f)) {
            val dsp = MicDsp(rate, 2).apply { gainDb = gain }
            var level = MicLevel.SILENT
            for (c in 0 until 8) {
                val x = Signals.sine(1000.0, 1.3, frames, rate, from = c.toLong() * frames)
                for (i in x.indices) x[i] = x[i].coerceIn(-1f, 32767f / 32768)
                level = dsp.process(Signals.toPcm(x), frames)
            }
            assertTrue("gain $gain clipped ${level.inClipped}", level.inClipped > 0)
            assertTrue(level.inPeakDb > -0.1f)
            if (gain == -10f) assertTrue("reduction ${level.reductionDb}", level.reductionDb < 3f) // the limiter alone can't tell
        }
    }

    @Test fun `a clean loud sine is not called clipped`() {
        val dsp = MicDsp(rate, 2)
        val amp = MicDsp.dbToLinear(-0.5f) * 0.999
        var total = 0
        for (c in 0 until 8) total += dsp.process(Signals.toPcm(Signals.sine(1000.0, amp, frames, rate, from = c.toLong() * frames)), frames).inClipped
        assertEquals(0, total)
    }

    @Test fun `a flat top just under full scale counts as clipping`() {
        // A clipper at -0.3 dBFS followed by nothing: flat runs, never reaching full scale.
        val dsp = MicDsp(rate, 2)
        val rail = MicDsp.dbToLinear(-0.3f)
        val x = Signals.sine(1000.0, 1.2, frames, rate)
        for (i in x.indices) x[i] = x[i].coerceIn(-rail, rail)
        assertTrue(dsp.process(Signals.toPcm(x), frames).inClipped > 0)
    }

    // --- Limiter hold ---

    @Test fun `low notes pushed 10 dB over the ceiling stay clean thanks to the hold`() {
        for (hz in listOf(46.875, 93.75)) {
            val dsp = MicDsp(rate, 2).apply { gainDb = 14.5f } // 0.5 of full scale +14.5 dB: 10 dB over -1.5 dBFS
            var out = FloatArray(0)
            var maxPeak = 0
            for (c in 0 until 40) {
                val a = sine(0.5f, hz.toFloat(), offset = c * frames)
                dsp.process(a, frames)
                maxPeak = maxOf(maxPeak, peak(a))
                out = ch0(a)
            }
            assertTrue("$hz Hz peak $maxPeak", maxPeak <= (MicDsp.CEILING * 32768).toInt() + 1)
            val d = Metrics.thdN(out, hz, rate)
            assertTrue("$hz Hz THD+N ${d * 100}%", d < 0.005)
        }
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
