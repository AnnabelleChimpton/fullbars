// SPDX-License-Identifier: Apache-2.0
package srtlakt

import srtlakt.protocol.SeqMath
import srtlakt.protocol.Srtla
import kotlin.math.max
import kotlin.math.min

enum class LinkPhase { DISCONNECTED, REGISTERING, CONNECTED }

/**
 * Per-uplink bookkeeping: registration phase, congestion window, in-flight packet log, RTT and throughput.
 *
 * Congestion behaviour follows the "classic" SRTLA rules so we interoperate predictably with every receiver:
 *  - NAK for a packet this link carried: window -= WINDOW_DECR (floor WINDOW_MIN * WINDOW_MULT)
 *  - SRTLA ACK for a packet this link carried: if in-flight * MULT > window, window += WINDOW_INCR - 1
 *  - any SRTLA-acked packet: every connected link gets window += 1 (cap WINDOW_MAX * WINDOW_MULT)
 *  - scheduling score = window / (in-flight + 1)
 *
 * On top of that, every SRTLA ACK for one of this link's packets is a delay sample (send -> ack). From those:
 *  - [minRttMs]: windowed minimum, the link's base delay; [ackRttMs]: smoothed; queue delay = the difference;
 *  - [deliveredBps]: bytes the receiver confirmed per second, and [maxDeliveredBps] its windowed maximum;
 *  - [capPackets]: an in-flight cap of about 2x the bandwidth-delay product plus the receiver's ACK batching, so a
 *    link can't be stuffed into seconds of bufferbloat. Classic SRTLA has no such cap; on capacity-limited links it
 *    let queues (and reordering, and false NAKs, and retransmissions) feed each other into a retransmission spiral.
 */
internal class LinkState(val id: Int, val label: String, private val config: SrtlaConfig) {
    var phase = LinkPhase.DISCONNECTED
    var window = DEFAULT_WINDOW
        private set

    private val log = LinkedHashMap<Int, Long>() // seq -> send time (ms), or NAKED
    /**
     * Separate send log for delay/delivery measurement: (send time ms shl 12) or size. Only this link's own SRTLA
     * ACK clears an entry. The SRT cumulative ACK (every 10 ms, all links) usually beats the receiver's batched
     * SRTLA ACK, so the in-flight [log] can't be used for this.
     */
    private val sentLog = LinkedHashMap<Int, Long>()
    val inFlight: Int get() = log.size

    var lastReceivedMs = NEVER
    /**
     * Last time the receiver proved it hears *this* link's uplink: REG3, a keepalive echo, or an SRTLA ACK for a
     * packet this link carried. Plain inbound traffic is not proof — receivers send SRT ACKs down every link, so a
     * link with a dead uplink but working downlink would otherwise look alive forever.
     */
    var lastProofMs = NEVER
    var lastRegSentMs = NEVER
    var lastKeepaliveSentMs = NEVER
    var backoffUntilMs = 0L

    var srttMs = -1.0
        private set
    var nakCount = 0L
        private set
    var packetsSent = 0L
        private set
    var bytesSent = 0L
        private set

    /** Delay from sending a packet to its SRTLA ACK (includes the receiver's ACK batching), smoothed. */
    var ackRttMs = -1.0
        private set
    private var minRttCur = Long.MAX_VALUE
    private var minRttPrev = Long.MAX_VALUE
    private var minRttRotatedMs = NEVER
    /** Windowed (~10 s) minimum send->ack delay: the link's base delay with empty queues. */
    val minRttMs: Long get() = min(minRttCur, minRttPrev).let { if (it == Long.MAX_VALUE) -1 else it }
    /** Queueing delay from ACK samples: smoothed minus minimum send->ack delay; 0 until there are samples. */
    private val sampledQueueMs: Int get() = if (ackRttMs < 0 || minRttMs < 0) 0 else max(0, (ackRttMs - minRttMs).toInt())

