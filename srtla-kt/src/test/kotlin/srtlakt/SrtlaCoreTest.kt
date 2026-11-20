// SPDX-License-Identifier: Apache-2.0
package srtlakt

import srtlakt.protocol.Bytes
import srtlakt.protocol.Packets
import srtlakt.protocol.Srt
import srtlakt.protocol.Srtla
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class Recorder : SrtlaOutput {
    val toLinks = mutableListOf<Pair<Int, ByteArray>>()
    val toClient = mutableListOf<ByteArray>()
    /** Links whose socket is "full": they refuse every packet. */
    val refusing = mutableSetOf<Int>()
    override fun toLink(linkId: Int, buf: ByteArray, off: Int, len: Int): Boolean {
        if (linkId in refusing) return false
        toLinks += linkId to buf.copyOfRange(off, off + len)
        return true
    }
    override fun toClient(buf: ByteArray, off: Int, len: Int) {
        toClient += buf.copyOfRange(off, off + len)
    }
    fun clear() { toLinks.clear(); toClient.clear() }
    fun ofType(type: Int) = toLinks.filter { Packets.type(it.second, it.second.size) == type }
}

private fun dataPkt(seq: Int) = ByteArray(1316).also { Bytes.writeInt(it, 0, seq) }

class SrtlaCoreTest {
    private val events = mutableListOf<SrtlaEvent>()
    private val core = SrtlaCore(SrtlaConfig(), Random(1)) { events += it }
    private val out = Recorder()
    private var now = 1_000L

    private fun feed(link: Int, pkt: ByteArray) = core.onLinkPacket(link, pkt, pkt.size, now, out)

    /** Plays the receiver side of registration for [n] links and returns once all are connected. */
    private fun register(n: Int) {
        repeat(n) { core.addLink(it, "link$it") }
        core.tick(now, out)
        val (reg1Link, reg1) = out.ofType(Srtla.TYPE_REG1).single()
        val fullId = reg1.copyOfRange(2, 258).also { for (i in 128 until 256) it[i] = 0x5a }
        out.clear()
        feed(reg1Link, Packets.reg2(fullId))
        val reg2s = out.ofType(Srtla.TYPE_REG2)
        assertEquals(n, reg2s.size, "REG2 broadcast to every link")
        reg2s.forEach { (_, p) -> assertEquals(0x5a, p[2 + 200].toInt()) }
        repeat(n) { feed(it, Packets.reg3()) }
        out.clear()
    }

    @Test
    fun `registration REG1 then REG2 broadcast then REG3 connects every link`() {
        register(3)
        assertTrue(core.isGroupRegistered)
        assertEquals(3, core.stats(now).connectedLinks)
        assertEquals(3, events.count { it is SrtlaEvent.LinkUp })
    }

    @Test
    fun `REG2 that does not echo our id half is ignored`() {
        core.addLink(0, "a")
        core.tick(now, out)
        feed(0, Packets.reg2(ByteArray(256) { 1 }))
        assertFalse(core.isGroupRegistered)
    }

    @Test
    fun `REG1 rotates to another link after REG2 timeout`() {
        core.addLink(0, "a"); core.addLink(1, "b")
        core.tick(now, out)
        now += 4_001
        core.tick(now, out)
        assertEquals(listOf(0, 1), out.ofType(Srtla.TYPE_REG1).map { it.first })
    }

    @Test
    fun `packets spread over links and a NAK shrinks the guilty link's share`() = spreadAndNak()

    /**
     * Sends [range] like a live source, with the receiver SRTLA-acking each packet [lag] packets later (a link
     * RTT's worth of packets in flight, well under the caps).
     */
    private fun sendAcked(range: IntRange, lag: Int = 16) {
        val pending = ArrayDeque<Pair<Int, Int>>()
        for (s in range) {
            val before = out.toLinks.size
            core.onClientPacket(dataPkt(s), 1316, now, out)
            out.toLinks.drop(before).forEach { (link, p) -> if (p.size == 1316) pending.addLast(link to Bytes.readInt(p, 0)) }
            while (pending.size > lag) pending.removeFirst().let { (link, seq) -> feed(link, Packets.srtlaAck(intArrayOf(seq))) }
            now += 1
        }
    }

