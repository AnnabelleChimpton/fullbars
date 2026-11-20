package app.irlsupreme.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The camera's audio at a rate other than the stream's: sines in 1024-frame AAC-sized blocks with whole-ms timestamps,
 * through [CameraResampler] and [CameraPlayout.pull] at 48 kHz, measured with an FFT. A conversion that restarts at
 * every block leaves sidebands at the block rate (43 Hz at 44.1 kHz) and images of high tones folded into the band.
 */
class CameraAudioResampleTest {
    private val outRate = 48_000
    private val block = 1024
    private val fftSize = 1 shl 16

    /** [hz] (one or more tones at -6 dBFS each, summed) at [inRate] through [convert] and the playout. */
    private fun run(
        inRate: Int,
        hz: DoubleArray,
        inChannels: Int = 1,
        outChannels: Int = 1,
        convert: (ShortArray, Long, CameraPlayout) -> Unit,
    ): DoubleArray {
        val playout = CameraPlayout(outRate, outChannels)
        val seconds = 2.5
        var b = 0
        while (b * block < seconds * inRate) {
            val pcm = ShortArray(block * inChannels) { i ->
                val n = b * block + i / inChannels
                var v = 0.0
                for (f in hz) v += 0.5 / hz.size * sin(2 * PI * f * n / inRate)
                (v * 32767).roundToLong().toShort()
            }
            val ptsNs = (b * block * 1000.0 / inRate).roundToLong() * 1_000_000L // RTMP: whole ms
            convert(pcm, ptsNs, playout)
            b++
        }
        // Pull from camera time 0.3 s on, one 1024-frame chunk at a time, as the audio pipeline does.
        val chunk = ShortArray(block * outChannels)
        val out = DoubleArray(fftSize)
        var got = 0
        var c = 0
        while (got < fftSize) {
            val target = 300_000_000L + c * block * 1_000_000_000L / outRate
            val n = playout.pull(chunk, block, target)
            assertEquals("chunk $c played in full", block, n)
            for (i in 0 until block) if (got < fftSize) out[got++] = chunk[i * outChannels] / 32768.0
            c++
        }
        assertEquals(0, playout.resyncs)
        assertEquals(0, playout.underruns)
        assertEquals(0, playout.gapFills)
        return out
    }

    private fun newConvert(inRate: Int, inChannels: Int = 1, outChannels: Int = 1): (ShortArray, Long, CameraPlayout) -> Unit {
        val r = CameraResampler(outRate, outChannels).apply { configure(inRate, inChannels) }
        return { pcm, pts, playout ->
            r.process(pcm, pcm.size / inChannels, pts)
            if (r.outFrames > 0) playout.push(r.outPtsNs, r.out, r.outFrames)
        }
    }

    /** The old conversion: each block on its own, linear interpolation, no phase carried over. */
    private fun oldConvert(inRate: Int): (ShortArray, Long, CameraPlayout) -> Unit = { pcm, pts, playout ->
        val inFrames = pcm.size
        val outFrames = (inFrames.toLong() * outRate / inRate).toInt()
        val out = ShortArray(outFrames)
        for (o in 0 until outFrames) {
            val pos = o.toDouble() * inRate / outRate
            val i0 = pos.toInt().coerceAtMost(inFrames - 1)
            val i1 = (i0 + 1).coerceAtMost(inFrames - 1)
            val frac = pos - i0
            out[o] = (pcm[i0] * (1 - frac) + pcm[i1] * frac).toInt().toShort()
        }
        playout.push(pts, out)
    }