    /**
     * How overdue this link's ACKs are. The receiver acknowledges a link's packets in batches of ~10, so the oldest
     * unacknowledged packet's ACK is only due once ~10 packets have followed it on this link, one base delay after
     * the packet that closed its batch. Time beyond that (plus a margin) means the link's queue is deep, or it is
     * losing everything. With fewer than a batch outstanding nothing is due: a link we stopped feeding must not look
     * stuck just because its last partial batch can't close.
     *
     * Why it matters: a link whose ACKs stopped produces no delay samples, so its queue would look frozen; and once
     * its lost packets age out it would even look *empty* and attract more traffic (in one test run a 2 Mbps link
     * was fed 5 Mbps for a minute, sustaining a resend storm).
     */
    fun overdueMs(nowMs: Long): Long {
        if (sentLog.size < ACK_BATCH_PACKETS) return 0
        var i = 0
        var closedAt = 0L
        for (v in sentLog.values) {
            if (++i == ACK_BATCH_PACKETS) {
                closedAt = (v and NO_SAMPLE.inv()) ushr 12
                break
            }
        }
        return max(0L, nowMs - closedAt - max(0L, minRttMs) - OVERDUE_MARGIN_MS)
    }

    /** Queueing delay: the larger of the sampled estimate and how overdue the oldest packet is. */
    fun queueDelayMs(nowMs: Long): Int = max(sampledQueueMs.toLong(), overdueMs(nowMs)).toInt()

    private var ackedWindowStartMs = NEVER
    private var ackedWindowBytes = 0L
    /** Bytes per second the receiver confirmed over the last ~0.5 s. */
    var deliveredBps = 0L
        private set
    private var maxDeliveredCur = 0L
    private var maxDeliveredPrev = 0L
    private var maxDeliveredRotatedMs = NEVER
    /** Windowed (~10 s) maximum of [deliveredBps]: what this link has shown it can carry recently. */
    val maxDeliveredBps: Long get() = max(maxDeliveredCur, maxDeliveredPrev)
    /** Packets sent while this link was already at its in-flight cap (all links were full). */
    var overCapSent = 0L
        private set

    /**
     * In-flight cap in packets: ~2x bandwidth-delay product at the best recent delivery rate, plus the receiver's
     * ACK batching (it acknowledges every ~10 packets per link), never below [MIN_CAP_PACKETS]. Doubling the BDP
     * lets the link ramp up (the cap grows as delivery grows) while bounding its queue to ~one base RTT.
     */
    fun capPackets(): Int {
        val rtt = minRttMs
        if (rtt < 0 || maxDeliveredBps <= 0) return MIN_CAP_PACKETS
        val bdpPackets = maxDeliveredBps / 8.0 * rtt / 1000.0 / AVG_PACKET_BYTES
        return max(MIN_CAP_PACKETS, (2 * bdpPackets).toInt() + ACK_BATCH_PACKETS)
    }

    /** This link's own packets not yet confirmed by its SRTLA ACKs (lost ones age out after [SENT_LOG_MAX_AGE_MS]). */
    val ownInFlight: Int get() = sentLog.size
    /** Full: at its in-flight cap, or its ACKs are overdue (deep queue). Gets traffic only if every link is full. */
    fun atCap(nowMs: Long): Boolean = ownInFlight >= capPackets() || overdueMs(nowMs) > 0

    private var rateWindowStartMs = NEVER
    private var rateWindowBytes = 0L
    var txBitsPerSec = 0L
        private set

    fun score(): Int = if (phase != LinkPhase.CONNECTED) -1 else window / (inFlight + 1)

