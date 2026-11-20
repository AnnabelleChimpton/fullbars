package app.irlsupreme.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AlertSoundsTest {
    @Test fun `chimes are short, stereo-interleaved, quiet enough to sit under a voice, and start without a click`() {
        for (style in AlertSounds.Style.entries) {
            val s = AlertSounds.chime(style, 48_000, stereo = true)
            val seconds = s.size / 2 / 48_000.0
            assertTrue("$style lasts $seconds s", seconds in 1.0..2.0)
            val peak = s.maxOf { abs(it.toInt()) }
            assertTrue("$style peaks at $peak", peak in 3_000..16_000) // about -20 to -6 dBFS
            assertTrue("starts near silence", abs(s[0].toInt()) < 200)
            for (f in 0 until s.size / 2) assertEquals(s[2 * f], s[2 * f + 1])
            assertTrue("rings down", s.takeLast(2_000).maxOf { abs(it.toInt()) } < peak / 5)
        }
    }

    @Test fun `mixing adds to the mic with clipping and says where it got to`() {
        val pcm = byteArrayOf(0x10, 0x00, 0xF0.toByte(), 0x7F, 0x00, 0x80.toByte(), 0, 0) // 16, 32752, -32768, 0
        val next = AlertSounds.mixInto(pcm, shortArrayOf(100, 100, -100, 5, 7), 0)
        assertEquals(4, next)
        fun sample(i: Int) = ((pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)).toShort().toInt()
        assertEquals(listOf(116, 32767, -32768, 5), (0..3).map(::sample))
        val pcm2 = ByteArray(8)
        assertEquals(5, AlertSounds.mixInto(pcm2, shortArrayOf(100, 100, -100, 5, 7), 4))
        assertEquals(7, ((pcm2[0].toInt() and 0xff) or (pcm2[1].toInt() shl 8)))
    }

    @Test fun `mixing can start part-way into a chunk`() {
        val pcm = ByteArray(8)
        assertEquals(2, AlertSounds.mixInto(pcm, shortArrayOf(100, 200), 0, pcmFrom = 4))
        fun sample(i: Int) = ((pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)).toShort().toInt()
        assertEquals(listOf(0, 0, 100, 200), (0..3).map(::sample))
    }

    @Test fun `attackMs is where the first note peaks, so the chime can be timed to land on a frame`() {
        for (style in AlertSounds.Style.entries) {
            val attack = AlertSounds.attackMs(style)
            assertTrue("$style attack $attack ms", attack in 1..30)
            // The loudest moment of the first note (before the second starts) comes right after the attack ramp:
            // within a few cycles of attackMs, never before it.
            val rate = 48_000
            val s = AlertSounds.chime(style, rate, stereo = false)
            val firstNote = s.take((style.step * rate).toInt())
            val peakMs = firstNote.indices.maxBy { abs(firstNote[it].toInt()) } * 1000.0 / rate
            assertTrue("$style peaks at $peakMs ms vs attack $attack ms", peakMs >= attack - 1 && peakMs <= attack + 6)
        }
    }

    @Test fun `a scheduled chime starts on its own sample, in the chunk that holds its start time`() {
        val rate = 48_000
        val chunk = 1_024
        val t0 = 1_000_000_000L
        val chunkNs = chunk * 1_000_000_000L / rate
        assertEquals("unscheduled: right away", 0, AlertSounds.startFrame(t0, chunk, rate, 0L))
        assertEquals("already due", 0, AlertSounds.startFrame(t0, chunk, rate, t0 - 5_000_000))
        assertEquals(480, AlertSounds.startFrame(t0, chunk, rate, t0 + 10_000_000)) // 10 ms in
        assertEquals("a later chunk", -1, AlertSounds.startFrame(t0, chunk, rate, t0 + chunkNs + 1))
        assertEquals(chunk - 1, AlertSounds.startFrame(t0, chunk, rate, t0 + chunkNs - 10_000))
    }
}
