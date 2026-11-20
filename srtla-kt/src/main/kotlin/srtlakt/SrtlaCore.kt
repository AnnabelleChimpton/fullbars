// SPDX-License-Identifier: Apache-2.0
package srtlakt

import srtlakt.protocol.Bytes
import srtlakt.protocol.KeepaliveInfo
import srtlakt.protocol.Packets
import srtlakt.protocol.Srt
import srtlakt.protocol.Srtla
import java.security.SecureRandom
import java.util.Random
import kotlin.math.max

data class SrtlaConfig(
    /**
     * A connected link with no proof from the receiver for this long is declared dead. 4 s like BELABOX's sender;
     * lower makes cellular links with deep buffers flap. Traffic leaves a silent link long before this (see
     * [LinkState.isSilent]); this only decides when it is reported down and re-registered.
     */
    val connTimeoutMs: Long = 4_000,
    /**
     * How long to wait for REG2 after sending REG1 before trying another link, before any round trip is known. Once a
     * link has an RTT the wait is 3 RTTs, but never below [reg2MinTimeoutMs] (a slow but working link would answer
     * late and leave its address tied to an orphan group on the receiver for ~10 s) nor above this.
     */
    val reg2TimeoutMs: Long = 2_000,
    val reg2MinTimeoutMs: Long = 1_000,
    /** Retry interval for REG2 on links that are not (yet) connected. Fast, so recovered links rejoin quickly. */
    val regRetryMs: Long = 500,
    val keepaliveIntervalMs: Long = 1_000,
    /** A connected link without proof for this long is probed every [silentProbeIntervalMs] instead. */
    val silentProbeAfterMs: Long = 500,
    val silentProbeIntervalMs: Long = 250,
    val regErrBackoffMs: Long = 1_000,
    val regNakBackoffMs: Long = 5_000,
    val maxInFlightLog: Int = 1_024,
    /** Duplicate control packets from the receiver (it echoes SRT ACKs on every link) are dropped within this window. */
    val dedupWindowMs: Long = 500,
    /** On group loss, send the local SRT caller a SHUTDOWN so it reconnects at once (see onRegNgp). */
    val resetClientOnGroupLoss: Boolean = true,
    /** Consecutive send failures (network gone) after which the I/O layer declares a link dead. */
    val sendErrorsToFail: Int = 5,
    /** Send retransmissions on the fastest link with room instead of wherever the next new packet would go. */
    val steerResends: Boolean = true,
    /**
     * One in this many new data packets goes to an idle lower-priority link with room, so its delay and capacity
     * estimates are current when traffic spills onto it (0 = never). Only matters once links have priorities.
     */
    val warmTrickleEvery: Int = 25,
)

sealed interface SrtlaEvent {
    data class GroupRegistered(val linkId: Int) : SrtlaEvent
    data object GroupLost : SrtlaEvent
    /** [afterOutage]: no other link was connected, so the receiver hears this phone again after hearing nothing. */
    data class LinkUp(val linkId: Int, val label: String, val afterOutage: Boolean = false) : SrtlaEvent
    data class LinkDown(val linkId: Int, val label: String, val reason: String) : SrtlaEvent
    data class LinkRefused(val linkId: Int, val label: String) : SrtlaEvent
}

data class LinkStats(
    val id: Int,
    val label: String,
    val phase: LinkPhase,
    val window: Int,
    val inFlight: Int,
    val rttMs: Int?,
    val lastReceivedAgoMs: Long?,
    val txBitsPerSec: Long,
    val packetsSent: Long,
    val naks: Long,
    /** Windowed minimum send->SRTLA-ACK delay (base delay), or null before samples. */
    val minRttMs: Int? = null,
    /** Smoothed minus minimum send->ack delay: how much this link is queueing. */
    val queueDelayMs: Int = 0,
    val deliveredBps: Long = 0,
    /** Best delivery rate seen recently (~10 s): this link's demonstrated capacity. */
    val maxDeliveredBps: Long = 0,
    val ownInFlight: Int = 0,
    val capPackets: Int = 0,
    val overCapSent: Long = 0,
    /** Time since the receiver last proved it hears this link (REG3, keepalive echo, SRTLA ACK); null before any. */
    val proofAgoMs: Long? = null,
    /**
     * Connected and proven recently enough that the receiver can hear it now. Hand-built stats (tests) of a connected
     * link count as proven; the core always fills it in.
     */
    val proven: Boolean = phase == LinkPhase.CONNECTED,
    val priority: LinkPriority = LinkPriority.PRIMARY,
    /** Packets the OS refused to queue on this link's socket (send buffer full). */
    val localDrops: Long = 0,
    /** Everything sent on this link so far (data used). */
    val bytesSent: Long = 0,
    /** The socket send buffer the OS actually granted (Android clamps requests), 0 if unknown. */
    val sendBufferBytes: Int = 0,
)

