package app.irlsupreme.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPtsTest {
    private val chunk = 21_333_333L // 1024 frames at 48 kHz

    @Test fun oneSourcePassesThrough() {
        val p = AudioPts(chunk)
        assertEquals(1_000_000_000L, p.next(1_000_000_000L))
        assertEquals(1_000_000_000L + chunk, p.next(1_000_000_000L + chunk))
    }

    @Test fun jitterNeverGoesBackwards() {
        val p = AudioPts(chunk)
        val a = p.next(5_000_000_000L)
        val b = p.next(5_000_000_000L - 1_000) // a timestamp read a hair early
        assertTrue(b > a)
    }

    @Test fun aReopenedMicThatOverlapsTheSilenceFollowsOn() {
        val p = AudioPts(chunk)
        // Silence clock while the mic was down...
        p.sourceChanged()
        var last = 0L
        for (i in 0 until 10) last = p.next(10_000_000_000L + i * chunk)
        // ...then the new mic's first chunk dates from 30 ms ago (its buffer latency).
        p.sourceChanged()
        val first = p.next(last - 30_000_000L)
        assertEquals(last + chunk, first)
        // The rest of that source keeps its own even spacing, shifted by the same amount.
        assertEquals(first + chunk, p.next(last - 30_000_000L + chunk))
    }

    @Test fun aGapIsKeptAsAGap() {
        val p = AudioPts(chunk)
        val a = p.next(1_000_000_000L)
        p.sourceChanged()
        assertEquals(3_000_000_000L, p.next(3_000_000_000L)) // the mic came back 2 s later: no shift
        assertTrue(a < 3_000_000_000L)
    }

    @Test fun jitterAndDriftAreSmoothedButFollowed() {
        val p = AudioPts(chunk)
        val rnd = java.util.Random(42)
        val t0 = 1_000_000_000L
        var prev = Long.MIN_VALUE
        var worstDelta = 0L
        var worstErr = 0L
        for (n in 0 until 20_000) { // ~7 minutes
            val truth = t0 + (n * chunk * 1.0001).toLong() // the mic's clock runs 100 ppm fast
            val raw = truth + (rnd.nextDouble() * 4_000_000 - 2_000_000).toLong() // ±2 ms of timestamp jitter
            val out = p.next(raw)
            if (prev != Long.MIN_VALUE && n > 200) worstDelta = maxOf(worstDelta, kotlin.math.abs(out - prev - chunk))
            if (n > 500) worstErr = maxOf(worstErr, kotlin.math.abs(out - truth))
            prev = out
        }
        assertTrue("delta off by $worstDelta ns", worstDelta < 500_000)
        assertTrue("tracking error $worstErr ns", worstErr < 1_000_000)
    }

    @Test fun aClockThatJumpsBackRebasesInsteadOfCrawling() {
        val p = AudioPts(chunk)
        var t = 2_000_000_000L
        var prev = p.next(t)
        for (n in 1 until 200) {
            t += chunk
            if (n == 100) t -= 100_000_000L // the timestamps re-anchor 100 ms earlier
            val out = p.next(t)
            assertTrue("chunk $n spaced ${out - prev}", out - prev >= chunk - 500_000)
            prev = out
        }
    }

    @Test fun anOverrunIsFilledWithWholeChunksOfSilence() {
        val p = AudioPts(chunk)
        val t0 = 3_000_000_000L
        var encoded = 0
        var last = Long.MIN_VALUE
        var raw = t0
        for (n in 0 until 200) {
            if (n == 100) raw += 40_000_000L // 40 ms lost to an overrun
            val out = p.next(raw)
            for (k in 0 until p.fillChunks) {
                val fill = p.fillFromNs + k * chunk
                assertTrue(fill > last); last = fill; encoded++
            }
            assertTrue(out > last); last = out; encoded++
            raw += chunk
        }
        val wall = raw - t0
        assertEquals(wall / chunk, encoded.toLong())
        assertEquals(201, encoded)
    }

    @Test fun aLongGapIsLeftForTheServer() {
        val p = AudioPts(chunk)
        p.next(1_000_000_000L)
        p.next(1_000_000_000L + 2_000_000_000L) // 2 s: far more than the encoder queue could take at once
        assertEquals(0, p.fillChunks)
    }

    @Test fun aMicThatComesBackLateGetsItsGapFilled() {
        val p = AudioPts(chunk)
        var last = 0L
        for (i in 0 until 10) last = p.next(10_000_000_000L + i * chunk)
        p.sourceChanged()
        val back = p.next(last + chunk + 3 * chunk + 4_000_000L) // three chunks and a bit later
        assertEquals(3, p.fillChunks)
        assertEquals(last + chunk, p.fillFromNs)
        assertTrue(back >= p.fillFromNs + 3 * chunk)
    }
}
