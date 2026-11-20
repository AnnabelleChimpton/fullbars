package app.irlsupreme.engine.ingest

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** What an RTMP camera sends, decoded from its FLV tags. Called on the session's thread. */
interface IngestListener {
    /** A camera started publishing ([session] increases with every new publisher; the newest wins). */
    fun onPublish(session: Int, name: String, remote: String) {}
    /** H.264 parameter sets, each with an Annex-B start code. */
    fun onVideoConfig(session: Int, sps: ByteArray, pps: ByteArray) {}
    /** One H.264 access unit in Annex-B form. Times in ms on the camera's clock. */
    fun onVideo(session: Int, annexB: ByteArray, dtsMs: Long, ptsMs: Long, keyframe: Boolean) {}
    /** AAC AudioSpecificConfig. */
    fun onAudioConfig(session: Int, asc: ByteArray) {}
    /** One raw AAC frame. */
    fun onAudio(session: Int, aac: ByteArray, ptsMs: Long) {}
    fun onEnd(session: Int, reason: String) {}
}

/**
 * A minimal publish-only RTMP server for cameras on the local network (the Pocket 3 streams to
 * `rtmp://<phone>:1935/live/<name>`; any RTMP encoder works the same way). One publisher at a time: a new one
 * replaces the old, as a camera that reconnected after a Wi-Fi blip should. H.264 + AAC (the Pocket 3 sends
 * "classic" FLV; Enhanced RTMP isn't needed for it).
 *
 * Plain RTMP has no authentication; the server listens on the phone's local networks (its hotspot, Wi-Fi), and the
 * stream [path] works as a shared secret against other devices on a public Wi-Fi.
 */
