package app.irlsupreme.engine.abr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeakSignalDetectorTest {
    /** Feeds one sample every 250 ms (the engine's bitrate tick) from [from] for [forMs]. */
    private fun WeakSignalDetector.run(from: Long, forMs: Long, atFloor: Boolean, backedUp: Boolean, clear: Boolean): Long {
        var t = from
        while (t < from + forMs) { update(t, atFloor, backedUp, clear); t += 250 }
        return t
    }

    @Test fun `switches on only at the floor with a backed-up queue for 4 s`() {
        val d = WeakSignalDetector()
        var t = d.run(0, 10_000, atFloor = false, backedUp = true, clear = false)
        assertFalse("above the floor, Auto quality can still cut", d.active)
        t = d.run(t, 10_000, atFloor = true, backedUp = false, clear = false)
        assertFalse("at the floor but the queue's fine", d.active)
        t = d.run(t, 4_000, atFloor = true, backedUp = true, clear = false) // samples up to 3.75 s in
        assertFalse("not yet 4 s", d.active)
        d.update(t, atFloor = true, backedUp = true, clear = false)
        assertTrue(d.active)
        assertEquals(1, d.episodes)
    }

    @Test fun `a short burst doesn't count, the bad stretch must be unbroken`() {
        val d = WeakSignalDetector()
        var t = d.run(0, 3_000, atFloor = true, backedUp = true, clear = false)
        t = d.run(t, 250, atFloor = true, backedUp = false, clear = false)
        d.run(t, 3_000, atFloor = true, backedUp = true, clear = false)
        assertFalse(d.active)
    }

    @Test fun `switches off after 5 s of a clear queue`() {
        val d = WeakSignalDetector()
        var t = d.run(0, 4_250, atFloor = true, backedUp = true, clear = false)
        assertTrue(d.active)
        t = d.run(t, 4_750, atFloor = true, backedUp = false, clear = true)
        assertTrue("4.75 s clear", d.active)
        t = d.run(t, 250, atFloor = true, backedUp = false, clear = false) // one murky sample restarts the hold
        t = d.run(t, 4_750, atFloor = true, backedUp = false, clear = true)
        assertTrue(d.active)
        d.run(t, 500, atFloor = true, backedUp = false, clear = true)
        assertFalse(d.active)
    }

    @Test fun `coming straight back makes the next exit hold longer, up to 40 s`() {
        val d = WeakSignalDetector()
        var t = 0L
        val holds = mutableListOf<Long>()
        repeat(6) {
            t = d.run(t, 4_250, atFloor = true, backedUp = true, clear = false)
            assertTrue(d.active)
            holds += d.exitHold
            t = d.run(t, d.exitHold + 250, atFloor = true, backedUp = false, clear = true)
            assertFalse(d.active)
        }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 40_000L, 40_000L), holds)
        // A minute of good connection later, it's back to 5 s.
        t = d.run(t, 61_000, atFloor = false, backedUp = false, clear = true)
        d.run(t, 4_250, atFloor = true, backedUp = true, clear = false)
        assertEquals(5_000L, d.exitHold)
    }

    @Test fun `reset when not live`() {
        val d = WeakSignalDetector()
        d.run(0, 4_250, atFloor = true, backedUp = true, clear = false)
        assertTrue(d.active)
        d.reset()
        assertFalse(d.active)
    }
}
