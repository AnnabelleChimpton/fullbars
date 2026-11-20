// SPDX-License-Identifier: Apache-2.0
package srtlakt

import srtlakt.protocol.Bytes
import srtlakt.protocol.Srt
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End to end over real UDP on loopback:
 * fake SRT caller -> SrtlaSender (3 links) -> FakeSrtlaReceiver -> fake SRT listener, and ACKs back.
 */
class LoopbackIntegrationTest {
    private val loopback = InetAddress.getLoopbackAddress()

    /**
     * Polls [cond]; [timeoutMs] is only an upper bound, so a healthy run is as fast as ever. The bounds are generous
     * because CI runners are shared and slow: a 3 s bound made this test flake there.
     */
    private fun waitFor(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(20)
        }
    }

    @Test
    fun `bonded stream arrives complete, survives losing a link`() {
        // Big buffers: nothing here resends a lost packet, so one kernel drop on a busy runner would leave the count
        // short of 3000 forever. Loopback only drops when a buffer fills.
        val server = DatagramSocket(0, loopback).apply { soTimeout = 100; receiveBufferSize = BIG_BUFFER }
        val received = Collections.synchronizedSet(HashSet<Int>())
        val running = AtomicBoolean(true)
        val serverThread = Thread {
            val buf = ByteArray(2048)
            while (running.get()) {
                val p = DatagramPacket(buf, buf.size)
                try { server.receive(p) } catch (_: Exception) { continue }
                Srt.dataSeq(buf, p.length)?.let { seq ->
                    if (received.add(seq) && received.size % 100 == 0) {
                        // cumulative SRT ACK back to the caller
                        val ack = ByteArray(44).also { Bytes.writeU16(it, 0, Srt.TYPE_ACK); Bytes.writeInt(it, 4, received.size); Bytes.writeInt(it, 16, seq + 1) }
                        server.send(DatagramPacket(ack, ack.size, p.socketAddress))
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val receiver = FakeSrtlaReceiver(InetSocketAddress("127.0.0.1", server.localPort))
        val events = Collections.synchronizedList(mutableListOf<SrtlaEvent>())
        // A long dedup window: a copy of an ACK delayed past the app's 500 ms by a starved CI thread would otherwise count
        // as a second delivery (harmless in a real stream, where SRT ignores an ACK it has already had).
        val cfg = SrtlaConfig(connTimeoutMs = 1_000, dedupWindowMs = 5_000)
        val sender = SrtlaSender(config = cfg, onEvent = { events += it }, log = { println(it) }).start()
        val linkPorts = mutableListOf<Int>()
        repeat(3) { i ->
            sender.addLink("link$i", receiver.address) {
                DatagramChannel.open().bind(InetSocketAddress(loopback, 0)).also {
                    linkPorts += (it.localAddress as InetSocketAddress).port
                }
            }
        }

        val client = DatagramSocket(0, loopback).apply { soTimeout = 50; receiveBufferSize = BIG_BUFFER }
        val acksAtClient = Collections.synchronizedList(mutableListOf<Int>())
        val clientRx = Thread {
            val buf = ByteArray(2048)
            while (running.get()) {
                val p = DatagramPacket(buf, buf.size)
                try { client.receive(p) } catch (_: Exception) { continue }
                Srt.ackNumber(buf, p.length)?.let { acksAtClient += it }
            }
        }.apply { isDaemon = true; start() }

        try {
            waitFor(WAIT_MS, "3 links connected") { sender.stats.connectedLinks == 3 }
            val target = InetSocketAddress(loopback, sender.localPort)
            fun send(range: IntRange) = range.forEach { seq ->
                val pkt = ByteArray(1316).also { Bytes.writeInt(it, 0, seq) }
                client.send(DatagramPacket(pkt, pkt.size, target))
                // ~5000 packets/s: still several times a 6 Mb/s stream (~570/s), but not the 25 000/s bursts that overflowed
                // Linux's small default UDP buffers on a 2-core CI runner. Nothing here resends a lost packet (SRT would).
                if (seq % 5 == 0) Thread.sleep(1)
            }

            send(0 until 3_000)
            try {
                waitFor(WAIT_MS, "first 3000 packets") { received.size == 3_000 }
            } catch (e: IllegalStateException) {
                val missing = (0 until 3_000).filterNot { it in received }
                throw IllegalStateException(
                    "${e.message}: got ${received.size}, missing ${missing.size} (first ${missing.take(10)}); " +
                        "sender noLink=${sender.stats.droppedNoLink} " +
                        "links=${sender.stats.connectedLinks}; receiver perConn=${receiver.dataPerConn}; events=${events.takeLast(8)}",
                )
            }
            val used = receiver.dataPerConn.values.count { it > 100 }
            assertTrue(used >= 2, "traffic spread over links: ${receiver.dataPerConn}")
            assertTrue(acksAtClient.isNotEmpty(), "SRT ACKs make it back to the caller")
            assertEquals(acksAtClient.size, acksAtClient.toSet().size, "each ACK delivered once despite 3 links")

            // Kill one uplink: the receiver stops hearing it entirely.
            receiver.blackholed += linkPorts[0]
            waitFor(PROTOCOL_WAIT_MS, "dead link detected") { sender.stats.connectedLinks == 2 }
            assertTrue(events.any { it is SrtlaEvent.LinkDown && it.reason == "timeout" })

            val before = received.size
            send(3_000 until 5_000)
            waitFor(WAIT_MS, "packets after link loss") { received.size >= before + 2_000 }
            assertEquals(0, sender.stats.droppedNoLink)

            // Bring it back: it re-registers by itself.
            receiver.blackholed.clear()
            waitFor(PROTOCOL_WAIT_MS, "link recovered") { sender.stats.connectedLinks == 3 }

            val st = sender.stats
            println(
                "socket buffers granted: localhost receive ${st.clientReceiveBufferBytes / 1024} KB, " +
                    "links send ${st.links.map { it.sendBufferBytes / 1024 }} KB; " +
                    "local drops ${st.localDrops} (every link full: ${st.droppedLocal}), client send drops ${st.clientSendDrops}",
            )
            assertTrue(st.clientReceiveBufferBytes > 0 && st.links.all { it.sendBufferBytes > 0 }, "buffer sizes read back: $st")
            assertEquals(0L, st.droppedLocal, "loopback never fills every link's socket")
        } finally {
            running.set(false)
            sender.close(); receiver.close(); client.close(); server.close()
            serverThread.join(500); clientRx.join(500)
        }
    }

    @Test
    fun `relink opens a fresh socket for the link and it registers again`() {
        val server = DatagramSocket(0, loopback).apply { soTimeout = 100 }
        val receiver = FakeSrtlaReceiver(InetSocketAddress("127.0.0.1", server.localPort))
        val events = Collections.synchronizedList(mutableListOf<SrtlaEvent>())
        val sender = SrtlaSender(onEvent = { events += it }).start()
        val opened = Collections.synchronizedList(mutableListOf<Int>())
        val id = sender.addLink("wifi", receiver.address) {
            DatagramChannel.open().bind(InetSocketAddress(loopback, 0)).also { opened += (it.localAddress as InetSocketAddress).port }
        }
        try {
            waitFor(WAIT_MS, "link connected") { sender.stats.connectedLinks == 1 }
            val t0 = System.nanoTime()
            sender.relink(id)
            waitFor(PROTOCOL_WAIT_MS, "re-registered on a new socket") {
                events.count { it is SrtlaEvent.LinkUp } == 2 && sender.stats.connectedLinks == 1
            }
            println("relink: back up after ${(System.nanoTime() - t0) / 1_000_000} ms")
            assertEquals(2, opened.size, "a second socket was opened")
            assertTrue(opened[0] != opened[1], "from a new local port: $opened")
            assertTrue(events.any { it is SrtlaEvent.LinkDown && it.reason == "address changed" })
            assertEquals(listOf(id), sender.stats.links.map { it.id }, "same link id")
        } finally {
            sender.close(); receiver.close(); server.close()
        }
    }

    private companion object {
        const val WAIT_MS = 15_000L
        /** Waits that also check the protocol's own timing (1 s link timeout): loose for CI, tight enough to catch a slowdown. */
        const val PROTOCOL_WAIT_MS = 8_000L
        const val BIG_BUFFER = 4 shl 20
    }
}
