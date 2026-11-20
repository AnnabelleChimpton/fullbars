package app.irlsupreme.engine.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The small pieces of the audio path: the playback ring, overrun detection, source crossfades and the mute ramp. */
class AudioPathTest {
    private fun le(vararg s: Int): ByteArray = ByteArray(s.size * 2).also { b ->
        s.forEachIndexed { i, v -> b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte() }
    }

    private fun shorts(b: ByteArray): IntArray = IntArray(b.size / 2) { i ->
        ((b[2 * i].toInt() and 0xff) or (b[2 * i + 1].toInt() shl 8)).toShort().toInt()
    }

    @Test fun `the ring keeps the newest audio, oldest first, and hands out copies`() {
        val ring = PcmRing(sampleRate = 4, channels = 2, seconds = 2) // 8 frames
        assertNull(ring.recent(2))
        ring.write(le(1, -1, 2, -2, 3, -3), 3)
        assertArrayEquals(shortArrayOf(1, -1, 2, -2, 3, -3), ring.recent(2)!!.pcm)
        ring.write(le(4, -4, 5, -5, 6, -6, 7, -7, 8, -8, 9, -9, 10, -10), 7) // 10 frames in all: wraps
        val all = ring.recent(2)!!
        assertEquals(16, all.pcm.size)
        assertEquals(3, all.pcm[0].toInt()); assertEquals(-10, all.pcm[15].toInt())
        assertEquals(2f, all.seconds, 0f)
        // The last second only.
        assertArrayEquals(shortArrayOf(7, -7, 8, -8, 9, -9, 10, -10), ring.recent(1)!!.pcm)
        // A copy: changing it doesn't change the ring.
        all.pcm.fill(0)
        assertEquals(3, ring.recent(2)!!.pcm[0].toInt())
        ring.clear()
        assertNull(ring.recent(2))
    }

    @Test fun `a chunk bigger than the ring keeps its end`() {
        val ring = PcmRing(sampleRate = 2, channels = 1, seconds = 2) // 4 samples
        ring.write(le(1, 2, 3, 4, 5, 6), 6)
        assertArrayEquals(shortArrayOf(3, 4, 5, 6), ring.recent(5)!!.pcm)
    }

    @Test fun `an overrun shows as a chunk that starts late on the record's timeline`() {
        val chunkNs = 21_333_333L
        val d = OverrunDetector(chunkNs)
        var t = 1_000_000_000L
        assertFalse(d.onChunk(t))
        repeat(100) { t += chunkNs + (if (it % 2 == 0) 400_000L else -400_000L); assertFalse(d.onChunk(t)) } // jitter
        t += chunkNs + 20_000_000L // a 20 ms period lost
        assertTrue(d.onChunk(t))
        assertEquals(20L, d.lastLostMs)
        t += chunkNs
        assertFalse(d.onChunk(t))
        d.reset()
        assertFalse(d.onChunk(t + 10 * chunkNs)) // nothing to compare with after a reset
    }

    @Test fun `switching sources crossfades instead of jumping`() {
        val frames = 8
        val from = le(*IntArray(frames) { 10_000 })
        val to = le(*IntArray(frames) { -10_000 })
        val out = ByteArray(frames * 2)
        crossfade(from, to, out, frames, 1, fadeFrames = 4)
        val s = shorts(out)
        for (i in 1 until frames) assertTrue("step ${s[i] - s[i - 1]}", abs(s[i] - s[i - 1]) <= 20_000 / 4 + 1)
        assertTrue(s[0] > 0) // starts near the old source
        for (i in 4 until frames) assertEquals(-10_000, s[i]) // then it's the new one
        // In place, from silence (the camera gone): a fade-in.
        val mic = le(*IntArray(frames) { 8_000 })
        crossfade(null, mic, mic, frames, 1, fadeFrames = 4)
        val m = shorts(mic)
        assertTrue(m[0] in 1..2_000)
        assertEquals(8_000, m[7])
    }

    @Test fun `mute ramps down and back up instead of cutting`() {
        val frames = 1024
        val chunk = { le(*IntArray(frames) { 12_000 }) }
        var g = 1f
        val down = chunk()
        g = applyRamp(down, frames, 1, g, 0f, 480)
        assertEquals(0f, g, 0f)
        val d = shorts(down)
        assertTrue(d[0] > 11_900 && d[479] == 0 && d[1023] == 0)
        for (i in 1 until frames) assertTrue(abs(d[i] - d[i - 1]) <= 30)
        val still = chunk()
        g = applyRamp(still, frames, 1, g, 0f, 480)
        assertTrue(shorts(still).all { it == 0 })
        val up = chunk()
        g = applyRamp(up, frames, 1, g, 1f, 480)
        assertEquals(1f, g, 0f)
        assertEquals(12_000, shorts(up)[1023])
        val heard = chunk()
        applyRamp(heard, frames, 1, 1f, 1f, 480)
        assertTrue(shorts(heard).all { it == 12_000 })
    }
}
