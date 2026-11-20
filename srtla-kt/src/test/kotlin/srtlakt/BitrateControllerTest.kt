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
    fun `without a delivery figure, cuts again within an episode only while the queue keeps growing`() {
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

    // --- richer link models for the delivery, link-change, silence and loss rules ---

    private data class L(
        val id: Int, val txBps: Long, val deliveredBps: Long = 0, val queueMs: Int = 0, val connected: Boolean = true,
        val agoMs: Long = 0, val naks: Long = 0, val packets: Long = 0, val full: Boolean = true,
    )

    private fun stats(vararg ls: L) = SenderStats(
        groupRegistered = true,
        links = ls.map {
            LinkStats(
                id = it.id, label = "l${it.id}", phase = if (it.connected) LinkPhase.CONNECTED else LinkPhase.DISCONNECTED,
                window = 20_000, inFlight = 0, rttMs = 50, lastReceivedAgoMs = it.agoMs, txBitsPerSec = it.txBps,
                packetsSent = it.packets, naks = it.naks, minRttMs = 50, queueDelayMs = it.queueMs,
                deliveredBps = it.deliveredBps, ownInFlight = if (it.full) 100 else 10, capPackets = 50,
            )
        },
        totalTxBitsPerSec = ls.filter { it.connected }.sumOf { it.txBps },
        droppedNoLink = 0,
    )

    /** Settles [c] at a steady state for [seconds]: two links share 1.15x the target, short queues, full delivery. */
    private fun warm(c: BitrateController, seconds: Double) {
        repeat((seconds * 4).toInt()) {
            now += 250
            val tx = (c.targetBps * 1.15).toLong()
            c.update(now, stats(L(0, tx / 2, tx / 2, 20), L(1, tx / 2, tx / 2, 20)), c.targetBps.toLong())
        }
    }

    @Test
    fun `a delivery collapse with a growing queue is cut to what the bond carries within 1_5 s`() {
        val c = BitrateController(minBps = 500_000, maxBps = 6_000_000, startBps = 5_000_000)
        warm(c, 2.0)
        val start = now
        var q = 250
        var media = c.targetBps.toLong()
        while (now - start < 1_500) {
            now += 250
            q += 150 // the queue keeps growing
            // The bond now delivers 1.8 Mbps of link bytes; the encoder follows the target ~0.5 s late.
            c.update(now, stats(L(0, 3_000_000, 900_000, q), L(1, 3_000_000, 900_000, q)), media)
            if (now - start >= 500) media = c.targetBps.toLong()
        }
        assertTrue(c.targetBps <= 2_000_000, "within 1.5 s: ${c.targetBps} (${c.lastAction})")
        assertTrue(c.targetBps >= 1_000_000, "anchored to delivery, not the floor: ${c.targetBps}")
    }

    @Test
    fun `a plateau while delivering less than is asked cuts every half second`() {
        val c = BitrateController(minBps = 500_000, maxBps = 5_000_000, startBps = 5_000_000)
        warm(c, 2.0)
        // The bond falls behind: it delivers 2.7 Mbps of link bytes while the queue builds, still under back-off.
        repeat(4) {
            now += 250
            c.update(now, stats(L(0, 2_875_000, 1_350_000, 150), L(1, 2_875_000, 1_350_000, 150)), 5_000_000)
        }
        val cuts = mutableListOf<Long>()
        var last = c.targetBps
        val start = now
        while (now - start < 2_000) {
            now += 250
            // The queue plateaus at 400 ms; the encoder hasn't followed yet.
            c.update(now, stats(L(0, 2_875_000, 1_350_000, 400), L(1, 2_875_000, 1_350_000, 400)), 5_000_000)
            if (c.targetBps < last) cuts += now - start
            last = c.targetBps
        }
        assertTrue(cuts.size >= 2, "repeated cuts: $cuts")
        assertTrue(cuts.first() <= 250, "the first at once: $cuts")
        cuts.zipWithNext().forEach { (a, b) -> assertTrue(b - a <= 500, "a cut every <= 0.5 s: $cuts") }
        assertTrue(c.targetBps >= 1_500_000, "then held under what the bond delivers: ${c.targetBps}")
    }

    @Test
    fun `holds while draining when the links deliver at least what is asked`() {
        val c = BitrateController(minBps = 500_000, maxBps = 6_000_000, startBps = 4_000_000)
        warm(c, 2.0)
        now += 250
        c.update(now, stats(L(0, 2_300_000, 2_300_000, 300), L(1, 2_300_000, 2_300_000, 300)), c.targetBps.toLong())
        val afterFirst = c.targetBps
        assertTrue(afterFirst < 4_000_000, "first cut")
        repeat(12) {
            now += 250
            // Still queueing, but delivering well over the new target: the cut is working.
            c.update(now, stats(L(0, 2_300_000, 2_300_000, 600 - it * 20), L(1, 2_300_000, 2_300_000, 600 - it * 20)), afterFirst.toLong())
        }
        assertEquals(afterFirst, c.targetBps, c.lastAction)
        assertEquals("hold (draining)", c.lastAction)
    }

    @Test
    fun `an app-limited survivor never pulls the first cut below x0_75`() {
        val c = BitrateController(minBps = 500_000, maxBps = 6_000_000, startBps = 5_000_000)
        // Before the event: link 0 carried most of it; link 1 delivered only 1 Mbps, all it was given.
        repeat(8) {
            now += 250
            c.update(now, stats(L(0, 4_750_000, 4_750_000, 20), L(1, 1_000_000, 1_000_000, 20, full = false)), 5_000_000)
        }
        val before = c.targetBps
        now += 250
        // Link 0 stops delivering (still connected); only the lightly used link's 1 Mbps shows up.
        c.update(now, stats(L(0, 4_750_000, 0, 400), L(1, 1_000_000, 1_000_000, 400, full = true)), before.toLong())
        assertEquals(before * 0.75, c.targetBps.toDouble(), 1.0)
    }

    @Test
    fun `all links silent with a deep queue holds instead of cutting, and leaves the memory alone`() {
        val c = controller()
        warm(c, 2.0)
        val t = c.targetBps
        val limit = c.knownLimitBps
        repeat(12) {
            now += 250
            val ago = 1_100L + it * 250
            c.update(now, stats(L(0, 1_700_000, 0, 900, agoMs = ago), L(1, 1_700_000, 0, 900, agoMs = ago)), t.toLong())
            assertTrue(c.lastAction.startsWith("hold"), "no cut while silent: ${c.lastAction}")
        }
        assertEquals("hold (all links silent)", c.lastAction)
        assertEquals(t, c.targetBps)
        assertEquals(limit, c.knownLimitBps)
    }

    @Test
    fun `a link that carried half the traffic leaving cuts by its share on the same tick`() {
        val c = BitrateController(minBps = 500_000, maxBps = 5_000_000, startBps = 5_000_000)
        warm(c, 2.0)
        val old = c.targetBps
        now += 250
        c.update(now, stats(L(0, 2_875_000, 2_875_000, 20), L(1, 2_875_000, 0, 0, connected = false)), old.toLong())
        assertTrue(c.targetBps <= 2_300_000, "same tick: ${c.targetBps}")
        assertEquals(old, c.knownLimitBps, "an episode starts at the old target")
    }

    @Test
    fun `a link that carried under 10 percent leaving changes nothing`() {
        val c = BitrateController(minBps = 500_000, maxBps = 6_000_000, startBps = 5_000_000)
        repeat(8) {
            now += 250
            c.update(now, stats(L(0, 5_200_000, 5_200_000, 20), L(1, 500_000, 500_000, 20)), 5_000_000)
        }
        val t = c.targetBps
        now += 250
        c.update(now, stats(L(0, 5_200_000, 5_200_000, 20), L(1, 500_000, 0, connected = false)), 5_000_000)
        assertTrue(c.targetBps >= t, "no cut: ${c.targetBps} vs $t")
    }

    @Test
    fun `links back after a gap with none resume at 60 percent of the limit and hold`() {
        val c = BitrateController(minBps = 500_000, maxBps = 6_000_000, startBps = 5_000_000)
        warm(c, 2.0)
        val limit = c.knownLimitBps
        repeat(20) { now += 250; c.update(now, stats(L(0, 0, connected = false), L(1, 0, connected = false))) }
        now += 250
        c.update(now, stats(L(0, 1_000_000, 0, 0), L(1, 1_000_000, 0, 0)), c.targetBps.toLong())
        val resumed = c.targetBps
        assertTrue(resumed <= limit * 0.6 + 1, "resumed at $resumed vs limit $limit")
        assertEquals(limit, c.knownLimitBps, "the limit is kept")
        val start = now
        while (now - start < 1_750) {
            now += 250
            val tx = (c.targetBps * 1.15).toLong()
            c.update(now, stats(L(0, tx / 2, tx / 2, 10), L(1, tx / 2, tx / 2, 10)), c.targetBps.toLong())
            assertEquals(resumed, c.targetBps, "no rise for 2 s (${c.lastAction})")
        }
        // An SRT restart does the same.
        val d = BitrateController(minBps = 500_000, maxBps = 6_000_000, startBps = 5_000_000)
        warm(d, 2.0)
        d.onResume()
        warm(d, 0.25)
        assertTrue(d.targetBps <= d.knownLimitBps * 0.6 + 1, "onResume: ${d.targetBps}")
    }

    @Test
    fun `a high NAK rate with a reorder-tolerant receiver holds, never cuts or probes`() {
        val c = BitrateController(minBps = 1_000_000, maxBps = 6_000_000, startBps = 3_000_000, receiverReorderTolerant = true)
        var naks = 0L
        var packets = 0L
        repeat(40) {
            now += 250
            packets += 100; naks += 5 // 5% NAKed, none lost for good
            val tx = (c.targetBps * 1.15).toLong()
            c.update(now, stats(L(0, tx, tx, 20, naks = naks, packets = packets)), c.targetBps.toLong())
        }
        assertEquals(3_000_000, c.targetBps, c.lastAction)
        assertEquals("hold (loss)", c.lastAction)
        assertEquals(0.05, c.nakRate, 0.001)
        // A stock receiver's NAKs are reordering noise: the same stream ramps.
        val stock = BitrateController(minBps = 1_000_000, maxBps = 6_000_000, startBps = 3_000_000)
        naks = 0; packets = 0
        repeat(40) {
            now += 250
            packets += 100; naks += 5
            val tx = (stock.targetBps * 1.15).toLong()
            stock.update(now, stats(L(0, tx, tx, 20, naks = naks, packets = packets)), stock.targetBps.toLong())
        }
        assertTrue(stock.targetBps > 3_000_000)
    }

    @Test
    fun `unrecovered loss of 1 percent for 3 s with a short queue cuts`() {
        val c = BitrateController(minBps = 1_000_000, maxBps = 6_000_000, startBps = 5_000_000)
        var packets = 0L
        var t = c.targetBps
        var cut = false
        repeat(14) {
            now += 250
            packets += 150 // 600 packets/s; 6 lost/s = 1%
            if (!cut) t = c.targetBps
            c.update(now, stats(L(0, 5_750_000, 5_750_000, 30, packets = packets)), 5_000_000, unrecoveredLossPerSec = 6.0)
            if (c.targetBps < t) cut = true
        }
        assertTrue(cut, "cut on loss: ${c.lastAction}")
        assertEquals(t * 0.75, c.targetBps.toDouble(), 1.0)
        assertEquals(t, c.knownLimitBps, "marks an episode")
        // Two lost packets a second is under the threshold.
        val d = BitrateController(minBps = 1_000_000, maxBps = 6_000_000, startBps = 5_000_000)
        packets = 0
        repeat(14) {
            now += 250
            packets += 150
            d.update(now, stats(L(0, 5_750_000, 5_750_000, 30, packets = packets)), 5_000_000, unrecoveredLossPerSec = 2.0)
        }
        assertTrue(d.targetBps >= 5_000_000, "no cut: ${d.lastAction}")
    }

    @Test
    fun `a measured start reaches past the capacity within 5 s on clean links`() {
        val c = BitrateController(minBps = 1_000_000, maxBps = 8_000_000)
        c.seed(5_000_000)
        assertEquals(3_750_000, c.targetBps)
        assertEquals(5_000_000, c.knownLimitBps)
        val start = now
        while (now - start < 5_000) {
            now += 250
            val tx = (c.targetBps * 1.15).toLong()
            c.update(now, stats(L(0, tx / 2, tx / 2, 5), L(1, tx / 2, tx / 2, 5)), c.targetBps.toLong())
        }
        assertTrue(c.targetBps >= 5_700_000, "after 5 s: ${c.targetBps}")
        // Without a seed, the old cold start.
        assertEquals(2_500_000, BitrateController(minBps = 1_000_000, maxBps = 8_000_000).targetBps)
        // A seed above the ceiling starts at the ceiling.
        val capped = BitrateController(minBps = 1_000_000, maxBps = 4_000_000).apply { seed(10_000_000) }
        assertEquals(4_000_000, capped.targetBps)
    }

    @Test
    fun `delay presets follow the latency`() {
        assertEquals(DelayPreset.FAST, DelayPreset.forLatency(800))
        assertEquals(DelayPreset.FAST, DelayPreset.forLatency(1_000))
        assertEquals(DelayPreset.BALANCED, DelayPreset.forLatency(2_000))
        assertEquals(DelayPreset.ROCK_SOLID, DelayPreset.forLatency(4_000))
        assertEquals(150, DelayPreset.forLatency(1_000).backOffQueueMs)
        assertEquals(200, DelayPreset.forLatency(2_000).backOffQueueMs)
        assertEquals(500, DelayPreset.forLatency(4_000).backOffQueueMs)
        assertEquals(80, DelayPreset.forLatency(2_000).rampQueueMs)
    }

    @Test
    fun `an emergency queue halves at once`() {
        val c = BitrateController(minBps = 500_000, maxBps = 6_000_000, startBps = 4_000_000)
        warm(c, 2.0)
        val t = c.targetBps
        now += 250
        c.update(now, stats(L(0, 2_300_000, 0, 800), L(1, 2_300_000, 0, 800)), t.toLong())
        assertEquals(t * 0.5, c.targetBps.toDouble(), 1.0)
    }
}