    private fun spreadAndNak() {
        register(2)
        sendAcked(0 until 200)
        val perLink = out.toLinks.filter { it.second.size == 1316 }.groupingBy { it.first }.eachCount()
        assertEquals(2, perLink.size)
        assertTrue(perLink.values.all { it > 50 }, "balanced: $perLink")

        // NAK everything link 0 carried -> its window collapses
        val link0Seqs = out.toLinks.filter { it.first == 0 }.map { Bytes.readInt(it.second, 0) }
        val nak = ByteArray(16 + 4 * link0Seqs.size).also { p ->
            Bytes.writeU16(p, 0, Srt.TYPE_NAK)
            link0Seqs.forEachIndexed { i, s -> Bytes.writeInt(p, 16 + 4 * i, s) }
        }
        feed(1, nak)
        val ack = ByteArray(44).also { Bytes.writeU16(it, 0, Srt.TYPE_ACK); Bytes.writeInt(it, 16, 200) }
        feed(1, ack)
        val w = core.stats(now).links
        assertTrue(w[0].window < w[1].window, "link0 penalised: $w")
        out.clear()
        sendAcked(200 until 400)
        val after = out.toLinks.filter { it.second.size == 1316 }.groupingBy { it.first }.eachCount()
        assertTrue((after[1] ?: 0) > (after[0] ?: 0), "healthy link preferred: $after")
    }

    @Test
    fun `NAKed packets stay in flight until the cumulative ACK and are penalised once`() {
        register(1)
        for (s in 0 until 20) core.onClientPacket(dataPkt(s), 1316, now, out)
        val w0 = core.stats(now).links[0].window
        val nak = ByteArray(24).also { Bytes.writeU16(it, 0, Srt.TYPE_NAK); Bytes.writeInt(it, 16, 5); Bytes.writeInt(it, 20, 6) }
        feed(0, nak)
        feed(0, nak.copyOf().also { Bytes.writeInt(it, 8, 99) }) // periodic NAK report repeats the same losses
        val afterNak = core.stats(now).links[0]
        assertEquals(20, afterNak.inFlight, "a link that just lost packets must not look empty")
        assertEquals(2, afterNak.naks)
        assertEquals(w0 - 2 * Srtla.WINDOW_DECR, afterNak.window)
        val ack = ByteArray(44).also { Bytes.writeU16(it, 0, Srt.TYPE_ACK); Bytes.writeInt(it, 16, 20) }
        feed(0, ack)
        assertEquals(0, core.stats(now).links[0].inFlight)
    }

    @Test
    fun `srtla ack grows the window of the link that carried the packet`() {
        register(1)
        for (s in 0 until 100) core.onClientPacket(dataPkt(s), 1316, now, out)
        val before = core.stats(now).links[0].window
        feed(0, Packets.srtlaAck(IntArray(10) { it }))
        val after = core.stats(now).links[0]
        assertTrue(after.window > before)
        assertEquals(90, after.inFlight)
    }

    @Test
    fun `silent link times out, re-registers with REG2 and comes back`() {
        register(2)
        now += 5_001
        feed(1, Packets.keepalive(now)) // link1 still alive
        core.tick(now, out)
        // dropped and immediately re-registering in the same tick
        assertEquals(LinkPhase.REGISTERING, core.stats(now).links[0].phase)
        assertTrue(events.any { it is SrtlaEvent.LinkDown && it.linkId == 0 })
        assertTrue(out.ofType(Srtla.TYPE_REG2).any { it.first == 0 }, "REG2 retry on dead link")

        feed(0, Packets.reg3())
        assertEquals(2, core.stats(now).connectedLinks)
    }

