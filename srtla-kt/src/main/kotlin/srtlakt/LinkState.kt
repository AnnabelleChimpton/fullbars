// SPDX-License-Identifier: Apache-2.0
package srtlakt

import srtlakt.protocol.SeqMath
import srtlakt.protocol.Srt
import srtlakt.protocol.Srtla
import kotlin.math.max
import kotlin.math.min

enum class LinkPhase { DISCONNECTED, REGISTERING, CONNECTED }

/**
 * How eagerly a link is used. The scheduler fills the highest class that has a working link with room and only
 * spills into the next class when that whole class is full, behind or silent. Lower classes still get a small trickle
 * so their delay and capacity estimates are fresh the moment traffic spills onto them.
 */
enum class LinkPriority { PRIMARY, SECONDARY, BACKUP }

/**
 * What the platform knows about a link before it carries anything, used to pick which link registers the group
 * first (a dead or captive first pick costs a whole registration timeout). Declared best first.
 */
enum class LinkHint { ETHERNET, CELLULAR, WIFI_VALIDATED, HELPER, UNKNOWN, WIFI_UNVALIDATED, CAPTIVE_PORTAL }

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
 *
 * Both logs are [SeqLog]s, so sending a packet allocates nothing.
 */
internal class LinkState(val id: Int, val label: String, private val config: SrtlaConfig) {
    var phase = LinkPhase.DISCONNECTED
    var window = DEFAULT_WINDOW
        private set
    var priority = LinkPriority.PRIMARY
    var hint = LinkHint.UNKNOWN
    /**
     * Scales [capPackets] (1.0 = full). A helper phone running warm gets a smaller share this way without being
     * dropped. Never scales below one ACK batch, or the link could not be measured at all.
     */
    var capScale = 1.0

    /**
     * seq -> send time (ms), with [NAKED] set once a NAK for it was charged to this link and [RESENT] set when this
     * copy was itself a retransmission.
     */
    private val log = SeqLog(config.maxInFlightLog)
    /** The newest sequence number appended to [log], and whether [log] is still in sequence order (see onSrtAck). */
    private var logTailSeq = 0
    private var logOrdered = true
    /**
     * Separate send log for delay/delivery measurement: (send time ms shl 12) or size. Only this link's own SRTLA
     * ACK clears an entry. The SRT cumulative ACK (every 10 ms, all links) usually beats the receiver's batched
     * SRTLA ACK, so the in-flight [log] can't be used for this.
     */
    private val sentLog = SeqLog(config.maxInFlightLog)
    val inFlight: Int get() = log.size

    var lastReceivedMs = NEVER
    /**
     * Last time the receiver proved it hears *this* link's uplink: REG3, a keepalive echo, or an SRTLA ACK for a
     * packet this link carried. Plain inbound traffic is not proof — receivers send SRT ACKs down every link, so a
     * link with a dead uplink but working downlink would otherwise look alive forever. Set through [onProof].
     */
    var lastProofMs = NEVER
        private set
    /** Whether a data packet went out on this link since its last proof (an idle link can't be judged silent). */
    private var sentSinceProof = false
    var lastRegSentMs = NEVER
    var lastKeepaliveSentMs = NEVER
    var backoffUntilMs = 0L
    /** The OS refused a send (socket buffer full): treat the link as full until then (a light congestion hint). */
    var refusedUntilMs = NEVER

    var srttMs = -1.0
        private set
    var nakCount = 0L
        private set
    var packetsSent = 0L
        private set
    var bytesSent = 0L
        private set
    /** Packets the OS refused to queue on this link's socket (its send buffer was full). */
    var localDrops = 0L
        private set
    /** The socket send buffer the OS granted, as reported by the I/O layer (stats only). */
    var sendBufferBytes = 0

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

    fun onProof(nowMs: Long) {
        lastProofMs = nowMs
        sentSinceProof = false
    }

    /** Time since the last proof, or null before the first. */
    fun proofAgoMs(nowMs: Long): Long? = if (lastProofMs == NEVER) null else nowMs - lastProofMs

    /**
     * How long without proof makes a link silent: 1 s, or three round trips on a slow link. A working link proves
     * itself far more often than that: SRTLA ACKs every ~10 of its packets, and keepalive probes every 250 ms once
     * it has been quiet for half a second.
     */
    fun silentAfterMs(): Long = max(SILENT_MIN_MS, 3 * max(minRttMs, srttMs.toLong()))

    /**
     * Sent data since its last proof, and that proof is older than [silentAfterMs]: the receiver isn't hearing this
     * link. It gets no new data until a proof arrives. Without this, a blackholed link's lost packets aged out of
     * [sentLog] after 3 s, it looked empty again, and the scheduler refilled it.
     */
    fun isSilent(nowMs: Long): Boolean =
        sentSinceProof && lastProofMs != NEVER && nowMs - lastProofMs > silentAfterMs()

