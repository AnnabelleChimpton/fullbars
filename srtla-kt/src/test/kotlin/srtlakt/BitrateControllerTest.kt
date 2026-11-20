// SPDX-License-Identifier: Apache-2.0
package srtlakt

import srtlakt.abr.BitrateController
import srtlakt.abr.DelayPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BitrateControllerTest {
    private var now = 0L

    /** Two connected links sharing [txBps], both queueing [queueMs]. */
    private fun stats(queueMs: Int, txBps: Long, connected: Boolean = true) = SenderStats(
        groupRegistered = true,
        links = (0..1).map {
            LinkStats(
                id = it, label = "l$it", phase = if (connected) LinkPhase.CONNECTED else LinkPhase.REGISTERING,
                window = 20_000, inFlight = 0, rttMs = 50, lastReceivedAgoMs = 0, txBitsPerSec = txBps / 2,
                packetsSent = 0, naks = 0, minRttMs = 50, queueDelayMs = queueMs,
            )
        },
        totalTxBitsPerSec = txBps,
        droppedNoLink = 0,
    )

    /** Runs [seconds] of 250 ms updates with the given conditions; the links carry [overhead] x the target. */
    private fun run(c: BitrateController, seconds: Double, queueMs: Int, overhead: Double = 1.15) {
        repeat((seconds * 4).toInt()) {
            now += 250
            c.update(now, stats(queueMs, (c.targetBps * overhead).toLong()))
        }
    }

    private fun controller() = BitrateController(minBps = 1_000_000, maxBps = 6_000_000, startBps = 3_000_000)

    @Test
    fun `probes upward from the start while queues stay short, reaching full quality in seconds`() {
        val c = controller()
        run(c, 5.0, queueMs = 20)
        assertTrue(c.targetBps in 3_600_000..4_300_000, "~5%/s before any congestion: ${c.targetBps}")
        run(c, 15.0, queueMs = 20)
        assertEquals(6_000_000, c.targetBps)
    }

    @Test
    fun `backs off on queue delay and remembers where the cliff was`() {
        val c = controller()
        run(c, 2.0, queueMs = 20)
        val before = c.targetBps
        run(c, 0.5, queueMs = 400)
        assertEquals((before * 0.75).toInt().toDouble(), c.targetBps.toDouble(), 1.0)
        assertEquals(before, c.knownLimitBps)
    }

    @Test
    fun `within one episode cuts again only while the queue keeps growing`() {
        val c = controller()
        run(c, 1.0, queueMs = 20)
        run(c, 0.25, queueMs = 300) // first cut
        val afterFirst = c.targetBps
        run(c, 3.0, queueMs = 250) // draining: high but falling
        assertEquals(afterFirst, c.targetBps, "no cascade while draining")
        run(c, 1.0, queueMs = 600) // getting worse
        assertTrue(c.targetBps < afterFirst, "worsening: cut again")
    }

    @Test
    fun `a resend storm cuts, but overhead alone without queueing does not`() {
        val c = controller()
        run(c, 1.0, queueMs = 20)
        val t = c.targetBps
        run(c, 2.0, queueMs = 30, overhead = 2.5) // overhead spike, no queue: measurement noise
        assertTrue(c.targetBps >= t, "no cut without queueing")
        run(c, 0.5, queueMs = 150, overhead = 2.5) // storm: overhead and queue together
        assertTrue(c.targetBps < t, "storm cut: ${c.targetBps}")
    }

    @Test
    fun `after an episode it settles under the cliff before probing again`() {
        val c = controller()
        run(c, 1.0, queueMs = 20)
        run(c, 0.25, queueMs = 400)
        val limit = c.knownLimitBps
        run(c, 20.0, queueMs = 20)
        assertTrue(c.targetBps <= limit * 0.88, "settled under ~85% of the cliff: ${c.targetBps} vs $limit")
        run(c, 20.0, queueMs = 20) // past SETTLE_MS: probing resumes
        assertTrue(c.targetBps > limit * 0.88, "probing again: ${c.targetBps}")
    }

    @Test
    fun `never ramps while the stream isn't flowing yet`() {
        val c = controller()
        repeat(20) { now += 250; c.update(now, stats(queueMs = 0, txBps = 0)) } // links up, no data yet
        assertEquals(3_000_000, c.targetBps)
        run(c, 2.0, queueMs = 10)
        assertTrue(c.targetBps > 3_000_000, "ramps once data flows")
        assertEquals(2_500_000, BitrateController(minBps = 1_000_000, maxBps = 6_000_000).targetBps, "cold start default")
    }

    @Test
    fun `holds through a tunnel and never leaves its range`() {
        val c = controller()
        run(c, 1.0, queueMs = 20)
        val t = c.targetBps
        repeat(40) { now += 250; c.update(now, stats(queueMs = 0, txBps = 0, connected = false)) }
        assertEquals(t, c.targetBps)
        run(c, 30.0, queueMs = 900)
        assertEquals(1_000_000, c.targetBps)
        val high = BitrateController(minBps = 1_000_000, maxBps = 2_000_000, startBps = 2_000_000, preset = DelayPreset.FAST)
        run(high, 60.0, queueMs = 0)
        assertEquals(2_000_000, high.targetBps)
    }

    @Test
    fun `an outside cap lowers the target to what is sent, never raises it`() {
        val c = controller()
        c.capAt(2_000_000)
        assertEquals(2_000_000, c.targetBps)
        c.capAt(5_000_000)
        assertEquals(2_000_000, c.targetBps, "a higher cap doesn't raise it")
        c.capAt(10)
        assertEquals(1_000_000, c.targetBps, "never under the floor")
        c.capAt(Int.MAX_VALUE)
        assertEquals(1_000_000, c.targetBps, "no cap is a no-op")
    }
}
