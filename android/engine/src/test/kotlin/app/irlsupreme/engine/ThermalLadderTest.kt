package app.irlsupreme.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun aForecastWanderingBetweenTheThresholdsDoesNotFlap() {
        val l = ThermalLadder()
        assertEquals(2, l.update(0, 0, 0.95f))
        // 0.88..0.95: not hot enough to enter rung 2, not cool enough to leave it. However long it wanders there.
        var t = 0L
        repeat(600) { i ->
            t += 500
            assertEquals(2, l.update(t, 0, if (i % 2 == 0) 0.90f else 0.94f))
        }
        // Under 0.88 counts as cooler: one rung down after the cool minute (rung 1 still holds at 0.85+).
        l.update(t + 500, 0, 0.86f)
        assertEquals(2, l.update(t + 60_000, 0, 0.86f))
        assertEquals(1, l.update(t + 60_500, 0, 0.86f))
    }

    @Test fun rungOneAlsoNeedsTheForecastWellUnderItsThreshold() {
        val l = ThermalLadder()
        assertEquals(1, l.update(0, 0, 0.85f))
        l.update(1_000, 0, 0.80f)
        assertEquals(1, l.update(120_000, 0, 0.80f)) // 0.78..0.85 holds rung 1
        l.update(121_000, 0, 0.70f)
        assertEquals(0, l.update(181_000, 0, 0.70f))
    }

    @Test fun theStatusStillDecidesAtOnceWhateverTheForecast() {
        val l = ThermalLadder()
        assertEquals(2, l.update(0, 3, 0.10f))
        l.update(1_000, 1, 0.10f)
        assertEquals(1, l.update(61_000, 1, 0.10f))
        assertEquals(0, ThermalLadder.coolRungFor(1, 0.5f))
        assertEquals(2, ThermalLadder.coolRungFor(0, 0.88f))
        assertEquals(1, ThermalLadder.coolRungFor(0, 0.87f))
        assertEquals(1, ThermalLadder.coolRungFor(0, 0.78f))
        assertEquals(0, ThermalLadder.coolRungFor(0, nan))
    }

    @Test fun aLowBatteryHoldsTheFirstRung() {
        assertTrue(ThermalLadder.batteryLow(15, charging = false))
        assertFalse(ThermalLadder.batteryLow(15, charging = true))
        assertFalse(ThermalLadder.batteryLow(16, charging = false))
        assertFalse(ThermalLadder.batteryLow(-1, charging = false)) // unknown
        val l = ThermalLadder()
        assertEquals(1, l.update(0, 0, nan, batteryLow = true))
        for (t in 1..200) assertEquals(1, l.update(t * 1_000L, 0, nan, batteryLow = true))
        // Plugged in: the floor goes, and it steps down after its cool minute like any other rung.
        l.update(201_000, 0, nan, batteryLow = false)
        assertEquals(1, l.update(260_999, 0, nan))
        assertEquals(0, l.update(261_000, 0, nan))
    }

    @Test fun aLowBatteryDoesNotMaskRealHeat() {
        val l = ThermalLadder()
        assertEquals(3, l.update(0, 4, nan, batteryLow = true))
        l.update(1_000, 0, nan, batteryLow = true)
        assertEquals(2, l.update(61_000, 0, nan, batteryLow = true))
        assertEquals(1, l.update(121_000, 0, nan, batteryLow = true))
        assertEquals(1, l.update(500_000, 0, nan, batteryLow = true))
    }

    @Test fun aListenerEventStepsUpAtOnceAndNeverDown() {
        val l = ThermalLadder()
        l.update(0, 0, nan)
        assertEquals(2, l.stepUp(3, nan)) // between ticks
        assertEquals(2, l.stepUp(0, nan)) // a cooler report doesn't skip the cool minute
        assertEquals(2, l.update(500, 0, nan))
        assertEquals(2, l.update(60_499, 0, nan))
        assertEquals(1, l.update(60_500, 0, nan))
        assertEquals(1, l.stepUp(0, nan, batteryLow = true))
    }

    @Test fun aListenerEventRestartsTheCoolMinute() {
        val l = ThermalLadder()
        l.update(0, 3, nan)
        l.update(1_000, 0, nan) // cooler from here
        l.stepUp(3, nan) // hot again, reported by the listener
        l.update(30_500, 0, nan)
        assertEquals(2, l.update(61_000, 0, nan))
        assertEquals(1, l.update(90_500, 0, nan))
    }
}
