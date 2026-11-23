package app.irlsupreme.engine.audio

import app.irlsupreme.engine.audio.testutil.Metrics
import app.irlsupreme.engine.audio.testutil.Signals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin

/** Importing a picked sound: rate conversion, channels, trimming, loudness, the hit, the 10 s cut. */
class AudioImportTest {
    private val rate = ImportMath.RATE

    /** Runs [mono] (at [inRate]) through the decoder's converter in uneven blocks, as a decoder hands them over. */
    private fun convert(mono: ShortArray, inRate: Int, channels: Int = 1): FloatArray {
        val c = StreamConverter()
        c.configure(inRate, channels)
        val out = ArrayList<Short>()
        val sizes = intArrayOf(1152, 1024, 576, 2048, 1000)
        var at = 0
        var k = 0
        while (at < mono.size / channels) {
            val n = minOf(sizes[k++ % sizes.size], mono.size / channels - at)
            c.push(mono.copyOfRange(at * channels, (at + n) * channels), n)
            for (i in 0 until c.outFrames * 2) out.add(c.out[i])
            at += n
        }
        while (c.finish()) for (i in 0 until c.outFrames * 2) out.add(c.out[i])
        return FloatArray(out.size) { out[it] / 32768f }
    }

    private fun tone(hz: Double, amp: Double, frames: Int, r: Int) =
        ShortArray(frames) { (amp * 32767 * sin(2 * PI * hz * it / r)).roundToInt().toShort() }

    @Test fun `a 1 kHz tone keeps a low THD+N from 44_1 to 48 kHz`() {
        val inFrames = 44_100 * 2
        val out = convert(tone(1000.0, 0.5, inFrames, 44_100), 44_100)
        // Exactly the file's length at 48 kHz: nothing lost at the end, no flush silence added.
        assertEquals((inFrames.toLong() * rate + 44_099) / 44_100, (out.size / 2).toLong())
        // Away from the very ends, a whole number of cycles.
        val l = Metrics.channel(out).copyOfRange(4_800, 4_800 + 48_000)
        val thdn = Metrics.thdN(l, 1000.0, rate)
        assertTrue("THD+N ${20 * log10(thdn)} dB", thdn < 10.0.pow(-70.0 / 20)) // better than -70 dB
        assertEquals(0.5, Metrics.toneAmp(l, 1000.0, rate), 0.005)
    }

    @Test fun `mono becomes the same signal on both sides`() {
        val out = convert(tone(440.0, 0.3, 24_000, 48_000), 48_000)
        val l = Metrics.channel(out, ch = 0)
        val r = Metrics.channel(out, ch = 1)
        assertArrayEquals(l, r, 0f)
        // At 48 kHz it passes straight through.
        assertEquals(24_000, l.size)
        assertEquals(0.3, Metrics.peak(out), 0.001)
    }

    @Test fun `mono to stereo maths duplicates every frame in place`() {
        val x = FloatArray(8)
        floatArrayOf(0.1f, -0.2f, 0.3f, 0.4f).copyInto(x)
        ImportMath.monoToStereo(x, 4, x)
        assertArrayEquals(floatArrayOf(0.1f, 0.1f, -0.2f, -0.2f, 0.3f, 0.3f, 0.4f, 0.4f), x, 0f)
    }

    @Test fun `loudness gain reaches the target, held under the peak ceiling and the boost limit`() {
        // A quiet, clean sound: straight to the target.
        assertEquals(6.0, ImportMath.loudnessGainDb(-24.0, 0.1f, -18.0)!!, 1e-9)
        // A loud, peaky one: the peak ceiling wins (peak at -3 dBFS can come up only 2 dB).
        val peak = 10.0.pow(-3.0 / 20).toFloat()
        assertEquals(2.0, ImportMath.loudnessGainDb(-30.0, peak, -18.0)!!, 1e-4)
        // Turned down when too loud.
        assertEquals(-8.0, ImportMath.loudnessGainDb(-8.0, 0.3f, -16.0)!!, 1e-9)
        // Never more than +24 dB.
        assertEquals(ImportMath.MAX_BOOST_DB, ImportMath.loudnessGainDb(-60.0, 0.001f, -16.0)!!, 1e-9)
        // Silence has no gain.
        assertNull(ImportMath.loudnessGainDb(-200.0, 0f, -16.0))
    }

    @Test fun `the loudness meter agrees with the bench's own BS_1770 meter`() {
        val frames = rate * 6
        val speech = Signals.speechLike(-22.0, frames, rate)
        val sine = Signals.sine(997.0, 0.1, frames, rate)
        for (x in listOf(speech, sine)) {
            val m = LoudnessMeter(2).apply {
                // In uneven blocks.
                var at = 0
                while (at < frames) { val n = minOf(3000, frames - at); add(x.copyOfRange(at * 2, (at + n) * 2), n); at += n }
            }
            assertEquals(Metrics.integratedLufs(x), m.integratedLufs(), 0.05)
        }
    }

    @Test fun `silence at either end is found`() {
        val x = FloatArray(1000 * 2)
        for (f in 200 until 700) x[f * 2 + 1] = 0.1f
        assertEquals(200..699, ImportMath.soundRange(x, 1000, 2))
        assertNull(ImportMath.soundRange(FloatArray(200), 100, 2))
        // -60 dBFS counts as silence.
        assertNull(ImportMath.soundRange(FloatArray(200) { 0.001f }, 100, 2))
    }

