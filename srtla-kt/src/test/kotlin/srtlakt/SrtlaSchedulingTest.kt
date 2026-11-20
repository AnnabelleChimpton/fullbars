// SPDX-License-Identifier: Apache-2.0
package srtlakt

import srtlakt.protocol.Bytes
import srtlakt.protocol.Packets
import srtlakt.protocol.Srt
import srtlakt.protocol.Srtla
import java.util.PriorityQueue
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scheduler against simulated links on a fake clock: each link has a round trip, the receiver acknowledges every
 * packet it hears and echoes keepalives, and a link's uplink can die (nothing it sends arrives, while the receiver's
 * SRT ACKs still come down it). One step is 1 ms; a stream packet goes out every [Sim.packetEveryMs].
 */
class SrtlaSchedulingTest {
    private var now = 1_000L
    private val events = ArrayList<Pair<Long, SrtlaEvent>>()

    private inner class Sim(config: SrtlaConfig = SrtlaConfig(), private val rtt: Map<Int, Long> = emptyMap()) : SrtlaOutput {
        val core = SrtlaCore(config, Random(1)) { events += now to it }
        val uplinkDead = HashSet<Int>()
        /** Sequence numbers whose next copy is lost (no SRTLA ACK), wherever it goes. */
        val lose = HashSet<Int>()
        /** How many of the next data packets on a link are lost; [lostSeqs] lists them. */
        val loseNextOn = HashMap<Int, Int>()
        val lostSeqs = ArrayList<Int>()
        var srtAcks = true
        var packetEveryMs = 2L
        /** (time, link, seq) of every data packet that left. */
        val data = ArrayList<Triple<Long, Int, Int>>()
        val keepalives = ArrayList<Pair<Long, Int>>()
        val reg1s = ArrayList<Int>()
        /** When each link last got proof from the receiver (an SRTLA ACK or keepalive echo delivered). */
        val lastProofAt = HashMap<Int, Long>()
        private var nextSeq = 0
        private var highest = -1
        private var ackNo = 0
        private var order = 0
        private val due = PriorityQueue<Triple<Long, Int, () -> Unit>>(compareBy<Triple<Long, Int, () -> Unit>> { it.first }.thenBy { it.second })

        private fun later(ms: Long, action: () -> Unit) { due += Triple(now + ms, order++, action) }
        private fun rttOf(link: Int) = rtt[link] ?: 30L

        fun feed(link: Int, pkt: ByteArray) {
            val type = Packets.type(pkt, pkt.size)
            if (type == Srtla.TYPE_ACK || type == Srtla.TYPE_KEEPALIVE) lastProofAt[link] = now
            core.onLinkPacket(link, pkt, pkt.size, now, this)
        }

        override fun toLink(linkId: Int, buf: ByteArray, off: Int, len: Int): Boolean {
            val pkt = buf.copyOfRange(off, off + len)
            val seq = Srt.dataSeqOrNone(pkt, len)
            val type = Packets.type(pkt, len)
            if (seq != Srt.NO_SEQ) data += Triple(now, linkId, seq)
            if (type == Srtla.TYPE_KEEPALIVE) keepalives += now to linkId
            if (type == Srtla.TYPE_REG1) reg1s += linkId
            if (linkId in uplinkDead) return true
            val r = rttOf(linkId)
            when {
                seq != Srt.NO_SEQ -> {
                    if (lose.remove(seq)) return true
                    val n = loseNextOn[linkId] ?: 0
                    if (n > 0) {
                        loseNextOn[linkId] = n - 1
                        lostSeqs += seq
                        return true
                    }
                    highest = maxOf(highest, seq)
                    later(r) { feed(linkId, Packets.srtlaAck(intArrayOf(seq))) }
                }
                type == Srtla.TYPE_KEEPALIVE -> later(r) { feed(linkId, pkt) }
                type == Srtla.TYPE_REG1 -> later(r) {
                    feed(linkId, Packets.reg2(pkt.copyOfRange(2, 258).also { for (i in 128 until 256) it[i] = 0x5a }))
                }
                type == Srtla.TYPE_REG2 -> later(r) { feed(linkId, Packets.reg3()) }
            }
            return true
        }

        override fun toClient(buf: ByteArray, off: Int, len: Int) = Unit

        fun send(seq: Int) = core.onClientPacket(dataPkt(seq), 1316, now, this)

        fun run(ms: Long) = repeat(ms.toInt()) {
            now++
            while (due.isNotEmpty() && due.peek().first <= now) due.poll().third()
            if (packetEveryMs > 0 && now % packetEveryMs == 0L) send(nextSeq++)
            // The receiver's cumulative ACK every 10 ms on every link, as if SRT repaired every loss.
            if (srtAcks && now % 10 == 0L && highest >= 0) {
                val ack = ByteArray(44).also { Bytes.writeU16(it, 0, Srt.TYPE_ACK); Bytes.writeInt(it, 4, ++ackNo); Bytes.writeInt(it, 16, highest + 1) }
                core.stats(now).links.forEach { l -> core.onLinkPacket(l.id, ack, ack.size, now, this) }
            }
            if (now % 50 == 0L) core.tick(now, this)
        }

        fun stats() = core.stats(now)
        fun dataOn(link: Int, from: Long = 0, to: Long = Long.MAX_VALUE) = data.count { it.second == link && it.first in from..to }
    }

