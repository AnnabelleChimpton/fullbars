package app.irlsupreme.engine.ingest

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream

/** One reassembled RTMP message. [timestamp] is in milliseconds (32-bit, as sent). */
class RtmpMessage(val typeId: Int, val streamId: Int, val timestamp: Long, val payload: ByteArray)

object RtmpType {
    const val SET_CHUNK_SIZE = 1
    const val ABORT = 2
    const val ACK = 3
    const val USER_CONTROL = 4
    const val WINDOW_ACK_SIZE = 5
    const val SET_PEER_BANDWIDTH = 6
    const val AUDIO = 8
    const val VIDEO = 9
    const val DATA_AMF3 = 15
    const val COMMAND_AMF3 = 17
    const val DATA_AMF0 = 18
    const val COMMAND_AMF0 = 20
}

/**
 * Reads RTMP chunk streams and reassembles messages (all four chunk header formats, extended timestamps, any number
 * of interleaved chunk streams). The peer's chunk size changes with Set Chunk Size, which the caller applies by
 * setting [chunkSize].
 */
class ChunkReader(private val input: DataInputStream) {
    var chunkSize = 128

    private class Stream {
        var timestamp = 0L
        var delta = 0L
        var length = 0
        var typeId = 0
        var streamId = 0
        var extended = false
        var buf: ByteArray? = null
        var filled = 0
    }

    private val streams = HashMap<Int, Stream>()

    fun readMessage(): RtmpMessage {
        while (true) {
            val b0 = input.readUnsignedByte()
            val fmt = b0 ushr 6
            val csid = when (val low = b0 and 0x3f) {
                0 -> 64 + input.readUnsignedByte()
                1 -> 64 + input.readUnsignedByte() + input.readUnsignedByte() * 256
                else -> low
            }
            val s = streams.getOrPut(csid) { Stream() }
            val starting = s.buf == null
            when (fmt) {
                0 -> {
                    val ts = readU24()
                    s.length = readU24()
                    s.typeId = input.readUnsignedByte()
                    s.streamId = Integer.reverseBytes(input.readInt()) // little-endian
                    s.extended = ts == 0xFFFFFF
                    s.timestamp = if (s.extended) input.readInt().toLong() and 0xFFFFFFFFL else ts.toLong()
                    s.delta = 0
                }
                1, 2 -> {
                    val d = readU24()
                    if (fmt == 1) {
                        s.length = readU24()
                        s.typeId = input.readUnsignedByte()
                    }
                    s.extended = d == 0xFFFFFF
                    s.delta = if (s.extended) input.readInt().toLong() and 0xFFFFFFFFL else d.toLong()
                    if (starting) s.timestamp += s.delta
                }
                else -> { // 3: same header; a new message reuses the last delta
                    if (s.extended) input.readInt()
                    if (starting) s.timestamp += s.delta
                }
            }
            if (s.length < 0 || s.length > MAX_MESSAGE) throw IOException("RTMP message too large (${s.length})")
            val buf = s.buf ?: ByteArray(s.length).also { s.buf = it; s.filled = 0 }
            val n = minOf(chunkSize, s.length - s.filled)
            input.readFully(buf, s.filled, n)
            s.filled += n
            if (s.filled >= s.length) {
                s.buf = null
                return RtmpMessage(s.typeId, s.streamId, s.timestamp and 0xFFFFFFFFL, buf)
            }
        }
    }

    private fun readU24(): Int =
        (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()

    private companion object { const val MAX_MESSAGE = 16 shl 20 }
}

/** Writes RTMP messages as chunks: a type-0 header, then type-3 continuation headers. Chunk stream ids 2..63. */
class ChunkWriter(private val out: OutputStream) {
    var chunkSize = 128

    @Synchronized
    fun write(csid: Int, typeId: Int, streamId: Int, timestamp: Long, payload: ByteArray) {
        require(csid in 2..63)
        val extended = timestamp >= 0xFFFFFF
        val header = ByteArray(12 + if (extended) 4 else 0)
        header[0] = csid.toByte()
        val ts = if (extended) 0xFFFFFF else timestamp.toInt()
        header[1] = (ts ushr 16).toByte(); header[2] = (ts ushr 8).toByte(); header[3] = ts.toByte()
        header[4] = (payload.size ushr 16).toByte(); header[5] = (payload.size ushr 8).toByte(); header[6] = payload.size.toByte()
        header[7] = typeId.toByte()
        header[8] = streamId.toByte(); header[9] = (streamId ushr 8).toByte()
        header[10] = (streamId ushr 16).toByte(); header[11] = (streamId ushr 24).toByte()
        if (extended) for (i in 0 until 4) header[12 + i] = (timestamp ushr (24 - 8 * i)).toByte()
        out.write(header)
        var off = 0
        while (true) {
            val n = minOf(chunkSize, payload.size - off)
            out.write(payload, off, n)
            off += n
            if (off >= payload.size) break
            out.write(0xC0 or csid)
            if (extended) for (i in 0 until 4) out.write((timestamp ushr (24 - 8 * i)).toInt())
        }
        out.flush()
    }
}

internal fun u32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

internal fun readU32(b: ByteArray, off: Int): Long =
    ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
        ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

/** Counts bytes read, for RTMP acknowledgements. */
class CountingInput(private val input: java.io.InputStream) : java.io.InputStream() {
    @Volatile var count = 0L
        private set

    override fun read(): Int = input.read().also { if (it >= 0) count++ }
    override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len).also { if (it > 0) count += it }
    override fun available(): Int = input.available()
    override fun close() = input.close()
}

internal fun DataInputStream.readFullyOrEof(b: ByteArray) {
    try {
        readFully(b)
    } catch (e: EOFException) {
        throw EOFException("peer closed during handshake")
    }
}
