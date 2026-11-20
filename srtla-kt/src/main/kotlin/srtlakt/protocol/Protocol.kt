// SPDX-License-Identifier: Apache-2.0
package srtlakt.protocol

/**
 * SRTLA wire constants.
 *
 * Values match the de-facto SRTLA protocol spoken by srtla_rec and every hosted SRTLA relay.
 * They were cross-checked against irlserver/srtla_send (MIT) — see NOTICE.
 */
object Srtla {
    const val TYPE_KEEPALIVE = 0x9000
    const val TYPE_ACK = 0x9100
    const val TYPE_REG1 = 0x9200
    const val TYPE_REG2 = 0x9201
    const val TYPE_REG3 = 0x9202
    const val TYPE_REG_ERR = 0x9210
    const val TYPE_REG_NGP = 0x9211
    const val TYPE_REG_NAK = 0x9212

    const val ID_LEN = 256
    const val REG1_LEN = 2 + ID_LEN
    const val REG2_LEN = 2 + ID_LEN
    const val REG3_LEN = 2

    const val KEEPALIVE_LEN = 10
    const val KEEPALIVE_EXT_LEN = 38
    const val KEEPALIVE_MAGIC = 0xC01F
    const val KEEPALIVE_EXT_VERSION = 0x0001

    // Per-link congestion window, in units of WINDOW_MULT.
    const val WINDOW_MIN = 1
    const val WINDOW_DEF = 20
    const val WINDOW_MAX = 60
    const val WINDOW_MULT = 1000
    const val WINDOW_DECR = 100
    const val WINDOW_INCR = 30
}

/** The subset of SRT we need to look inside of. */
object Srt {
    const val TYPE_HANDSHAKE = 0x8000
    const val TYPE_KEEPALIVE = 0x8001
    const val TYPE_ACK = 0x8002
    const val TYPE_NAK = 0x8003
    const val TYPE_SHUTDOWN = 0x8005
    const val TYPE_ACKACK = 0x8006

    const val CONTROL_HEADER_LEN = 16
    const val HANDSHAKE_CIF_LEN = 48
    const val HS_REQTYPE_INDUCTION = 1
    const val SEQ_MASK = 0x7fffffff
    private const val NAK_RANGE_FLAG = 0x80000000.toInt()
    /** Most sequence numbers taken from one NAK (a reused buffer for [nakSeqsInto] should be this big). */
    const val NAK_MAX_IDS = 1000
    /** "Not a data packet" from [dataSeqOrNone] / [ackNumberOrNone]. */
    const val NO_SEQ = -1

    fun isControl(buf: ByteArray, len: Int): Boolean = len >= 1 && (buf[0].toInt() and 0x80) != 0

    /** Sequence number of an SRT data packet, or null for control packets / short buffers. */
    fun dataSeq(buf: ByteArray, len: Int): Int? {
        if (len < 4) return null
        val v = Bytes.readInt(buf, 0)
        return if (v and 0x80000000.toInt() == 0) v else null
    }

    /**
     * Like [dataSeq] without boxing (this runs for every packet): the sequence number, or -1 for control packets and
     * short buffers. SRT sequence numbers are 31-bit, so -1 never collides with a real one.
     */
    fun dataSeqOrNone(buf: ByteArray, len: Int): Int {
        if (len < 4) return NO_SEQ
        val v = Bytes.readInt(buf, 0)
        return if (v and 0x80000000.toInt() == 0) v else NO_SEQ
    }

    /** Cumulative ACK number (next expected sequence) of an SRT ACK, or null. */
    fun ackNumber(buf: ByteArray, len: Int): Int? = ackNumberOrNone(buf, len).takeIf { it != NO_SEQ }

    /** [ackNumber] without boxing: -1 when [buf] isn't an SRT ACK. */
    fun ackNumberOrNone(buf: ByteArray, len: Int): Int {
        if (len < 20 || Packets.typeOrNone(buf, len) != TYPE_ACK) return NO_SEQ
        return Bytes.readInt(buf, 16) and SEQ_MASK
    }

    /** Lost sequence numbers listed in an SRT NAK (loss report), with ranges expanded (capped). */
    fun nakSeqs(buf: ByteArray, len: Int): IntArray {
        val out = IntArray(NAK_MAX_IDS)
        return out.copyOf(nakSeqsInto(buf, len, out))
    }

