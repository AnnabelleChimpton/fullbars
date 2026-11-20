// SPDX-License-Identifier: Apache-2.0
package srtlakt

import srtlakt.protocol.Bytes
import srtlakt.protocol.KeepaliveInfo
import srtlakt.protocol.Packets
import srtlakt.protocol.SeqMath
import srtlakt.protocol.Srt
import srtlakt.protocol.Srtla
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {
    @Test
    fun `reg packets have type then 256-byte id`() {
        val id = ByteArray(Srtla.ID_LEN) { it.toByte() }
        val reg1 = Packets.reg1(id)
        assertEquals(258, reg1.size)
        assertEquals(0x92, reg1[0].toInt() and 0xff)
        assertEquals(0x00, reg1[1].toInt())
        assertContentEquals(id, reg1.copyOfRange(2, 258))
        assertEquals(Srtla.TYPE_REG2, Packets.type(Packets.reg2(id), 258))
        assertContentEquals(byteArrayOf(0x92.toByte(), 0x02), Packets.reg3())
        assertContentEquals(byteArrayOf(0x92.toByte(), 0x11), Packets.regNgp())
    }

    @Test
    fun `extended keepalive is backwards compatible and round-trips`() {
        val info = KeepaliveInfo(7, 20_000, 3, 85, 2, 750_000)
        val pkt = Packets.keepaliveExt(info, 123_456_789L)
        assertEquals(38, pkt.size)
        // old receivers only look at type + timestamp
        assertEquals(123_456_789L, Packets.keepaliveTimestamp(pkt, 10))
        assertEquals(info, Packets.keepaliveInfo(pkt, pkt.size))
        assertNull(Packets.keepaliveInfo(Packets.keepalive(5), 10))
    }

    @Test
    fun `srtla ack carries seqs after 4-byte header`() {
        val seqs = intArrayOf(1, 2, 0x7fffffff)
        val pkt = Packets.srtlaAck(seqs)
        assertEquals(16, pkt.size)
        assertContentEquals(seqs, Packets.srtlaAckSeqs(pkt, pkt.size))
    }

    @Test
    fun `srt data seq and control detection`() {
        val data = ByteArray(16).also { Bytes.writeInt(it, 0, 42) }
        assertEquals(42, Srt.dataSeq(data, 16))
        assertFalse(Srt.isControl(data, 16))
        val ctrl = ByteArray(16).also { Bytes.writeU16(it, 0, Srt.TYPE_ACK) }
        assertNull(Srt.dataSeq(ctrl, 16))
        assertTrue(Srt.isControl(ctrl, 16))
    }

    @Test
    fun `srt ack number`() {
        val ack = ByteArray(44).also { Bytes.writeU16(it, 0, Srt.TYPE_ACK); Bytes.writeInt(it, 16, 1000) }
        assertEquals(1000, Srt.ackNumber(ack, ack.size))
        assertNull(Srt.ackNumber(ack, 19))
    }

    @Test
    fun `srt nak expands ranges across wraparound`() {
        val pkt = ByteArray(16 + 4 + 8).also {
            Bytes.writeU16(it, 0, Srt.TYPE_NAK)
            Bytes.writeInt(it, 16, 5)
            Bytes.writeInt(it, 20, 0x7ffffffe or 0x80000000.toInt()) // range start
            Bytes.writeInt(it, 24, 1)                                 // range end (wrapped)
        }
        assertContentEquals(intArrayOf(5, 0x7ffffffe, 0x7fffffff, 0, 1), Srt.nakSeqs(pkt, pkt.size))
    }

    @Test
    fun `handshake induction detection`() {
        val hs = ByteArray(64).also {
            Bytes.writeU16(it, 0, Srt.TYPE_HANDSHAKE)
            Bytes.writeInt(it, 16 + 20, Srt.HS_REQTYPE_INDUCTION)
        }
        assertTrue(Srt.isHandshakeInduction(hs, hs.size))
        Bytes.writeInt(hs, 16 + 20, -1) // conclusion
        assertFalse(Srt.isHandshakeInduction(hs, hs.size))
    }

    @Test
    fun `sequence math wraps`() {
        assertEquals(0, SeqMath.next(SeqMath.MAX))
        assertEquals(1, SeqMath.diff(0, SeqMath.MAX))
        assertEquals(-1, SeqMath.diff(SeqMath.MAX, 0))
        assertTrue(SeqMath.after(3, SeqMath.MAX - 3))
        assertFalse(SeqMath.after(5, 5))
    }
}