data class SenderStats(
    val groupRegistered: Boolean,
    val links: List<LinkStats>,
    val totalTxBitsPerSec: Long,
    val droppedNoLink: Long,
    /** Packets every link refused to queue (each socket's send buffer was full): lost before leaving the phone. */
    val droppedLocal: Long = 0,
    /** SRT control packets from the receiver held back during the caller's handshake. */
    val droppedDuringHandshake: Long = 0,
    /** Sends that failed with an error (network gone), all links together. */
    val sendErrors: Long = 0,
    /** Packets for the local SRT caller the OS wouldn't queue (localhost socket full). */
    val clientSendDrops: Long = 0,
    /** The localhost socket's receive buffer the OS actually granted, 0 if unknown. */
    val clientReceiveBufferBytes: Int = 0,
) {
    val connectedLinks: Int get() = links.count { it.phase == LinkPhase.CONNECTED }
    /**
     * Connected links the receiver has heard from recently. Lower than [connectedLinks] while links are silent but not
     * yet timed out (a dead zone): that is when to show the streamer trouble, seconds before links are declared down.
     */
    val provenLinks: Int get() = links.count { it.phase == LinkPhase.CONNECTED && it.proven }
    /** The most recent proof on any connected link, or null when none is connected. */
    val freshestProofAgoMs: Long? get() = links.filter { it.phase == LinkPhase.CONNECTED }.mapNotNull { it.proofAgoMs }.minOrNull()

    private val connected: List<LinkStats> get() = links.filter { it.phase == LinkPhase.CONNECTED }

    /** Queueing delay across links, weighted by how much each link carries (a busy queueing link matters most). */
    val queueDelayMs: Int
        get() {
            val c = connected
            val w = c.sumOf { it.txBitsPerSec }
            return if (w <= 0) c.maxOfOrNull { it.queueDelayMs } ?: 0
            else (c.sumOf { it.queueDelayMs.toLong() * it.txBitsPerSec } / w).toInt()
        }
    val deliveredBps: Long get() = connected.sumOf { it.deliveredBps }
    /** Sum of each link's best recent delivery rate: what the bond has demonstrated it can carry. */
    val capacityBps: Long get() = connected.sumOf { it.maxDeliveredBps }
    val packetsSent: Long get() = links.sumOf { it.packetsSent }
    val overCapSent: Long get() = links.sumOf { it.overCapSent }
    val naks: Long get() = links.sumOf { it.naks }
    val localDrops: Long get() = links.sumOf { it.localDrops }
    val bytesSent: Long get() = links.sumOf { it.bytesSent }

    companion object {
        val EMPTY = SenderStats(false, emptyList(), 0, 0)
    }
}

/** Where the core wants bytes to go. Implementations must not retain [buf] after returning. */
interface SrtlaOutput {
    /**
     * Returns false when the packet did not leave: the socket's send buffer was full, or the link has no socket (a
     * send error is also false). The core then tries another link and never counts the packet as sent on this one.
     */
    fun toLink(linkId: Int, buf: ByteArray, off: Int, len: Int): Boolean
    fun toClient(buf: ByteArray, off: Int, len: Int)
}

/**
 * I/O-free, clock-free SRTLA sender state machine. Single-threaded: callers serialise all calls.
 *
 * Data flow: the local SRT caller (libsrt) sends to us -> [onClientPacket] picks the best link.
 * Packets arriving on any link -> [onLinkPacket]: SRTLA control is consumed here, SRT control is
 * de-duplicated and handed back to the client.
 *
 * The per-packet paths ([onClientPacket] for data, SRTLA and SRT ACKs in [onLinkPacket]) allocate nothing.
 */
