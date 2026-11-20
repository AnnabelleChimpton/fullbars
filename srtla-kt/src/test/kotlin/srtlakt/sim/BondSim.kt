// SPDX-License-Identifier: Apache-2.0
package srtlakt.sim

import srtlakt.LinkPhase
import srtlakt.LinkStats
import srtlakt.SenderStats
import srtlakt.abr.BitrateController
import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * A closed-loop model of a bonded uplink driving a [BitrateController], deterministic for a given seed. It is a
 * bottleneck model, not a packet simulator: good enough to compare controller changes, not to predict absolute
 * numbers on a real bond.
 *
 * Each link is a bottleneck with a rate, a base RTT and a FIFO buffer (bounded, in ms of its nominal rate; what
 * overflows is lost and resent). The encoder applies a new target after [encoderLagMs] (300-500 ms, picked per seed),
 * with +-15% frame-size noise and a keyframe burst every 2 s. Bits on the links are media x [overhead] (SRT headers
 * and ordinary resends), plus, when a [Cliff] is set, a reorder-to-resend term above its onset: on links with
 * different delays the receiver asks for resends of packets that were only late, and past a point that feeds itself.
 * That reproduces the ~3.3 Mbps cliff seen on a 2/2/1.5 Mbit bond.
 *
 * Every [TICK_MS] it fills [SenderStats] the way SrtlaCore does (queue delay sampled one RTT late and topped up by how
 * overdue the oldest packet is, a 1 s send rate, 500 ms delivery windows, in-flight vs cap, NAK and packet counts,
 * time since the link last heard anything) and calls [BitrateController.update].
 */