    /**
     * Writes the lost sequence numbers of an SRT NAK into [out] (ranges expanded, at most `out.size`) and returns how
     * many it wrote: 0 when [buf] isn't a NAK. Lets the caller keep one array for every NAK instead of allocating.
     */
    fun nakSeqsInto(buf: ByteArray, len: Int, out: IntArray): Int {
        if (len < CONTROL_HEADER_LEN + 4 || Packets.typeOrNone(buf, len) != TYPE_NAK) return 0
        val max = out.size
        var n = 0
        var i = CONTROL_HEADER_LEN
        while (i + 3 < len && n < max) {
            val id = Bytes.readInt(buf, i)
            i += 4
            if (id and NAK_RANGE_FLAG == 0) {
                out[n++] = id
                continue
            }
            if (i + 3 >= len) break
            val start = id and SEQ_MASK
            val end = Bytes.readInt(buf, i)
            i += 4
            if (end and NAK_RANGE_FLAG != 0 || SeqMath.diff(end, start) < 0) continue
            var s = start
            while (n < max) {
                out[n++] = s
                if (s == end) break
                s = SeqMath.next(s)
            }
        }
        return n
    }

    /** True for the first handshake packet of a new SRT connection from the caller. */
    fun isHandshakeInduction(buf: ByteArray, len: Int): Boolean {
        if (len < CONTROL_HEADER_LEN + HANDSHAKE_CIF_LEN) return false
        if (Packets.typeOrNone(buf, len) != TYPE_HANDSHAKE) return false
        return Bytes.readInt(buf, CONTROL_HEADER_LEN + 20) == HS_REQTYPE_INDUCTION
    }
}

/** Per-connection telemetry carried in an extended keepalive. */
data class KeepaliveInfo(
    val connId: Int,
    val window: Int,
    val inFlight: Int,
    val rttMs: Int,
    val nakCount: Int,
    val bitrateBytesPerSec: Int,
)

object Packets {
    /** 16-bit packet type (SRT control type or SRTLA type), or null if too short. */
    fun type(buf: ByteArray, len: Int): Int? = if (len < 2) null else Bytes.readU16(buf, 0)

    /** [type] without boxing, for the per-packet paths: [NO_TYPE] when too short. */
    fun typeOrNone(buf: ByteArray, len: Int): Int = if (len < 2) NO_TYPE else Bytes.readU16(buf, 0)

    const val NO_TYPE = -1

    fun reg1(id: ByteArray): ByteArray = typed(Srtla.TYPE_REG1, id)
    fun reg2(id: ByteArray): ByteArray = typed(Srtla.TYPE_REG2, id)
    fun reg3(): ByteArray = typeOnly(Srtla.TYPE_REG3)
    fun regErr(): ByteArray = typeOnly(Srtla.TYPE_REG_ERR)
    fun regNgp(): ByteArray = typeOnly(Srtla.TYPE_REG_NGP)
    fun regNak(): ByteArray = typeOnly(Srtla.TYPE_REG_NAK)

    fun keepalive(nowMs: Long): ByteArray = ByteArray(Srtla.KEEPALIVE_LEN).also {
        Bytes.writeU16(it, 0, Srtla.TYPE_KEEPALIVE)
        Bytes.writeLong(it, 2, nowMs)
    }

    /** Backwards-compatible extended keepalive: old receivers read bytes 0–9 and ignore the rest. */
    fun keepaliveExt(info: KeepaliveInfo, nowMs: Long): ByteArray = ByteArray(Srtla.KEEPALIVE_EXT_LEN).also {
        Bytes.writeU16(it, 0, Srtla.TYPE_KEEPALIVE)
        Bytes.writeLong(it, 2, nowMs)
        Bytes.writeU16(it, 10, Srtla.KEEPALIVE_MAGIC)
        Bytes.writeU16(it, 12, Srtla.KEEPALIVE_EXT_VERSION)
        Bytes.writeInt(it, 14, info.connId)
        Bytes.writeInt(it, 18, info.window)
        Bytes.writeInt(it, 22, info.inFlight)
        Bytes.writeInt(it, 26, info.rttMs)
        Bytes.writeInt(it, 30, info.nakCount)
        Bytes.writeInt(it, 34, info.bitrateBytesPerSec)
    }