    @Test
    fun `link whose uplink died is dropped even though SRT ACKs still arrive on it`() {
        register(2)
        val srtAck = ByteArray(44).also { Bytes.writeU16(it, 0, Srt.TYPE_ACK) }
        repeat(6) {
            now += 1_000
            Bytes.writeInt(srtAck, 4, it) // distinct ACKs
            feed(0, srtAck)               // downlink of link0 still delivers receiver traffic...
            feed(1, Packets.keepalive(now)) // ...but only link1 gets keepalive echoes back
            core.tick(now, out)
        }
        val down = events.filterIsInstance<SrtlaEvent.LinkDown>().single()
        assertEquals(0, down.linkId)
        assertEquals("uplink dead", down.reason)
        assertEquals(1, core.stats(now).connectedLinks)
    }

    @Test
    fun `no connected link means packets are dropped and counted`() {
        core.addLink(0, "a")
        core.onClientPacket(dataPkt(1), 1316, now, out)
        assertEquals(1, core.stats(now).droppedNoLink)
        assertTrue(out.toLinks.isEmpty())
    }

    @Test
    fun `REG_NGP with nothing connected restarts group registration`() {
        register(1)
        now += 5_001
        core.tick(now, out) // link times out
        out.clear()
        feed(0, Packets.regNgp())
        assertFalse(core.isGroupRegistered)
        core.tick(now, out)
        assertEquals(1, out.ofType(Srtla.TYPE_REG1).size)
    }

    @Test
    fun `group loss after an outage tells the SRT caller to reconnect`() {
        register(1)
        val fromServer = ByteArray(44).also { Bytes.writeU16(it, 0, Srt.TYPE_ACK); Bytes.writeInt(it, 12, 0x1234abcd) }
        feed(0, fromServer) // learn the caller's SRT socket id
        out.clear()
        now += 15_000 // tunnel
        core.tick(now, out)
        feed(0, Packets.regNgp()) // receiver forgot the group
        assertTrue(events.contains(SrtlaEvent.GroupLost))
        // No link can carry a re-handshake yet: telling the caller now would get its handshake dropped.
        assertTrue(out.toClient.isEmpty())

        core.tick(now, out)
        val (reg1Link, reg1) = out.ofType(Srtla.TYPE_REG1).single()
        val fullId = reg1.copyOfRange(2, 258).also { for (i in 128 until 256) it[i] = 0x5a }
        feed(reg1Link, Packets.reg2(fullId))
        assertTrue(out.toClient.isEmpty(), "group registered but no link connected yet")
        feed(0, Packets.reg3())
        val shutdown = out.toClient.single()
        assertEquals(Srt.TYPE_SHUTDOWN, Packets.type(shutdown, shutdown.size))
        assertEquals(0x1234abcd, Bytes.readInt(shutdown, 12))
        // A repeated REG3 doesn't reset the caller again.
        feed(0, Packets.reg3())
        assertEquals(1, out.toClient.size)
    }

    @Test
    fun `after group loss REG1 goes out on the link that answered with NGP`() {
        register(3)
        now += 15_000 // dead zone: every link times out
        core.tick(now, out)
        out.clear()
        feed(2, Packets.regNgp()) // only link 2 is back
        assertEquals(listOf(2), out.ofType(Srtla.TYPE_REG1).map { it.first })
        feed(1, Packets.regNgp()) // a second NGP doesn't start another registration
        core.tick(now, out)
        assertEquals(listOf(2), out.ofType(Srtla.TYPE_REG1).map { it.first })
    }

    @Test
    fun `the caller's SHUTDOWN goes out on every connected link`() {
        register(3)
        core.onLinkFailed(1, now, "network error")
        val shutdown = ByteArray(Srt.CONTROL_HEADER_LEN).also { Bytes.writeU16(it, 0, Srt.TYPE_SHUTDOWN) }
        core.onClientPacket(shutdown, shutdown.size, now, out)
        assertEquals(listOf(0, 2), out.ofType(Srt.TYPE_SHUTDOWN).map { it.first })
    }

    @Test
    fun `hard link failure drops it immediately and retries soon`() {
        register(2)
        core.onLinkFailed(1, now, "network error")
        assertEquals(1, core.stats(now).connectedLinks)
        now += 600
        core.tick(now, out)
        assertTrue(out.ofType(Srtla.TYPE_REG2).any { it.first == 1 })
    }

