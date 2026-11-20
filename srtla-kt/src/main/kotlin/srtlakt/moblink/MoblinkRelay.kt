// SPDX-License-Identifier: Apache-2.0
package srtlakt.moblink

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.DatagramChannel
import java.util.concurrent.atomic.AtomicLong

/**
 * The helper phone's side of Moblink ([Moblink]): connects to one streamer, and on `startTunnel` forwards UDP between
 * the streamer and the BRB server over this phone's own mobile data. Reconnects every [reconnectDelayMs] until [stop].
 * Nothing here is Android-specific: the factories decide which network each socket uses.
 *
 * A streaming phone can vanish without closing anything (it left Wi-Fi range, its hotspot went down, it crashed): the
 * relay pings it every [pingIntervalMs] and gives up on a connection that has said nothing for [idleTimeoutMs], so a
 * pocketed helper reconnects instead of sitting on "Ready" forever.
 */
class MoblinkRelay(
    private val host: String,
    private val port: Int,
    /** Stable across launches (a UUID string): a streamer replaces the old session when the same id reconnects. */
    private val relayId: String,
    name: String,
    private val password: String,
    /** A TCP socket connected to host:port over the local network (Wi-Fi / the streamer's hotspot). */
    private val connectSocket: (String, Int) -> Socket = { h, p -> Socket().apply { connect(InetSocketAddress(h, p), 10_000) } },
    /** An unbound UDP channel for the streamer side of a tunnel; bound here to an ephemeral port on all interfaces. */
    private val streamerChannel: () -> DatagramChannel = { DatagramChannel.open() },
    /** A UDP channel that leaves through mobile data (Android: Network.bindSocket on the cellular network). */
    private val destinationChannel: () -> DatagramChannel = { DatagramChannel.open() },
    /** Resolves the BRB server's name, ideally through the same network as [destinationChannel]. */
    private val resolve: (String) -> InetAddress = { InetAddress.getByName(it) },
    private val status: () -> Moblink.StatusReport = { Moblink.StatusReport(null, null) },
    private val onState: (State) -> Unit = {},
    private val log: (String) -> Unit = {},
    /** Until the streamer identifies us: one that accepts the connection and then says nothing is not a streamer. */
    private val handshakeTimeoutMs: Int = 10_000,
    /** Once identified: three missed pings' worth of silence means the streamer is gone. */
    private val idleTimeoutMs: Int = (3 * Moblink.PING_INTERVAL_MS).toInt(),
    private val pingIntervalMs: Long = Moblink.PING_INTERVAL_MS,
    private val reconnectDelayMs: Long = Moblink.RECONNECT_DELAY_MS,
) {
    private val name = Moblink.cleanName(name)

    sealed interface State {
        data object Connecting : State
        data object WrongPassword : State
        /** Connected and identified; [tunnelTo] is the BRB server once the streamer has asked for a tunnel. */
        data class Connected(val tunnelTo: String?) : State
        data class Waiting(val reason: String) : State
    }

    /** Bytes forwarded, for the helper's screen. */
    val bytesUp = AtomicLong()
    val bytesDown = AtomicLong()

    @Volatile private var running = false
    @Volatile private var ws: WebSocket? = null
    @Volatile private var tunnel: Tunnel? = null
    private var thread: Thread? = null
    private val lock = Object()
    /**
     * The streamer refused our password: keep retrying quietly (the streamer may be restarted with the right code) but
     * don't flip the helper's screen back to "Connecting" every few seconds. A new relay (a rescanned code) starts clean.
     */
    @Volatile private var wrongPassword = false

    fun start(): MoblinkRelay {
        running = true
        thread = Thread(::run, "moblink-relay").apply { isDaemon = true; start() }
        return this
    }

    fun stop() {
        running = false
        ws?.close()
        tunnel?.close()
        synchronized(lock) { lock.notifyAll() }
        thread?.join(2_000)
    }

    private fun run() {
        while (running) {
            if (!wrongPassword) onState(State.Connecting)
            val reason = try {
                session()
            } catch (e: SocketTimeoutException) {
                "the streaming phone stopped answering"
            } catch (e: IOException) {
                e.message ?: e.toString()
            } catch (e: RuntimeException) {
                "error: $e"
            } finally {
                tunnel?.close(); tunnel = null
                ws?.close(); ws = null
            }
            if (!running) break
            log("moblink relay: $reason; retrying in ${reconnectDelayMs / 1000.0} s")
            if (!wrongPassword) onState(State.Waiting(reason))
            synchronized(lock) { if (running) lock.wait(reconnectDelayMs) }
        }
    }

    /** One connection; returns why it ended. */
    private fun session(): String {
        val socket = connectSocket(host, port)
        socket.tcpNoDelay = true
        socket.soTimeout = handshakeTimeoutMs
        val ws = WebSocket.connect(socket, host, port).also { this.ws = it }
        while (running) {
            val text = ws.receive() ?: return "the streaming phone closed the connection"
            when (val m = Moblink.decodeToRelay(text)) {
                is Moblink.Hello -> ws.send(Moblink.encode(
                    Moblink.Identify(relayId, name, Moblink.authentication(password, m.salt, m.challenge)),
                ))
                is Moblink.Identified -> if (m.result == Moblink.OK) {
                    log("moblink relay: identified")
                    wrongPassword = false
                    ws.setReadTimeout(idleTimeoutMs)
                    keepPinging(ws)
                    onState(State.Connected(null))
                } else {
                    wrongPassword = true
                    onState(State.WrongPassword)
                    return WRONG_PASSWORD
                }
                is Moblink.Request -> ws.send(Moblink.encode(answer(m)))
                null -> log("moblink relay: ignoring $text")
            }
        }
        return "stopped"
    }

    /**
     * Our own pings: any WebSocket peer answers them, so the read timeout works whether or not the streamer pings us
     * (ours does every 10 s; others may not). A pong or any message resets the timeout.
     */
    private fun keepPinging(ws: WebSocket) = Thread({
        try {
            while (running && !ws.closed && this.ws === ws) {
                Thread.sleep(pingIntervalMs)
                if (this.ws === ws) runCatching { ws.ping() }
            }
        } catch (_: InterruptedException) {}
    }, "moblink-ping").apply { isDaemon = true; start() }

    private fun answer(r: Moblink.Request): Moblink.Response = when (val d = r.data) {
        is Moblink.StartTunnel -> {
            tunnel?.close(); tunnel = null
            try {
                val t = Tunnel(d.address, d.port)
                tunnel = t
                onState(State.Connected("${d.address}:${d.port}"))
                log("moblink relay: tunnel ${t.localPort} -> ${d.address}:${d.port}")
                Moblink.Response(r.id, Moblink.OK, Moblink.TunnelStarted(t.localPort))
            } catch (e: IOException) {
                // No mobile data (or the server's name doesn't resolve): the streamer simply has no link through us.
                log("moblink relay: can't open tunnel: $e")
                Moblink.Response(r.id, Moblink.UNKNOWN_REQUEST, null)
            }
        }
        Moblink.Status -> Moblink.Response(r.id, Moblink.OK, status())
        is Moblink.OtherRequest -> Moblink.Response(r.id, Moblink.UNKNOWN_REQUEST, null)
    }

    /** streamer <-> [streamerSide] … [destination] <-> BRB server. Datagrams pass through unchanged. */
    private inner class Tunnel(address: String, port: Int) {
        private val destination: DatagramChannel
        private val streamerSide: DatagramChannel
        @Volatile private var streamer: SocketAddress? = null
        @Volatile private var lastFromDestination = System.nanoTime()
        @Volatile private var open = true
        val localPort: Int

        init {
            val dest = InetSocketAddress(resolve(address), port)
            destination = destinationChannel().apply { configureBlocking(true); if (localAddress == null) bind(null); connect(dest) }
            streamerSide = try {
                streamerChannel().apply { configureBlocking(true); bind(InetSocketAddress(0)) }
            } catch (e: IOException) {
                destination.close(); throw e
            }
            localPort = (streamerSide.localAddress as InetSocketAddress).port
            Thread(::up, "moblink-up").apply { isDaemon = true; start() }
            Thread(::down, "moblink-down").apply { isDaemon = true; start() }
        }

        private fun up() {
            val buf = ByteBuffer.allocate(MAX_DATAGRAM)
            try {
                while (open) {
                    buf.clear()
                    val from = streamerSide.receive(buf) ?: continue
                    streamer = from // replies go to whoever sent last (the streamer's port can change)
                    buf.flip()
                    bytesUp.addAndGet(buf.remaining().toLong())
                    try { destination.write(buf) } catch (e: IOException) { if (e is ClosedChannelException) throw e }
                }
            } catch (_: IOException) {
            } finally { close() }
        }

        private fun down() {
            val buf = ByteBuffer.allocate(MAX_DATAGRAM)
            // The destination's silence ends the tunnel; a watchdog wakes us since a blocking read has no timeout.
            val watchdog = Thread {
                try {
                    while (open) {
                        Thread.sleep(1_000)
                        if (System.nanoTime() - lastFromDestination > Moblink.TUNNEL_IDLE_TIMEOUT_MS * 1_000_000) {
                            log("moblink relay: no reply from the server for ${Moblink.TUNNEL_IDLE_TIMEOUT_MS / 1000} s")
                            close()
                            ws?.close() // reconnect, as Moblin's relays do; the streamer asks for a fresh tunnel
                        }
                    }
                } catch (_: InterruptedException) {}
            }.apply { isDaemon = true; start() }
            try {
                while (open) {
                    buf.clear()
                    destination.receive(buf) ?: continue
                    lastFromDestination = System.nanoTime()
                    buf.flip()
                    bytesDown.addAndGet(buf.remaining().toLong())
                    val to = streamer ?: continue
                    try { streamerSide.send(buf, to) } catch (e: IOException) { if (e is ClosedChannelException) throw e }
                }
            } catch (_: IOException) {
            } finally { watchdog.interrupt(); close() }
        }

        fun close() {
            if (!open) return
            open = false
            runCatching { streamerSide.close() }
            runCatching { destination.close() }
        }
    }

    private companion object {
        const val MAX_DATAGRAM = 2048
        const val WRONG_PASSWORD = "wrong password"
    }
}
