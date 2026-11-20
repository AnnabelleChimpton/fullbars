// SPDX-License-Identifier: Apache-2.0
package srtlakt.moblink

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoblinkTest {
    @Test
    fun authenticationMatchesMoblinsFormula() {
        // python3: base64(sha256(base64(sha256(b'1234salty')) + b'chal'))
        assertEquals("41zH7XWO0mH+Wx5TDpOZGwLl1wPPVqNdTq/2sLFwv2o=", Moblink.authentication("1234", "salty", "chal"))
    }

    @Test
    fun messagesUseMoblinsJsonShapes() {
        assertEquals(
            """{"hello":{"apiVersion":"0.1","authentication":{"challenge":"c","salt":"s"}}}""",
            Moblink.encode(Moblink.Hello("0.1", "c", "s")),
        )
        assertEquals("""{"identified":{"result":{"ok":{}}}}""", Moblink.encode(Moblink.Identified(Moblink.OK)))
        assertEquals(
            """{"request":{"id":1,"data":{"startTunnel":{"address":"203.0.113.5","port":5000}}}}""",
            Moblink.encode(Moblink.Request(1, Moblink.StartTunnel("203.0.113.5", 5000))),
        )
        assertEquals("""{"request":{"id":2,"data":{"status":{}}}}""", Moblink.encode(Moblink.Request(2, Moblink.Status)))
        assertEquals(
            """{"response":{"id":2,"result":{"ok":{}},"data":{"status":{"batteryPercentage":87,"thermalState":"white"}}}}""",
            Moblink.encode(Moblink.Response(2, Moblink.OK, Moblink.StatusReport(87, Moblink.Thermal.WHITE))),
        )
        assertEquals(
            """{"response":{"id":1,"result":{"ok":{}},"data":{"status":{}}}}""",
            Moblink.encode(Moblink.Response(1, Moblink.OK, Moblink.StatusReport(null, null))),
        )
    }

    @Test
    fun decodesWhatMoblinAndMoblinkRustSend() {
        assertEquals(Moblink.Hello("1.0", "c", "s"),
            Moblink.decodeToRelay("""{"hello":{"apiVersion":"1.0","authentication":{"challenge":"c","salt":"s"}}}"""))
        assertEquals(Moblink.Identified(Moblink.WRONG_PASSWORD),
            Moblink.decodeToRelay("""{"identified":{"result":{"wrongPassword":{}}}}"""))
        assertEquals(Moblink.Request(7, Moblink.StartTunnel("brb.example.com", 5000)),
            Moblink.decodeToRelay("""{"request":{"id":7,"data":{"startTunnel":{"address":"brb.example.com","port":5000}}}}"""))
        assertEquals(Moblink.Request(3, Moblink.OtherRequest("somethingNew")),
            Moblink.decodeToRelay("""{"request":{"id":3,"data":{"somethingNew":{}}}}"""))
        assertEquals(Moblink.Identify("123E4567-E89B-12D3-A456-426614174000", "Pixel 7", "abc="),
            Moblink.decodeToStreamer("""{"identify":{"id":"123E4567-E89B-12D3-A456-426614174000","name":"Pixel 7","authentication":"abc="}}"""))
        // moblink-rust sends only the battery; Moblin omits unknown fields.
        assertEquals(Moblink.Response(2, Moblink.OK, Moblink.StatusReport(55, null)),
            Moblink.decodeToStreamer("""{"response":{"id":2,"result":{"ok":{}},"data":{"status":{"batteryPercentage":55}}}}"""))
        assertEquals(Moblink.Response(1, Moblink.OK, Moblink.TunnelStarted(40123)),
            Moblink.decodeToStreamer("""{"response":{"id":1,"result":{"ok":{}},"data":{"startTunnel":{"port":40123}}}}"""))
        assertNull(Moblink.decodeToStreamer("""{"nonsense":{}}"""))
        assertNull(Moblink.decodeToStreamer("not json"))
        assertEquals("Sam's phone", Moblink.cleanName("  Sam's phone \nsecond line"))
    }

    /** A UDP echo standing in for the BRB server. */
    private class EchoServer : AutoCloseable {
        val ch: DatagramChannel = DatagramChannel.open().bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val port get() = (ch.localAddress as InetSocketAddress).port
        private val t = thread(isDaemon = true) {
            val b = ByteBuffer.allocate(2048)
            try {
                while (true) { b.clear(); val from = ch.receive(b); b.flip(); ch.send(b, from) }
            } catch (_: Exception) {}
        }
        override fun close() = ch.close()
    }

    @Test
    fun helperTunnelsUdpToTheServerAndReportsStatus() {
        EchoServer().use { echo ->
            val tunnels = ArrayBlockingQueue<InetSocketAddress>(4)
            val statuses = ArrayBlockingQueue<Int>(4)
            val streamer = MoblinkStreamer(
                "s3cret", listenPort = 0,
                onTunnel = { _, at -> tunnels.add(at) },
                onTunnelGone = {},
                onStatus = { h -> h.batteryPercentage?.let(statuses::add) },
                statusIntervalMs = 200,
            )
            streamer.setDestination("127.0.0.1", echo.port)
            val states = ArrayBlockingQueue<MoblinkRelay.State>(16)
            val relay = MoblinkRelay(
                "127.0.0.1", streamer.port, UUID.randomUUID().toString(), "Test helper", "s3cret",
                status = { Moblink.StatusReport(64, Moblink.Thermal.YELLOW) },
                onState = { states.add(it) },
            ).start()
            try {
                val at = assertNotNull(tunnels.poll(5, TimeUnit.SECONDS))
                assertEquals(64, statuses.poll(5, TimeUnit.SECONDS))
                assertEquals("Test helper", streamer.helpers.single().name)
                assertEquals(Moblink.Thermal.YELLOW, streamer.helpers.single().thermal)

                // The streamer's SRTLA link would send here; the helper forwards to the server and back.
                DatagramChannel.open().use { link ->
                    link.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), at.port))
                    link.socket().soTimeout = 2_000
                    for (i in 0 until 20) {
                        link.write(ByteBuffer.wrap("pkt$i".toByteArray()))
                        val p = java.net.DatagramPacket(ByteArray(64), 64)
                        link.socket().receive(p)
                        assertEquals("pkt$i", String(p.data, 0, p.length))
                    }
                }
                assert(relay.bytesUp.get() > 0 && relay.bytesDown.get() > 0)
            } finally {
                relay.stop()
                streamer.stop()
            }
        }
    }

    @Test
    fun wrongPasswordIsRefused() {
        val streamer = MoblinkStreamer("right", listenPort = 0, onTunnel = { _, _ -> }, onTunnelGone = {})
        val states = ArrayBlockingQueue<MoblinkRelay.State>(16)
        val relay = MoblinkRelay("127.0.0.1", streamer.port, UUID.randomUUID().toString(), "Intruder", "wrong",
            onState = { states.add(it) }).start()
        try {
            var s: MoblinkRelay.State?
            do { s = states.poll(5, TimeUnit.SECONDS) } while (s != null && s !is MoblinkRelay.State.WrongPassword)
            assertIs<MoblinkRelay.State.WrongPassword>(s)
            assertEquals(0, streamer.helpers.size)
        } finally {
            relay.stop()
            streamer.stop()
        }
    }

    @Test
    fun reconnectingHelperReplacesItsOldTunnel() {
        EchoServer().use { echo ->
            val events = ArrayBlockingQueue<String>(16)
            val streamer = MoblinkStreamer("pw", listenPort = 0,
                onTunnel = { h, _ -> events.add("up ${h.name}") }, onTunnelGone = { h -> events.add("gone ${h.name}") })
            streamer.setDestination("127.0.0.1", echo.port)
            val id = UUID.randomUUID().toString()
            val a = MoblinkRelay("127.0.0.1", streamer.port, id, "A", "pw").start()
            try {
                assertEquals("up A", events.poll(5, TimeUnit.SECONDS))
                val b = MoblinkRelay("127.0.0.1", streamer.port, id, "A", "pw").start()
                try {
                    assertEquals("gone A", events.poll(5, TimeUnit.SECONDS))
                    assertEquals("up A", events.poll(5, TimeUnit.SECONDS))
                    assertEquals(1, streamer.helpers.size)
                } finally { b.stop() }
            } finally {
                a.stop()
                streamer.stop()
            }
        }
    }

    @Test
    fun wrongPasswordDoesNotFlickerBackToConnecting() {
        val streamer = MoblinkStreamer("right", listenPort = 0, onTunnel = { _, _ -> }, onTunnelGone = {})
        val states = ArrayBlockingQueue<MoblinkRelay.State>(64)
        val relay = MoblinkRelay("127.0.0.1", streamer.port, UUID.randomUUID().toString(), "Intruder", "wrong",
            onState = { states.add(it) }, reconnectDelayMs = 100).start()
        try {
            var s: MoblinkRelay.State?
            do { s = states.poll(5, TimeUnit.SECONDS) } while (s != null && s !is MoblinkRelay.State.WrongPassword)
            assertIs<MoblinkRelay.State.WrongPassword>(s)
            Thread.sleep(800) // several quiet retries
            val after = generateSequence { states.poll() }.toList()
            assertTrue(after.none { it is MoblinkRelay.State.Connecting || it is MoblinkRelay.State.Waiting }, "got $after")
        } finally {
            relay.stop()
            streamer.stop()
        }
    }

    /** A streamer that identifies the relay and then goes quiet without closing (it walked out of Wi-Fi range). */
    @Test
    fun relayNoticesASilentStreamerAndReconnects() {
        val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        val connections = AtomicInteger()
        val held = java.util.Collections.synchronizedList(ArrayList<Socket>())
        thread(isDaemon = true) {
            try {
                while (true) {
                    val sock = server.accept()
                    held += sock
                    connections.incrementAndGet()
                    thread(isDaemon = true) {
                        runCatching {
                            val ws = WebSocket.accept(sock)
                            ws.send(Moblink.encode(Moblink.Hello(Moblink.API_VERSION, "c", "s")))
                            ws.receive() // identify
                            ws.send(Moblink.encode(Moblink.Identified(Moblink.OK)))
                            // Silence from here on: nothing is read, so the relay's pings go unanswered.
                        }
                    }
                }
            } catch (_: IOException) {}
        }
        val states = ArrayBlockingQueue<MoblinkRelay.State>(64)
        val relay = MoblinkRelay(
            "127.0.0.1", server.localPort, UUID.randomUUID().toString(), "Helper", "pw",
            onState = { states.add(it) }, idleTimeoutMs = 600, pingIntervalMs = 150, reconnectDelayMs = 200,
        ).start()
        try {
            fun next(): MoblinkRelay.State? = states.poll(5, TimeUnit.SECONDS)
            var s = next()
            while (s != null && s !is MoblinkRelay.State.Connected) s = next()
            assertIs<MoblinkRelay.State.Connected>(s)
            s = next()
            assertIs<MoblinkRelay.State.Waiting>(s)
            assertEquals("the streaming phone stopped answering", s.reason)
            val deadline = System.currentTimeMillis() + 5_000
            while (connections.get() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertTrue(connections.get() >= 2, "the relay should have reconnected")
        } finally {
            relay.stop()
            server.close()
            held.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun streamerAsksAgainAfterAFailedTunnel() {
        EchoServer().use { echo ->
            val tunnels = ArrayBlockingQueue<InetSocketAddress>(4)
            val streamer = MoblinkStreamer("pw", listenPort = 0, onTunnel = { _, at -> tunnels.add(at) }, onTunnelGone = {},
                tunnelRetryMs = 200)
            streamer.setDestination("127.0.0.1", echo.port)
            val opens = AtomicInteger()
            val relay = MoblinkRelay("127.0.0.1", streamer.port, UUID.randomUUID().toString(), "Late data", "pw",
                destinationChannel = {
                    if (opens.getAndIncrement() == 0) throw IOException("no mobile data yet") else DatagramChannel.open()
                }).start()
            try {
                assertNotNull(tunnels.poll(5, TimeUnit.SECONDS))
                assertEquals(2, opens.get())
            } finally {
                relay.stop()
                streamer.stop()
            }
        }
    }

    /** The app's link manager may already be shut down when a helper's tunnel goes at stream end: no crash. */
    @Test
    fun callbackFailuresAtStopStayOnTheStreamersThreads() {
        EchoServer().use { echo ->
            val escaped = java.util.Collections.synchronizedList(ArrayList<Throwable>())
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { _, e -> escaped += e }
            try {
                val tunnels = ArrayBlockingQueue<InetSocketAddress>(4)
                val gone = ArrayBlockingQueue<String>(4)
                val streamer = MoblinkStreamer("pw", listenPort = 0, onTunnel = { _, at -> tunnels.add(at) },
                    onTunnelGone = { h -> gone.add(h.name); throw RejectedExecutionException("executor shut down") })
                streamer.setDestination("127.0.0.1", echo.port)
                val relay = MoblinkRelay("127.0.0.1", streamer.port, UUID.randomUUID().toString(), "H", "pw").start()
                try {
                    assertNotNull(tunnels.poll(5, TimeUnit.SECONDS))
                    streamer.stop()
                    assertEquals("H", gone.poll(5, TimeUnit.SECONDS))
                    Thread.sleep(200)
                    assertEquals(emptyList(), escaped.toList())
                } finally {
                    relay.stop()
                    streamer.stop()
                }
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(previous)
            }
        }
    }
}
