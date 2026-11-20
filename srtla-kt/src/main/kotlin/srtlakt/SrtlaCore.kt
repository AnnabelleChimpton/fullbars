// SPDX-License-Identifier: Apache-2.0
package srtlakt

import srtlakt.protocol.Bytes
import srtlakt.protocol.KeepaliveInfo
import srtlakt.protocol.Packets
import srtlakt.protocol.Srt
import srtlakt.protocol.Srtla
import java.security.SecureRandom
import java.util.Random

data class SrtlaConfig(
    /** A connected link with no inbound packet for this long is declared dead. */
    val connTimeoutMs: Long = 5_000,
    /** How long to wait for REG2 after sending REG1 before trying another link. */
    val reg2TimeoutMs: Long = 4_000,
    /** Retry interval for REG2 on links that are not (yet) connected. Fast, so recovered links rejoin quickly. */
    val regRetryMs: Long = 500,
    val keepaliveIntervalMs: Long = 1_000,
    val regErrBackoffMs: Long = 1_000,
    val regNakBackoffMs: Long = 5_000,
    val maxInFlightLog: Int = 1_024,
    /** Duplicate control packets from the receiver (it echoes SRT ACKs on every link) are dropped within this window. */
    val dedupWindowMs: Long = 500,
    /** On group loss, send the local SRT caller a SHUTDOWN so it reconnects at once (see onRegNgp). */
    val resetClientOnGroupLoss: Boolean = true,
    /** Consecutive send failures (network gone) after which the I/O layer declares a link dead. */
    val sendErrorsToFail: Int = 5,

)

sealed interface SrtlaEvent {
    data class GroupRegistered(val linkId: Int) : SrtlaEvent
    data object GroupLost : SrtlaEvent
    data class LinkUp(val linkId: Int, val label: String) : SrtlaEvent
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
)

data class SenderStats(
    val groupRegistered: Boolean,
    val links: List<LinkStats>,
    val totalTxBitsPerSec: Long,
    val droppedNoLink: Long,
) {
    val connectedLinks: Int get() = links.count { it.phase == LinkPhase.CONNECTED }

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

    companion object {
        val EMPTY = SenderStats(false, emptyList(), 0, 0)
    }
}

/** Where the core wants bytes to go. Implementations must not retain [buf] after returning. */
interface SrtlaOutput {
    fun toLink(linkId: Int, buf: ByteArray, off: Int, len: Int)
    fun toClient(buf: ByteArray, off: Int, len: Int)
}

/**
 * I/O-free, clock-free SRTLA sender state machine. Single-threaded: callers serialise all calls.
 *
 * Data flow: the local SRT caller (libsrt) sends to us -> [onClientPacket] picks the best link.
 * Packets arriving on any link -> [onLinkPacket]: SRTLA control is consumed here, SRT control is
 * de-duplicated and handed back to the client.
 */
