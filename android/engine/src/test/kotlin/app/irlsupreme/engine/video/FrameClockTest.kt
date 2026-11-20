package app.irlsupreme.engine.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A clock that only moves when the code under test waits on it (or a test moves it): no real sleeping. */
internal class FakeTickTime(var now: Long = 1_000_000_000L) : TickTime {
    override fun nanoTime(): Long = now
    override fun parkNanos(ns: Long) { if (ns > 0) now += ns }
}

class FrameClockTest {
    @Test fun ticksAreOnePeriodApartAndWaitForTheirTime() {
        val t = FakeTickTime()
        val c = FrameClock(30, t)
        val start = c.tickTimeNs()
        repeat(10) {
            assertEquals(0, c.awaitTick())
            assertEquals(start + it * c.periodNs, c.tickTimeNs())
            assertEquals(c.tickTimeNs(), t.now) // woke exactly on time
            c.advance()
        }
    }

    @Test fun aOneAndAHalfPeriodOverrunCountsOneSkipAndTimesOnlyGoUp() {
        val t = FakeTickTime()
        val c = FrameClock(30, t)
        val p = c.periodNs
        assertEquals(0, c.awaitTick())
        var last = c.tickTimeNs()
        t.now += p + p * 3 / 2 // this tick's work ran into the next tick and half the one after
        c.advance()
        assertEquals(1, c.awaitTick())
        assertTrue(c.tickTimeNs() > last)
        assertTrue(c.tickTimeNs() <= t.now) // the tick it lands on has already started: no bunching
        last = c.tickTimeNs()
        c.advance()
        assertEquals(0, c.awaitTick())
        assertEquals(last + p, c.tickTimeNs())
    }

    @Test fun aSmallOverrunSkipsNothing() {
        val t = FakeTickTime()
        val c = FrameClock(30, t)
        c.awaitTick()
        t.now += c.periodNs * 3 / 2
        c.advance()
        assertEquals(0, c.awaitTick())
    }

    private fun deltas(c: FrameClock, ticks: Int): List<Long> {
        val out = ArrayList<Long>()
        var prev = -1L
        repeat(ticks) {
            c.awaitTick()
            if (prev >= 0) out += c.tickTimeNs() - prev
            prev = c.tickTimeNs()
            c.advance()
        }
        return out
    }

    @Test fun setPeriodRebasesAtTheCurrentTickSoSpacingIsConstantAfter() {
        val t = FakeTickTime()
        val c = FrameClock(30, t)
        deltas(c, 10)
        c.awaitTick()
        val at = c.tickTimeNs()
        c.setPeriod(ThermalCadence.periodNs(20))
        assertEquals(at, c.tickTimeNs()) // the current tick keeps its time
        c.advance()
        val d20 = deltas(c, 60)
        assertTrue(d20.all { it == 50_000_000L })
        c.awaitTick()
        c.setPeriod(ThermalCadence.periodNs(15))
        c.advance()
        val d15 = deltas(c, 60)
        assertTrue(d15.all { it == 66_666_666L })
        c.awaitTick()
        c.setPeriod(c.nominalPeriodNs)
        c.advance()
        assertTrue(deltas(c, 30).all { it == 33_333_333L })
    }

    @Test fun noRungGivesTheOldFourOnOneOffPattern() {
        for (rung in 0..3) for (phone in listOf(true, false)) for (camFps in listOf(0, 20, 24)) {
            val t = FakeTickTime()
            val c = FrameClock(30, t)
            val fps = ThermalCadence.outputFps(30, rung, phone, camFps)
            c.awaitTick()
            c.setPeriod(ThermalCadence.periodNs(fps))
            c.advance()
            val d = deltas(c, 120)
            assertEquals("rung $rung phone $phone cam $camFps: $fps fps", 1, d.toSet().size)
        }
    }