    @Test
    fun `duplicate SRT acks from several links reach the client once`() {
        register(3)
        val ack = ByteArray(44).also { Bytes.writeU16(it, 0, Srt.TYPE_ACK); Bytes.writeInt(it, 16, 7) }
        repeat(3) { feed(it, ack) }
        assertEquals(1, out.toClient.size)
        now += 600
        core.tick(now, out)
        feed(0, ack)
        assertEquals(2, out.toClient.size, "same bytes after the dedup window are forwarded")
    }

    /** A full-size SRT handshake with the given request type (1 = induction, -1 = conclusion). */
    private fun handshake(reqType: Int) = ByteArray(Srt.CONTROL_HEADER_LEN + Srt.HANDSHAKE_CIF_LEN).also {
        Bytes.writeU16(it, 0, Srt.TYPE_HANDSHAKE)
        Bytes.writeInt(it, Srt.CONTROL_HEADER_LEN + 20, reqType)
    }

    @Test
    fun `ACKs that overtake the handshake reply are held back until the caller is connected`() {
        register(2)
        val ack = ByteArray(44).also { Bytes.writeU16(it, 0, Srt.TYPE_ACK); Bytes.writeInt(it, 16, 1) }
        val induction = handshake(1)
        core.onClientPacket(induction, induction.size, now, out)
        feed(0, handshake(1)) // induction reply
        val conclusion = handshake(-1)
        core.onClientPacket(conclusion, conclusion.size, now, out)
        feed(1, ack) // broadcast ACK on the fast link arrives first...
        feed(0, handshake(-1)) // ...then the conclusion reply on the slow one
        assertEquals(listOf(Srt.TYPE_HANDSHAKE, Srt.TYPE_HANDSHAKE), out.toClient.map { Packets.type(it, it.size) })
        assertEquals(1, core.droppedDuringHandshake)

        core.onClientPacket(dataPkt(1), 1316, now, out) // caller is connected: it sends data
        val ack2 = ByteArray(44).also { Bytes.writeU16(it, 0, Srt.TYPE_ACK); Bytes.writeInt(it, 16, 2) }
        feed(1, ack2)
        assertEquals(Srt.TYPE_ACK, Packets.type(out.toClient.last(), out.toClient.last().size), "ACKs flow once connected")
    }

    @Test
    fun `a link that stops acknowledging hits its in-flight cap and traffic moves to the other`() {
        register(2)
        // Link 0's queue is filling: its SRTLA ACKs stop. The SRT receiver's cumulative ACKs keep clearing every
        // link's classic in-flight log (as they do every 10 ms in reality), so the classic score can't tell the links
        // apart and keeps choosing link 0. The cap must stop it.
        for (s in 0 until 300) {
            val before = out.toLinks.size
            core.onClientPacket(dataPkt(s), 1316, now, out)
            out.toLinks.drop(before).forEach { (link, p) -> if (link == 1) feed(1, Packets.srtlaAck(intArrayOf(Bytes.readInt(p, 0)))) }
            feed(1, ByteArray(44).also { a -> Bytes.writeU16(a, 0, Srt.TYPE_ACK); Bytes.writeInt(a, 16, s + 1) })
            now += 2
        }
        val st = core.stats(now).links
        val sent = out.toLinks.filter { it.second.size == 1316 }.groupingBy { it.first }.eachCount()
        assertEquals(st[0].capPackets, sent[0], "link 0 capped at ${st[0].capPackets}: $sent")
        assertEquals(0L, core.stats(now).overCapSent, "never over cap while link 1 has room")
        assertTrue(st[1].deliveredBps > 0 && st[1].minRttMs != null, "delivery measured on link 1: ${st[1]}")
    }

    @Test
    fun `when every link is full packets still go out and are counted as over cap`() {
        register(2)
        for (s in 0 until 100) core.onClientPacket(dataPkt(s), 1316, now, out)
        val st = core.stats(now)
        assertEquals(100, out.toLinks.count { it.second.size == 1316 }, "nothing dropped")
        assertEquals(100L - 2 * LinkState.MIN_CAP_PACKETS, st.overCapSent)
    }

