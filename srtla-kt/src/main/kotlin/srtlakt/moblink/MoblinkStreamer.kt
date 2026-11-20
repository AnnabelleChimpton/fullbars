// SPDX-License-Identifier: Apache-2.0
package srtlakt.moblink

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The streaming phone's side of Moblink ([Moblink]): accepts helper phones, checks their password, and asks each for a
 * tunnel to the BRB server. Every tunnel becomes one more bonding link (`helperIp:tunnelPort`), reported through
 * [onTunnel] / [onTunnelGone]. Advertising over mDNS is the platform's job (Android: NsdManager).
 *
 * The callbacks run on this class's own threads, often while the stream is ending; an exception from one is logged and
 * swallowed here, since on Android an uncaught exception on any thread ends the whole app (and the stream with it).
 */
class MoblinkStreamer(
    private val password: String,
    private val listenPort: Int = Moblink.DEFAULT_PORT,
    private val onTunnel: (Helper, InetSocketAddress) -> Unit,
    private val onTunnelGone: (Helper) -> Unit,
    private val onStatus: (Helper) -> Unit = {},
    private val log: (String) -> Unit = {},
    private val statusIntervalMs: Long = 5_000,
    /**
     * A helper that couldn't open a tunnel (no mobile data yet, say) is asked again after this long, then twice as
     * long each time up to [tunnelRetryMaxMs], for as long as it stays. A tunnel that opens starts the wait over.
     */
    private val tunnelRetryMs: Long = 1_000,
    private val tunnelRetryMaxMs: Long = 10_000,
) {
    /** A connected helper. [localAddress]: this phone's end of its connection (which network it's on). */
    class Helper(val id: String, val name: String, val localAddress: java.net.InetAddress) {
        @Volatile var batteryPercentage: Int? = null
            internal set
        @Volatile var thermal: Moblink.Thermal? = null
            internal set
        @Volatile var tunnel: InetSocketAddress? = null
            internal set
        override fun toString() = "$name ($id)"
    }

    private inner class Session(val ws: WebSocket) {
        val challenge = Moblink.randomString()
        val salt = Moblink.randomString()
        var helper: Helper? = null
        val nextId = AtomicInteger(1)
        val pending = ConcurrentHashMap<Int, Moblink.RequestData>()
        /** Tunnel requests that failed in a row (sets the retry wait). */
        @Volatile var tunnelFailures = 0
        fun request(d: Moblink.RequestData) {
            val id = nextId.getAndIncrement()
            pending[id] = d
            ws.send(Moblink.encode(Moblink.Request(id, d)))
        }
    }

    private val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(listenPort)) }
    val port: Int get() = server.localPort
    private val sessions = ConcurrentHashMap<String, Session>() // by helper id, identified only
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "moblink-timer").apply { isDaemon = true } }
    @Volatile private var running = true
    @Volatile private var destination: Moblink.StartTunnel? = null

    val helpers: List<Helper> get() = sessions.values.mapNotNull { it.helper }

    init {
        Thread(::acceptLoop, "moblink-accept").apply { isDaemon = true; start() }
        timer.scheduleWithFixedDelay(::keepalive, Moblink.PING_INTERVAL_MS, Moblink.PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        timer.scheduleWithFixedDelay(::askStatus, statusIntervalMs, statusIntervalMs, TimeUnit.MILLISECONDS)
    }

    /** Where helpers should send (the BRB server). Asks every connected helper for a fresh tunnel. */
    fun setDestination(address: String, port: Int) {
        val d = Moblink.StartTunnel(address, port)
        destination = d
        sessions.values.forEach { s -> runCatching { s.request(d) } }
    }

    fun stop() {
        running = false
        runCatching { server.close() }
        timer.shutdownNow()
        sessions.values.forEach { it.ws.close() }
    }

    private fun acceptLoop() {
        while (running) {
            val socket = try { server.accept() } catch (_: IOException) { break }
            Thread({ serve(socket) }, "moblink-helper").apply { isDaemon = true; start() }
        }
    }

    private fun serve(socket: Socket) {
        val ws = try {
            socket.tcpNoDelay = true
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS // until it identifies; then pongs arrive every 10 s
            WebSocket.accept(socket)
        } catch (e: IOException) {
            log("moblink: handshake from ${socket.inetAddress} failed: $e")
            return
        }
        val s = Session(ws)
        try {
            ws.send(Moblink.encode(Moblink.Hello(Moblink.API_VERSION, s.challenge, s.salt)))
            while (running) {
                val text = ws.receive() ?: break
                when (val m = Moblink.decodeToStreamer(text)) {
                    is Moblink.Identify -> identify(s, m) ?: break
                    is Moblink.Response -> {
                        val helper = s.helper ?: break // a response before identifying: drop, as Moblin does
                        response(s, helper, m)
                    }
                    null -> log("moblink: ignoring $text")
                }
            }
        } catch (e: IOException) {
            log("moblink: ${s.helper ?: ws.remoteAddress}: $e")
        } finally {
            ws.close()
            val h = s.helper
            if (h != null && sessions.remove(h.id, s)) {
                log("moblink: $h left")
                if (h.tunnel != null) tunnelGone(h)
            }
        }
    }

    /** Null to drop the connection. */
    private fun identify(s: Session, m: Moblink.Identify): Unit? {
        if (s.helper != null) {
            s.ws.send(Moblink.encode(Moblink.Identified(Moblink.ALREADY_IDENTIFIED)))
            return Unit
        }
        if (m.authentication != Moblink.authentication(password, s.salt, s.challenge)) {
            log("moblink: wrong password from ${s.ws.remoteAddress}")
            s.ws.send(Moblink.encode(Moblink.Identified(Moblink.WRONG_PASSWORD)))
            return null
        }
        val h = Helper(m.id, Moblink.cleanName(m.name).ifEmpty { "Helper" }, s.ws.localAddress)
        s.helper = h
        // The same helper reconnecting replaces its old session (and its old tunnel).
        sessions.put(h.id, s)?.let { old ->
            old.helper?.takeIf { it.tunnel != null }?.let(::tunnelGone)
            old.helper?.tunnel = null
            old.ws.close()
        }
        s.ws.setReadTimeout(IDLE_TIMEOUT_MS)
        s.ws.send(Moblink.encode(Moblink.Identified(Moblink.OK)))
        log("moblink: $h joined from ${s.ws.remoteAddress}")
        destination?.let { s.request(it) }
        s.request(Moblink.Status)
        return Unit
    }

    private fun response(s: Session, h: Helper, m: Moblink.Response) {
        val asked = s.pending.remove(m.id) ?: return
        when {
            asked is Moblink.StartTunnel && m.result == Moblink.OK && m.data is Moblink.TunnelStarted -> {
                if (asked != destination) return // superseded by a newer destination
                val endpoint = InetSocketAddress(s.ws.remoteAddress, m.data.port)
                if (h.tunnel != null) tunnelGone(h)
                h.tunnel = endpoint
                s.tunnelFailures = 0
                log("moblink: $h tunnel at $endpoint")
                safely("onTunnel") { onTunnel(h, endpoint) }
            }
            asked is Moblink.StartTunnel -> {
                val wait = tunnelRetryDelayMs(s.tunnelFailures++, tunnelRetryMs, tunnelRetryMaxMs)
                log("moblink: $h couldn't open a tunnel (${m.result}); asking again in ${wait / 1000.0} s")
                askAgainLater(s, h, wait)
            }
            asked == Moblink.Status && m.data is Moblink.StatusReport -> {
                h.batteryPercentage = m.data.batteryPercentage
                h.thermal = m.data.thermal
                // It answers, so it's reachable: a helper still without a tunnel is asked again now rather than at
                // the end of its retry wait (its mobile data may just have come up).
                val d = destination
                if (h.tunnel == null && d != null && s.pending.values.none { it is Moblink.StartTunnel }) runCatching { s.request(d) }
                safely("onStatus") { onStatus(h) }
            }
        }
    }

    private fun tunnelGone(h: Helper) = safely("onTunnelGone") { onTunnelGone(h) }

    private inline fun safely(what: String, block: () -> Unit) {
        try { block() } catch (e: RuntimeException) { log("moblink: $what failed: $e") }
    }

    /** Asks [h] for a tunnel again unless it left, the stream ended, or a newer request is already out. */
    private fun askAgainLater(s: Session, h: Helper, waitMs: Long) {
        if (!running) return
        try {
            timer.schedule({
                val d = destination
                if (running && d != null && sessions[h.id] === s && s.pending.values.none { it is Moblink.StartTunnel }) {
                    runCatching { s.request(d) }
                }
            }, waitMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {} // stopping
    }

    private fun keepalive() {
        val deadline = System.nanoTime() - 2 * Moblink.PING_INTERVAL_MS * 1_000_000
        sessions.values.forEach { s ->
            if (s.ws.lastPongNanos < deadline) {
                log("moblink: ${s.helper} stopped answering")
                s.ws.close() // its reader thread cleans up
            } else runCatching { s.ws.ping() }
        }
    }

    private fun askStatus() = sessions.values.forEach { s -> runCatching { s.request(Moblink.Status) } }

    /** Asks one helper for a new tunnel, e.g. after its link failed. */
    fun restartTunnel(helperId: String) {
        val d = destination ?: return
        sessions[helperId]?.let { s -> runCatching { s.request(d) } }
    }

    internal companion object {
        const val HANDSHAKE_TIMEOUT_MS = 10_000
        const val IDLE_TIMEOUT_MS = 30_000

        /** The wait before tunnel request number [failures] + 1: [firstMs], doubling, at most [maxMs]. */
        fun tunnelRetryDelayMs(failures: Int, firstMs: Long, maxMs: Long): Long =
            (firstMs shl failures.coerceIn(0, 20)).coerceAtMost(maxMs)
    }
}