    private fun dataPkt(seq: Int) = ByteArray(1316).also { Bytes.writeInt(it, 0, seq) }

    private fun Sim.connect(vararg ids: Int, priority: (Int) -> LinkPriority = { LinkPriority.PRIMARY }) {
        val every = packetEveryMs
        packetEveryMs = 0
        ids.forEach { core.addLink(it, "link$it", priority(it)) }
        run(600)
        assertEquals(ids.size, stats().connectedLinks, "all links registered")
        packetEveryMs = every
    }

    @Test
    fun `a blackholed link gets no data once silent, even after its lost packets age out, and is down within 4 s`() {
        val sim = Sim()
        sim.connect(0, 1)
        sim.run(1_000)
        assertTrue(sim.dataOn(0) > 100 && sim.dataOn(1) > 100, "both links carry the stream")
        val kill = now
        sim.uplinkDead += 0
        sim.run(6_000)

        val lastOn0 = sim.data.filter { it.second == 0 }.maxOf { it.first }
        assertTrue(lastOn0 < kill + 1_100, "data to the dead link stopped ${lastOn0 - kill} ms after it died")
        assertTrue(sim.dataOn(1, kill + 1_100) > 2_000, "the other link carries everything")

        val (downAt, down) = events.first { it.second is SrtlaEvent.LinkDown }
        assertEquals("uplink dead", (down as SrtlaEvent.LinkDown).reason)
        val proof = sim.lastProofAt.getValue(0)
        assertTrue(downAt - proof <= 4_000 + 50, "down ${downAt - proof} ms after its last proof")

        // While it was silent but still connected, it was probed every 250 ms.
        val probes = sim.keepalives.filter { it.second == 0 && it.first in (proof + 700)..(downAt - 1) }.map { it.first }
        assertTrue(probes.size >= 10, "probes: $probes")
        assertEquals(setOf(250L), probes.zipWithNext { a, b -> b - a }.toSet(), "probe spacing: $probes")
    }

    @Test
    fun `a silent link is no longer counted as proven`() {
        val sim = Sim()
        sim.connect(0, 1)
        sim.run(500)
        assertEquals(2, sim.stats().provenLinks)
        sim.uplinkDead += 0
        sim.run(1_200)
        val st = sim.stats()
        assertEquals(2, st.connectedLinks)
        assertEquals(1, st.provenLinks)
        assertTrue(st.links[0].proofAgoMs!! > 1_000 && !st.links[0].proven, "${st.links[0]}")
    }