    @Test fun `fade curve runs smoothly from 0 to 1`() {
        assertEquals(0f, ImportMath.fadeCurve(0f), 0f)
        assertEquals(1f, ImportMath.fadeCurve(1f), 0f)
        assertEquals(0.5f, ImportMath.fadeCurve(0.5f), 1e-6f)
        var prev = 0f
        for (i in 1..1000) {
            val g = ImportMath.fadeCurve(i / 1000f)
            assertTrue(g >= prev)
            prev = g
        }
        // Flat at both ends: the first and last steps are tiny compared with the middle's.
        val first = ImportMath.fadeCurve(0.001f)
        val mid = ImportMath.fadeCurve(0.5005f) - ImportMath.fadeCurve(0.4995f)
        assertTrue(first < mid / 100)
    }

    /** A sound [lead] frames of silence in, then a 1 kHz tone burst [len] long, then [tail] of silence. */
    private fun burst(lead: Int, len: Int, tail: Int, amp: Float = 0.05f): FloatArray {
        val x = FloatArray((lead + len + tail) * 2)
        for (f in 0 until len) {
            val v = amp * sin(2 * PI * 1000.0 * f / rate).toFloat()
            x[(lead + f) * 2] = v; x[(lead + f) * 2 + 1] = v
        }
        return x
    }

    @Test fun `an alert sound is trimmed, levelled and its hit found`() {
        val lead = rate / 2
        val x = burst(lead, rate, rate / 4)
        val range = ImportMath.soundRange(x, x.size / 2, 2)!!
        val (clip, cut) = AudioImporter.finishAlert(x, range.first, range.last + 1, x.size / 2)
        assertTrue(!cut)
        val y = FloatArray(clip.pcm.size) { clip.pcm[it] / 32768f }
        // The half second of silence is gone: a few ms of edge at most either side.
        val edge = rate * ImportMath.EDGE_FADE_MS / 1000
        assertTrue(y.size / 2 <= rate + 2 * edge + 2)
        // -18 LUFS, give or take the edges.
        assertEquals(ImportMath.ALERT_TARGET_LUFS, Metrics.integratedLufs(y), 0.3)
        // The hit lands within the first ms or two: the edge fade and the tone's first peak.
        assertTrue("attack ${clip.attackMs} ms", clip.attackMs in 0..5)
        // No click at either end.
        assertTrue(abs(y[0]) < 0.01f && abs(y[y.size - 1]) < 0.01f)
    }

    @Test fun `an alert sound longer than 10 s is cut with a 50 ms fade`() {
        val x = burst(0, rate * 12, 0, 0.2f)
        val (clip, cut) = AudioImporter.finishAlert(x, 0, rate * 12, rate * 12)
        assertTrue(cut)
        assertEquals(rate * ImportMath.ALERT_MAX_S, clip.pcm.size / 2)
        val y = Metrics.channel(FloatArray(clip.pcm.size) { clip.pcm[it] / 32768f })
        val fade = rate * ImportMath.CUT_FADE_MS / 1000
        // Full level just before the fade, down to nothing at the end, smoothly.
        val before = Metrics.peak(y, 1, y.size - fade - 2_000, y.size - fade)
        val inFade = Metrics.peak(y, 1, y.size - fade / 4, y.size)
        assertTrue(inFade < before * 0.2)
        assertTrue(abs(y.last()) < 0.002f)
    }

    @Test fun `TTS speech is cut at 20 s, not the alert sounds' 10`() {
        val x = burst(0, rate * 25, 0, 0.2f)
        val (clip, cut) = AudioImporter.finishAlert(x, 0, rate * 25, rate * 25, ImportMath.SPEECH_MAX_S)
        assertTrue(cut)
        assertEquals(rate * ImportMath.SPEECH_MAX_S, clip.pcm.size / 2)
        val (short, shortCut) = AudioImporter.finishAlert(burst(0, rate * 15, 0, 0.2f), 0, rate * 15, rate * 15, ImportMath.SPEECH_MAX_S)
        assertTrue(!shortCut)
        assertEquals(rate * 15, short.pcm.size / 2)
    }

    @Test fun `the hit is the first loud moment, not the loudest`() {
        val x = FloatArray(rate * 2)
        // A quiet tick at 10 ms, the real hit at 200 ms, a louder one at 600 ms.
        x[(rate / 100) * 2] = 0.01f
        x[(rate / 5) * 2] = 0.5f
        x[(rate * 6 / 10) * 2] = 0.9f
        assertEquals(rate / 5, ImportMath.attackFrame(x, rate, 2))
        assertEquals(0, ImportMath.attackFrame(FloatArray(100), 50, 2))
    }

    @Test fun `alert clips round-trip through their file`() {
        val clip = AlertClip(ShortArray(4_800) { (it * 7 - 16_000).toShort() }, 42)
        val f = java.io.File.createTempFile("clip", ".snd")
        try {
            clip.save(f)
            val back = AlertClip.load(f)!!
            assertEquals(42, back.attackMs)
            assertArrayEquals(clip.pcm, back.pcm)
            f.writeBytes(byteArrayOf(1, 2, 3))
            assertNull(AlertClip.load(f))
        } finally { f.delete() }
    }

    @Test fun `a clip for a mono stream is folded`() {
        val clip = AlertClip(shortArrayOf(100, 300, -100, -300), 0)
        assertArrayEquals(shortArrayOf(200, -200), AlertSoundLayout.forStream(clip, 48_000, stereo = false))
        assertTrue(AlertSoundLayout.forStream(clip, 48_000, stereo = true) === clip.pcm)
    }

    private fun Double.pow(e: Double) = Math.pow(this, e)
}
