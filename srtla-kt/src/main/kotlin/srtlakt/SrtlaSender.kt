// SPDX-License-Identifier: Apache-2.0
package srtlakt

import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketAddress
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
 */
class SrtlaSender(
    listenAddress: InetSocketAddress = InetSocketAddress("127.0.0.1", 0),
    private val config: SrtlaConfig = SrtlaConfig(),
    private val tickIntervalMs: Long = 50,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onEvent: (SrtlaEvent) -> Unit = {},
) : AutoCloseable {

    private val selector = Selector.open()
    private val clientChannel = DatagramChannel.open().apply {
        configureBlocking(false)
        bind(listenAddress)
    }
    private val core = SrtlaCore(config, onEvent = { e -> onEvent(e) })
    private val linkChannels = HashMap<Int, DatagramChannel>()
    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    private val nextLinkId = AtomicInteger(0)
    private val started = CountDownLatch(1)
    @Volatile private var running = false
    @Volatile private var clientAddress: SocketAddress? = null
    @Volatile var stats: SenderStats = SenderStats.EMPTY
        private set
    @Volatile var sendErrors: Long = 0
        private set

    private val thread = Thread(::loop, "srtla-sender").apply { isDaemon = true }
    private val rx = ByteBuffer.allocate(MAX_DATAGRAM)

    val localPort: Int get() = (clientChannel.localAddress as InetSocketAddress).port

    fun start(): SrtlaSender {
        running = true
        clientChannel.register(selector, SelectionKey.OP_READ, CLIENT)
        thread.start()
        started.await()
        return this
    }

    /** Adds an uplink and returns its id. The channel is created (and bound) by [channelFactory]. */
    fun addLink(
        label: String,
        remote: InetSocketAddress,
        channelFactory: () -> DatagramChannel = { DatagramChannel.open() },
    ): Int {
        val id = nextLinkId.getAndIncrement()
        post {
            try {
                val ch = channelFactory()
                ch.configureBlocking(false)
                if (ch.localAddress == null) ch.bind(null)
                ch.connect(remote)
                ch.register(selector, SelectionKey.OP_READ, id)
                linkChannels[id] = ch
                core.addLink(id, label)
            } catch (e: IOException) {
                onEvent(SrtlaEvent.LinkDown(id, label, "could not open: ${e.message}"))
            }
        }
        return id
    }

    fun removeLink(id: Int) = post {
        core.removeLink(id)
        linkChannels.remove(id)?.let { runCatching { it.close() } }
    }

    override fun close() {
        if (!running) return
        running = false
        selector.wakeup()
        thread.join(2_000)
        linkChannels.values.forEach { runCatching { it.close() } }
        runCatching { clientChannel.close() }
        runCatching { selector.close() }
    }

    private fun post(cmd: () -> Unit) {
        commands.add(cmd)
        selector.wakeup()
    }

    private val consecutiveSendErrors = HashMap<Int, Int>()
    private val failedLinks = ArrayList<Pair<Int, String>>()

    private val output = object : SrtlaOutput {
        override fun toLink(linkId: Int, buf: ByteArray, off: Int, len: Int) {
            val ch = linkChannels[linkId] ?: return
            try {
                ch.write(ByteBuffer.wrap(buf, off, len))
                consecutiveSendErrors.remove(linkId)
            } catch (e: IOException) {
                sendErrors++
                val n = (consecutiveSendErrors[linkId] ?: 0) + 1
                consecutiveSendErrors[linkId] = n
                // The OS says this network is gone: fail fast instead of waiting for the proof-of-delivery timeout.
                // Deferred to the end of the loop iteration; we're inside a core callback here.
                if (n == config.sendErrorsToFail) failedLinks += linkId to "network error: ${e.message}"
            }
        }

        override fun toClient(buf: ByteArray, off: Int, len: Int) {
            val addr = clientAddress ?: return
            try {
                clientChannel.send(ByteBuffer.wrap(buf, off, len), addr)
            } catch (_: IOException) {
                sendErrors++
            }
        }
    }

    private fun loop() {
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
                drain(ch, att)
            }

            val now = clock()
            if (failedLinks.isNotEmpty()) {
                failedLinks.forEach { (id, why) -> core.onLinkFailed(id, now, why) }
                failedLinks.clear()
            }
            if (lastTick == Long.MIN_VALUE || now - lastTick >= tickIntervalMs) {
                core.tick(now, output)
                stats = core.stats(now)
                lastTick = now
            }
        }
    }

    private fun drain(ch: DatagramChannel, att: Any) {
        // Bounded so one busy socket can't starve the others.
        repeat(64) {
            rx.clear()
            val from = try {
                ch.receive(rx)
            } catch (_: IOException) {
                null
            } ?: return
            val now = clock()
            val len = rx.position()
            if (att == CLIENT) {
                clientAddress = from
                core.onClientPacket(rx.array(), len, now, output)
            } else {
                core.onLinkPacket(att as Int, rx.array(), len, now, output)
            }
        }
    }

    private companion object {
        const val CLIENT = "client"
        const val MAX_DATAGRAM = 2048
    }
}