class BondSim(
    links: List<LinkSpec>,
    val controller: BitrateController,
    seed: Long,
    val overhead: Double = 1.15,
    val cliff: Cliff? = null,
) {
    /** One uplink. [bufferMs]: how much its bottleneck buffer holds, in ms of [rateBps]. */
    data class LinkSpec(val rateBps: Long, val baseRttMs: Int, val bufferMs: Int = 1_500)

    /**
     * Reorder-driven resends: above [onsetMediaBps] of media, each extra 1% of media adds [gain]% resends (capped at
     * [maxResend]). Reordering scales with how hard the links are pushed, so the onset is in media terms.
     */
    data class Cliff(val onsetMediaBps: Long, val gain: Double = 2.5, val maxResend: Double = 1.5)

    /** One tick of output, for the bench's metrics. */
    data class Sample(
        val tMs: Long, val targetBps: Int, val appliedBps: Int, val queueMs: Int, val mediaBps: Long,
        val deliveredBps: Long, val action: String,
    )

    private val rnd = Random(seed)
    val encoderLagMs: Long = 300L + rnd.nextInt(201)
    private val links = links.mapIndexed { i, s -> SimLink(i, s) }

    var nowMs = 0L
        private set
    private var tick = 0L
    private var applied = controller.targetBps
    private var pendingFromMs = Long.MIN_VALUE
    private var lastRequested = controller.targetBps
    private val mediaRing = DoubleArray(4) // bits per tick over the last second
    private var resendCarry = 0.0
    val samples = ArrayList<Sample>()

    /** Changes a link's rate now (0 = blackholed: it still accepts packets into its buffer, but nothing leaves). */
    fun setRate(linkId: Int, rateBps: Long) { links[linkId].rate = rateBps }

    /** Takes a link out (the network went away: its sends fail, its queue is lost) or brings it back. */
    fun setConnected(linkId: Int, connected: Boolean) {
        val l = links[linkId]
        if (!connected) l.reset()
        l.connected = connected
    }

    /** The highest media rate the bond carries without a growing queue, given the overhead and the cliff. */
    fun kneeMediaBps(): Long {
        val cap = links.filter { it.connected }.sumOf { it.rate }.toDouble()
        var lo = 0.0
        var hi = cap
        repeat(60) {
            val m = (lo + hi) / 2
            if (m * overhead * (1 + resendFraction(m)) <= cap) lo = m else hi = m
        }
        return lo.roundToLong()
    }

    private fun resendFraction(mediaBps: Double): Double {
        val c = cliff ?: return 0.0
        return (c.gain * (mediaBps / c.onsetMediaBps - 1)).coerceIn(0.0, c.maxResend)
    }

    fun run(durationMs: Long, each: (BondSim) -> Unit = {}) {
        val end = nowMs + durationMs
        while (nowMs < end) {
            step()
            each(this)
        }
    }

    private fun step() {
        nowMs += TICK_MS
        tick++
        val dt = TICK_MS / 1000.0
        // Encoder: a new target lands encoderLagMs after the first request it hasn't applied yet.
        val t = controller.targetBps
        if (t != lastRequested && pendingFromMs == Long.MIN_VALUE) pendingFromMs = nowMs
        lastRequested = t
        if (pendingFromMs != Long.MIN_VALUE && nowMs - pendingFromMs >= encoderLagMs) {
            applied = t
            pendingFromMs = Long.MIN_VALUE
        }
        // Frames: noise per tick, and the keyframe's extra bits taken from the rest of the GOP (CBR on average).
        val gopTicks = (KEYFRAME_MS / TICK_MS).toInt()
        val shape = if (tick % gopTicks == 0L) KEY_TICK_SHARE else (gopTicks - KEY_TICK_SHARE) / (gopTicks - 1)
        val mediaBits = applied * dt * shape * (1 + (rnd.nextDouble() * 2 - 1) * NOISE)
        mediaRing[(tick % mediaRing.size).toInt()] = mediaBits
        val mediaBps = mediaRing.sum() / (mediaRing.size * dt)

        // Link bits: overhead, reorder resends above the cliff, and resends of what the buffers dropped last tick.
        val offered = mediaBits * overhead * (1 + resendFraction(mediaBps)) + resendCarry
        val resendBits = mediaBits * overhead * resendFraction(mediaBps) + resendCarry
        resendCarry = 0.0
        val up = links.filter { it.connected }
        val weight = up.sumOf { max(it.rate, 1L) }.toDouble()
        for (l in up) {
            val share = if (weight > 0) offered * max(l.rate, 1L) / weight else 0.0
            resendCarry += l.carry(nowMs, share, resendBits * (if (offered > 0) share / offered else 0.0), dt)
        }
        // A resend that would arrive after the SRT latency is dropped by the sender, so the carry can't grow forever.
        resendCarry = min(resendCarry, offered)

        val stats = SenderStats(
            groupRegistered = true,
            links = links.map { it.stats(nowMs) },
            totalTxBitsPerSec = links.sumOf { if (it.connected) it.txBps() else 0L },
            droppedNoLink = 0,
        )
        controller.update(nowMs, stats, mediaBps.roundToLong())
        samples += Sample(
            nowMs, controller.targetBps, applied, stats.queueDelayMs, mediaBps.roundToLong(),
            stats.deliveredBps, controller.lastAction,
        )
    }

    private class SimLink(val id: Int, spec: LinkSpec) {
        val nominal = spec.rateBps
        var rate = spec.rateBps
        val rttMs = spec.baseRttMs
        val bufferMs = spec.bufferMs
        var connected = true
        private var backlog = 0.0
        private val txRing = DoubleArray(4)
        private var txIdx = 0
        private val queueHist = IntArray(16)
        private var histIdx = 0
        private var lastDrainMs = 0L
        private var lastSampleQueue = 0
        private var windowBits = 0.0
        private var windowStartMs = 0L
        private var delivered = 0L
        private val maxRing = LongArray(20) // 500 ms windows over 10 s
        private var maxIdx = 0
        private var packets = 0.0
        private var naks = 0.0

        fun reset() {
            backlog = 0.0; txRing.fill(0.0); queueHist.fill(0); windowBits = 0.0; delivered = 0; maxRing.fill(0)
            lastSampleQueue = 0
        }

        /** Sends [bits] into the link for one tick; returns what overflowed (to be resent). */
        fun carry(nowMs: Long, bits: Double, resendBits: Double, dt: Double): Double {
            txRing[txIdx] = bits; txIdx = (txIdx + 1) % txRing.size
            packets += bits / PACKET_BITS
            naks += resendBits / PACKET_BITS
            backlog += bits
            val limit = nominal * bufferMs / 1000.0
            val dropped = max(0.0, backlog - limit)
            backlog -= dropped
            naks += dropped / PACKET_BITS
            val drained = min(backlog, rate * dt)
            backlog -= drained
            if (drained > 0) lastDrainMs = nowMs
            windowBits += drained
            if (nowMs - windowStartMs >= DELIVERY_WINDOW_MS) {
                delivered = (windowBits * 1000 / (nowMs - windowStartMs)).roundToLong()
                maxRing[maxIdx] = delivered; maxIdx = (maxIdx + 1) % maxRing.size
                windowBits = 0.0; windowStartMs = nowMs
            }
            // A delay sample exists only when something was acknowledged; it describes a packet sent a while ago.
            val q = if (rate > 0) (backlog * 1000 / rate).roundToInt() else lastSampleQueue
            queueHist[histIdx] = q; histIdx = (histIdx + 1) % queueHist.size
            if (drained > 0) {
                val back = (rttMs / TICK_MS).toInt().coerceIn(0, queueHist.size - 1)
                lastSampleQueue = queueHist[(histIdx - 1 - back + queueHist.size) % queueHist.size]
            }
            return dropped
        }

        fun txBps(): Long = (txRing.sum() / (txRing.size * TICK_MS / 1000.0)).roundToLong()

        fun stats(nowMs: Long): LinkStats {
            val silentMs = nowMs - lastDrainMs
            val overdue = max(0L, silentMs - rttMs - OVERDUE_MARGIN_MS).toInt()
            val maxDelivered = maxRing.max()
            val inFlight = ((backlog + rate * rttMs / 1000.0) / PACKET_BITS).roundToInt()
            val cap = if (maxDelivered <= 0) MIN_CAP_PACKETS
            else max(MIN_CAP_PACKETS, (2 * maxDelivered / 8.0 * rttMs / 1000.0 / 1316).toInt() + 10)
            return LinkStats(
                id = id, label = "sim$id", phase = if (connected) LinkPhase.CONNECTED else LinkPhase.REGISTERING,
                window = 20_000, inFlight = inFlight, rttMs = rttMs + lastSampleQueue,
                lastReceivedAgoMs = if (connected) silentMs + rttMs / 2 else null,
                txBitsPerSec = if (connected) txBps() else 0, packetsSent = packets.roundToLong(),
                naks = naks.roundToLong(), minRttMs = rttMs, queueDelayMs = if (connected) max(lastSampleQueue, overdue) else 0,
                deliveredBps = if (connected) delivered else 0, maxDeliveredBps = maxDelivered,
                ownInFlight = inFlight, capPackets = cap,
            )
        }
    }

    companion object {
        const val TICK_MS = 250L
        const val KEYFRAME_MS = 2_000L
        /** The keyframe tick carries this many ticks' worth of bits. */
        const val KEY_TICK_SHARE = 2.0
        const val NOISE = 0.15
        const val PACKET_BITS = 1316 * 8.0
        const val DELIVERY_WINDOW_MS = 500L
        const val OVERDUE_MARGIN_MS = 100L
        const val MIN_CAP_PACKETS = 20
    }
}