    @Test fun nudgesAreClampedAndNeverMakeTimeGoBackwards() {
        val t = FakeTickTime()
        val c = FrameClock(60, t)
        assertEquals(FrameClock.MAX_NUDGE_NS, c.nudge(5_000_000))
        assertEquals(-FrameClock.MAX_NUDGE_NS, c.nudge(-5_000_000))
        var prev = Long.MIN_VALUE
        repeat(200) { i ->
            c.awaitTick()
            assertTrue(c.tickTimeNs() > prev)
            if (prev != Long.MIN_VALUE) assertTrue(kotlin.math.abs(c.tickTimeNs() - prev - c.periodNs) <= FrameClock.MAX_NUDGE_NS)
            prev = c.tickTimeNs()
            c.nudge(if (i % 2 == 0) -1_000_000 else 1_000_000)
            c.advance()
        }
    }
}

class ThermalCadenceTest {
    @Test fun reducedCameraRatePrefersTwentyThenTwentyFour() {
        assertEquals(20, ThermalCadence.reducedCameraFps(30, listOf(15, 20, 24, 30)))
        assertEquals(24, ThermalCadence.reducedCameraFps(30, listOf(15, 24, 30)))
        assertEquals(0, ThermalCadence.reducedCameraFps(30, listOf(15, 30)))
        assertEquals(30, ThermalCadence.reducedCameraFps(60, listOf(30, 60)))
        assertEquals(0, ThermalCadence.reducedCameraFps(60, listOf(60)))
    }

    @Test fun outputRatePerRungAndSource() {
        assertEquals(30, ThermalCadence.outputFps(30, 0, true, 0))
        assertEquals(30, ThermalCadence.outputFps(30, 1, true, 20))
        assertEquals(20, ThermalCadence.outputFps(30, 2, true, 20)) // camera switched to [20,20]
        assertEquals(15, ThermalCadence.outputFps(30, 2, true, 0)) // camera couldn't: an even half
        assertEquals(15, ThermalCadence.outputFps(30, 2, false, 20)) // external camera at 30: an even 15
        assertEquals(30, ThermalCadence.outputFps(60, 2, false, 0)) // 60 fps streams: an even 30
        assertEquals(15, ThermalCadence.outputFps(30, 3, false, 0))
    }

    @Test fun aPhoneCameraThatCantHoldTheRateSetsItEvenly() {
        assertEquals(30, ThermalCadence.baseFps(60, phoneOnAir = true, cameraFps = 30))
        assertEquals(60, ThermalCadence.baseFps(60, phoneOnAir = false, cameraFps = 30)) // external camera: unchanged
        assertEquals(60, ThermalCadence.baseFps(60, phoneOnAir = true, cameraFps = 60))
        assertEquals(30, ThermalCadence.baseFps(30, phoneOnAir = true, cameraFps = 24)) // 24 doesn't divide 30: keep 30
    }

    @Test fun overlayAndPreviewRates() {
        assertEquals(listOf(1, 2, 4, 4), (0..3).map { ThermalCadence.browserLatchEvery(it) })
        assertEquals(1, ThermalCadence.previewEvery(0, touched = false))
        assertEquals(2, ThermalCadence.previewEvery(1, touched = false))
        assertEquals(1, ThermalCadence.previewEvery(2, touched = true))
    }

    @Test fun forcedKeyframesFollowPtsTime() {
        val interval = 2_000_000_000L
        assertTrue(!ThermalCadence.keyframeDue(1_999_999_999L, 0, interval))
        assertTrue(ThermalCadence.keyframeDue(2_000_000_000L, 0, interval))
        // At 15 fps over 10 s of PTS: a keyframe every 2 s exactly (30 frames), not every 4 s.
        var last = 0L
        val forcedAt = ArrayList<Long>()
        val p = ThermalCadence.periodNs(15)
        for (i in 0 until 150) {
            val pts = i * p
            if (ThermalCadence.keyframeDue(pts, last, interval - p / 2)) { forcedAt += pts; last = pts }
        }
        val gaps = forcedAt.zipWithNext { a, b -> b - a }
        assertTrue(gaps.all { kotlin.math.abs(it - interval) <= p })
    }
}
