// SPDX-License-Identifier: Apache-2.0
package srtlakt

import srtlakt.protocol.Bytes
import srtlakt.protocol.Packets
import srtlakt.protocol.Srt
import java.lang.management.ManagementFactory
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The per-packet paths allocate nothing in steady state: hundreds of packets a second per link on the sender thread
 * used to box every sequence number and allocate log entries (garbage the phone's collector then had to chase).
 */
class AllocationTest {
    private object Sink : SrtlaOutput {
        override fun toLink(linkId: Int, buf: ByteArray, off: Int, len: Int) = true
        override fun toClient(buf: ByteArray, off: Int, len: Int) = Unit
    }

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    @Test
    fun `data packets, SRTLA ACKs and echoed SRT ACKs allocate nothing per packet`() {
        val core = SrtlaCore(SrtlaConfig(), Random(1))
        var now = 1_000L
        repeat(3) { core.addLink(it, "l$it") }
        core.tick(now, object : SrtlaOutput by Sink {
            override fun toLink(linkId: Int, buf: ByteArray, off: Int, len: Int): Boolean {
                if (Packets.type(buf, len) == 0x9200) { // REG1: answer it, then REG3 every link
                    val id = buf.copyOfRange(2, 258)
                    core.onLinkPacket(linkId, Packets.reg2(id), 258, now, Sink)
                }
                return true
            }
        })
        repeat(3) { core.onLinkPacket(it, Packets.reg3(), 2, now, Sink) }

        val data = ByteArray(1316)
        val srtlaAck = Packets.srtlaAck(IntArray(10))
        val srtAck = ByteArray(44).also { Bytes.writeU16(it, 0, Srt.TYPE_ACK) }
        var seq = 0
        var ackNo = 0
        fun step(n: Int) {
            for (i in 0 until n) {
                Bytes.writeInt(data, 0, seq)
                core.onClientPacket(data, data.size, now, Sink)
                seq++
                if (seq % 10 == 0) {
                    for (k in 0 until 10) Bytes.writeInt(srtlaAck, 4 + 4 * k, seq - 30 + k) // a batch, a link RTT late
                    core.onLinkPacket(seq / 10 % 3, srtlaAck, srtlaAck.size, now, Sink)
                    Bytes.writeInt(srtAck, 4, ++ackNo)
                    Bytes.writeInt(srtAck, 16, seq - 40)
                    for (l in 0 until 3) core.onLinkPacket(l, srtAck, srtAck.size, now, Sink) // echoed on every link
                }
                if (seq % 2 == 0) now++
            }
        }
        step(200_000) // warm up: JIT, logs and dedup at their working size
        val before = threads.currentThreadAllocatedBytes
        step(100_000)
        val allocated = threads.currentThreadAllocatedBytes - before
        assertTrue(allocated < 100_000, "allocated $allocated bytes for 100000 packets")
    }
}
