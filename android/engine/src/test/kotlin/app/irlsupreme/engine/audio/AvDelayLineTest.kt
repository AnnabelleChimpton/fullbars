package app.irlsupreme.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AvDelayLineTest {
    private val chunkNs = 1024 * 1_000_000_000L / 48_000
    private val bytes = 16

    private class Out(val index: Int, val ptsNs: Long, val atChunk: Int)

    /** Pushes [n] chunks, each tagged with its index, asking for [target] (by chunk index); returns what came out. */
    private fun run(n: Int, line: AvDelayLine, target: (Int) -> Long): List<Out> {
        val out = ArrayList<Out>()
        val pcm = ByteArray(bytes)
        for (i in 0 until n) {
            java.nio.ByteBuffer.wrap(pcm).putInt(0, i)
            line.push(pcm, 1_000_000_000L + i * chunkNs, target(i)) { b, p -> out.add(Out(java.nio.ByteBuffer.wrap(b).getInt(0), p, i)) }
        }
        return out
    }

    @Test fun `with no delay every chunk goes straight through, uncopied`() {
        val line = AvDelayLine(chunkNs, bytes)
        val pcm = ByteArray(bytes)
        var got: ByteArray? = null
        var pts = 0L
        line.push(pcm, 5_000L, 0L) { b, p -> got = b; pts = p }
        assertSame(pcm, got)
        assertEquals(5_000L, pts)
        assertEquals(0, line.held)
    }

    @Test fun `a step to 120 ms slews in gently, stays monotonic and converges`() {
        val line = AvDelayLine(chunkNs, bytes)
        val d = 120_000_000L
        val out = run(600, line) { if (it < 50) 0L else d }
        // Every chunk comes out exactly once, in order.
        assertEquals((0 until out.size).toList(), out.map { it.index })
        for (k in 1 until out.size) {
            val step = out[k].ptsNs - out[k - 1].ptsNs
            assertTrue("chunk $k step $step", step > 0)
            assertTrue("chunk $k step off by ${step - chunkNs}", kotlin.math.abs(step - chunkNs) <= 1_000_000)
        }
        // Converged: stamped capture time + D, and it leaves within one chunk of that time.
        for (o in out.takeLast(100)) {
            val capture = 1_000_000_000L + o.index * chunkNs
            assertEquals(d, o.ptsNs - capture)
            val leftAt = 1_000_000_000L + o.atChunk * chunkNs
            assertTrue("left ${o.ptsNs - leftAt} ns before its stamp", o.ptsNs - leftAt in 0 until chunkNs)
        }
        assertEquals(d, line.appliedNs)
        assertEquals((d / chunkNs).toInt(), line.held)
    }

    @Test fun `coming back down releases the held chunks without overlap`() {
        val line = AvDelayLine(chunkNs, bytes)
        val out = run(1200, line) { if (it < 400) 150_000_000L else 0L }
        for (k in 1 until out.size) assertTrue(out[k].ptsNs - out[k - 1].ptsNs >= chunkNs - 1_000_000)
        assertEquals(0L, line.appliedNs)
        assertEquals(0, line.held)
        assertEquals(out.size, out.last().index + 1)
    }

    @Test fun `the delay is kept to 0-400 ms`() {
        val line = AvDelayLine(chunkNs, bytes)
        run(2000, line) { 2_000_000_000L }
        assertEquals(AvDelayLine.MAX_DELAY_NS, line.appliedNs)
        run(2000, line) { -300_000_000L }
        assertEquals(0L, line.appliedNs)
    }
}
