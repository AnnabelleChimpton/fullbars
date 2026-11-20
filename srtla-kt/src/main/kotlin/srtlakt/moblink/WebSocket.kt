// SPDX-License-Identifier: Apache-2.0
package srtlakt.moblink

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Just enough RFC 6455 for Moblink's small JSON messages over a local network: text frames, ping/pong, close.
 * Blocking: one reader thread per connection calls [receive] in a loop; [send] may be called from any thread.
 * No extensions (compression), no subprotocols, messages up to [MAX_MESSAGE] bytes.
 */
class WebSocket private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
    /** Clients mask what they send (RFC 6455 §5.3); servers don't. */
    private val maskOutgoing: Boolean,
) : AutoCloseable {
    @Volatile var closed = false
        private set

    /** When the last pong arrived (System.nanoTime), for keepalive timeouts. */
    @Volatile var lastPongNanos = System.nanoTime()
        private set

    val remoteAddress get() = socket.inetAddress
    val localAddress get() = socket.localAddress

    /** The next text message, or null once the connection has closed. Answers pings itself. */
    fun receive(): String? {
        val message = java.io.ByteArrayOutputStream()
        var messageOpcode = -1
        while (!closed) {
            val b0 = input.read()
            if (b0 < 0) break
            val b1 = readByte()
            val fin = b0 and 0x80 != 0
            val opcode = b0 and 0x0F
            val masked = b1 and 0x80 != 0
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = (readByte().toLong() shl 8) or readByte().toLong()
            else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or readByte().toLong() } }
            if (len > MAX_MESSAGE || message.size() + len > MAX_MESSAGE) throw IOException("WebSocket message too big")
            val mask = if (masked) ByteArray(4).also { readFully(it) } else null
            val payload = ByteArray(len.toInt()).also { readFully(it) }
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
            when (opcode) {
                OP_CLOSE -> { runCatching { writeFrame(OP_CLOSE, payload.copyOf(minOf(2, payload.size))) }; break }
                OP_PING -> writeFrame(OP_PONG, payload)
                OP_PONG -> lastPongNanos = System.nanoTime()
                OP_TEXT, OP_BINARY, OP_CONTINUATION -> {
                    if (opcode != OP_CONTINUATION) messageOpcode = opcode
                    message.write(payload)
                    if (fin) {
                        if (messageOpcode == OP_TEXT) return message.toString(Charsets.UTF_8)
                        message.reset() // binary: not part of Moblink, ignore
                    }
                }
                else -> throw IOException("bad WebSocket opcode $opcode")
            }
        }
        close()
        return null
    }

    fun setReadTimeout(ms: Int) { socket.soTimeout = ms }

    fun send(text: String) = writeFrame(OP_TEXT, text.toByteArray(Charsets.UTF_8))

    fun ping() = writeFrame(OP_PING, ByteArray(0))

    @Synchronized
    private fun writeFrame(opcode: Int, payload: ByteArray) {
        if (closed && opcode != OP_CLOSE) throw IOException("WebSocket closed")
        val header = java.io.ByteArrayOutputStream(14)
        header.write(0x80 or opcode)
        val maskBit = if (maskOutgoing) 0x80 else 0
        when {
            payload.size < 126 -> header.write(maskBit or payload.size)
            payload.size < 65536 -> { header.write(maskBit or 126); header.write(payload.size shr 8); header.write(payload.size and 0xFF) }
            else -> { header.write(maskBit or 127); for (s in 56 downTo 0 step 8) header.write(((payload.size.toLong() shr s) and 0xFF).toInt()) }
        }
        val body = if (maskOutgoing) {
            val mask = ByteArray(4).also { rng.nextBytes(it) }
            header.write(mask)
            ByteArray(payload.size) { i -> (payload[i].toInt() xor mask[i and 3].toInt()).toByte() }
        } else payload
        output.write(header.toByteArray())
        output.write(body)
        output.flush()
    }

    override fun close() {
        if (closed) return
        runCatching { writeFrame(OP_CLOSE, byteArrayOf(0x03, 0xE8.toByte())) } // 1000 normal closure
        closed = true
        runCatching { socket.close() }
    }

    private fun readByte(): Int = input.read().also { if (it < 0) throw EOFException() }
    private fun readFully(b: ByteArray) {
        var off = 0
        while (off < b.size) { val n = input.read(b, off, b.size - off); if (n < 0) throw EOFException(); off += n }
    }

    companion object {
        const val MAX_MESSAGE = 1 shl 20
        private const val OP_CONTINUATION = 0
        private const val OP_TEXT = 1
        private const val OP_BINARY = 2
        private const val OP_CLOSE = 8
        private const val OP_PING = 9
        private const val OP_PONG = 10
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private val rng = SecureRandom()

        internal fun acceptKey(key: String): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray()))

        /** Server side: completes the HTTP upgrade on an accepted [socket], or throws (and closes it). */
        fun accept(socket: Socket): WebSocket {
            try {
                val input = BufferedInputStream(socket.getInputStream())
                val headers = readHttpHead(input)
                val key = headers["sec-websocket-key"] ?: throw IOException("not a WebSocket upgrade")
                val out = socket.getOutputStream()
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: ${acceptKey(key)}\r\n\r\n").toByteArray())
                out.flush()
                return WebSocket(socket, input, out, maskOutgoing = false)
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw e
            }
        }

        /** Client side: performs the HTTP upgrade for ws://[host]:[port][path] on an already connected [socket]. */
        fun connect(socket: Socket, host: String, port: Int, path: String = "/"): WebSocket {
            try {
                val key = Base64.getEncoder().encodeToString(ByteArray(16).also { rng.nextBytes(it) })
                val out = socket.getOutputStream()
                out.write(("GET $path HTTP/1.1\r\nHost: $host:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray())
                out.flush()
                val input = BufferedInputStream(socket.getInputStream())
                val headers = readHttpHead(input)
                if (headers[":status"]?.contains(" 101") != true) throw IOException("upgrade refused: ${headers[":status"]}")
                if (headers["sec-websocket-accept"] != acceptKey(key)) throw IOException("bad Sec-WebSocket-Accept")
                return WebSocket(socket, input, out, maskOutgoing = true)
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw e
            }
        }

        /** The request/status line under ":status", then lower-cased header names. */
        private fun readHttpHead(input: InputStream): Map<String, String> {
            val lines = ArrayList<String>()
            val line = StringBuilder()
            var total = 0
            while (true) {
                val c = input.read()
                if (c < 0) throw EOFException()
                if (++total > 16 * 1024) throw IOException("HTTP head too long")
                if (c == '\n'.code) {
                    val l = line.toString().trimEnd('\r')
                    if (l.isEmpty()) break
                    lines += l
                    line.setLength(0)
                } else line.append(c.toChar())
            }
            if (lines.isEmpty()) throw IOException("empty HTTP head")
            val map = HashMap<String, String>()
            map[":status"] = lines[0]
            for (l in lines.drop(1)) {
                val i = l.indexOf(':')
                if (i > 0) map[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            return map
        }
    }
}