    @Test
    fun `queue delay is the smoothed ack delay above the link's minimum`() {
        register(1)
        repeat(50) { i -> // base delay 40 ms
            core.onClientPacket(dataPkt(i), 1316, now, out); now += 40; feed(0, Packets.srtlaAck(intArrayOf(i)))
        }
        assertTrue(core.stats(now).links[0].queueDelayMs < 5, "no queue yet: ${core.stats(now).links[0]}")
        repeat(30) { j -> // queue builds: 240 ms send->ack
            val i = 50 + j
            core.onClientPacket(dataPkt(i), 1316, now, out); now += 240; feed(0, Packets.srtlaAck(intArrayOf(i)))
        }
        val l = core.stats(now).links[0]
        assertEquals(40, l.minRttMs)
        assertTrue(l.queueDelayMs in 150..200, "queue delay ~200 ms: $l")
    }

    @Test
    fun `keepalive echo yields rtt`() {
        register(1)
        core.tick(now, out)
        val ka = out.ofType(Srtla.TYPE_KEEPALIVE).single().second
        now += 42
        feed(0, ka)
        assertEquals(42, core.stats(now).links[0].rttMs)
    }

    @Test
    fun `a link whose socket refuses a packet hands it to the next link and is never charged with it`() {
        register(2)
        out.refusing += 0
        for (s in 0 until 10) core.onClientPacket(dataPkt(s), 1316, now, out)
        val data = out.toLinks.filter { it.second.size == 1316 }
        assertEquals(10, data.size, "nothing lost")
        assertTrue(data.all { it.first == 1 }, "all on link 1: ${data.map { it.first }}")
        val st = core.stats(now).links
        assertEquals(0, st[0].ownInFlight, "link 0's send log untouched")
        assertEquals(0, st[0].packetsSent)
        assertTrue(st[0].localDrops >= 1, "${st[0]}")
        assertEquals(0L, core.stats(now).droppedLocal)
    }

    @Test
    fun `when every socket refuses the packet is counted as dropped, not as sent`() {
        register(2)
        out.refusing += setOf(0, 1)
        core.onClientPacket(dataPkt(1), 1316, now, out)
        val st = core.stats(now)
        assertEquals(1L, st.droppedLocal)
        assertEquals(0L, st.packetsSent)
        assertEquals(2L, st.localDrops)
        assertTrue(st.links.all { it.ownInFlight == 0 })
        // The hold is short: once the sockets drain, link 0 is used again.
        out.refusing.clear()
        now += 10
        core.onClientPacket(dataPkt(2), 1316, now, out)
        assertEquals(1L, core.stats(now).packetsSent)
    }

    @Test
    fun `an SRT ACK echoed on every link walks the in-flight logs once`() {
        register(3)
        for (s in 0 until 30) core.onClientPacket(dataPkt(s), 1316, now, out)
        val ack = ByteArray(44).also { Bytes.writeU16(it, 0, Srt.TYPE_ACK); Bytes.writeInt(it, 16, 30) }
        repeat(3) { feed(it, ack) }
        assertEquals(1L, core.srtAckWalks)
        assertEquals(0, core.stats(now).links.sumOf { it.inFlight })
    }

    @Test
    fun `retransmissions out of sequence order are still cleared by the cumulative ACK`() {
        register(1)
        for (s in 0 until 20) core.onClientPacket(dataPkt(s), 1316, now, out)
        core.onClientPacket(dataPkt(3), 1316, now, out) // a resend re-enters at the tail
        core.onClientPacket(dataPkt(20), 1316, now, out)
        val ack = { n: Int -> ByteArray(44).also { Bytes.writeU16(it, 0, Srt.TYPE_ACK); Bytes.writeInt(it, 4, n); Bytes.writeInt(it, 16, n) } }
        feed(0, ack(10))
        assertEquals(11, core.stats(now).links[0].inFlight, "10..20 left, the resent 3 cleared too")
        feed(0, ack(21))
        assertEquals(0, core.stats(now).links[0].inFlight)
        // In order again: the fast path clears from the head.
        for (s in 21 until 31) core.onClientPacket(dataPkt(s), 1316, now, out)
        feed(0, ack(26))
        assertEquals(5, core.stats(now).links[0].inFlight)
    }
}
