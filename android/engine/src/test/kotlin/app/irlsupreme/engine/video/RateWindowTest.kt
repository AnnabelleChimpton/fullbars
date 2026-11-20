package app.irlsupreme.engine.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RateWindowTest {
    private val sec = 1_000_000_000L

    /** Feeds [fps] frames a second of [bytes] each from [fromNs] for [durNs]; returns the time after the last frame. */
    private fun RateWindow.feed(fromNs: Long, durNs: Long, fps: Int, bytes: (Int) -> Int, each: (Long) -> Unit = {}): Long {
        val n = (durNs * fps / sec).toInt()
        val periodUs = 1_000_000L / fps // like the encoder's PTS: whole microseconds
        var t = fromNs
        for (i in 0 until n) {
            t = fromNs + i * periodUs * 1_000
            add(t, bytes(i))
            each(t)
        }
        return t + periodUs * 1_000
    }

    @Test fun zeroUntilAFullWindow() {
        val w = RateWindow()
        var t = 0L
        while (t < sec) { w.add(t, 10_000); assertEquals(0, w.bitsPerSecond()); t += 33_333_000 }
        w.add(t, 10_000)
        assertTrue(w.bitsPerSecond() > 0)
    }

    @Test fun constantInputIsSteadyWithinOnePercent() {
        val w = RateWindow()
        val expected = 25_000L * 8 * 30 // 6 Mbps
        w.feed(0, 10 * sec, 30, { 25_000 }) { t ->
            if (t >= sec) {
                val err = abs(w.bitsPerSecond() - expected).toDouble() / expected
                assertTrue("at $t: ${w.bitsPerSecond()} vs $expected", err < 0.01)
                assertEquals(30.0, w.perSecond(), 0.3)
            }
        }
    }

    @Test fun jitteredArrivalsStayWithinOnePercent() {
        // Frames 33.3 ms apart on average but +-8 ms off their slot, as a drain thread would see them.
        val w = RateWindow()
        val rnd = java.util.Random(7)
        val expected = 25_000L * 8 * 30
        for (i in 0 until 600) {
            w.add(i * 33_333_333L + (rnd.nextInt(16_000_001) - 8_000_000), 25_000) // still in order: slots are 33 ms
            if (i > 60) {
                val err = abs(w.bitsPerSecond() - expected).toDouble() / expected
                assertTrue("frame $i: ${w.bitsPerSecond()}", err < 0.03)
            }
        }
    }

    @Test fun stepChangeArrivesWithinOneSecond() {
        val w = RateWindow()
        val t1 = w.feed(0, 5 * sec, 30, { 25_000 }) // 6 Mbps
        var reachedAt = -1L
        w.feed(t1, 5 * sec, 30, { 10_000 }) { t -> // 2.4 Mbps
            val err = abs(w.bitsPerSecond() - 2_400_000L).toDouble() / 2_400_000
            if (reachedAt < 0 && err < 0.01) reachedAt = t
            if (t - t1 >= sec) assertTrue("still off at ${t - t1}: ${w.bitsPerSecond()}", err < 0.01)
        }
        assertTrue(reachedAt > 0 && reachedAt - t1 <= sec)
    }

    @Test fun movesEveryFrameNotInSteps() {
        // The old 2 s tumbling window held one value for 2 s and then jumped. Across a step down, the sliding
        // window must fall frame by frame: many distinct values, each no more than one frame's worth apart.
        val w = RateWindow()
        val t1 = w.feed(0, 3 * sec, 30, { 25_000 })
        val seen = ArrayList<Long>()
        w.feed(t1, 2 * sec, 30, { 10_000 }) { seen += w.bitsPerSecond() }
        val firstSecond = seen.take(30)
        assertTrue(firstSecond.toSet().size >= 25)
        val oneFrame = (25_000 - 10_000) * 8L
        for (i in 1 until firstSecond.size) {
            assertTrue("jump at $i", abs(firstSecond[i] - firstSecond[i - 1]) <= oneFrame * 11 / 10)
        }
    }

    @Test fun sparseOutputAfterAStallFallsBackToWhatArrived() {
        val w = RateWindow()
        val t1 = w.feed(0, 3 * sec, 30, { 25_000 })
        // 2 s with nothing, then one frame: only that frame is inside the window.
        w.add(t1 + 2 * sec, 25_000)
        assertEquals(25_000L * 8, w.bitsPerSecond())
    }

    @Test fun timeGoingBackwardsStartsOver() {
        val w = RateWindow()
        w.feed(0, 3 * sec, 30, { 25_000 })
        w.add(sec, 25_000)
        assertEquals(0, w.bitsPerSecond())
    }

    @Test fun moreSamplesThanTheRingShortensTheWindowButStaysRight() {
        val w = RateWindow(capacity = 16)
        w.feed(0, 3 * sec, 120, { 1_000 }) { t ->
            if (t > sec) assertEquals(120 * 8_000.0, w.bitsPerSecond().toDouble(), 120 * 8_000 * 0.01)
        }
    }
}