    /** Connected and heard from recently: the receiver can hear it right now. */
    fun isProven(nowMs: Long): Boolean =
        phase == LinkPhase.CONNECTED && lastProofMs != NEVER && nowMs - lastProofMs < silentAfterMs()

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
        var s = sentLog.first()
        repeat(ACK_BATCH_PACKETS - 1) { s = sentLog.next(s) }
        val closedAt = (sentLog.value(s) and NO_SAMPLE.inv()) ushr 12
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
     * [capScale] shrinks it for a link that should carry less.
     */
    fun capPackets(): Int {
        val rtt = minRttMs
        val full = if (rtt < 0 || maxDeliveredBps <= 0) MIN_CAP_PACKETS
        else max(MIN_CAP_PACKETS, (2 * (maxDeliveredBps / 8.0 * rtt / 1000.0 / AVG_PACKET_BYTES)).toInt() + ACK_BATCH_PACKETS)
        return if (capScale >= 1.0) full else max(ACK_BATCH_PACKETS, (full * capScale).toInt())
    }

    /** This link's own packets not yet confirmed by its SRTLA ACKs (lost ones age out after [SENT_LOG_MAX_AGE_MS]). */
    val ownInFlight: Int get() = sentLog.size

    /**
     * Full: at its in-flight cap, its ACKs are overdue (deep queue), it has gone silent, or the OS just refused a
     * send. Gets traffic only if every link is full.
     */
    fun atCap(nowMs: Long): Boolean =
        ownInFlight >= capPackets() || overdueMs(nowMs) > 0 || isSilent(nowMs) || nowMs < refusedUntilMs

    private var rateWindowStartMs = NEVER
    private var rateWindowBytes = 0L
    var txBitsPerSec = 0L
        private set

    fun score(): Int = if (phase != LinkPhase.CONNECTED) -1 else window / (inFlight + 1)

    /** The OS wouldn't queue a packet on this link: count it and keep the link out of the way briefly. */
    fun onRefused(nowMs: Long) {
        localDrops++
        refusedUntilMs = nowMs + REFUSED_HOLD_MS
    }

    /** A control packet (keepalive, REG) the OS refused: counted, but no reason to steer data away. */
    fun countLocalDrop() {
        localDrops++
    }

