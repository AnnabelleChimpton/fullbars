package app.irlsupreme.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ThermalLadderTest {
    private val nan = Float.NaN

    @Test fun rungsFromStatusAndForecast() {
        assertEquals(0, ThermalLadder.rungFor(0, nan))
        assertEquals(0, ThermalLadder.rungFor(1, 0.5f)) // LIGHT
        assertEquals(1, ThermalLadder.rungFor(2, nan)) // MODERATE
        assertEquals(1, ThermalLadder.rungFor(0, 0.85f)) // forecast sees it coming
        assertEquals(2, ThermalLadder.rungFor(3, nan)) // SEVERE
        assertEquals(2, ThermalLadder.rungFor(1, 0.95f))
        assertEquals(3, ThermalLadder.rungFor(4, nan)) // CRITICAL
        assertEquals(3, ThermalLadder.rungFor(6, 0.2f)) // SHUTDOWN
        assertEquals(listOf(100, 85, 70, 50), ThermalLadder.CEILING_PERCENT.toList())
    }

    @Test fun stepsDownAtOnce() {
        val l = ThermalLadder()
        assertEquals(0, l.update(0, 0, nan))
        assertEquals(2, l.update(500, 3, nan))
        assertEquals(3, l.update(1_000, 4, nan))
    }

    @Test fun recoversOneRungPerCoolMinute() {
        val l = ThermalLadder()
        l.update(0, 4, nan)
        assertEquals(3, l.update(1_000, 0, nan)) // cooler from here
        assertEquals(3, l.update(60_999, 0, nan))
        assertEquals(2, l.update(61_000, 0, nan))
        assertEquals(2, l.update(120_999, 0, nan))
        assertEquals(1, l.update(121_000, 0, nan))
        assertEquals(0, l.update(181_000, 0, nan))
    }

    @Test fun heatingAgainRestartsTheCoolMinute() {
        val l = ThermalLadder()
        l.update(0, 3, nan)
        l.update(1_000, 0, nan)
        assertEquals(2, l.update(30_000, 3, nan)) // hot again half-way: timer reset
        l.update(31_000, 0, nan)
        assertEquals(2, l.update(90_000, 0, nan))
        assertEquals(1, l.update(91_000, 0, nan))
    }

    @Test fun holdsWhileStillAtItsLevel() {
        val l = ThermalLadder()
        l.update(0, 2, nan)
        for (t in 1..300) assertEquals(1, l.update(t * 1_000L, 2, nan))
    }
}