    /**
     * The strongest spur (dB below the strongest tone) between 20 Hz and 20 kHz, away from the tones and their
     * harmonics (±10 Hz; the window's main lobe is ±3 Hz).
     */
    private fun worstSpurDb(x: DoubleArray, tones: DoubleArray): Double {
        val n = x.size
        val re = DoubleArray(n) { i ->
            // 4-term Blackman-Harris: side lobes at -92 dB, so leakage can't pass for a spur.
            val a = 2 * PI * i / (n - 1)
            x[i] * (0.35875 - 0.48829 * cos(a) + 0.14128 * cos(2 * a) - 0.01168 * cos(3 * a))
        }
        val im = DoubleArray(n)
        fft(re, im)
        val binHz = outRate.toDouble() / n
        val mag = DoubleArray(n / 2) { sqrt(re[it] * re[it] + im[it] * im[it]) }
        val peak = mag.max()
        var worst = 0.0
        for (k in 1 until n / 2) {
            val f = k * binHz
            if (f < 20 || f > 20_000) continue
            if (tones.any { t -> (1..20).any { h -> abs(f - t * h) < 10 } }) continue
            worst = maxOf(worst, mag[k])
        }
        return 20 * log10(worst / peak)
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            for (i in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val wr = cos(ang * k); val wi = sin(ang * k)
                    val ar = re[i + k + len / 2]; val ai = im[i + k + len / 2]
                    val xr = ar * wr - ai * wi; val xi = ar * wi + ai * wr
                    re[i + k + len / 2] = re[i + k] - xr; im[i + k + len / 2] = im[i + k] - xi
                    re[i + k] += xr; im[i + k] += xi
                }
            }
            len = len shl 1
        }
    }

    @Test fun `44_1 and 32 kHz cameras convert cleanly at 1 and 8 kHz`() {
        for (inRate in intArrayOf(44_100, 32_000)) {
            for (hz in doubleArrayOf(1_000.0, 8_000.0)) {
                val spur = worstSpurDb(run(inRate, doubleArrayOf(hz), convert = newConvert(inRate)), doubleArrayOf(hz))
                assertTrue("$inRate Hz in, $hz Hz tone: worst spur $spur dB", spur < -70)
            }
        }
    }

    @Test fun `the old per-block conversion fails the same measurement at 8 kHz`() {
        // Guards the test itself: it must be able to see what was wrong. At 44.1 kHz the linear interpolation's images
        // fold into the band (to 4.1 and 12 kHz); at 32 kHz they land out of band and only the per-block restart
        // shows, ~-65 dB, which is still short of the bar the new conversion clears by ~30 dB.
        for (inRate in intArrayOf(44_100, 32_000)) {
            val spur = worstSpurDb(run(inRate, doubleArrayOf(8_000.0), convert = oldConvert(inRate)), doubleArrayOf(8_000.0))
            assertTrue("$inRate Hz in: old worst spur $spur dB", spur > if (inRate == 44_100) -40.0 else -70.0)
        }
    }

    /** The strongest bin within [halfHz] of [hz], in dB below the strongest bin overall. */
    private fun bandDb(x: DoubleArray, hz: Double, halfHz: Double): Double {
        val n = x.size
        val re = DoubleArray(n) { i -> val a = 2 * PI * i / (n - 1); x[i] * (0.35875 - 0.48829 * cos(a) + 0.14128 * cos(2 * a) - 0.01168 * cos(3 * a)) }
        val im = DoubleArray(n)
        fft(re, im)
        val mag = DoubleArray(n / 2) { sqrt(re[it] * re[it] + im[it] * im[it]) }
        val binHz = outRate.toDouble() / n
        var best = 0.0
        for (k in ((hz - halfHz) / binHz).toInt()..((hz + halfHz) / binHz).toInt()) best = maxOf(best, mag[k])
        return 20 * log10(best / mag.max())
    }

    @Test fun `no block-rate sidebands`() {
        // Where a per-block phase jump puts them: 8 kHz ± 43.07 Hz (44100 / 1024).
        val side = 44_100.0 / block
        val new = run(44_100, doubleArrayOf(8_000.0), convert = newConvert(44_100))
        val old = run(44_100, doubleArrayOf(8_000.0), convert = oldConvert(44_100))
        for (s in doubleArrayOf(-side, side)) {
            val n = bandDb(new, 8_000.0 + s, 2.0)
            val o = bandDb(old, 8_000.0 + s, 2.0)
            assertTrue("new sideband at ${8_000 + s} Hz: $n dB", n < -70)
            assertTrue("old sideband at ${8_000 + s} Hz: $o dB", o > -70)
        }
    }

    @Test fun `stereo 44_1 kHz to a mono and to a stereo stream`() {
        val stereoToMono = run(44_100, doubleArrayOf(1_000.0), inChannels = 2, outChannels = 1, convert = newConvert(44_100, 2, 1))
        assertTrue(worstSpurDb(stereoToMono, doubleArrayOf(1_000.0)) < -70)
        val monoToStereo = run(44_100, doubleArrayOf(8_000.0), inChannels = 1, outChannels = 2, convert = newConvert(44_100, 1, 2))
        assertTrue(worstSpurDb(monoToStereo, doubleArrayOf(8_000.0)) < -70)
    }

    @Test fun `a 96 kHz camera is low-passed before it is halved, so ultrasonics don't fold into the band`() {
        // 30 kHz can't exist at 48 kHz; unfiltered it would fold to 18 kHz.
        val x = run(96_000, doubleArrayOf(1_000.0, 30_000.0), convert = newConvert(96_000))
        val spur = worstSpurDb(x, doubleArrayOf(1_000.0))
        assertTrue("worst spur $spur dB", spur < -65)
    }

    @Test fun `48 kHz passes through sample for sample`() {
        val r = CameraResampler(outRate, 1).apply { configure(48_000, 1) }
        val pcm = ShortArray(block) { (it * 31).toShort() }
        r.process(pcm, block, 5_000_000L)
        assertEquals(block, r.outFrames)
        assertEquals(5_000_000L, r.outPtsNs)
        for (i in 0 until block) assertEquals(pcm[i], r.out[i])
    }

    @Test fun `a timestamp jump starts the conversion again, contiguous blocks don't`() {
        val r = CameraResampler(outRate, 1).apply { configure(44_100, 1) }
        val pcm = ShortArray(block) { 1000 }
        r.process(pcm, block, 0L)
        r.process(pcm, block, 23_000_000L) // whole-ms wobble: contiguous
        r.process(pcm, block, 46_000_000L)
        assertEquals(0, r.restarts)
        r.process(pcm, block, 46_000_000L + 23_000_000L + 21_000_000L) // a lost AAC frame
        assertEquals(1, r.restarts)
        // Output carries on at the input's length: 1024 in, ~1114.6 out per block on average.
        var total = 0
        val r2 = CameraResampler(outRate, 1).apply { configure(44_100, 1) }
        for (b in 0 until 100) { r2.process(pcm, block, (b * block * 1000.0 / 44_100).roundToLong() * 1_000_000L); total += r2.outFrames }
        val expected = 100 * block * 48_000.0 / 44_100
        assertTrue("total $total vs $expected", abs(total - expected) < 2 * CameraResampler.HALF_TAPS + 2)
        // DC passes at unity: a constant stays that constant.
        assertTrue(r2.out.take(r2.outFrames).all { abs(it - 1000) <= 1 })
    }
}
