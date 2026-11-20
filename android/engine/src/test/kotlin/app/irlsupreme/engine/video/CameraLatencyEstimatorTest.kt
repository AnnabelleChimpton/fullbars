package app.irlsupreme.engine.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CameraLatencyEstimatorTest {
    private val frame = 33_333_333L
    private val ms = 1_000_000L

    @Test fun jitteryInputConvergesToTheMedian() {
        val e = CameraLatencyEstimator()
        val rnd = Random(7)
        var t = 0L
        repeat(120) {
            // 45 ms +- 8 ms, with the odd 120 ms outlier (a frame that waited behind a slow tick).
            val l = if (it % 25 == 0) 120 * ms else 45 * ms + rnd.nextLong(-8 * ms, 8 * ms + 1)
            e.record(t, l)
            t += frame
        }
        val m = e.medianNs!!
        assertTrue("median ${m / 1e6} ms", m in 42 * ms..48 * ms)
        assertTrue(e.p95Ns!! >= m)
        assertTrue(e.p95Ns!! <= 120 * ms)
    }

    @Test fun nothingIsPublishedBeforeEnoughSamples() {
        val e = CameraLatencyEstimator(minSamples = 20)
        repeat(19) { e.record(it * frame, 40 * ms) }
        assertNull(e.medianNs)
        repeat(8) { e.record((19 + it) * frame, 40 * ms) }
        assertEquals(40 * ms, e.medianNs)
    }

    @Test fun aResetHoldsTheOldValueUntilRefilled() {
        val e = CameraLatencyEstimator(minSamples = 20)
        var t = 0L
        repeat(90) { e.record(t, 40 * ms); t += frame }
        assertEquals(40 * ms, e.medianNs)
        e.reset() // flipped to the other camera, which takes longer
        repeat(19) { e.record(t, 70 * ms); t += frame }
        assertEquals("held while refilling", 40 * ms, e.medianNs)
        repeat(10) { e.record(t, 70 * ms); t += frame }
        assertEquals(70 * ms, e.medianNs)
    }

    @Test fun aStepWithoutAResetMovesOnceTheWindowHasPassed() {
        val e = CameraLatencyEstimator(windowNs = 2_000_000_000L)
        var t = 0L
        repeat(90) { e.record(t, 40 * ms); t += frame }
        repeat(20) { e.record(t, 60 * ms); t += frame } // 2/3 s of the new value: still the minority
        assertEquals(40 * ms, e.medianNs)
        repeat(80) { e.record(t, 60 * ms); t += frame } // over 2 s on, the old ones are out of the window
        assertEquals(60 * ms, e.medianNs)
    }

    @Test fun outOfRangeSamplesAreRejected() {
        val e = CameraLatencyEstimator()
        assertFalse(e.record(0, -1))
        assertFalse(e.record(0, 301 * ms))
        assertTrue(e.record(0, 300 * ms))
        assertEquals(2, e.rejected)
    }

    @Test fun realtimeTimestampsConvertExactlyWithAKnownOffset() {
        val asleepFor = 12_345_678_901_234L // boot clock ahead of monotonic by the time spent asleep
        val captureMono = 50_000_000_000L
        val sensorTs = captureMono + asleepFor
        assertEquals(captureMono, CaptureClock.toMonotonicNs(sensorTs, CaptureTimeBase.REALTIME, asleepFor))
        assertEquals(sensorTs, CaptureClock.toMonotonicNs(sensorTs, CaptureTimeBase.MONOTONIC, asleepFor))
        assertNull(CaptureClock.toMonotonicNs(sensorTs, CaptureTimeBase.UNKNOWN, asleepFor))
    }

    @Test fun bootOffsetTakesTheTightestSample() {
        // Fake clocks: boot = mono + 1 s; the first try is preempted between reads, the second isn't.
        var mono = 0L
        val steps = longArrayOf(0, 5_000_000, 100, 100) // added before each mono read
        var i = 0
        val off = CaptureClock.bootMinusMono(
            tries = 2,
            mono = { mono += steps.getOrElse(i++) { 100 }; mono },
            boot = { mono + 50 + 1_000_000_000L },
        )
        assertTrue("$off", kotlin.math.abs(off - 1_000_000_000L) <= 100)
    }

    @Test fun clearMeansUnknown() {
        val e = CameraLatencyEstimator()
        repeat(40) { e.record(it * frame, 40 * ms) }
        e.clear()
        assertNull(e.medianNs)
        assertNull(e.p95Ns)
    }
}