    @Test
    fun `a NAKed packet is resent on the fast link and a repeat loss is charged to the resend's carrier`() {
        val sim = Sim(rtt = mapOf(0 to 20L, 1 to 150L))
        sim.srtAcks = false // keep every packet in the in-flight logs
        sim.connect(0, 1)
        sim.run(1_800)
        sim.loseNextOn[1] = 3 // the slow link loses a few (fewer than an ACK batch, so it doesn't look stalled)
        sim.run(200)
        sim.packetEveryMs = 0
        sim.run(300)
        val st0 = sim.stats().links
        assertEquals(20, st0[0].minRttMs)
        assertEquals(150, st0[1].minRttMs)
        val seq = sim.lostSeqs.last()

        fun nak(s: Int, stamp: Int) = ByteArray(20).also {
            Bytes.writeU16(it, 0, Srt.TYPE_NAK); Bytes.writeInt(it, 8, stamp); Bytes.writeInt(it, 16, s)
        }
        sim.feed(0, nak(seq, 1))
        val afterNak = sim.stats().links
        assertEquals(1, afterNak[1].naks, "the slow link lost it")
        assertEquals(0, afterNak[0].naks)
        val slowInFlight = afterNak[1].inFlight

        sim.lose += seq // the resend is lost too
        sim.send(seq)
        assertEquals(0, sim.data.last().second, "resent on the fast link")
        assertEquals(slowInFlight, sim.stats().links[1].inFlight, "the slow link still counts its lost packet")

        sim.feed(0, nak(seq, 2)) // a periodic repeat right away is not a new loss of the resend
        assertEquals(0, sim.stats().links[0].naks)
        sim.run(30)
        sim.feed(0, nak(seq, 3))
        val after = sim.stats().links
        assertEquals(1, after[0].naks, "the second loss is the fast link's")
        assertEquals(1, after[1].naks, "the slow link isn't charged twice")
    }

    @Test
    fun `a slow link's loss is charged to it when the NAK comes back over the fast link within the slow round trip`() {
        val sim = Sim(rtt = mapOf(0 to 20L, 1 to 150L))
        sim.srtAcks = false
        sim.connect(0, 1)
        sim.run(1_800)
        sim.loseNextOn[1] = 1
        while (sim.lostSeqs.isEmpty()) sim.run(1)
        val lostAt = now
        val seq = sim.lostSeqs.single()
        // The receiver notices the gap as later packets arrive over the fast link and reports it down that link,
        // long before a round trip of the slow link has passed.
        sim.run(40)
        val nak = ByteArray(20).also { Bytes.writeU16(it, 0, Srt.TYPE_NAK); Bytes.writeInt(it, 8, 1); Bytes.writeInt(it, 16, seq) }
        sim.feed(0, nak)
        val links = sim.stats().links
        assertTrue(now - lostAt < links[1].minRttMs!!, "NAK ${now - lostAt} ms after the loss, slow RTT ${links[1].minRttMs}")
        assertEquals(1, links[1].naks, "the slow link lost it")
        assertEquals(0, links[0].naks)
    }

    @Test
    fun `a backup link carries only a trickle while the primary has room, and the whole stream soon after it dies`() {
        val sim = Sim()
        sim.connect(0, 1) { if (it == 1) LinkPriority.BACKUP else LinkPriority.PRIMARY }
        val start = now
        sim.run(3_000)
        val share = sim.dataOn(1, start).toDouble() / sim.data.count { it.first >= start }
        assertTrue(share > 0.01 && share < 0.06, "backup share while the primary has room: $share")
        assertTrue(sim.stats().links[1].minRttMs != null, "the trickle keeps the backup's delay known")

        val kill = now
        sim.uplinkDead += 0
        sim.run(2_000)
        val lastOnPrimary = sim.data.filter { it.second == 0 }.maxOf { it.first }
        val gap = lastOnPrimary - kill
        assertTrue(gap < 400, "the backup took the whole stream $gap ms after the primary died")
        assertTrue(sim.dataOn(1, lastOnPrimary + 1) > 700)
    }