    /** [retransmit]: the core saw this sequence number go out before (possibly on another link). */
    fun onSent(seq: Int?, bytes: Int, nowMs: Long, overCap: Boolean = false, retransmit: Boolean = false) {
        if (overCap) overCapSent++
        if (seq != null) {
            log.remove(seq) // a retransmit re-enters at the tail
            log[seq] = nowMs
            // Karn's rule: a resent packet's ACK can't be matched to a transmission, so it's never a delay sample.
            val resent = retransmit || sentLog.remove(seq) != null
            sentLog[seq] = (nowMs shl 12) or bytes.coerceIn(0, 4095).toLong() or (if (resent) NO_SAMPLE else 0L)
            if (sentLog.size > config.maxInFlightLog) {
                val it = sentLog.keys.iterator()
                it.next(); it.remove()
            }
            if (log.size > config.maxInFlightLog) {
                val it = log.keys.iterator()
                it.next(); it.remove()
            }
        }
        packetsSent++
        bytesSent += bytes
        rateWindowBytes += bytes
        updateRate(nowMs)
    }

    fun updateRate(nowMs: Long) {
        if (rateWindowStartMs == NEVER) {
            rateWindowStartMs = nowMs
            return
        }
        val elapsed = nowMs - rateWindowStartMs
        if (elapsed >= RATE_WINDOW_MS) {
            txBitsPerSec = rateWindowBytes * 8 * 1000 / elapsed
            rateWindowBytes = 0
            rateWindowStartMs = nowMs
        }
    }

    /** SRT cumulative ACK: everything before [ack] has been delivered. */
    fun onSrtAck(ack: Int) {
        val it = log.keys.iterator()
        while (it.hasNext()) if (SeqMath.after(ack, it.next())) it.remove()
    }

    /**
     * Returns true if this link carried [seq].
     *
     * The NAK'd packet deliberately STAYS in the in-flight log until a cumulative SRT ACK (or its retransmission)
     * clears it. Removing it immediately made a link that just lost a burst look empty, so the scheduler refilled
     * it at once — a feedback loop that, in the capped-link baseline, doubled retransmissions vs BELABOX's sender.
     */
    fun onNak(seq: Int): Boolean {
        val sent = log[seq] ?: return false
        if (sent == NAKED) return true // already penalised for this loss (periodic NAK reports repeat it)
        log[seq] = NAKED
        window = max(window - Srtla.WINDOW_DECR, Srtla.WINDOW_MIN * Srtla.WINDOW_MULT)
        nakCount++
        return true
    }

    /** Returns true if this link carried [seq]. */
    fun onSrtlaAckSpecific(seq: Int, nowMs: Long = NEVER): Boolean {
        val sent = sentLog.remove(seq)
        if (sent != null && nowMs != NEVER) onDelivered((sent and NO_SAMPLE.inv()) ushr 12, (sent and 0xfff).toInt(), nowMs, sent and NO_SAMPLE == 0L)
        if (log.remove(seq) == null) return sent != null
        if (inFlight.toLong() * Srtla.WINDOW_MULT > window) {
            window = min(window + Srtla.WINDOW_INCR - 1, MAX_WINDOW)
        }
        return true
    }

    fun onSrtlaAckGlobal() {
        if (phase == LinkPhase.CONNECTED) window = min(window + 1, MAX_WINDOW)
    }

    private var batchMinRtt = Long.MAX_VALUE

    /**
     * Receivers acknowledge in batches (srtla_rec: every ~10 packets per link), so all but the newest packet in a
     * batch waited for the batch to fill, longer the slower the link runs. Only the batch's smallest delay (its
     * newest packet, which triggered the ACK) is a clean delay sample; feed it once per batch from [endAckBatch].
     */
    fun beginAckBatch() {
        batchMinRtt = Long.MAX_VALUE
    }

    fun endAckBatch() {
        val rtt = batchMinRtt
        if (rtt == Long.MAX_VALUE) return
        batchMinRtt = Long.MAX_VALUE
        ackRttMs = if (ackRttMs < 0) rtt.toDouble() else ackRttMs * 0.875 + rtt * 0.125
        minRttCur = min(minRttCur, rtt)
    }

    /** True if this link's delay log has [seq] (used to spot retransmissions across links). */
    fun hasSent(seq: Int): Boolean = sentLog.containsKey(seq)

