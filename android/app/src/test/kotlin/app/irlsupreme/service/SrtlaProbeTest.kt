package app.irlsupreme.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import srtlakt.protocol.Packets
import srtlakt.protocol.Srtla
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class SrtlaProbeTest {
    /** A stand-in SRTLA receiver on localhost that answers the first REG1 with [reply] (REG2 echoes the id, as srtla_rec does). */
    private fun fakeReceiver(reply: (ByteArray) -> ByteArray?): DatagramSocket {
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            runCatching {
                val buf = ByteArray(1500)
                while (!socket.isClosed) {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    if (Packets.type(p.data, p.length) != Srtla.TYPE_REG1) continue
                    val id = p.data.copyOfRange(2, p.length)
                    val out = reply(id) ?: continue
                    socket.send(DatagramPacket(out, out.size, p.socketAddress))
                }
            }
        }
        return socket
    }

    @Test fun `a receiver that answers REG2 is reachable`() {
        fakeReceiver { id -> Packets.reg2(id) }.use { rx ->
            assertTrue(srtlaReachable("127.0.0.1", rx.localPort, timeoutMs = 1_000))
        }
    }

    @Test fun `a full receiver still proves the port is open`() {
        fakeReceiver { Packets.regErr() }.use { rx ->
            assertTrue(srtlaReachable("127.0.0.1", rx.localPort, timeoutMs = 1_000))
        }
    }

    @Test fun `silence means blocked`() {
        fakeReceiver { null }.use { rx ->
            assertFalse(srtlaReachable("127.0.0.1", rx.localPort, timeoutMs = 200))
        }
    }

    @Test fun `only SRTLA registration replies count`() {
        assertTrue(isSrtlaReply(Packets.reg2(ByteArray(Srtla.ID_LEN)), Srtla.REG2_LEN))
        assertTrue(isSrtlaReply(Packets.regNgp(), 2))
        assertFalse(isSrtlaReply(Packets.keepalive(0), Srtla.KEEPALIVE_LEN))
        assertFalse(isSrtlaReply(ByteArray(1), 1))
    }
}