class RtmpIngestServer(
    private val port: Int = 1935,
    private val listener: IngestListener,
    /** Accept only publishes to this app/stream path (e.g. "live/abc123"); null = any. */
    private val path: String? = null,
) {
    private var server: ServerSocket? = null
    private val sessions = AtomicInteger(0)
    private val current = AtomicReference<Socket?>(null)
    @Volatile private var running = false

    val localPort: Int get() = server?.localPort ?: -1

    fun start(): RtmpIngestServer {
        val ss = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        server = ss
        running = true
        Thread({
            while (running) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                Thread({ serve(s) }, "ingest-rtmp-session").apply { isDaemon = true; start() }
            }
        }, "ingest-rtmp-accept").apply { isDaemon = true; start() }
        return this
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        runCatching { current.getAndSet(null)?.close() }
    }

    private fun serve(socket: Socket) {
        var session = 0
        var reason = "disconnected"
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = READ_TIMEOUT_MS
            val counting = CountingInput(BufferedInputStream(socket.getInputStream(), 64 * 1024))
            val input = DataInputStream(counting)
            val out = BufferedOutputStream(socket.getOutputStream(), 16 * 1024)
            handshake(input, out)
            val reader = ChunkReader(input)
            val writer = ChunkWriter(out)
            var appName = ""
            var acked = 0L
            var window = DEFAULT_WINDOW
            var avcLengthSize = 4
            while (true) {
                val m = reader.readMessage()
                if (counting.count - acked >= window) { // keep peers that wait for acknowledgements going
                    acked = counting.count
                    writer.write(2, RtmpType.ACK, 0, 0, u32(acked and 0xFFFFFFFFL))
                }
                when (m.typeId) {
                    RtmpType.SET_CHUNK_SIZE -> reader.chunkSize = (readU32(m.payload, 0) and 0x7FFFFFFF).toInt().coerceIn(1, 1 shl 24)
                    RtmpType.WINDOW_ACK_SIZE -> window = readU32(m.payload, 0).coerceAtLeast(1)
                    RtmpType.COMMAND_AMF0, RtmpType.COMMAND_AMF3 -> {
                        val body = if (m.typeId == RtmpType.COMMAND_AMF3) m.payload.copyOfRange(1, m.payload.size) else m.payload
                        val cmd = runCatching { Amf0.decodeAll(body) }.getOrNull() ?: continue
                        val name = cmd.getOrNull(0) as? String ?: continue
                        val tx = cmd.getOrNull(1) as? Double ?: 0.0
                        when (name) {
                            "connect" -> {
                                appName = ((cmd.getOrNull(2) as? Map<*, *>)?.get("app") as? String).orEmpty().trim('/')
                                writer.write(2, RtmpType.WINDOW_ACK_SIZE, 0, 0, u32(DEFAULT_WINDOW))
                                writer.write(2, RtmpType.SET_PEER_BANDWIDTH, 0, 0, u32(DEFAULT_WINDOW) + byteArrayOf(2))
                                writer.write(2, RtmpType.SET_CHUNK_SIZE, 0, 0, u32(OUT_CHUNK_SIZE.toLong()))
                                writer.chunkSize = OUT_CHUNK_SIZE
                                writer.write(3, RtmpType.COMMAND_AMF0, 0, 0, Amf0.encode(
                                    "_result", tx,
                                    mapOf("fmsVer" to "FMS/3,0,1,123", "capabilities" to 31.0),
                                    mapOf("level" to "status", "code" to "NetConnection.Connect.Success",
                                        "description" to "Connection succeeded.", "objectEncoding" to 0.0),
                                ))
                            }
                            "releaseStream", "FCPublish", "_checkbw", "getStreamLength" ->
                                writer.write(3, RtmpType.COMMAND_AMF0, 0, 0, Amf0.encode("_result", tx, null, Undefined))
                            "createStream" -> writer.write(3, RtmpType.COMMAND_AMF0, 0, 0, Amf0.encode("_result", tx, null, STREAM_ID.toDouble()))
                            "publish" -> {
                                val stream = (cmd.getOrNull(3) as? String).orEmpty().substringBefore('?')
                                val full = listOf(appName, stream).filter { it.isNotEmpty() }.joinToString("/")
                                if (path != null && full != path) {
                                    writer.write(5, RtmpType.COMMAND_AMF0, STREAM_ID, 0, Amf0.encode(
                                        "onStatus", 0.0, null,
                                        mapOf("level" to "error", "code" to "NetStream.Publish.BadName", "description" to "Unknown stream."),
                                    ))
                                    reason = "refused publish to an unknown path"
                                    return
                                }
                                // Newest publisher wins: a camera reconnecting after a Wi-Fi blip replaces its stale session.
                                current.getAndSet(socket)?.takeIf { it !== socket }?.let { runCatching { it.close() } }
                                session = sessions.incrementAndGet()
                                writer.write(2, RtmpType.USER_CONTROL, 0, 0, byteArrayOf(0, 0) + u32(STREAM_ID.toLong())) // StreamBegin
                                writer.write(5, RtmpType.COMMAND_AMF0, STREAM_ID, 0, Amf0.encode(
                                    "onStatus", 0.0, null,
                                    mapOf("level" to "status", "code" to "NetStream.Publish.Start", "description" to "Publishing $full."),
                                ))
                                listener.onPublish(session, full, socket.remoteSocketAddress.toString())
                            }
                            "FCUnpublish", "deleteStream", "closeStream" -> { reason = "camera stopped"; return }
                        }
                    }
                    RtmpType.VIDEO -> if (session != 0) {
                        avcLengthSize = video(session, m, avcLengthSize)
                    }
                    RtmpType.AUDIO -> if (session != 0) audio(session, m)
                }
            }
        } catch (e: IOException) {
            if (reason == "disconnected") reason = e.message ?: "disconnected"
        } catch (e: RuntimeException) {
            reason = "bad data: $e"
        } finally {
            current.compareAndSet(socket, null)
            runCatching { socket.close() }
            if (session != 0) listener.onEnd(session, reason)
        }
    }

    /** FLV video tag: frame type + codec, AVC packet type, composition time, then AVCC data. */
    private fun video(session: Int, m: RtmpMessage, lengthSize: Int): Int {
        val p = m.payload
        if (p.size < 5 || (p[0].toInt() and 0x0F) != 7) return lengthSize // AVC only
        val keyframe = ((p[0].toInt() and 0xFF) ushr 4) == 1
        val cts = ((p[2].toInt() shl 16) or ((p[3].toInt() and 0xFF) shl 8) or (p[4].toInt() and 0xFF)).let {
            if (it and 0x800000 != 0) it or -0x1000000 else it // signed 24-bit
        }
        when (p[1].toInt()) {
            0 -> { // AVCDecoderConfigurationRecord
                val cfg = parseAvcConfig(p, 5) ?: return lengthSize
                listener.onVideoConfig(session, cfg.sps, cfg.pps)
                return cfg.lengthSize
            }
            1 -> {
                val annexB = avccToAnnexB(p, 5, lengthSize) ?: return lengthSize
                listener.onVideo(session, annexB, m.timestamp, m.timestamp + cts, keyframe)
            }
        }
        return lengthSize
    }

    /** FLV audio tag: format (10 = AAC) + rate/size/channels, AAC packet type, then data. */
    private fun audio(session: Int, m: RtmpMessage) {
        val p = m.payload
        if (p.size < 2 || ((p[0].toInt() and 0xFF) ushr 4) != 10) return
        val data = p.copyOfRange(2, p.size)
        if (p[1].toInt() == 0) listener.onAudioConfig(session, data) else listener.onAudio(session, data, m.timestamp)
    }

    private fun handshake(input: DataInputStream, out: java.io.OutputStream) {
        val c0 = input.readUnsignedByte()
        if (c0 != 3) throw IOException("not RTMP (version $c0)")
        val c1 = ByteArray(HANDSHAKE)
        input.readFullyOrEof(c1)
        val s1 = ByteArray(HANDSHAKE).also { random.nextBytes(it) }
        System.arraycopy(u32(System.currentTimeMillis() and 0xFFFFFFFFL), 0, s1, 0, 4)
        s1[4] = 0; s1[5] = 0; s1[6] = 0; s1[7] = 0
        out.write(3)
        out.write(s1)
        out.write(c1) // S2 echoes C1
        out.flush()
        input.readFullyOrEof(ByteArray(HANDSHAKE)) // C2
    }

    class AvcConfig(val sps: ByteArray, val pps: ByteArray, val lengthSize: Int)

    companion object {
        private const val HANDSHAKE = 1536
        private const val STREAM_ID = 1
        private const val DEFAULT_WINDOW = 2_500_000L
        private const val OUT_CHUNK_SIZE = 4096
        private const val READ_TIMEOUT_MS = 10_000
        private val random = SecureRandom()
        private val START = byteArrayOf(0, 0, 0, 1)

        /** Parses an AVCDecoderConfigurationRecord at [off]: first SPS and PPS (with start codes) and NAL length size. */
        fun parseAvcConfig(b: ByteArray, off: Int): AvcConfig? = runCatching {
            var i = off + 4
            val lengthSize = (b[i].toInt() and 3) + 1
            i++
            val numSps = b[i].toInt() and 0x1F
            i++
            var sps: ByteArray? = null
            repeat(numSps) {
                val len = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
                if (sps == null) sps = START + b.copyOfRange(i + 2, i + 2 + len)
                i += 2 + len
            }
            val numPps = b[i].toInt() and 0xFF
            i++
            var pps: ByteArray? = null
            repeat(numPps) {
                val len = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
                if (pps == null) pps = START + b.copyOfRange(i + 2, i + 2 + len)
                i += 2 + len
            }
            AvcConfig(sps ?: return null, pps ?: return null, lengthSize)
        }.getOrNull()

        /** Length-prefixed NAL units (AVCC) at [off] to Annex-B. */
        fun avccToAnnexB(b: ByteArray, off: Int, lengthSize: Int): ByteArray? {
            val out = ByteArrayOutputStream(b.size + 16)
            var i = off
            while (i + lengthSize <= b.size) {
                var len = 0
                for (k in 0 until lengthSize) len = (len shl 8) or (b[i + k].toInt() and 0xFF)
                i += lengthSize
                if (len <= 0 || i + len > b.size) return null
                out.write(START)
                out.write(b, i, len)
                i += len
            }
            return out.toByteArray().takeIf { it.isNotEmpty() }
        }
    }
}