    fun keepaliveTimestamp(buf: ByteArray, len: Int): Long? {
        if (len < Srtla.KEEPALIVE_LEN || type(buf, len) != Srtla.TYPE_KEEPALIVE) return null
        return Bytes.readLong(buf, 2)
    }

    fun keepaliveInfo(buf: ByteArray, len: Int): KeepaliveInfo? {
        if (len < Srtla.KEEPALIVE_EXT_LEN || type(buf, len) != Srtla.TYPE_KEEPALIVE) return null
        if (Bytes.readU16(buf, 10) != Srtla.KEEPALIVE_MAGIC) return null
        if (Bytes.readU16(buf, 12) != Srtla.KEEPALIVE_EXT_VERSION) return null
        return KeepaliveInfo(
            connId = Bytes.readInt(buf, 14),
            window = Bytes.readInt(buf, 18),
            inFlight = Bytes.readInt(buf, 22),
            rttMs = Bytes.readInt(buf, 26),
            nakCount = Bytes.readInt(buf, 30),
            bitrateBytesPerSec = Bytes.readInt(buf, 34),
        )
    }

    /** SRTLA ACK: 2-byte type, 2 bytes padding, then big-endian u32 sequence numbers. */
    fun srtlaAck(seqs: IntArray): ByteArray = ByteArray(4 + 4 * seqs.size).also {
        Bytes.writeU16(it, 0, Srtla.TYPE_ACK)
        seqs.forEachIndexed { i, s -> Bytes.writeInt(it, 4 + 4 * i, s) }
    }

    fun srtlaAckSeqs(buf: ByteArray, len: Int): IntArray = IntArray(srtlaAckCount(buf, len)) { srtlaAckSeq(buf, it) }

    /** How many sequence numbers an SRTLA ACK carries (0 if it isn't one); read each with [srtlaAckSeq], no copy. */
    fun srtlaAckCount(buf: ByteArray, len: Int): Int =
        if (len < 8 || typeOrNone(buf, len) != Srtla.TYPE_ACK) 0 else (len - 4) / 4

    fun srtlaAckSeq(buf: ByteArray, i: Int): Int = Bytes.readInt(buf, 4 + 4 * i)

    private fun typed(type: Int, id: ByteArray): ByteArray {
        require(id.size == Srtla.ID_LEN) { "SRTLA id must be ${Srtla.ID_LEN} bytes" }
        return ByteArray(2 + Srtla.ID_LEN).also {
            Bytes.writeU16(it, 0, type)
            id.copyInto(it, 2)
        }
    }

    private fun typeOnly(type: Int) = ByteArray(2).also { Bytes.writeU16(it, 0, type) }
}

/** 31-bit SRT sequence arithmetic with wraparound. */
object SeqMath {
    const val MAX = 0x7fffffff

    fun next(s: Int): Int = (s + 1) and MAX

    /** Signed distance a - b in sequence space. */
    fun diff(a: Int, b: Int): Int {
        val d = (a - b) and MAX
        return if (d > 0x3fffffff) d - MAX - 1 else d
    }

    fun after(a: Int, b: Int): Boolean = diff(a, b) > 0
}

object Bytes {
    fun readU16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 8) or (b[off + 1].toInt() and 0xff)

    fun readInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 24) or ((b[off + 1].toInt() and 0xff) shl 16) or
            ((b[off + 2].toInt() and 0xff) shl 8) or (b[off + 3].toInt() and 0xff)

    fun readLong(b: ByteArray, off: Int): Long =
        (readInt(b, off).toLong() shl 32) or (readInt(b, off + 4).toLong() and 0xffffffffL)

    fun writeU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 8).toByte(); b[off + 1] = v.toByte()
    }

    fun writeInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte(); b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte(); b[off + 3] = v.toByte()
    }

    fun writeLong(b: ByteArray, off: Int, v: Long) {
        writeInt(b, off, (v ushr 32).toInt()); writeInt(b, off + 4, v.toInt())
    }
}