    /**
     * [seq]: the SRT data sequence number, or [Srt.NO_SEQ] for control packets.
     * [retransmit]: another link sent this sequence number before, so this link's copy can't be timed.
     * [resend]: this copy is a retransmission (the sequence number went out before on any link, this one included).
     */
    fun onSent(
        seq: Int,
        bytes: Int,
        nowMs: Long,
        overCap: Boolean = false,
        retransmit: Boolean = false,
        resend: Boolean = retransmit,
    ) {
        if (overCap) overCapSent++
        if (seq != Srt.NO_SEQ) {
            val k = seq.toLong()
            // A retransmit re-enters at the tail. A key that sorts before the old tail breaks the sequence order
            // onSrtAck's fast path relies on.
            if (log.size > 0 && !SeqMath.after(seq, logTailSeq)) logOrdered = false
            // Karn's rule: a resent packet's ACK can't be matched to a transmission, so it's never a delay sample.
            val resent = retransmit || sentLog.remove(k, ABSENT) != ABSENT
            log.putLast(k, (nowMs and TIME_MASK) or (if (resend || resent) RESENT else 0L))
            logTailSeq = seq
            sentLog.putLast(k, (nowMs shl 12) or bytes.coerceIn(0, 4095).toLong() or (if (resent) NO_SAMPLE else 0L))
            sentSinceProof = true
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

    /**
     * SRT cumulative ACK: everything before [ack] has been delivered. The log is normally in sequence order, so this
     * stops at the first entry the ACK doesn't cover. Only after a retransmission re-entered at the tail (out of
     * order) does it walk everything, and then it checks whether the order is back.
     */
    fun onSrtAck(ack: Int) {
        if (logOrdered) {
            while (log.size > 0 && SeqMath.after(ack, log.key(log.first()).toInt())) log.removeFirst()
            return
        }
        var s = log.first()
        var ordered = true
        var havePrev = false
        var prev = 0
        while (s != SeqLog.NIL) {
            val seq = log.key(s).toInt()
            if (SeqMath.after(ack, seq)) {
                s = log.removeSlot(s)
                continue
            }
            if (havePrev && !SeqMath.after(seq, prev)) ordered = false
            prev = seq; havePrev = true
            s = log.next(s)
        }
        logOrdered = ordered
        // The newest entry may have been cleared: the next append must be checked against the tail that's left.
        if (havePrev) logTailSeq = prev
    }

    /** True if this link's in-flight log still holds [seq] (it carried it, and no cumulative ACK has cleared it). */
    fun carried(seq: Int): Boolean = seq.toLong() in log

    /**
     * When this link sent [seq] if it carried it and was not yet charged a NAK for it; [NOT_UNPENALISED] otherwise.
     * The core charges a repeated loss to the latest carrier that hasn't paid for it yet (see [penalise]).
     */
    fun unpenalisedSendMs(seq: Int): Long {
        val v = log.get(seq.toLong(), ABSENT)
        return if (v == ABSENT || v and NAKED != 0L) NOT_UNPENALISED else v and TIME_MASK
    }

    /** Whether this link's copy of [seq] in the in-flight log was a retransmission. */
    fun carriedResend(seq: Int): Boolean {
        val v = log.get(seq.toLong(), ABSENT)
        return v != ABSENT && v and RESENT != 0L
    }

    /** Whether this link holds [seq] already charged with a NAK (periodic NAK reports repeat the same losses). */
    fun penalised(seq: Int): Boolean {
        val v = log.get(seq.toLong(), ABSENT)
        return v != ABSENT && v and NAKED != 0L
    }

    /**
     * Charges a NAK for [seq] to this link. The NAK'd packet deliberately STAYS in the in-flight log until a
     * cumulative SRT ACK (or its retransmission) clears it. Removing it immediately made a link that just lost a
     * burst look empty, so the scheduler refilled it at once — a feedback loop that, in the capped-link baseline,
     * doubled retransmissions vs BELABOX's sender.
     */
    fun penalise(seq: Int) {
        val s = log.slotOf(seq.toLong())
        if (s == SeqLog.NIL) return
        val v = log.value(s)
        if (v and NAKED != 0L) return
        log.setValue(s, v or NAKED)
        window = max(window - Srtla.WINDOW_DECR, Srtla.WINDOW_MIN * Srtla.WINDOW_MULT)
        nakCount++
    }

    /** Returns true if this link carried [seq] (and charges it; kept for callers that only have one link). */
    fun onNak(seq: Int): Boolean {
        if (!carried(seq)) return false
        penalise(seq)
        return true
    }

    /** Returns true if this link carried [seq]. */
    fun onSrtlaAckSpecific(seq: Int, nowMs: Long = NEVER): Boolean {
        val k = seq.toLong()
        val sent = sentLog.remove(k, ABSENT)
        if (sent != ABSENT && nowMs != NEVER) onDelivered((sent and NO_SAMPLE.inv()) ushr 12, (sent and 0xfff).toInt(), nowMs, sent and NO_SAMPLE == 0L)
        if (log.remove(k, ABSENT) == ABSENT) return sent != ABSENT
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
    fun hasSent(seq: Int): Boolean = seq.toLong() in sentLog

    /** Another link resent [seq]: this link's copy can't be timed either. */
    fun markResent(seq: Int) {
        val s = sentLog.slotOf(seq.toLong())
        if (s != SeqLog.NIL) sentLog.setValue(s, sentLog.value(s) or NO_SAMPLE)
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
        while (sentLog.size > 0 && nowMs - ((sentLog.value(sentLog.first()) and NO_SAMPLE.inv()) ushr 12) > SENT_LOG_MAX_AGE_MS) {
            sentLog.removeFirst()
        }
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
        clearLogs()
        ackRttMs = -1.0
        minRttCur = Long.MAX_VALUE; minRttPrev = Long.MAX_VALUE; minRttRotatedMs = NEVER
        maxDeliveredCur = 0; maxDeliveredPrev = 0; maxDeliveredRotatedMs = NEVER
        deliveredBps = 0; ackedWindowBytes = 0; ackedWindowStartMs = NEVER
        refusedUntilMs = NEVER
    }

    /** New SRT session upstream: sequence numbers restart, drop the log. */
    fun resetSession() = clearLogs()

    private fun clearLogs() {
        log.clear()
        sentLog.clear()
        logOrdered = true
        sentSinceProof = false
    }

    companion object {
        const val NEVER = Long.MIN_VALUE
        /** Flag bit in [log] values: a NAK for this packet was charged to the link. The send time stays readable. */
        private const val NAKED = 1L shl 62
        /** Flag bit in [log] values: this copy was a retransmission. */
        private const val RESENT = 1L shl 61
        /** The send time's bits in a [log] value (clock values are positive milliseconds, far below 2^61). */
        private const val TIME_MASK = RESENT - 1
        private const val ABSENT = Long.MIN_VALUE + 1
        const val NOT_UNPENALISED = Long.MIN_VALUE
        const val DEFAULT_WINDOW = Srtla.WINDOW_DEF * Srtla.WINDOW_MULT
        const val MAX_WINDOW = Srtla.WINDOW_MAX * Srtla.WINDOW_MULT
        private const val RATE_WINDOW_MS = 1000L
        private const val DELIVERY_WINDOW_MS = 500L
        private const val WINDOW_HALF_MS = 5_000L
        const val MIN_CAP_PACKETS = 20
        private const val SENT_LOG_MAX_AGE_MS = 3_000L
        private const val OVERDUE_MARGIN_MS = 100L
        const val SILENT_MIN_MS = 1_000L
        /** How long a link stays out of the way after the OS refused a send: about one socket-buffer drain. */
        const val REFUSED_HOLD_MS = 5L
        /** Flag bit in [sentLog] entries (above any realistic send time << 12). */
        private const val NO_SAMPLE = 1L shl 62
        const val ACK_BATCH_PACKETS = 10
        private const val AVG_PACKET_BYTES = 1316.0
    }
}
