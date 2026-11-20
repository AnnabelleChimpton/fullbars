package app.irlsupreme.engine.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CadenceMeterTest {
    private val p30 = 1_000_000_000L / 30

    @Test fun histogramPercentilesAndReset() {
        val h = DurationHistogram(100_000, 500)
        for (i in 1..100) h.record(i * 100_000L) // 0.1 .. 10 ms
        assertEquals(100, h.count)
        // Reported as the top of the value's bucket: at most one bucket (0.1 ms) high, never above the max.
        assertTrue(h.percentileNs(0.5) in 5_000_000L..5_100_000L)
        assertTrue(h.percentileNs(0.99) in 9_900_000L..10_000_000L)
        assertEquals(10_000_000L, h.maxNs)
        h.record(900_000_000L) // past the last bucket: reported as the max
        assertEquals(900_000_000L, h.percentileNs(1.0))
        h.reset()
        assertEquals(0L, h.percentileNs(0.5))
        assertEquals(0L, h.maxNs)
    }

    @Test fun aFiftyMillisecondSourceGapIsOneGap() {
        val m = CadenceMeter(p30)
        var ts = 0L
        for (i in 0 until 30) {
            m.onArrival(ts + 1_000)
            m.onLatch(ts + 2_000_000, ts, m.lastArrivalNs)
            ts += p30
        }
        ts += 50_000_000L // the source paused for 50 ms
        for (i in 0 until 30) {
            m.onArrival(ts + 1_000)
            m.onLatch(ts + 2_000_000, ts, m.lastArrivalNs)
            ts += p30
        }
        assertEquals(1, m.sourceGaps)
        assertEquals(60, m.latched)
        assertEquals(0, m.snapshot().overwritten)
        // Each frame waited ~2 ms for its tick.
        assertTrue(m.snapshot().latchedAgeP50Ms in 1.5f..2.5f)
    }

    @Test fun aFlipStartsANewRunSoItsPauseIsntAGap() {
        val m = CadenceMeter(p30)
        m.onLatch(0, 0, 0)
        m.restartRun()
        m.onLatch(1_000_000_000, 1_000_000_000, 0)
        assertEquals(0, m.sourceGaps)
    }

    @Test fun repeatsAreLiveTicksWithNoNewFrame() {
        val m = CadenceMeter(p30)
        m.onLiveTick(true); m.onLiveTick(false); m.onLiveTick(false); m.onLiveTick(true)
        assertEquals(2, m.repeatTicksWhileLive)
    }

    /**
     * A free-running 30.00 Hz tick grid against a steady source: in step gives no repeats; a 29.97 Hz source gives
     * about one repeat every 33 s, the beat the phase lock removes.
     */
    @Test fun beatAgainstTheGridShowsAsRepeats() {
        fun repeatsOver(seconds: Int, srcHz: Double): Long {
            val m = CadenceMeter(p30)
            val srcPeriod = 1e9 / srcHz
            var k = 0L
            fun arrival(i: Long) = (p30 / 2 + i * srcPeriod).toLong() // starts mid-period
            var newest = -1L
            for (tick in 0 until seconds * 30L) {
                val t = tick * p30
                var got = false
                while (arrival(k) <= t) { m.onArrival(arrival(k)); newest = k; k++; got = true }
                if (got) m.onLatch(t, (newest * srcPeriod).toLong(), m.lastArrivalNs)
                if (tick > 0) m.onLiveTick(got)
            }
            return m.repeatTicksWhileLive
        }
        assertEquals(0, repeatsOver(100, 30.0))
        val r = repeatsOver(100, 29.97)
        assertTrue("$r repeats in 100 s", r in 2..4)
    }
}