class SrtlaCore(
    private val config: SrtlaConfig = SrtlaConfig(),
    random: Random = SecureRandom(),
    private val onEvent: (SrtlaEvent) -> Unit = {},
) {
    private val srtlaId = ByteArray(Srtla.ID_LEN).also { random.nextBytes(it) }
    private val links = LinkedHashMap<Int, LinkState>()
    /** The same links, for index loops on the per-packet paths (an iterator per packet adds up). */
    private val linkList = ArrayList<LinkState>()

    private var groupRegistered = false
    private var reg1LinkId: Int? = null
    private var reg1SentMs = LinkState.NEVER
    private var reg1Cursor = 0
    /** Links that got a REG1 in this registration round: a late REG2 from any of them is still a valid group. */
    private val reg1Tried = HashSet<Int>()

    private var droppedNoLink = 0L
    private var droppedLocal = 0L
    /** Between the caller's handshake induction and its first non-handshake packet. */
    private var clientHandshaking = false
    /** SRT control packets from the receiver held back during a handshake (see onSrtFromReceiver). */
    var droppedDuringHandshake = 0L
        private set
    /** The local caller's SRT socket id, learnt from the receiver's packets to it (0 = not yet known). */
    private var clientSocketId = 0
    /**
     * Group was lost and the local SRT caller still has to be told to re-handshake. Held until a link is CONNECTED
     * again: a SHUTDOWN at loss time makes the caller re-handshake at once while no link can carry it, the induction
     * is dropped, and the caller (which never resends it) sits out its handshake timeout on the slate.
     */
    private var pendingClientReset = false
    private val dedup = SeqLog(DEDUP_MAX) // packet fingerprint -> first seen
    private val nakSeqs = IntArray(Srt.NAK_MAX_IDS)
    /** SRT ACKs that made the links walk their in-flight logs (tests check echoed copies don't). */
    internal var srtAckWalks = 0L
        private set
    /** New data packets since a lower-priority link last got its warm-up share. */
    private var sinceTrickle = 0

    val isGroupRegistered: Boolean get() = groupRegistered

    fun addLink(id: Int, label: String, priority: LinkPriority = LinkPriority.PRIMARY, hint: LinkHint = LinkHint.UNKNOWN) {
        require(id !in links) { "link $id already exists" }
        val l = LinkState(id, label, config).also { it.priority = priority; it.hint = hint }
        links[id] = l
        linkList += l
    }

    fun removeLink(id: Int) {
        val l = links.remove(id) ?: return
        linkList.remove(l)
        if (reg1LinkId == id) reg1LinkId = null
        if (l.phase == LinkPhase.CONNECTED) onEvent(SrtlaEvent.LinkDown(id, l.label, "removed"))
    }

    /** Moves a link to another priority class (e.g. a helper phone running hot is demoted). */
    fun setPriority(id: Int, priority: LinkPriority) {
        links[id]?.priority = priority
    }

    /** Updates what the platform knows about a link (validated, captive portal...), for picking the REG1 link. */
    fun setHint(id: Int, hint: LinkHint) {
        links[id]?.hint = hint
    }

    /** Shrinks a link's in-flight cap to [scale] (0..1] of what it has earned, so it carries a smaller share. */
    fun setCapScale(id: Int, scale: Double) {
        links[id]?.capScale = scale.coerceIn(0.05, 1.0)
    }

    /** The I/O layer reports the socket send buffer the OS granted this link, for [stats]. */
    fun setSendBufferBytes(id: Int, bytes: Int) {
        links[id]?.sendBufferBytes = bytes
    }

    /**
     * The link's socket was replaced (its network's address changed, or sends kept failing): the receiver knows it by
     * its old source address, so it must register again. Marks it down and clears its backoff so REG2 goes out on the
     * next [tick].
     */
    fun relink(id: Int) {
        val l = links[id] ?: return
        markDown(l, "address changed")
        l.backoffUntilMs = 0
        l.lastRegSentMs = LinkState.NEVER
        if (reg1LinkId == id) reg1LinkId = null
    }

    fun onClientPacket(buf: ByteArray, len: Int, nowMs: Long, out: SrtlaOutput) {
        val control = Srt.isControl(buf, len)
        if (control) {
            val type = Packets.typeOrNone(buf, len)
            if (Srt.isHandshakeInduction(buf, len)) {
                for (i in 0 until linkList.size) linkList[i].resetSession()
                dedup.clear()
                clientHandshaking = true
            } else if (clientHandshaking && type != Srt.TYPE_HANDSHAKE) {
                clientHandshaking = false // the caller only sends anything else once it considers itself connected
            }
            // The caller sends its SHUTDOWN exactly once when the stream ends. Lost on one (possibly dying) link, the
            // server only notices at its peer-idle timeout and treats it as a dropout, holding the BRB slate on air.
            // Send it on every connected link; duplicates are ignored by the server once the session is closed.
            if (type == Srt.TYPE_SHUTDOWN) {
                var sent = false
                for (i in 0 until linkList.size) {
                    val l = linkList[i]
                    if (l.phase != LinkPhase.CONNECTED) continue
                    if (out.toLink(l.id, buf, 0, len)) sent = true else l.onRefused(nowMs)
                }
                if (sent) return
            }
        } else {
            clientHandshaking = false
        }

        val seq = Srt.dataSeqOrNone(buf, len)
        var resend = false
        if (seq != Srt.NO_SEQ) {
            for (i in 0 until linkList.size) {
                val l = linkList[i]
                if (l.hasSent(seq) || l.carried(seq)) { resend = true; break }
            }
        }
        var overCap = false
        var link = if (resend && config.steerResends) resendLink(seq, nowMs) else null
        if (link == null) link = selectLink(nowMs, warm = seq != Srt.NO_SEQ && !resend)
        if (link == null) {
            link = leastLoadedLink(nowMs, null)
            overCap = link != null
        }
        if (link == null) {
            droppedNoLink++
            return
        }
        if (!out.toLink(link.id, buf, 0, len)) {
            // The socket's buffer is full (a burst, a stalled radio). Not a network failure: one more try on the next
            // best link, which the refusal has just made the first one yield to.
            link.onRefused(nowMs)
            val refused = link
            link = selectLink(nowMs, warm = false)
            overCap = false
            if (link == null) {
                link = leastLoadedLink(nowMs, refused)
                overCap = link != null
            }
            if (link == null || !out.toLink(link.id, buf, 0, len)) {
                link?.onRefused(nowMs)
                droppedLocal++
                return
            }
        }
        var resentElsewhere = false
        if (resend) {
            for (i in 0 until linkList.size) {
                val l = linkList[i]
                if (l !== link && l.hasSent(seq)) {
                    l.markResent(seq)
                    resentElsewhere = true
                }
            }
        }
        link.onSent(seq, len, nowMs, overCap, resentElsewhere, resend)
    }

    fun onLinkPacket(linkId: Int, buf: ByteArray, len: Int, nowMs: Long, out: SrtlaOutput) {
        val link = links[linkId] ?: return
        link.lastReceivedMs = nowMs
        when (Packets.typeOrNone(buf, len)) {
            Packets.NO_TYPE -> return
            Srtla.TYPE_REG2 -> if (len == Srtla.REG2_LEN) onReg2(link, buf, nowMs, out)
            Srtla.TYPE_REG3 -> {
                link.onProof(nowMs)
                onReg3(link, out)
            }
            Srtla.TYPE_REG_NGP -> onRegNgp(link, nowMs, out)
            Srtla.TYPE_REG_ERR -> {
                link.backoffUntilMs = nowMs + config.regErrBackoffMs
                if (reg1LinkId == link.id) reg1LinkId = null
            }
            Srtla.TYPE_REG_NAK -> {
                markDown(link, "refused by receiver")
                link.backoffUntilMs = nowMs + config.regNakBackoffMs
                onEvent(SrtlaEvent.LinkRefused(link.id, link.label))
            }
            Srtla.TYPE_KEEPALIVE -> {
                if (len >= Srtla.KEEPALIVE_LEN) {
                    val ts = Bytes.readLong(buf, 2)
                    if (ts <= nowMs && nowMs - ts < 60_000) {
                        link.onRttSample(nowMs - ts)
                        link.onProof(nowMs)
                    }
                }
            }
            Srtla.TYPE_ACK -> {
                val n = Packets.srtlaAckCount(buf, len)
                for (i in 0 until linkList.size) linkList[i].beginAckBatch()
                for (k in 0 until n) {
                    val seq = Packets.srtlaAckSeq(buf, k)
                    for (i in 0 until linkList.size) {
                        val l = linkList[i]
                        if (l.onSrtlaAckSpecific(seq, nowMs)) {
                            l.onProof(nowMs)
                            break
                        }
                    }
                    for (i in 0 until linkList.size) linkList[i].onSrtlaAckGlobal()
                }
                for (i in 0 until linkList.size) linkList[i].endAckBatch()
            }
            else -> onSrtFromReceiver(buf, len, nowMs, out)
        }
    }

    /** Housekeeping: registration, keepalives, timeouts. Call every ~50–100 ms. */
    fun tick(nowMs: Long, out: SrtlaOutput) {
        for (l in linkList) {
            if (l.phase == LinkPhase.CONNECTED && nowMs - l.lastProofMs > config.connTimeoutMs) {
                markDown(l, if (nowMs - l.lastReceivedMs > config.connTimeoutMs) "timeout" else "uplink dead")
            }
            l.updateRate(nowMs)
            l.updateDelivered(nowMs)
        }

        if (!groupRegistered) {
            driveReg1(nowMs, out)
        } else {
            for (l in linkList) {
                if (l.phase == LinkPhase.CONNECTED || nowMs < l.backoffUntilMs) continue
                if (l.lastRegSentMs == LinkState.NEVER || nowMs - l.lastRegSentMs >= config.regRetryMs) {
                    send(out, l, Packets.reg2(srtlaId))
                    l.phase = LinkPhase.REGISTERING
                    l.lastRegSentMs = nowMs
                }
            }
        }

        for (l in linkList) {
            if (l.phase != LinkPhase.CONNECTED) continue
            // A link that has gone quiet is probed fast: any echo puts it back in service at once, and a dead one is
            // known for sure sooner. One that answers normally only needs the ordinary keepalive.
            val interval = if (nowMs - l.lastProofMs > config.silentProbeAfterMs) config.silentProbeIntervalMs else config.keepaliveIntervalMs
            if (l.lastKeepaliveSentMs == LinkState.NEVER || nowMs - l.lastKeepaliveSentMs >= interval) {
                val info = KeepaliveInfo(
                    connId = l.id,
                    window = l.window,
                    inFlight = l.inFlight,
                    rttMs = if (l.srttMs < 0) 0 else l.srttMs.toInt(),
                    nakCount = l.nakCount.toInt(),
                    bitrateBytesPerSec = (l.txBitsPerSec / 8).toInt(),
                )
                send(out, l, Packets.keepaliveExt(info, nowMs))
                l.lastKeepaliveSentMs = nowMs
            }
        }

        while (dedup.size > 0 && nowMs - dedup.value(dedup.first()) > config.dedupWindowMs) dedup.removeFirst()
    }

    fun stats(nowMs: Long): SenderStats {
        val ls = linkList.map { l ->
            LinkStats(
                id = l.id,
                label = l.label,
                phase = l.phase,
                window = l.window,
                inFlight = l.inFlight,
                rttMs = if (l.srttMs < 0) null else l.srttMs.toInt(),
                lastReceivedAgoMs = if (l.lastReceivedMs == LinkState.NEVER) null else nowMs - l.lastReceivedMs,
                txBitsPerSec = l.txBitsPerSec,
                packetsSent = l.packetsSent,
                naks = l.nakCount,
                minRttMs = l.minRttMs.takeIf { it >= 0 }?.toInt(),
                queueDelayMs = l.queueDelayMs(nowMs),
                deliveredBps = l.deliveredBps,
                maxDeliveredBps = l.maxDeliveredBps,
                ownInFlight = l.ownInFlight,
                capPackets = l.capPackets(),
                overCapSent = l.overCapSent,
                proofAgoMs = l.proofAgoMs(nowMs),
                proven = l.isProven(nowMs),
                priority = l.priority,
                localDrops = l.localDrops,
                bytesSent = l.bytesSent,
                sendBufferBytes = l.sendBufferBytes,
            )
        }
        return SenderStats(
            groupRegistered, ls, ls.sumOf { it.txBitsPerSec }, droppedNoLink,
            droppedLocal = droppedLocal, droppedDuringHandshake = droppedDuringHandshake,
        )
    }

    // --- internals ---------------------------------------------------------------------------

    /**
     * Classic SRTLA choice (best window score) among the links that are proven and under their in-flight cap, in the
     * highest priority class that has one. With [warm], one in [SrtlaConfig.warmTrickleEvery] new packets goes to an
     * idle link of a lower class instead (see [trickleLink]).
     */
    private fun selectLink(nowMs: Long, warm: Boolean): LinkState? {
        var best: LinkState? = null
        for (p in PRIORITIES) {
            var bestScore = -1
            for (i in 0 until linkList.size) {
                val l = linkList[i]
                if (l.priority != p || l.phase != LinkPhase.CONNECTED || !l.isProven(nowMs) || l.atCap(nowMs)) continue
                val s = l.score()
                if (s > bestScore) {
                    best = l; bestScore = s
                }
            }
            if (best != null) break
        }
        if (best == null || !warm || config.warmTrickleEvery <= 0 || best.priority == LinkPriority.BACKUP) return best
        if (++sinceTrickle < config.warmTrickleEvery) return best
        val warmLink = trickleLink(best.priority, nowMs) ?: return best
        sinceTrickle = 0
        return warmLink
    }

    /**
     * A connected link of a lower class than [active] with room, the one that has waited longest for data. Keeping
     * them lightly used keeps their minimum delay and delivery rate (so their caps) valid: otherwise the first spill
     * onto a backup link runs into the minimum cap while it relearns.
     */
    private fun trickleLink(active: LinkPriority, nowMs: Long): LinkState? {
        var best: LinkState? = null
        for (i in 0 until linkList.size) {
            val l = linkList[i]
            if (l.priority <= active || l.phase != LinkPhase.CONNECTED || l.atCap(nowMs)) continue
            if (best == null || l.packetsSent < best.packetsSent) best = l
        }
        return best
    }

    /**
     * Where to resend [seq]: the proven link with room that delivers soonest (base delay plus its current queue),
     * preferring one that didn't carry it already, in the highest priority class that has one. A resend that goes
     * back out on a slow or lossy link is often too late or lost again. Null when no link qualifies yet (no delay
     * samples); the normal choice applies then.
     */
    private fun resendLink(seq: Int, nowMs: Long): LinkState? {
        var best: LinkState? = null
        var bestCarried = true
        var bestCost = Long.MAX_VALUE
        for (i in 0 until linkList.size) {
            val l = linkList[i]
            val rtt = l.minRttMs
            if (rtt < 0 || l.phase != LinkPhase.CONNECTED || !l.isProven(nowMs) || l.atCap(nowMs)) continue
            val carried = l.hasSent(seq) || l.carried(seq)
            val cost = rtt + l.queueDelayMs(nowMs)
            val better = when {
                best == null -> true
                l.priority != best.priority -> l.priority < best.priority
                carried != bestCarried -> !carried
                else -> cost < bestCost
            }
            if (better) {
                best = l; bestCarried = carried; bestCost = cost
            }
        }
        return best
    }

    /**
     * Every link is full (the stream exceeds what the bond carries right now): the least overdue link, then the one
     * with the most relative room. Never a link whose ACKs have stopped, that has gone silent, or whose socket just
     * refused a packet while another is still answering; higher classes first among equals.
     */
    private fun leastLoadedLink(nowMs: Long, exclude: LinkState?): LinkState? {
        var best: LinkState? = null
        for (i in 0 until linkList.size) {
            val l = linkList[i]
            if (l === exclude || l.phase != LinkPhase.CONNECTED) continue
            if (best == null || lessLoaded(l, best, nowMs)) best = l
        }
        return best
    }

    private fun lessLoaded(a: LinkState, b: LinkState, nowMs: Long): Boolean {
        val ar = nowMs < a.refusedUntilMs
        val br = nowMs < b.refusedUntilMs
        if (ar != br) return !ar
        val asl = a.isSilent(nowMs)
        val bsl = b.isSilent(nowMs)
        if (asl != bsl) return !asl
        val ao = a.overdueMs(nowMs)
        val bo = b.overdueMs(nowMs)
        if (ao != bo) return ao < bo
        if (a.priority != b.priority) return a.priority < b.priority
        return a.ownInFlight.toDouble() / a.capPackets() < b.ownInFlight.toDouble() / b.capPackets()
    }

    private fun reg1TimeoutMs(l: LinkState): Long =
        if (l.srttMs < 0) config.reg2TimeoutMs
        else (3 * l.srttMs.toLong()).coerceIn(config.reg2MinTimeoutMs, max(config.reg2MinTimeoutMs, config.reg2TimeoutMs))

    /**
     * One REG1 at a time, never on several links at once: srtla_rec ties each REG1 sender's address to its own group
     * and answers that address's later REG2 for another group with REG_ERR for ~10 s. The links take turns, best
     * first by [LinkHint] (a dead or captive first pick costs a whole timeout); a captive-portal Wi-Fi only gets a turn
     * when nothing else is there.
     */
    private fun driveReg1(nowMs: Long, out: SrtlaOutput) {
        val pending = reg1LinkId?.let { links[it] }
        if (pending != null && nowMs - reg1SentMs < reg1TimeoutMs(pending)) return
        val ready = linkList.filter { nowMs >= it.backoffUntilMs }
        if (ready.isEmpty()) return
        val anyOpen = ready.any { it.hint != LinkHint.CAPTIVE_PORTAL }
        val candidates = ready.filter { !anyOpen || it.hint != LinkHint.CAPTIVE_PORTAL }.sortedBy { it.hint.ordinal }
        val l = candidates[reg1Cursor++ % candidates.size]
        send(out, l, Packets.reg1(srtlaId))
        reg1LinkId = l.id
        reg1SentMs = nowMs
        reg1Tried += l.id
    }

    private fun onReg2(link: LinkState, buf: ByteArray, nowMs: Long, out: SrtlaOutput) {
        // A late answer to an earlier, timed-out REG1 is as good a group as any, and taking it keeps that link's
        // address from being stranded in an orphan group on the receiver.
        if (groupRegistered || (reg1LinkId != link.id && link.id !in reg1Tried)) return
        val half = Srtla.ID_LEN / 2
        for (i in 0 until half) if (buf[2 + i] != srtlaId[i]) return // not an answer to our REG1
        buf.copyInto(srtlaId, 0, 2, 2 + Srtla.ID_LEN)
        groupRegistered = true
        reg1LinkId = null
        reg1Tried.clear()
        reg1Cursor = 0
        onEvent(SrtlaEvent.GroupRegistered(link.id))
        for (l in linkList) {
            if (nowMs < l.backoffUntilMs) continue
            send(out, l, Packets.reg2(srtlaId))
            l.phase = LinkPhase.REGISTERING
            l.lastRegSentMs = nowMs
        }
    }

    private fun onReg3(link: LinkState, out: SrtlaOutput) {
        if (link.phase == LinkPhase.CONNECTED) return
        if (!groupRegistered) return
        val alone = linkList.none { it.phase == LinkPhase.CONNECTED }
        link.phase = LinkPhase.CONNECTED
        link.resetCongestion()
        onEvent(SrtlaEvent.LinkUp(link.id, link.label, afterOutage = alone))
        // First link up after a group loss: now the caller's immediate re-handshake has a link to go out on.
        if (pendingClientReset) {
            pendingClientReset = false
            sendClientShutdown(out)
        }
    }

    private fun onRegNgp(link: LinkState, nowMs: Long, out: SrtlaOutput) {
        // Receiver doesn't know our group (e.g. it restarted, or forgot us during a long outage such as a tunnel —
        // every receiver we tested drops groups after ~10 s of silence). Only start over if nothing else is connected.
        if (linkList.any { it.phase == LinkPhase.CONNECTED }) {
            markDown(link, "unknown group")
            link.backoffUntilMs = nowMs + config.regErrBackoffMs
            return
        }
        if (groupRegistered) {
            groupRegistered = false
            reg1Tried.clear()
            reg1Cursor = 0
            linkList.forEach { it.phase = LinkPhase.DISCONNECTED }
            onEvent(SrtlaEvent.GroupLost)
            // The SRT session behind the old group is unreachable now (the receiver will forward a new group from a
            // new socket). The local SRT caller must re-handshake instead of idling out (~30 s), but only once a link
            // is CONNECTED again (see pendingClientReset / onReg3), or its handshake is dropped here.
            if (config.resetClientOnGroupLoss) pendingClientReset = true
        }
        // The NGP proves this link works both ways right now: register the new group over it instead of letting
        // tick() rotate REG1 onto a possibly dead link (a timeout lost per wrong pick). If it doesn't answer,
        // driveReg1's rotation takes over after the usual timeout.
        if (!groupRegistered && reg1LinkId == null && nowMs >= link.backoffUntilMs) {
            send(out, link, Packets.reg1(srtlaId))
            reg1LinkId = link.id
            reg1SentMs = nowMs
            reg1Tried += link.id
        }
    }

    private fun sendClientShutdown(out: SrtlaOutput) {
        val id = clientSocketId
        if (id == 0) return
        val shutdown = ByteArray(Srt.CONTROL_HEADER_LEN).also {
            Bytes.writeU16(it, 0, Srt.TYPE_SHUTDOWN)
            Bytes.writeInt(it, 12, id)
        }
        out.toClient(shutdown, 0, shutdown.size)
    }

    /** Hard failure reported by the I/O layer (e.g. the network interface disappeared). */
    fun onLinkFailed(linkId: Int, nowMs: Long, reason: String) {
        val l = links[linkId] ?: return
        if (l.phase == LinkPhase.DISCONNECTED) return
        markDown(l, reason)
        l.backoffUntilMs = nowMs + config.regRetryMs
    }

    private fun onSrtFromReceiver(buf: ByteArray, len: Int, nowMs: Long, out: SrtlaOutput) {
        if (len >= Srt.CONTROL_HEADER_LEN) clientSocketId = Bytes.readInt(buf, 12) // destination socket id
        if (Srt.isControl(buf, len)) {
            // The receiver answers a handshake on one link but broadcasts SRT ACKs on all of them, so over a slow
            // link an ACK can overtake the handshake reply. Some SRT callers (RootEncoder) treat that as fatal and
            // fail every attempt. ACKs before the connection exists carry nothing, so hold them back until then.
            val type = Packets.typeOrNone(buf, len)
            if (clientHandshaking && type != Srt.TYPE_HANDSHAKE && type != Srt.TYPE_SHUTDOWN) {
                droppedDuringHandshake++
                return
            }
            // The receiver echoes each ACK and NAK on every link: only the first copy is worth the per-link walks.
            if (isDuplicate(buf, len, nowMs)) return
            when (type) {
                Srt.TYPE_ACK -> {
                    val ack = Srt.ackNumberOrNone(buf, len)
                    if (ack != Srt.NO_SEQ) {
                        srtAckWalks++
                        for (i in 0 until linkList.size) linkList[i].onSrtAck(ack)
                    }
                }
                Srt.TYPE_NAK -> {
                    val n = Srt.nakSeqsInto(buf, len, nakSeqs)
                    for (k in 0 until n) chargeNak(nakSeqs[k], nowMs)
                }
            }
        }
        out.toClient(buf, 0, len)
    }

    /**
     * Charges a reported loss to the link that lost it: the latest carrier not yet charged for it. A resend that is
     * lost again is then blamed on the link that carried the resend, not on the first one forever.
     *
     * One exception: when the latest carrier's copy is itself a resend sent less than a round trip ago, the receiver
     * can't know about it yet, so this report is a periodic repeat of the earlier loss and nobody is charged twice.
     * An original transmission is always charged, however soon the NAK comes: in a bond the receiver spots a gap
     * when later packets arrive on the faster links and reports it down the fastest one, so the NAK for a slow
     * link's loss routinely arrives well inside that slow link's own round trip.
     */
    private fun chargeNak(seq: Int, nowMs: Long) {
        var latest: LinkState? = null
        var latestMs = Long.MIN_VALUE
        for (i in 0 until linkList.size) {
            val l = linkList[i]
            val sentMs = l.unpenalisedSendMs(seq)
            if (sentMs == LinkState.NOT_UNPENALISED) continue
            if (latest == null || sentMs >= latestMs) {
                latest = l; latestMs = sentMs
            }
        }
        if (latest == null) return
        if (latest.carriedResend(seq)) {
            val rtt = max(latest.minRttMs, latest.srttMs.toLong())
            if (rtt > 0 && nowMs - latestMs < rtt) return
        }
        latest.penalise(seq)
    }

    private fun isDuplicate(buf: ByteArray, len: Int, nowMs: Long): Boolean {
        if (config.dedupWindowMs <= 0) return false
        var h = 1125899906842597L
        for (i in 0 until len) h = 31 * h + buf[i]
        h = h * 31 + len
        if (h in dedup) return true
        dedup.put(h, nowMs)
        return false
    }

    private fun markDown(l: LinkState, reason: String) {
        val wasConnected = l.phase == LinkPhase.CONNECTED
        l.phase = LinkPhase.DISCONNECTED
        l.resetCongestion()
        l.lastRegSentMs = LinkState.NEVER
        if (wasConnected) onEvent(SrtlaEvent.LinkDown(l.id, l.label, reason))
    }

    private fun send(out: SrtlaOutput, l: LinkState, pkt: ByteArray) {
        if (!out.toLink(l.id, pkt, 0, pkt.size)) l.countLocalDrop() // counted; a lost keepalive or REG is retried anyway
    }

    private companion object {
        val PRIORITIES = LinkPriority.values()
        const val DEDUP_MAX = 256
    }
}
