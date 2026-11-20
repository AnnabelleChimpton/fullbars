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
}
