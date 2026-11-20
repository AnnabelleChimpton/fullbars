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

    @Test fun aClockThatJumpsBackEasesBackInsteadOfCrawlingOrStayingLate() {
        val p = AudioPts(chunk)
        var t = 2_000_000_000L
        var prev = p.next(t)
        for (n in 1 until 600) {
            t += chunk
            if (n == 100) t -= 100_000_000L // the timestamps re-anchor 100 ms earlier
            val out = p.next(t)
            // No run of 1 µs steps: every chunk at most 0.5 ms closer than one chunk, never further apart.
            assertTrue("chunk $n spaced ${out - prev}", out - prev >= chunk - 500_000 && out - prev <= chunk + 500_000)
            prev = out
        }
        // ~4 s later the PTS are back on the capture clock, not 100 ms late for the rest of the source.
        assertTrue("left ${prev - t} ns late", kotlin.math.abs(prev - t) < 1_000_000)
    }

    @Test fun lonelySpikesLeaveNoLastingShift() {
        val p = AudioPts(chunk)
        val t0 = 4_000_000_000L
        val start = p.next(t0) - t0
        var prev = t0 + start
        var fills = 0
        for (n in 1 until 1000) {
            var raw = t0 + n * chunk
            if (n == 300) raw += 6_000_000L // one chunk's timestamp 6 ms late...
            if (n == 600) raw -= 6_000_000L // ...and one 6 ms early, each back on time the chunk after
            val out = p.next(raw)
            fills += p.fillChunks
            assertTrue("chunk $n spaced ${out - prev}", kotlin.math.abs(out - prev - chunk) <= 500_000)
            prev = out
        }
        assertEquals(0, fills)
        val end = prev - (t0 + 999 * chunk)
        assertTrue("shifted ${end - start} ns", kotlin.math.abs(end - start) < 1_000_000)
    }

    @Test fun aSpikeOfAWholeChunkThatReturnsIsEasedBack() {
        val p = AudioPts(chunk)
        val t0 = 4_000_000_000L
        var prev = p.next(t0)
        for (n in 1 until 400) {
            var raw = t0 + n * chunk
            if (n == 100) raw += 25_000_000L // looks like an overrun for one chunk only
            val out = p.next(raw)
            assertTrue(out > prev)
            if (n > 101) assertTrue("chunk $n spaced ${out - prev}", out - prev >= chunk - 500_000)
            prev = out
        }
        assertTrue("left ${prev - (t0 + 399 * chunk)} ns off", kotlin.math.abs(prev - (t0 + 399 * chunk)) < 1_000_000)
    }

    @Test fun aShortGapIsBelievedOnceTheNextChunkConfirmsIt() {
        val p = AudioPts(chunk)
        val t0 = 4_000_000_000L
        var raw = t0
        var prev = p.next(raw)
        for (n in 1 until 200) {
            raw += chunk
            if (n == 100) raw += 10_000_000L // 10 ms really lost: every later timestamp is 10 ms on
            val out = p.next(raw)
            if (n == 100) assertTrue("held on the first chunk", out - prev < chunk + 500_000)
            if (n == 101) assertTrue("taken on the second", out - prev > chunk + 9_000_000)
            prev = out
        }
        assertTrue("left ${prev - raw} ns off", kotlin.math.abs(prev - raw) < 1_000_000)
    }

    @Test fun aClockResetBackRebasesAtOnce() {
        val p = AudioPts(chunk)
        var t = 9_000_000_000L
        var prev = p.next(t)
        for (n in 1 until 20) {
            t += chunk
            if (n == 10) t -= 3_000_000_000L // 3 s back: easing that out would take minutes
            val out = p.next(t)
            assertEquals(prev + chunk, out)
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

    @Test fun theEncoderQueueHasRoomForTheLongestFill() {
        // A fill encodes MAX_FILL_CHUNKS of silence and the real chunk at once, on top of whatever was waiting.
        assertTrue(AudioPipeline.MAX_QUEUED_CHUNKS >= AudioPts.MAX_FILL_CHUNKS + 1 + 12)
    }
}
