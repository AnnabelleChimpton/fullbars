package app.irlsupreme.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ChunkClockTest {
    private val rate = 48_000
    private val chunkFrames = 1024L
    private val chunkNs = chunkFrames * 1_000_000_000L / rate
    private fun framesNs(f: Long) = f * 1_000_000_000L / rate

    @Test fun `a timestamp failure late in a record continues the timeline instead of jumping ahead`() {
        val c = ChunkClock(rate, chunkNs)
        val openNs = 1_000_000_000_000L // frame 0 on the monotonic clock
        val start = 30L * 60 * rate / chunkFrames * chunkFrames // ~30 minutes in, on a chunk boundary
        val good = c.fromTimestamp(openNs + framesNs(start - 480), start - 480, start)
        assertEquals(openNs + framesNs(start), good)
        // The next chunk's timestamp fails: it must land one chunk later, not 30 minutes in the future.
        val next = c.fallback(openNs + framesNs(start + 2 * chunkFrames), start + chunkFrames)
        assertTrue("jump ${next - good}", abs(next - good - chunkNs) < 1_000)
    }

    @Test fun `a first failure long after opening is anchored at that chunk, not at the open`() {
        val c = ChunkClock(rate, chunkNs)
        val start = 20L * 60 * rate // 20 minutes of frames without a good timestamp
        val now = 5_000_000_000_000L
        assertEquals(now - chunkNs, c.fallback(now, start))
        // Later failures stay evenly spaced on that anchor, whatever the read time.
        assertTrue(abs(c.fallback(now + 123, start + chunkFrames) - now) < 1_000)
    }

    @Test fun `a failure on the first chunk dates it one chunk before the read, as before`() {
        val c = ChunkClock(rate, chunkNs)
        assertEquals(7_000_000_000L - chunkNs, c.fallback(7_000_000_000L, 0))
    }

    @Test fun `reset forgets the old record's timeline`() {
        val c = ChunkClock(rate, chunkNs)
        c.fromTimestamp(1_000_000_000L, 0, 0)
        c.reset()
        assertEquals(9_000_000_000L - chunkNs, c.fallback(9_000_000_000L, 0))
    }
}
