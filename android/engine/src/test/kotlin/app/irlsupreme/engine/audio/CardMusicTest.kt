package app.irlsupreme.engine.audio

import app.irlsupreme.engine.audio.testutil.Metrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Card music: the loop's seam, the fades, and following the scene on air. */
class CardMusicTest {
    private val rate = 48_000

    /** A "file" of [frames] stereo frames from [gen], handed out in blocks of [block], rewound to the top. */
    private class FakeSource(private val frames: Int, private val block: Int = 700, private val gen: (Int) -> Float) : PcmSource {
        var pos = 0
        var rewinds = 0
        override fun read(dst: FloatArray, maxFrames: Int): Int {
            if (pos >= frames) return -1
            val n = minOf(maxFrames, block, frames - pos)
            for (f in 0 until n) { val v = gen(pos + f); dst[f * 2] = v; dst[f * 2 + 1] = v }
            pos += n
            return n
        }
        override fun rewind() { pos = 0; rewinds++ }
    }

    private fun sineAt(hz: Double, amp: Double): (Int) -> Float = { i -> (amp * sin(2 * PI * hz * i / 48_000)).toFloat() }

    private fun pull(s: LoopStitcher, frames: Int): FloatArray {
        val out = FloatArray(frames * 2)
        val block = FloatArray(4096)
        var w = 0
        while (w < frames) {
            val n = s.next(block, minOf(2048, frames - w))
            System.arraycopy(block, 0, out, w * 2, n * 2)
            w += n
        }
        return out
    }

    // A loop whose ends don't line up: 1.0123 s of a 440 Hz tone, after 300 frames of lead-in.
    private val start = 300L
    private val end = start + (rate * 1.0123).toLong()
    private val amp = 0.5
    private val smoothKink = Math.pow(2 * PI * 440 / rate, 2.0) * amp

    @Test fun `the loop's seam is sample-continuous, with no click`() {
        val src = FakeSource(end.toInt() + 5_000, gen = sineAt(440.0, amp))
        val out = pull(LoopStitcher(src, start, end), rate * 4)
        assertTrue(src.rewinds >= 3)
        val l = Metrics.channel(out)
        val kink = Metrics.maxKink(l)
        assertTrue("kink $kink vs a smooth wave's $smoothKink", kink < smoothKink * 1.5)
        // Right after the first seam the music carries on exactly where the head left off.
        val n = LoopStitcher.XFADE_FRAMES
        val seamAt = (end - start).toInt() - n
        for (i in 0 until 2_000) {
            val expected = sineAt(440.0, amp)((start + n + i).toInt())
            assertEquals(expected, l[seamAt + n + i], 1e-6f)
        }
        // Before it, the file itself from its trimmed start.
        for (i in 0 until 2_000) assertEquals(sineAt(440.0, amp)((start + i).toInt()), l[i], 1e-6f)
    }

    @Test fun `sanity - wrapping with no crossfade clicks`() {
        val src = FakeSource(end.toInt(), gen = sineAt(440.0, amp))
        val out = pull(LoopStitcher(src, start, end, xfadeFrames = 0), rate * 3)
        assertTrue(Metrics.maxKink(Metrics.channel(out)) > smoothKink * 10)
    }

    @Test fun `the seam keeps its loudness on unrelated material`() {
        val r = java.util.Random(3)
        val noise = FloatArray(rate * 2) { (r.nextGaussian() * 0.1).toFloat() }
        val src = FakeSource(noise.size, gen = { noise[it] })
        val e = rate.toLong() * 2 - 1_000
        val out = Metrics.channel(pull(LoopStitcher(src, 0, e), rate * 5))
        val seam = (e - LoopStitcher.XFADE_FRAMES).toInt()
        val w = LoopStitcher.XFADE_FRAMES
        val at = Metrics.rmsDb(out, 1, seam, seam + w)
        val away = Metrics.rmsDb(out, 1, seam - 10 * w, seam - 2 * w)
        assertEquals(away, at, 1.5)
    }

    @Test fun `a file that gives nothing is reported, not looped for ever`() {
        val s = LoopStitcher(FakeSource(0, gen = { 0f }), 0, 0)
        assertEquals(0, s.next(FloatArray(2048), 1024))
        assertTrue(s.broken)
    }

    /** A ring and a mixer, with the decoder stood in for: a constant level, topped up after every chunk. */
    private inner class Rig(val value: Float = 0.5f) {
        val ring = MusicRing(rate * 2)
        val mixer = MusicMixer(ring, rate)
        private var gen = 0
        private var track: MusicTrack? = null
        val block = FloatArray(4096) { value }

        fun feed() {
            val req = mixer.request
            if (req.gen != gen) { gen = req.gen; track = req.track; ring.startGen(gen) }
            if (track != null) while (ring.free() > 0) ring.write(block, minOf(2048, ring.free()))
        }

        /** [chunks] chunks of 1024 frames; the gain each frame got (left channel / [value]). */
        fun run(chunks: Int): FloatArray {
            val out = FloatArray(chunks * 1024)
            val fx = FloatArray(2048)
            repeat(chunks) { k ->
                fx.fill(0f)
                mixer.addInto(fx, 1024, 2)
                feed()
                for (f in 0 until 1024) out[k * 1024 + f] = fx[f * 2] / value
            }
            return out
        }
    }

