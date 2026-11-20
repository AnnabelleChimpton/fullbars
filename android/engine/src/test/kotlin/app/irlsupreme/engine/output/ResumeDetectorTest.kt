package app.irlsupreme.engine.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeDetectorTest {
    private var now = 10_000L
    private var lastHeard = now
    private val d = ResumeDetector()
    private val firedAt = ArrayList<Long>()

    /** Polls every 150 ms for [ms]; the server is heard every 40 ms unless [silent]. */
    private fun run(ms: Long, silent: Boolean) {
        val end = now + ms
        while (now < end) {
            now += 10
            if (!silent && now % 40 == 0L) lastHeard = now
            if (now % 150 == 0L && d.update(now, now - lastHeard)) firedAt += now
        }
    }

    @Test fun `silences of 1_6, 4 and 7 s each fire exactly once, right after the server is heard again`() {
        run(3_000, silent = false)
        for (gap in listOf(1_600L, 4_000L, 7_000L)) {
            val before = firedAt.size
            run(gap, silent = true)
            assertEquals("nothing during a $gap ms gap", before, firedAt.size)
            val back = now
            run(3_000, silent = false)
            assertEquals("one keyframe after a $gap ms gap", before + 1, firedAt.size)
            assertTrue("fired ${firedAt.last() - back} ms after the return", firedAt.last() - back <= 200)
        }
    }

    @Test fun `a gap under a second does not arm`() {
        run(2_000, silent = false)
        repeat(5) {
            run(800, silent = true)
            run(1_000, silent = false)
        }
        assertEquals(emptyList<Long>(), firedAt)
    }

    @Test fun `a return reported both by a link coming up and by the silence ending fires once`() {
        run(2_000, silent = false)
        repeat(6) {
            run(1_100, silent = true)
            d.onLinkUpAfterOutage() // the link registers again...
            run(400, silent = false) // ...and the server is heard: two signals, one return
        }
        run(2_000, silent = false)
        assertEquals(6, firedAt.size)
        firedAt.zipWithNext { a, b -> assertTrue("fired $a then $b", b - a >= 1_000) }
    }

    @Test fun `a link up after an outage fires, rate-limited too`() {
        assertEquals(false, d.update(now, 20))
        d.onLinkUpAfterOutage()
        assertEquals(true, d.update(now, 20))
        d.onLinkUpAfterOutage()
        assertEquals(false, d.update(now + 500, 20))
        assertEquals(true, d.update(now + 1_000, 20))
        assertEquals(false, d.update(now + 2_000, 20))
    }
}