    /** Another link resent [seq]: this link's copy can't be timed either. */
    fun markResent(seq: Int) {
        sentLog[seq]?.let { sentLog[seq] = it or NO_SAMPLE }
    }

    private fun onDelivered(sentMs: Long, bytes: Int, nowMs: Long, sample: Boolean) {
        val rtt = nowMs - sentMs
        if (rtt >= 0 && sample) {
            batchMinRtt = min(batchMinRtt, rtt)
            if (minRttRotatedMs == NEVER) minRttRotatedMs = nowMs
            if (nowMs - minRttRotatedMs >= WINDOW_HALF_MS) {
                minRttPrev = minRttCur; minRttCur = Long.MAX_VALUE; minRttRotatedMs = nowMs
            }
        }
        if (ackedWindowStartMs == NEVER) ackedWindowStartMs = nowMs
        ackedWindowBytes += bytes
        updateDelivered(nowMs)
    }

    /** Also called from tick so an idle link's delivery rate decays to 0 and lost packets age out. */
    fun updateDelivered(nowMs: Long) {
        val it = sentLog.values.iterator()
        while (it.hasNext()) if (nowMs - ((it.next() and NO_SAMPLE.inv()) ushr 12) > SENT_LOG_MAX_AGE_MS) it.remove() else break
        if (ackedWindowStartMs == NEVER) return
        val elapsed = nowMs - ackedWindowStartMs
        if (elapsed >= DELIVERY_WINDOW_MS) {
            deliveredBps = ackedWindowBytes * 8 * 1000 / elapsed
            ackedWindowBytes = 0
            ackedWindowStartMs = nowMs
            if (maxDeliveredRotatedMs == NEVER) maxDeliveredRotatedMs = nowMs
            if (nowMs - maxDeliveredRotatedMs >= WINDOW_HALF_MS) {
                maxDeliveredPrev = maxDeliveredCur; maxDeliveredCur = 0; maxDeliveredRotatedMs = nowMs
            }
            maxDeliveredCur = max(maxDeliveredCur, deliveredBps)
        }
    }

    fun onRttSample(rttMs: Long) {
        if (rttMs < 0) return
        srttMs = if (srttMs < 0) rttMs.toDouble() else srttMs * 0.875 + rttMs * 0.125
    }

    /** Fresh congestion state, e.g. after the link (re)registers. */
    fun resetCongestion() {
        window = DEFAULT_WINDOW
        log.clear()
        sentLog.clear()
        ackRttMs = -1.0
        minRttCur = Long.MAX_VALUE; minRttPrev = Long.MAX_VALUE; minRttRotatedMs = NEVER
        maxDeliveredCur = 0; maxDeliveredPrev = 0; maxDeliveredRotatedMs = NEVER
        deliveredBps = 0; ackedWindowBytes = 0; ackedWindowStartMs = NEVER
    }

    /** New SRT session upstream: sequence numbers restart, drop the log. */
    fun resetSession() {
        log.clear()
        sentLog.clear()
    }

    companion object {
        const val NEVER = Long.MIN_VALUE
        private const val NAKED = Long.MIN_VALUE + 1
        const val DEFAULT_WINDOW = Srtla.WINDOW_DEF * Srtla.WINDOW_MULT
        const val MAX_WINDOW = Srtla.WINDOW_MAX * Srtla.WINDOW_MULT
        private const val RATE_WINDOW_MS = 1000L
        private const val DELIVERY_WINDOW_MS = 500L
        private const val WINDOW_HALF_MS = 5_000L
        const val MIN_CAP_PACKETS = 20
        private const val SENT_LOG_MAX_AGE_MS = 3_000L
        private const val OVERDUE_MARGIN_MS = 100L
        /** Flag bit in [sentLog] entries (above any realistic send time << 12). */
        private const val NO_SAMPLE = 1L shl 62
        private const val ACK_BATCH_PACKETS = 10
        private const val AVG_PACKET_BYTES = 1316.0
    }
}
