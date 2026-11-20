// SPDX-License-Identifier: Apache-2.0
package srtlakt

import srtlakt.protocol.Packets
import srtlakt.protocol.Srt
import srtlakt.protocol.Srtla
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.Random

/**
 * Minimal in-process SRTLA receiver for tests (our own clean implementation, not derived from srtla_rec).
 * Groups links by the 256-byte id, echoes keepalives, sends SRTLA ACKs every [ackEvery] data packets per link,
 * forwards data to [srtServer] and sends the server's SRT ACKs back on every link.
 */
class FakeSrtlaReceiver(private val srtServer: InetSocketAddress, private val ackEvery: Int = 10) : AutoCloseable {
    private val socket = DatagramSocket(0, java.net.InetAddress.getLoopbackAddress()).apply { soTimeout = 100; receiveBufferSize = 4 shl 20 }
    private val upstream = DatagramSocket(0, java.net.InetAddress.getLoopbackAddress()).apply { soTimeout = 100; receiveBufferSize = 4 shl 20 }
    val address = InetSocketAddress("127.0.0.1", socket.localPort)

    private var groupId: ByteArray? = null
    private val conns = Collections.synchronizedSet(LinkedHashSet<SocketAddress>())
    private val pendingAcks = HashMap<SocketAddress, MutableList<Int>>()
    @Volatile private var lastActive: SocketAddress? = null
    /** Packets from these sender ports are silently dropped (simulates a dead uplink). */
    val blackholed: MutableSet<Int> = Collections.synchronizedSet(HashSet())
    val dataPerConn: MutableMap<Int, Int> = Collections.synchronizedMap(HashMap())
    @Volatile private var running = true

    private val rxThread = Thread {
        val buf = ByteArray(2048)
        while (running) {
            val p = DatagramPacket(buf, buf.size)
            try { socket.receive(p) } catch (_: SocketTimeoutException) { continue } catch (_: Exception) { break }
            val from = p.socketAddress as InetSocketAddress
            if (from.port in blackholed) continue
            handle(buf.copyOf(p.length), from)
        }
    }.apply { isDaemon = true; start() }

    private val upThread = Thread {
        val buf = ByteArray(2048)
        while (running) {
            val p = DatagramPacket(buf, buf.size)
            try { upstream.receive(p) } catch (_: SocketTimeoutException) { continue } catch (_: Exception) { break }
            val pkt = buf.copyOf(p.length)
            val targets = if (Packets.type(pkt, pkt.size) == Srt.TYPE_ACK) conns.toList() else listOfNotNull(lastActive)
            targets.forEach { send(pkt, it) }
        }
    }.apply { isDaemon = true; start() }

    private fun handle(pkt: ByteArray, from: InetSocketAddress) {
        when (Packets.type(pkt, pkt.size)) {
            Srtla.TYPE_REG1 -> if (pkt.size == Srtla.REG1_LEN) {
                val id = pkt.copyOfRange(2, 258)
                val serverHalf = ByteArray(128).also { Random().nextBytes(it) }
                serverHalf.copyInto(id, 128)
                groupId = id
                conns.clear()
                send(Packets.reg2(id), from)
            }
            Srtla.TYPE_REG2 -> if (pkt.size == Srtla.REG2_LEN) {
                if (groupId?.contentEquals(pkt.copyOfRange(2, 258)) == true) {
                    conns += from
                    send(Packets.reg3(), from)
                } else {
                    send(Packets.regNgp(), from)
                }
            }
            Srtla.TYPE_KEEPALIVE -> if (from in conns) send(pkt, from)
            else -> {
                if (from !in conns) return
                lastActive = from
                Srt.dataSeq(pkt, pkt.size)?.let { seq ->
                    dataPerConn.merge(from.port, 1, Int::plus)
                    val list = pendingAcks.getOrPut(from) { mutableListOf() }
                    list += seq
                    if (list.size >= ackEvery) {
                        send(Packets.srtlaAck(list.toIntArray()), from)
                        list.clear()
                    }
                }
                upstream.send(DatagramPacket(pkt, pkt.size, srtServer))
            }
        }
    }

    private fun send(pkt: ByteArray, to: SocketAddress) {
        if ((to as InetSocketAddress).port in blackholed) return // dead link: nothing gets through either way
        socket.send(DatagramPacket(pkt, pkt.size, to))
    }

    override fun close() {
        running = false
        rxThread.join(500); upThread.join(500)
        socket.close(); upstream.close()
    }
}
