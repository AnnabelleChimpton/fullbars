// SPDX-License-Identifier: Apache-2.0
package srtlakt

import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * UDP front-end for [SrtlaCore].
 *
 * The local SRT caller (e.g. libsrt) sends to [localPort]; every uplink is a [DatagramChannel] connected to the
 * receiver. On Android, supply a [channelFactory] that binds the channel to a specific `android.net.Network`
 * (`network.bindSocket(channel.socket())`) so each link really leaves through its own interface.
 *
 * One background thread owns the core; public methods are thread-safe.
 *
 * [log]: one-off diagnostics (socket buffer sizes the OS granted, sockets replaced); never called per packet.
 * [onThreadStart]: runs first on the sender thread, e.g. to raise its scheduling priority on Android.
 * [linkSendBufferBytes] / [clientReceiveBufferBytes]: socket buffers to ask for (0 = leave the OS default). The OS may
 * grant less (Android clamps to wmem_max / rmem_max); what it granted is in the stats and logged once.
 */
class SrtlaSender(
    listenAddress: InetSocketAddress = InetSocketAddress("127.0.0.1", 0),
    private val config: SrtlaConfig = SrtlaConfig(),
    private val tickIntervalMs: Long = 50,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onEvent: (SrtlaEvent) -> Unit = {},
    private val log: (String) -> Unit = {},
    private val onThreadStart: () -> Unit = {},
    private val linkSendBufferBytes: Int = DEFAULT_LINK_SEND_BUFFER,
    clientReceiveBufferBytes: Int = DEFAULT_CLIENT_RECEIVE_BUFFER,
) : AutoCloseable {

    private val selector = Selector.open()
    private val clientChannel = DatagramChannel.open().apply {
        configureBlocking(false)
        // A keyframe arrives from the caller as one burst of hundreds of datagrams; while a link is busy the default
        // localhost buffer (~200 KB) overflowed and dropped them silently, leaving gaps SRT had to repair end to end.
        if (clientReceiveBufferBytes > 0) runCatching { setOption(StandardSocketOptions.SO_RCVBUF, clientReceiveBufferBytes) }
        bind(listenAddress)
    }
    /** The localhost receive buffer the OS actually granted. */
    val clientReceiveBufferGranted: Int = runCatching { clientChannel.getOption(StandardSocketOptions.SO_RCVBUF) }.getOrDefault(0)
    private val core = SrtlaCore(config, onEvent = { e -> onEvent(e) })

    /** A link's socket and what's needed to open it again (same id, new socket: see [relink]). */
    private class LinkIo(val label: String, val remote: InetSocketAddress, val factory: () -> DatagramChannel, var channel: DatagramChannel)
    private val linkIo = HashMap<Int, LinkIo>()
    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    private val nextLinkId = AtomicInteger(0)
    private val started = CountDownLatch(1)
    @Volatile private var running = false
    @Volatile private var clientAddress: SocketAddress? = null
    @Volatile var stats: SenderStats = SenderStats.EMPTY
        private set
    /** Sends that failed with an error, all links together (also in [SenderStats.sendErrors]). */
    @Volatile var sendErrors: Long = 0
        private set
    @Volatile private var clientSendDrops: Long = 0

    private val thread = Thread(::loop, "srtla-sender").apply { isDaemon = true }
    private val rx = ByteBuffer.allocate(MAX_DATAGRAM)
    private val rxArray = rx.array()
    /** Reused view of [rxArray] for sending what was just received (every data packet): no allocation per packet. */
    private val rxView = ByteBuffer.wrap(rxArray)

    val localPort: Int get() = (clientChannel.localAddress as InetSocketAddress).port

    init {
        log("srtla: localhost receive buffer ${clientReceiveBufferGranted / 1024} KB (asked ${clientReceiveBufferBytes / 1024} KB)")
    }

    fun start(): SrtlaSender {
        running = true
        clientChannel.register(selector, SelectionKey.OP_READ, CLIENT)
        thread.start()
        started.await()
        return this
    }

    /**
     * Adds an uplink and returns its id. The channel is created (and bound) by [channelFactory], which may be called
     * again later to replace the socket (see [relink]). [priority] and [hint]: see [LinkPriority] and [LinkHint].
     */
    fun addLink(
        label: String,
        remote: InetSocketAddress,
        priority: LinkPriority = LinkPriority.PRIMARY,
        hint: LinkHint = LinkHint.UNKNOWN,
        channelFactory: () -> DatagramChannel = { DatagramChannel.open() },
    ): Int {
        val id = nextLinkId.getAndIncrement()
        post {
            try {
                val ch = open(id, label, remote, channelFactory)
                linkIo[id] = LinkIo(label, remote, channelFactory, ch)
                core.addLink(id, label, priority, hint)
                core.setSendBufferBytes(id, sendBufferOf(ch))
            } catch (e: IOException) {
                onEvent(SrtlaEvent.LinkDown(id, label, "could not open: ${e.message}"))
            }
        }
        return id
    }

    fun removeLink(id: Int) = post {
        core.removeLink(id)
        linkIo.remove(id)?.let { runCatching { it.channel.close() } }
        consecutiveSendErrors.remove(id)
    }

    fun setLinkPriority(id: Int, priority: LinkPriority) = post { core.setPriority(id, priority) }
    fun setLinkHint(id: Int, hint: LinkHint) = post { core.setHint(id, hint) }
    /** Shrinks link [id]'s in-flight cap to [scale] of what it has earned (1.0 = normal). */
    fun setLinkCapScale(id: Int, scale: Double) = post { core.setCapScale(id, scale) }

    /**
     * Replaces link [id]'s socket with a fresh one from its factory and registers it again, keeping the id (UI labels
     * and helper mappings stay). For when the network's address changed: a connected socket keeps its old source
     * address, so no amount of REG2 retries on it would help.
     */
    fun relink(id: Int) = post {
        if (replaceChannel(id, "address changed")) core.relink(id)
    }

    override fun close() {
        if (!running) return
        running = false
        selector.wakeup()
        thread.join(2_000)
        linkIo.values.forEach { runCatching { it.channel.close() } }
        runCatching { clientChannel.close() }
        runCatching { selector.close() }
    }

    private fun post(cmd: () -> Unit) {
        commands.add(cmd)
        selector.wakeup()
    }

    private fun open(id: Int, label: String, remote: InetSocketAddress, factory: () -> DatagramChannel): DatagramChannel {
        val ch = factory()
        try {
            ch.configureBlocking(false)
            if (ch.localAddress == null) ch.bind(null)
            // Room for a keyframe burst. Kept moderate: anything queued here is delay the link's in-flight cap counts
            // but can't take back. A factory-made channel that refuses the option still works with the default.
            if (linkSendBufferBytes > 0) runCatching { ch.setOption(StandardSocketOptions.SO_SNDBUF, linkSendBufferBytes) }
            ch.connect(remote)
            ch.register(selector, SelectionKey.OP_READ, id)
        } catch (e: IOException) {
            runCatching { ch.close() }
            throw e
        }
        log("srtla: link $label send buffer ${sendBufferOf(ch) / 1024} KB (asked ${linkSendBufferBytes / 1024} KB)")
        return ch
    }

    private fun sendBufferOf(ch: DatagramChannel): Int = runCatching { ch.getOption(StandardSocketOptions.SO_SNDBUF) }.getOrDefault(0)

    /** Opens a new socket for link [id] before closing the old one, so a failed open leaves the link as it was. */
    private fun replaceChannel(id: Int, why: String): Boolean {
        val io = linkIo[id] ?: return false
        val fresh = try {
            open(id, io.label, io.remote, io.factory)
        } catch (e: Exception) {
            log("srtla: link ${io.label}: couldn't open a new socket ($why): $e")
            return false
        }
        runCatching { io.channel.close() }
        io.channel = fresh
        consecutiveSendErrors.remove(id)
        core.setSendBufferBytes(id, sendBufferOf(fresh))
        log("srtla: link ${io.label}: new socket ($why)")
        return true
    }

    private val consecutiveSendErrors = HashMap<Int, Int>()
    private val failedLinks = ArrayList<Pair<Int, String>>()

    private fun view(buf: ByteArray, off: Int, len: Int): ByteBuffer {
        if (buf !== rxArray) return ByteBuffer.wrap(buf, off, len) // the core's own control packets: rare
        rxView.clear()
        rxView.position(off)
        rxView.limit(off + len)
        return rxView
    }

    private val output = object : SrtlaOutput {
        override fun toLink(linkId: Int, buf: ByteArray, off: Int, len: Int): Boolean {
            val ch = linkIo[linkId]?.channel ?: return false
            return try {
                // 0 means the socket's send buffer is full: not a failure of the network, so it doesn't count
                // towards declaring the link dead. The core tries another link.
                val sent = ch.write(view(buf, off, len)) > 0
                if (sent && consecutiveSendErrors.isNotEmpty()) consecutiveSendErrors.remove(linkId)
                sent
            } catch (e: IOException) {
                sendErrors++
                val n = (consecutiveSendErrors[linkId] ?: 0) + 1
                consecutiveSendErrors[linkId] = n
                // The OS says this network is gone: fail fast instead of waiting for the proof-of-delivery timeout,
                // and open a new socket (a socket pinned to a stale source address never recovers by itself).
                // Deferred to the end of the loop iteration; we're inside a core callback here.
                if (n == config.sendErrorsToFail) failedLinks += linkId to "network error: ${e.message}"
                false
            }
        }

        override fun toClient(buf: ByteArray, off: Int, len: Int) {
            val addr = clientAddress ?: return
            try {
                if (clientChannel.send(view(buf, off, len), addr) == 0) clientSendDrops++
            } catch (_: IOException) {
                sendErrors++
            }
        }
    }

    private fun loop() {
        runCatching(onThreadStart)
        started.countDown()
        var lastTick = Long.MIN_VALUE
        while (running) {
            try {
                selector.select(tickIntervalMs)
            } catch (_: IOException) {
                break
            }
            while (true) commands.poll()?.invoke() ?: break

            val keys = selector.selectedKeys().iterator()
            while (keys.hasNext()) {
                val key = keys.next()
                keys.remove()
                if (!key.isValid || !key.isReadable) continue
                val ch = key.channel() as DatagramChannel
                val att = key.attachment()
                if (att == CLIENT) drainClient(ch) else drainLink(ch, att as Int)
            }

            val now = clock()
            if (failedLinks.isNotEmpty()) {
                failedLinks.forEach { (id, why) ->
                    core.onLinkFailed(id, now, why)
                    replaceChannel(id, "send errors")
                }
                failedLinks.clear()
            }
            if (lastTick == Long.MIN_VALUE || now - lastTick >= tickIntervalMs) {
                core.tick(now, output)
                stats = core.stats(now).copy(
                    sendErrors = sendErrors,
                    clientSendDrops = clientSendDrops,
                    clientReceiveBufferBytes = clientReceiveBufferGranted,
                )
                lastTick = now
            }
        }
    }

    /**
     * Everything the local caller has queued, until the socket is empty: a full localhost buffer drops the caller's
     * packets silently. Bounded only so a runaway sender can't stall housekeeping forever.
     */
    private fun drainClient(ch: DatagramChannel) {
        repeat(CLIENT_DRAIN_MAX) {
            rx.clear()
            val from = try {
                ch.receive(rx)
            } catch (_: IOException) {
                null
            } ?: return
            if (from != clientAddress) clientAddress = from
            core.onClientPacket(rxArray, rx.position(), clock(), output)
        }
    }

    /** A link's socket is connected, so read() (no sender address to allocate). Bounded so one link can't starve the others. */
    private fun drainLink(ch: DatagramChannel, id: Int) {
        repeat(LINK_DRAIN_MAX) {
            rx.clear()
            val n = try {
                ch.read(rx)
            } catch (_: IOException) {
                0
            }
            if (n <= 0) return
            core.onLinkPacket(id, rxArray, n, clock(), output)
        }
    }

    companion object {
        private const val CLIENT = "client"
        private const val MAX_DATAGRAM = 2048
        private const val CLIENT_DRAIN_MAX = 4096
        private const val LINK_DRAIN_MAX = 64
        /** About a second of a 6-8 Mbps stream: a keyframe burst fits, a weak link can't hide seconds of queue. */
        const val DEFAULT_LINK_SEND_BUFFER = 1 shl 20
        const val DEFAULT_CLIENT_RECEIVE_BUFFER = 4 shl 20
    }
}
