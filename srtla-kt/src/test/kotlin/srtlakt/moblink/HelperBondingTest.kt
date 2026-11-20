// SPDX-License-Identifier: Apache-2.0
package srtlakt.moblink

import srtlakt.FakeSrtlaReceiver
import srtlakt.SrtlaConfig
import srtlakt.SrtlaSender
import srtlakt.protocol.Bytes
import srtlakt.protocol.Srt
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole helper-phone path on loopback, as the app wires it: SRT caller -> SrtlaSender with one ordinary link and
 * one link through a Moblink helper (streamer asks, relay tunnels) -> SRTLA receiver -> SRT listener, and ACKs back.
 * Then the ordinary link dies and the helper alone carries the stream.
 */
class HelperBondingTest {
    private val loopback = InetAddress.getLoopbackAddress()

    private fun waitFor(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(20)
        }
    }

    @Test
    fun `a helper phone's tunnel is a full bonding link`() {
        val server = DatagramSocket(0, loopback).apply { soTimeout = 100 }
        val received = Collections.synchronizedSet(HashSet<Int>())
        val running = AtomicBoolean(true)
        val serverThread = Thread {
            val buf = ByteArray(2048)
            while (running.get()) {
                val p = DatagramPacket(buf, buf.size)
                try { server.receive(p) } catch (_: Exception) { continue }
                Srt.dataSeq(buf, p.length)?.let { seq ->
                    if (received.add(seq) && received.size % 100 == 0) {
                        val ack = ByteArray(44).also { Bytes.writeU16(it, 0, Srt.TYPE_ACK); Bytes.writeInt(it, 4, received.size); Bytes.writeInt(it, 16, seq + 1) }
                        server.send(DatagramPacket(ack, ack.size, p.socketAddress))
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val receiver = FakeSrtlaReceiver(InetSocketAddress("127.0.0.1", server.localPort))
        val sender = SrtlaSender(config = SrtlaConfig(connTimeoutMs = 1_000)).start()
        var localPort = 0
        sender.addLink("Wi-Fi", receiver.address) {
            DatagramChannel.open().bind(InetSocketAddress(loopback, 0)).also { localPort = (it.localAddress as InetSocketAddress).port }
        }

        // The streaming phone's side: each helper tunnel becomes a link (LinkManager.addHelper does this on Android).
        val helperLinks = Collections.synchronizedMap(HashMap<String, Int>())
        val streamer = MoblinkStreamer(
            "pw", listenPort = 0,
            onTunnel = { h, endpoint -> helperLinks[h.id] = sender.addLink(h.name, endpoint) },
            onTunnelGone = { h -> helperLinks.remove(h.id)?.let(sender::removeLink) },
        )
        streamer.setDestination("127.0.0.1", receiver.address.port)
        val relay = MoblinkRelay("127.0.0.1", streamer.port, UUID.randomUUID().toString(), "Sam's phone", "pw").start()

        val client = DatagramSocket(0, loopback).apply { soTimeout = 50 }
        val acks = Collections.synchronizedList(mutableListOf<Int>())
        val clientRx = Thread {
            val buf = ByteArray(2048)
            while (running.get()) {
                val p = DatagramPacket(buf, buf.size)
                try { client.receive(p) } catch (_: Exception) { continue }
                Srt.ackNumber(buf, p.length)?.let { acks += it }
            }
        }.apply { isDaemon = true; start() }

        try {
            waitFor(5_000, "Wi-Fi + helper connected") { sender.stats.connectedLinks == 2 }
            val target = InetSocketAddress(loopback, sender.localPort)
            fun send(range: IntRange) = range.forEach { seq ->
                val pkt = ByteArray(1316).also { Bytes.writeInt(it, 0, seq) }
                client.send(DatagramPacket(pkt, pkt.size, target))
                if (seq % 50 == 0) Thread.sleep(2)
            }

            send(0 until 3_000)
            waitFor(3_000, "first 3000 packets") { received.size == 3_000 }
            assertEquals(2, receiver.dataPerConn.values.count { it > 100 }, "both links carry data: ${receiver.dataPerConn}")
            assertTrue(relay.bytesUp.get() > 100 * 1316, "the helper forwarded data (${relay.bytesUp.get()} bytes)")
            assertTrue(relay.bytesDown.get() > 0, "receiver replies came back through the helper")
            assertTrue(acks.isNotEmpty(), "SRT ACKs reach the caller")

            // The phone's own Wi-Fi dies: the helper alone keeps the stream going.
            receiver.blackholed += localPort
            waitFor(4_000, "Wi-Fi link detected dead") { sender.stats.connectedLinks == 1 }
            val before = received.size
            send(3_000 until 5_000)
            waitFor(3_000, "packets via the helper alone") { received.size >= before + 2_000 }
            assertEquals(0, sender.stats.droppedNoLink)
        } finally {
            running.set(false)
            relay.stop(); streamer.stop()
            sender.close(); receiver.close(); client.close(); server.close()
            serverThread.join(500); clientRx.join(500)
        }
    }
}