class SrtlaCore(
    private val config: SrtlaConfig = SrtlaConfig(),
    random: Random = SecureRandom(),
    private val onEvent: (SrtlaEvent) -> Unit = {},
) {
    private val srtlaId = ByteArray(Srtla.ID_LEN).also { random.nextBytes(it) }
    private val links = LinkedHashMap<Int, LinkState>()

    private var groupRegistered = false
    private var reg1LinkId: Int? = null
    private var reg1SentMs = LinkState.NEVER
    private var reg1Cursor = 0

    private var droppedNoLink = 0L
    /** Between the caller's handshake induction and its first non-handshake packet. */
    private var clientHandshaking = false
    /** SRT control packets from the receiver held back during a handshake (see onSrtFromReceiver). */
    var droppedDuringHandshake = 0L
        private set
    private var clientSocketId: Int? = null
    /**
     * Group was lost and the local SRT caller still has to be told to re-handshake. Held until a link is CONNECTED
     * again: a SHUTDOWN at loss time makes the caller re-handshake at once while no link can carry it, the induction
     * is dropped, and the caller (which never resends it) sits out its handshake timeout on the slate.
     */
    private var pendingClientReset = false
    private val dedup = LinkedHashMap<Long, Long>() // packet fingerprint -> first seen

    val isGroupRegistered: Boolean get() = groupRegistered

    fun addLink(id: Int, label: String) {
        require(id !in links) { "link $id already exists" }
        links[id] = LinkState(id, label, config)
    }

    fun removeLink(id: Int) {
        val l = links.remove(id) ?: return
        if (reg1LinkId == id) reg1LinkId = null
        if (l.phase == LinkPhase.CONNECTED) onEvent(SrtlaEvent.LinkDown(id, l.label, "removed"))
    }

    fun onClientPacket(buf: ByteArray, len: Int, nowMs: Long, out: SrtlaOutput) {
        if (Srt.isHandshakeInduction(buf, len)) {
            links.values.forEach { it.resetSession() }
            dedup.clear()
            clientHandshaking = true
        } else if (clientHandshaking && !(Srt.isControl(buf, len) && Packets.type(buf, len) == Srt.TYPE_HANDSHAKE)) {
            clientHandshaking = false // the caller only sends anything else once it considers itself connected
        }
        // The caller sends its SHUTDOWN exactly once when the stream ends. Lost on one (possibly dying) link, the
        // server only notices at its peer-idle timeout and treats it as a dropout, holding the BRB slate on air.
        // Send it on every connected link; duplicates are ignored by the server once the session is closed.
        if (Srt.isControl(buf, len) && Packets.type(buf, len) == Srt.TYPE_SHUTDOWN) {
            var sent = false
            for (l in links.values) if (l.phase == LinkPhase.CONNECTED) {
                out.toLink(l.id, buf, 0, len)
                sent = true
            }
            if (sent) return
        }
        var overCap = false
        val link = selectLink(nowMs) ?: leastLoadedLink(nowMs)?.also { overCap = true }
        if (link == null) {
            droppedNoLink++
            return
        }
        out.toLink(link.id, buf, 0, len)
        val seq = Srt.dataSeq(buf, len)
        var resent = false
        if (seq != null) for (l in links.values) if (l !== link && l.hasSent(seq)) {
            l.markResent(seq)
            resent = true
        }
        link.onSent(seq, len, nowMs, overCap, resent)
    }

    fun onLinkPacket(linkId: Int, buf: ByteArray, len: Int, nowMs: Long, out: SrtlaOutput) {
        val link = links[linkId] ?: return
        link.lastReceivedMs = nowMs
        when (Packets.type(buf, len)) {
            null -> return
            Srtla.TYPE_REG2 -> if (len == Srtla.REG2_LEN) onReg2(link, buf, nowMs, out)
            Srtla.TYPE_REG3 -> {
                link.lastProofMs = nowMs
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
                val ts = Packets.keepaliveTimestamp(buf, len)
                if (ts != null && ts <= nowMs && nowMs - ts < 60_000) {
                    link.onRttSample(nowMs - ts)
                    link.lastProofMs = nowMs
                }
            }
            Srtla.TYPE_ACK -> {
                links.values.forEach { it.beginAckBatch() }
                for (seq in Packets.srtlaAckSeqs(buf, len)) {
                    for (l in links.values) if (l.onSrtlaAckSpecific(seq, nowMs)) {
                        l.lastProofMs = nowMs
                        break
                    }
                    links.values.forEach { it.onSrtlaAckGlobal() }
                }
                links.values.forEach { it.endAckBatch() }
            }
            else -> onSrtFromReceiver(buf, len, nowMs, out)
        }
    }

    /** Housekeeping: registration, keepalives, timeouts. Call every ~50–100 ms. */
    fun tick(nowMs: Long, out: SrtlaOutput) {
        for (l in links.values) {
            if (l.phase == LinkPhase.CONNECTED && nowMs - l.lastProofMs > config.connTimeoutMs) {
                markDown(l, if (nowMs - l.lastReceivedMs > config.connTimeoutMs) "timeout" else "uplink dead")
            }
            l.updateRate(nowMs)
            l.updateDelivered(nowMs)
        }

        if (!groupRegistered) {
            driveReg1(nowMs, out)
        } else {
            for (l in links.values) {
                if (l.phase == LinkPhase.CONNECTED || nowMs < l.backoffUntilMs) continue
                if (l.lastRegSentMs == LinkState.NEVER || nowMs - l.lastRegSentMs >= config.regRetryMs) {
                    send(out, l, Packets.reg2(srtlaId))
                    l.phase = LinkPhase.REGISTERING
                    l.lastRegSentMs = nowMs
                }
            }
        }

        for (l in links.values) {
            if (l.phase != LinkPhase.CONNECTED) continue
            if (l.lastKeepaliveSentMs == LinkState.NEVER || nowMs - l.lastKeepaliveSentMs >= config.keepaliveIntervalMs) {
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

        val dedupIt = dedup.values.iterator()
        while (dedupIt.hasNext()) if (nowMs - dedupIt.next() > config.dedupWindowMs) dedupIt.remove() else break
    }

    fun stats(nowMs: Long): SenderStats {
        val ls = links.values.map { l ->
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
            )
        }
        return SenderStats(groupRegistered, ls, ls.sumOf { it.txBitsPerSec }, droppedNoLink)
    }

    // --- internals ---------------------------------------------------------------------------

    /** Classic SRTLA choice (best window score), among links that are under their in-flight cap. */
    private fun selectLink(nowMs: Long): LinkState? {
        var best: LinkState? = null
        var bestScore = -1
        for (l in links.values) {
            if (l.atCap(nowMs)) continue
            val s = l.score()
            if (s > bestScore) {
                best = l; bestScore = s
            }
        }
        return best
    }

    /**
     * Every link is full (the stream exceeds what the bond carries right now): the least overdue link, then the one
     * with the most relative room. Never a link whose ACKs have stopped while another is still answering.
     */
    private fun leastLoadedLink(nowMs: Long): LinkState? =
        links.values.filter { it.phase == LinkPhase.CONNECTED }
            .minWithOrNull(compareBy<LinkState> { it.overdueMs(nowMs) }.thenBy { it.ownInFlight.toDouble() / it.capPackets() })

    private fun driveReg1(nowMs: Long, out: SrtlaOutput) {
        val pending = reg1LinkId
        if (pending != null && links.containsKey(pending) && nowMs - reg1SentMs < config.reg2TimeoutMs) return
        val candidates = links.values.filter { nowMs >= it.backoffUntilMs }
        if (candidates.isEmpty()) return
        val l = candidates[reg1Cursor++ % candidates.size]
        send(out, l, Packets.reg1(srtlaId))
        reg1LinkId = l.id
        reg1SentMs = nowMs
    }

    private fun onReg2(link: LinkState, buf: ByteArray, nowMs: Long, out: SrtlaOutput) {
        if (groupRegistered || reg1LinkId != link.id) return
        val half = Srtla.ID_LEN / 2
        for (i in 0 until half) if (buf[2 + i] != srtlaId[i]) return // not an answer to our REG1
        buf.copyInto(srtlaId, 0, 2, 2 + Srtla.ID_LEN)
        groupRegistered = true
        reg1LinkId = null
        onEvent(SrtlaEvent.GroupRegistered(link.id))
        for (l in links.values) {
            if (nowMs < l.backoffUntilMs) continue
            send(out, l, Packets.reg2(srtlaId))
            l.phase = LinkPhase.REGISTERING
            l.lastRegSentMs = nowMs
        }
    }

    private fun onReg3(link: LinkState, out: SrtlaOutput) {
        if (link.phase == LinkPhase.CONNECTED) return
        if (!groupRegistered) return
        link.phase = LinkPhase.CONNECTED
        link.resetCongestion()
        onEvent(SrtlaEvent.LinkUp(link.id, link.label))
        // First link up after a group loss: now the caller's immediate re-handshake has a link to go out on.
        if (pendingClientReset) {
            pendingClientReset = false
            sendClientShutdown(out)
        }
    }

    private fun onRegNgp(link: LinkState, nowMs: Long, out: SrtlaOutput) {
        // Receiver doesn't know our group (e.g. it restarted, or forgot us during a long outage such as a tunnel —
        // every receiver we tested drops groups after ~10 s of silence). Only start over if nothing else is connected.
        if (links.values.any { it.phase == LinkPhase.CONNECTED }) {
            markDown(link, "unknown group")
            link.backoffUntilMs = nowMs + config.regErrBackoffMs
            return
        }
        if (groupRegistered) {
            groupRegistered = false
            links.values.forEach { it.phase = LinkPhase.DISCONNECTED }
            onEvent(SrtlaEvent.GroupLost)
            // The SRT session behind the old group is unreachable now (the receiver will forward a new group from a
            // new socket). The local SRT caller must re-handshake instead of idling out (~30 s), but only once a link
            // is CONNECTED again (see pendingClientReset / onReg3), or its handshake is dropped here.
            if (config.resetClientOnGroupLoss) pendingClientReset = true
        }
        // The NGP proves this link works both ways right now: register the new group over it instead of letting
        // tick() round-robin REG1 onto a possibly dead link (reg2TimeoutMs lost per wrong pick). If it doesn't
        // answer, driveReg1's rotation takes over after the usual timeout.
        if (!groupRegistered && reg1LinkId == null && nowMs >= link.backoffUntilMs) {
            send(out, link, Packets.reg1(srtlaId))
            reg1LinkId = link.id
            reg1SentMs = nowMs
        }
    }

    private fun sendClientShutdown(out: SrtlaOutput) {
        val id = clientSocketId ?: return
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
            when (Packets.type(buf, len)) {
                Srt.TYPE_ACK -> Srt.ackNumber(buf, len)?.let { ack -> links.values.forEach { it.onSrtAck(ack) } }
                Srt.TYPE_NAK -> for (seq in Srt.nakSeqs(buf, len)) {
                    for (l in links.values) if (l.onNak(seq)) break
                }
            }
            // The receiver answers a handshake on one link but broadcasts SRT ACKs on all of them, so over a slow
            // link an ACK can overtake the handshake reply. Some SRT callers (RootEncoder) treat that as fatal and
            // fail every attempt. ACKs before the connection exists carry nothing, so hold them back until then.
            val type = Packets.type(buf, len)
            if (clientHandshaking && type != Srt.TYPE_HANDSHAKE && type != Srt.TYPE_SHUTDOWN) {
                droppedDuringHandshake++
                return
            }
            if (isDuplicate(buf, len, nowMs)) return
        }
        out.toClient(buf, 0, len)
    }

    private fun isDuplicate(buf: ByteArray, len: Int, nowMs: Long): Boolean {
        if (config.dedupWindowMs <= 0) return false
        var h = 1125899906842597L
        for (i in 0 until len) h = 31 * h + buf[i]
        h = h * 31 + len
        if (dedup.containsKey(h)) return true
        dedup[h] = nowMs
        if (dedup.size > 256) {
            val it = dedup.keys.iterator(); it.next(); it.remove()
        }
        return false
    }

    private fun markDown(l: LinkState, reason: String) {
        val wasConnected = l.phase == LinkPhase.CONNECTED
        l.phase = LinkPhase.DISCONNECTED
        l.resetCongestion()
        l.lastRegSentMs = LinkState.NEVER
        if (wasConnected) onEvent(SrtlaEvent.LinkDown(l.id, l.label, reason))
    }

    private fun send(out: SrtlaOutput, l: LinkState, pkt: ByteArray) = out.toLink(l.id, pkt, 0, pkt.size)
}
