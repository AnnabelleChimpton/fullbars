package app.irlsupreme.engine.abr

import app.irlsupreme.engine.output.RtmpMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.max
import kotlin.math.min

class RtmpRateControllerTest {
    /**
     * One TCP uplink with a send queue in front of it, driven by the controller through the same [RtmpMeter] the
     * output uses. The encoder applies a new target 500 ms after it's asked, with frame noise and a keyframe every 2 s;
     * audio adds 128 kbps. Simulated in 50 ms steps; the controller runs every [tickMs].
     */
    private class Link(val c: RtmpRateController, var capacityBps: Long, val tickMs: Long = 250, seed: Long = 1) {
        val rnd = Random(seed)
        val meter = RtmpMeter(30)
        var nowMs = 0L
        var queueBits = 0.0
        var sentBytes = 0L
        var inBytes = 0L
        var applied = c.targetBps
        var pendingFrom = Long.MIN_VALUE
        var lastAsked = c.targetBps
        var ceiling = c.maxBps
        var peakWaitMs = 0.0
        var flushes = 0
        val targets = ArrayList<Pair<Long, Int>>()
        val actions = ArrayList<String>()

        fun run(ms: Long) {
            val end = nowMs + ms
            while (nowMs < end) {
                nowMs += STEP_MS
                if (c.targetBps != lastAsked && pendingFrom == Long.MIN_VALUE) pendingFrom = nowMs
                lastAsked = c.targetBps
                if (pendingFrom != Long.MIN_VALUE && nowMs - pendingFrom >= 500) { applied = c.targetBps; pendingFrom = Long.MIN_VALUE }
                val dt = STEP_MS / 1000.0
                val key = nowMs % 2_000 == 0L
                val video = applied * dt * (if (key) 4.0 else 0.92) * (1 + (rnd.nextDouble() * 2 - 1) * 0.1)
                val input = video + AUDIO_BPS * dt
                queueBits += input
                inBytes += (input / 8).toLong()
                val out = min(queueBits, capacityBps * dt)
                queueBits -= out
                sentBytes += (out / 8).toLong()
                if (capacityBps > 0) peakWaitMs = max(peakWaitMs, queueBits * 1000 / capacityBps)
                if (nowMs % tickMs == 0L) {
                    meter.update(nowMs, sentBytes, inBytes, (queueBits / 8).toLong(), 0)
                    c.update(nowMs, meter.queueDelayMs, meter.throughputBps, ceiling)
                    if (c.flushNow) { queueBits = 0.0; flushes++ }
                    targets += nowMs to c.targetBps
                    actions += "$nowMs ${c.targetBps} ${meter.queueDelayMs} ${c.knownLimitBps} ${c.lastAction}"
                }
            }
        }

        fun cuts(fromMs: Long = 0): Int = targets.filter { it.first > fromMs }.zipWithNext().count { (a, b) -> b.second < a.second }
    }

    @Test
    fun `a drop from 6 to 4 Mbps and back cuts once near the bottleneck and recovers within 20 s`() {
        // The link carries 6.5 Mbps when clear, so the 6 Mbps ceiling plus audio fits.
        val c = RtmpRateController(minBps = 1_000_000, maxBps = 6_000_000)
        val link = Link(c, 6_500_000)
        link.run(30_000)
        assertEquals(6_000_000, c.targetBps)
        link.peakWaitMs = 0.0
        link.capacityBps = 4_000_000
        val dropAt = link.nowMs
        link.run(40_000)
        val minTarget = link.targets.filter { it.first > dropAt }.minOf { it.second }
        assertTrue("never under 0.7x the bottleneck: $minTarget", minTarget >= 2_800_000)
        assertTrue("peak backlog under 1 s: ${link.peakWaitMs}", link.peakWaitMs < 1_000)
        link.capacityBps = 6_500_000
        val upAt = link.nowMs
        link.run(30_000)
        val back = link.targets.firstOrNull { it.first > upAt && it.second >= 5_400_000 }?.first
        assertTrue("back within 10% of the ceiling within 20 s: ${back?.minus(upAt)}\n${link.actions.filterIndexed { i, _ -> i % 4 == 0 }.takeLast(140).joinToString("\n")}", back != null && back - upAt <= 20_000)
        assertEquals("no flushes on a mere bottleneck", 0, link.flushes)
    }

    @Test
    fun `a linearly draining 3 s backlog cuts at most twice`() {
        val c = RtmpRateController(minBps = 1_000_000, maxBps = 6_000_000)
        val link = Link(c, 8_000_000)
        link.run(5_000)
        link.queueBits = 2.9 * 8_000_000 // ~2.9 s to drain at 8 Mbps, emptying steadily
        link.run(20_000)
        assertTrue("cuts: ${link.cuts(5_000)}", link.cuts(5_000) <= 2)
        assertEquals(0, link.flushes)
        assertTrue(c.targetBps >= 4_000_000)
    }

    @Test
    fun `a 2 s full stall does not hit the floor`() {
        val c = RtmpRateController(minBps = 1_000_000, maxBps = 6_000_000)
        val link = Link(c, 6_500_000)
        link.run(20_000)
        link.capacityBps = 0
        val at = link.nowMs
        link.run(2_000)
        link.capacityBps = 6_500_000
        link.run(15_000)
        val minTarget = link.targets.filter { it.first > at }.minOf { it.second }
        assertTrue("one 25% cut at most: $minTarget", minTarget >= 4_500_000)
        assertEquals(0, link.flushes)
    }

    @Test
    fun `a backlog past 3 s raises flushNow`() {
        val c = RtmpRateController(minBps = 1_000_000, maxBps = 6_000_000)
        c.update(0, 100, 6_000_000)
        assertFalse(c.flushNow)
        c.update(500, 3_500, 0)
        assertTrue(c.flushNow)
        // And in the loop: a long stall builds a backlog past 3 s, which gets flushed.
        val d = RtmpRateController(minBps = 1_000_000, maxBps = 6_000_000)
        val link = Link(d, 6_500_000)
        link.run(10_000)
        link.capacityBps = 0
        link.run(5_000)
        assertTrue(link.flushes >= 1)
    }

    @Test
    fun `one burst over the threshold is not congestion`() {
        val c = RtmpRateController(minBps = 1_000_000, maxBps = 6_000_000)
        c.update(0, 50, 6_000_000)
        c.update(500, 600, 6_000_000)
        assertEquals(6_000_000, c.targetBps)
        c.update(1_000, 50, 6_000_000)
        c.update(1_500, 600, 6_000_000)
        assertEquals(6_000_000, c.targetBps)
        c.update(2_000, 650, 5_000_000)
        assertEquals((5_000_000 * 0.85).toInt(), c.targetBps)
        assertEquals(6_000_000, c.knownLimitBps)
    }

    @Test
    fun `the ceiling caps the target and the ramp`() {
        val c = RtmpRateController(minBps = 1_000_000, maxBps = 6_000_000)
        c.update(0, 0, 6_000_000, ceilingBps = 4_000_000)
        assertEquals(4_000_000, c.targetBps)
        repeat(100) { c.update(500L * (it + 1), 0, 4_000_000, ceilingBps = 4_000_000) }
        assertEquals(4_000_000, c.targetBps)
        repeat(100) { c.update(50_500L + 500L * it, 0, 4_000_000, ceilingBps = 100) }
        assertEquals("never under the floor", 1_000_000, c.targetBps)
    }

    private companion object {
        const val STEP_MS = 50L
        const val AUDIO_BPS = 128_000
    }
}
