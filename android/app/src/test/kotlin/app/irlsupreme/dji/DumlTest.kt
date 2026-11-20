package app.irlsupreme.dji

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DumlTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `frames round-trip and checksums verify`() {
        val f = DjiCommands.startStream()
        val m = DjiMessage.decode(f)
        assertEquals(0x02, m.cmdSet); assertEquals(0x8E, m.cmdId); assertEquals(0x40, m.flags)
        assertArrayEquals(f, m.encode())
        f[12] = (f[12] + 1).toByte()
        assertTrue(runCatching { DjiMessage.decode(f) }.isFailure)
    }

    @Test
    fun `start and stop match djictl's wire bytes`() {
        // djictl writes message id 0xB4BB big-endian, so the wire bytes are B4 BB.
        val start = DjiCommands.startStream()
        assertEquals(0xB4, start[6].toInt() and 0xFF); assertEquals(0xBB, start[7].toInt() and 0xFF)
        assertArrayEquals(hex("0802"), byteArrayOf(start[5], start[4])) // receiver DM368
        assertArrayEquals(hex("0101 1a00 0102".replace(" ", "")), DjiMessage.decode(DjiCommands.stopStream()).payload)
    }

    @Test
    fun `configure carries resolution, bitrate, fps and the URL`() {
        val url = "rtmp://192.168.43.1:1935/live/abcdefghjk"
        val m = DjiMessage.decode(DjiCommands.configureStream(DjiCommands.RES_1080P, 6000, DjiCommands.FPS_30, url))
        assertEquals(0x08, m.cmdSet); assertEquals(0x78, m.cmdId)
        val p = m.payload
        assertEquals(0x2A, p[1].toInt()); assertEquals(0x0A, p[3].toInt())
        assertEquals(6000, (p[4].toInt() and 0xFF) or ((p[5].toInt() and 0xFF) shl 8))
        assertEquals(0x03, p[8].toInt())
        assertEquals(url.length, (p[12].toInt() and 0xFF) or ((p[13].toInt() and 0xFF) shl 8))
        assertEquals(url, String(p, 14, p.size - 14))
    }

    @Test
    fun `reassembler joins split frames and separates glued ones`() {
        val a = DjiCommands.pair("0123456789abcdef0123456789abcdef", "fbrs")
        val b = DjiCommands.sessionPing(keepalive = true)
        val r = DumlReassembler()
        assertEquals(0, r.feed(a.copyOfRange(0, 10)).size)
        val got = r.feed(a.copyOfRange(10, a.size) + b + byteArrayOf(0x55))
        assertEquals(2, got.size)
        assertEquals(0x45, got[0].cmdId); assertEquals(0x2B, got[1].cmdId)
        assertEquals(0, r.feed(byteArrayOf(0x00, 0x13)).size) // noise after a stray 0x55
        assertEquals(1, r.feed(DjiCommands.startStream()).size)
    }

    @Test
    fun `reply swaps sender and receiver and keeps the id`() {
        val req = DjiMessage(0x0207, 0x1234, 0x460740, byteArrayOf(1))
        val rep = req.reply(byteArrayOf(0))
        assertEquals(0x0702, rep.target); assertEquals(0x1234, rep.id); assertEquals(0xC0, rep.flags)
        assertEquals(0x07, rep.cmdSet); assertEquals(0x46, rep.cmdId)
    }

    @Test
    fun `pocket 3 is recognised in both advert formats`() {
        assertEquals(DjiAdvert.POCKET_3, DjiAdvert.modelId(hex("2000000000000000")))
        assertEquals(DjiAdvert.POCKET_3, DjiAdvert.modelId(hex("000000ee000400000000910000")))
        assertEquals("Osmo Pocket 3", DjiAdvert.name(0x0020))
    }
}