    @Test
    fun `group registers within the shorter timeout when the first link never answers REG1`() {
        val sim = Sim()
        sim.uplinkDead += 0
        val start = now
        repeat(3) { sim.core.addLink(it, "link$it") }
        sim.packetEveryMs = 0
        sim.run(3_000)
        val (at, reg) = events.first { it.second is SrtlaEvent.GroupRegistered }
        assertEquals(1, (reg as SrtlaEvent.GroupRegistered).linkId)
        assertTrue(at - start <= 2_000 + 50 + 30, "registered after ${at - start} ms")
        assertEquals(listOf(LinkPhase.REGISTERING, LinkPhase.CONNECTED, LinkPhase.CONNECTED), sim.stats().links.map { it.phase })
    }

    @Test
    fun `a late REG2 from a timed-out REG1 link is accepted`() {
        val sim = Sim(rtt = mapOf(0 to 2_500L))
        sim.uplinkDead += 1
        sim.core.addLink(0, "slow"); sim.core.addLink(1, "dead")
        sim.packetEveryMs = 0
        sim.run(2_600)
        assertEquals(listOf(0, 1), sim.reg1s, "REG1 moved on after the timeout")
        val reg = events.map { it.second }.filterIsInstance<SrtlaEvent.GroupRegistered>().single()
        assertEquals(0, reg.linkId)
    }

    @Test
    fun `REG1 goes to the most promising link first and a captive portal waits`() {
        val sim = Sim()
        sim.uplinkDead += setOf(0, 1, 2)
        sim.core.addLink(0, "wifi", hint = LinkHint.WIFI_UNVALIDATED)
        sim.core.addLink(1, "cell", hint = LinkHint.CELLULAR)
        sim.core.addLink(2, "portal", hint = LinkHint.CAPTIVE_PORTAL)
        sim.packetEveryMs = 0
        sim.run(5_000)
        assertEquals(listOf(1, 0, 1), sim.reg1s.take(3))
        assertTrue(2 !in sim.reg1s)
    }

    @Test
    fun `relink re-registers the link on the next tick`() {
        val sim = Sim()
        sim.connect(0, 1)
        sim.core.relink(1)
        assertTrue(events.any { (_, e) -> e is SrtlaEvent.LinkDown && e.linkId == 1 && e.reason == "address changed" })
        val before = sim.reg1s.size
        sim.run(50)
        assertEquals(before, sim.reg1s.size)
        assertEquals(LinkPhase.REGISTERING, sim.stats().links[1].phase)
        sim.run(40)
        assertEquals(2, sim.stats().connectedLinks)
        val up = events.map { it.second }.filterIsInstance<SrtlaEvent.LinkUp>().last()
        assertEquals(1, up.linkId)
        assertEquals(false, up.afterOutage)
    }

    @Test
    fun `the first link up after none were connected is flagged as the end of an outage`() {
        val sim = Sim()
        sim.connect(0)
        val first = events.map { it.second }.filterIsInstance<SrtlaEvent.LinkUp>().single()
        assertTrue(first.afterOutage)
    }

    @Test
    fun `a helper reporting thermal RED carries at most half its former share`() {
        val sim = Sim()
        sim.connect(0, 1)
        var start = now
        sim.run(3_000)
        val before = sim.dataOn(1, start).toDouble() / sim.data.count { it.first >= start }

        val load = srtlakt.moblink.HelperLoad()
        assertTrue(load.update(srtlakt.moblink.Moblink.Thermal.RED, 70))
        // What the app's link manager does with it.
        sim.core.setPriority(1, if (load.demoted) LinkPriority.SECONDARY else LinkPriority.PRIMARY)
        sim.core.setCapScale(1, load.capScale)
        start = now
        sim.run(3_000)
        val after = sim.dataOn(1, start).toDouble() / sim.data.count { it.first >= start }
        assertTrue(after <= before * 0.5, "helper share $before -> $after")
        assertTrue(after > 0, "still warm")
    }
}
