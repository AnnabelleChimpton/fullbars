package app.irlsupreme.engine.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The real [FrameClock] (on a fake time source) and [CadenceMeter], against a simulated camera: frames at a given
 * rate with random arrival jitter, a clock crystal off by some ppm, starting right on a tick boundary (the worst
 * phase). Counts what viewers would see: ticks with no new frame (repeats) and frames overwritten before a tick took
 * them (drops), after a settling time.
 */
class FramePhaseLockTest {
    private class Run(val repeats: Int, val drops: Int, val maxNudge: Long, val maxSpacingErr: Long, val lockedAtEnd: Boolean)

    private fun simulate(
        srcHz: Double,
        jitterNs: Long,
        ppm: Double = 0.0,
        lock: Boolean,
        seconds: Int = 120,
        settleS: Int = 5,
        startPhaseNs: Long = 300_000L,
        seed: Int = 1,
        fps: Int = 30,
    ): Run {
        val time = FakeTickTime(0)
        val clock = FrameClock(fps, time)
        val meter = CadenceMeter(clock.periodNs)
        val pll = FramePhaseLock()
        val rnd = Random(seed)
        val srcPeriod = 1e9 / srcHz * (1 + ppm * 1e-6)
        var k = 0L
        fun arrival(i: Long, j: Long) = startPhaseNs + (i * srcPeriod).toLong() + j
        var nextJitter = rnd.nextLong(-jitterNs, jitterNs + 1)
        var next = arrival(0, nextJitter)
        var repeats = 0
        var drops = 0
        var maxNudge = 0L
        var maxSpacingErr = 0L
        var prevTick = Long.MIN_VALUE
        val settleNs = settleS * 1_000_000_000L
        while (time.now < seconds * 1_000_000_000L) {
            clock.awaitTick()
            val t = clock.tickTimeNs()
            var fresh = 0
            while (next <= t) {
                meter.onArrival(next)
                fresh++
                k++
                nextJitter = rnd.nextLong(-jitterNs, jitterNs + 1)
                next = arrival(k, nextJitter)
            }
            if (fresh > 0) meter.onLatch(t, (k * srcPeriod).toLong(), meter.lastArrivalNs)
            if (t > settleNs) {
                if (fresh == 0) repeats++
                if (fresh > 1) drops += fresh - 1
            }
            if (prevTick != Long.MIN_VALUE) maxSpacingErr = maxOf(maxSpacingErr, abs(t - prevTick - clock.periodNs))
            prevTick = t
            if (lock) {
                val n = pll.update(t, clock.periodNs, meter.arrivals, meter.lastArrivalNs, allowed = true)
                maxNudge = maxOf(maxNudge, abs(clock.nudge(n)))
            }
            clock.advance()
        }
        return Run(repeats, drops, maxNudge, maxSpacingErr, pll.locked)
    }

    @Test fun withoutTheLockJitterOnATickBoundaryRepeatsAndDrops() {
        for (hz in listOf(29.97, 30.0, 30.01)) {
            val r = simulate(hz, 3_000_000, ppm = 100.0, lock = false)
            assertTrue("$hz Hz: ${r.repeats} repeats", r.repeats > 0)
            assertTrue("$hz Hz: ${r.drops} drops", r.drops > 0)
        }
    }

    @Test fun lockedAtThirtyThereAreNoRepeatsOrDrops() {
        for (ppm in listOf(-100.0, 0.0, 100.0)) for (jitter in listOf(2_000_000L, 4_000_000L)) for (seed in 1..3) {
            for (hz in listOf(30.0, 30.01)) {
                val r = simulate(hz, jitter, ppm, lock = true, seed = seed)
                assertEquals("$hz Hz $ppm ppm ±${jitter / 1_000_000} ms: repeats", 0, r.repeats)
                assertEquals("$hz Hz $ppm ppm ±${jitter / 1_000_000} ms: drops", 0, r.drops)
                assertTrue(r.lockedAtEnd)
            }
        }
    }

    @Test fun lockedAt2997AtMostOneRepeatPer33Seconds() {
        for (seed in 1..3) {
            val r = simulate(29.97, 4_000_000, ppm = 100.0, lock = true, seconds = 200, seed = seed)
            assertTrue("${r.repeats} repeats", r.repeats <= (200 - 5) / 33)
            assertEquals(0, r.drops)
        }
    }

    @Test fun nudgesStayWithinAQuarterMillisecondAndSpacingWithinHalf() {
        val r = simulate(30.0, 4_000_000, ppm = -100.0, lock = true)
        assertTrue(r.maxNudge <= FrameClock.MAX_NUDGE_NS)
        assertTrue(r.maxSpacingErr <= 500_000L)
        val r60 = simulate(60.0, 2_000_000, ppm = 100.0, lock = true, fps = 60)
        assertEquals(0, r60.repeats)
        assertEquals(0, r60.drops)
        assertTrue(r60.maxSpacingErr <= 500_000L)
    }

    @Test fun aSourceTooFarOffTheRateIsNeverSteeredTowards() {
        val pll = FramePhaseLock()
        val p = 33_333_333L
        val src = 40_000_000L // 25 fps
        var arrivals = 0L
        var last = 0L
        for (tick in 1..600L) {
            val t = tick * p
            while (last + src <= t) { last += src; arrivals++ }
            assertEquals(0L, pll.update(t, p, arrivals, last, allowed = true))
        }
    }

    @Test fun notAllowedMeansFreeRunningAndAFreshStart() {
        val pll = FramePhaseLock()
        val p = 33_333_333L
        var arrivals = 0L
        for (tick in 1..100L) {
            arrivals++
            pll.update(tick * p, p, arrivals, tick * p - 1_000_000, allowed = true)
        }
        assertTrue(pll.measuredIntervalNs > 0)
        assertEquals(0L, pll.update(101 * p, p, arrivals + 1, 101 * p, allowed = false))
        assertEquals(0.0, pll.measuredIntervalNs, 0.0)
        // The first ticks after it's allowed again only measure: no steering on a stale estimate.
        assertEquals(0L, pll.update(102 * p, p, arrivals + 2, 102 * p - 1_000_000, allowed = true))
    }

    @Test fun periodFollowsASourceWithinHalfAPercentOnly() {
        fun slaved(srcPeriod: Double): Long {
            val pll = FramePhaseLock()
            val p = 33_333_333L
            var arrivals = 0L
            var t = 0.0
            for (tick in 1..300L) {
                t += srcPeriod
                arrivals++
                pll.update(tick * p, p, arrivals, t.toLong(), allowed = true)
            }
            return pll.slavedPeriodNs(p)
        }
        val s2997 = slaved(1e9 / 29.97)
        assertTrue("$s2997", abs(s2997 - 1e9 / 29.97) < 5_000)
        assertEquals(33_333_333L, slaved(1e9 / 29.0)) // 3% off: not followed
    }
}