    private val a = MusicTrack("/music/a", 0, 1000, 0f)
    private val b = MusicTrack("/music/b", 0, 1000, 0f, micOff = true)

    @Test fun `music fades in over 0_8 s on a raised cosine, and out the same way`() {
        val r = Rig()
        r.mixer.wanted = a
        val fadeIn = r.run(60)
        val first = fadeIn.indexOfFirst { it > 0f }
        assertTrue("starts within a chunk or two: $first", first in 0..2048)
        val fade = (rate * MusicMixer.FADE_S).toInt()
        assertEquals(0.5f, fadeIn[first + fade / 2], 0.01f)
        assertEquals(1f, fadeIn[first + fade + 10], 1e-4f)
        for (i in first + 1 until first + fade) assertTrue(fadeIn[i] >= fadeIn[i - 1])
        assertTrue(r.mixer.engaged)

        r.mixer.wanted = null
        val fadeOut = r.run(40)
        assertEquals(0.5f, fadeOut[fade / 2 - 1], 0.01f)
        for (i in 1 until fade) assertTrue(fadeOut[i] <= fadeOut[i - 1])
        assertEquals(0f, fadeOut[fade + 10], 0f)
        r.run(1)
        assertFalse(r.mixer.engaged)
    }

    @Test fun `changing cards fades the old music out fully before the new one comes in`() {
        val r = Rig()
        r.mixer.wanted = a
        r.run(50)
        val genA = r.mixer.request.gen
        r.mixer.wanted = b
        assertTrue(r.mixer.micOff)
        val out = r.run(100)
        val fade = (rate * MusicMixer.FADE_S).toInt()
        // Down to silence first...
        val silentAt = out.indexOfFirst { it == 0f }
        assertTrue(silentAt in fade - 2 until fade + 2)
        // ...then the new one, from its own start.
        assertEquals(genA + 1, r.mixer.request.gen)
        assertEquals(1f, out.last(), 1e-4f)
    }

    @Test fun `a new volume for the same music glides, with no fade`() {
        val r = Rig()
        r.mixer.wanted = a
        r.run(50)
        r.mixer.wanted = a.copy(gainDb = -6f, micOff = true)
        val out = r.run(2)
        // One chunk's glide to -6 dB, never dipping below it.
        val target = Math.pow(10.0, -6.0 / 20).toFloat()
        assertEquals(target, out[1023], 1e-4f)
        assertEquals(target, out[2047], 1e-4f)
        assertTrue(out.all { it >= target - 1e-4f })
        assertTrue(r.mixer.micOff)
    }

    @Test fun `music that can't be played doesn't keep the mic off, and the next music still plays`() {
        val r = Rig()
        r.mixer.wanted = b // asks for the mic off
        assertTrue(r.mixer.micOff)
        r.run(1)
        // The decoder couldn't open it.
        r.ring.failedGen = r.mixer.request.gen
        assertTrue(r.mixer.failed)
        assertFalse(r.mixer.micOff)
        assertFalse(r.mixer.engaged)
        // A new volume for the same music changes nothing: it still can't play.
        r.mixer.wanted = b.copy(gainDb = -6f)
        assertFalse(r.mixer.micOff)
        assertFalse(r.mixer.engaged)
        // Other music is asked for afresh, with no fade to wait through.
        r.mixer.wanted = a
        assertFalse(r.mixer.failed)
        assertTrue(r.mixer.engaged)
        val out = r.run(60)
        assertEquals(1f, out.last(), 1e-4f)
    }

    @Test fun `music that fails while playing goes quiet at once and lets the mic back`() {
        val r = Rig()
        r.mixer.wanted = b
        r.run(50)
        assertTrue(r.mixer.micOff)
        r.ring.failedGen = r.mixer.request.gen
        assertFalse(r.mixer.micOff)
        assertFalse(r.mixer.engaged)
        // Leaving the card: the mixer lets go without a fade over nothing.
        r.mixer.wanted = null
        assertTrue(r.mixer.engaged)
        r.run(1)
        assertFalse(r.mixer.engaged)
    }

    @Test fun `a ring that runs dry plays silence and never waits`() {
        val ring = MusicRing(rate)
        val m = MusicMixer(ring, rate, prefillFrames = 100)
        m.wanted = a
        val fx = FloatArray(2048)
        m.addInto(fx, 1024, 2) // asks the decoder
        ring.startGen(m.request.gen)
        ring.write(FloatArray(400) { 1f }, 200)
        m.addInto(fx, 1024, 2)
        assertEquals(1, m.underruns)
        assertTrue(abs(fx[2 * 500]) == 0f)
    }
}
