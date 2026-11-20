package app.irlsupreme.dji

// DJI's BLE "DUML" protocol, as needed to start a Pocket 3 livestream.
//
// Adapted from Osmosis (github.com/konradit/osmosis, MIT, Copyright (c) 2026 Konrad Iturbe), which vendors the framing
// from dji-remote (github.com/dimadesu/dji-remote, MIT, Copyright (c) 2026 Dmytro Antonov) and ports the livestream
// frames from djictl (github.com/xaionaro-go/djictl, CC0-1.0). See THIRD_PARTY_NOTICES.md. Unofficial integration:
// DJI publishes no SDK for this.

import java.io.ByteArrayOutputStream

/** DUML checksums (reflected CRC-8 and CRC-16, DJI's initial values). */
object DjiCrc {
    fun crc8(data: ByteArray, len: Int = data.size): Int {
        var crc = 0x77
        for (i in 0 until len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8C else crc ushr 1 }
        }
        return crc and 0xFF
    }

    fun crc16(data: ByteArray, len: Int = data.size): Int {
        var crc = 0x3692
        for (i in 0 until len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8408 else crc ushr 1 }
        }
        return crc and 0xFFFF
    }
}

/**
 * One BLE DUML frame: `55 len 04 crc8 | target(2 LE) id(2 LE) type(3 LE) | payload | crc16(2 LE)`.
 * `type` = flags | cmdSet << 8 | cmdId << 16; flags 0x40 = request, 0xC0 = response, 0x00 = push.
 */
class DjiMessage(val target: Int, val id: Int, val type: Int, val payload: ByteArray) {
    val flags get() = type and 0xFF
    val cmdSet get() = (type shr 8) and 0xFF
    val cmdId get() = (type shr 16) and 0xFF
    val isRequest get() = flags == 0x40
    val isResponse get() = flags and 0x80 != 0

    fun encode(): ByteArray {
        val len = 13 + payload.size
        require(len <= 0xFF) { "DUML frame too long ($len)" }
        val b = ByteArray(len)
        b[0] = 0x55; b[1] = len.toByte(); b[2] = 0x04
        b[3] = DjiCrc.crc8(b, 3).toByte()
        b[4] = target.toByte(); b[5] = (target shr 8).toByte()
        b[6] = id.toByte(); b[7] = (id shr 8).toByte()
        b[8] = type.toByte(); b[9] = (type shr 8).toByte(); b[10] = (type shr 16).toByte()
        payload.copyInto(b, 11)
        val crc = DjiCrc.crc16(b, len - 2)
        b[len - 2] = crc.toByte(); b[len - 1] = (crc shr 8).toByte()
        return b
    }

    /** The response to this request: swapped sender/receiver, same id, flags 0xC0. */
    fun reply(payload: ByteArray): DjiMessage =
        DjiMessage(((target and 0xFF) shl 8) or ((target shr 8) and 0xFF), id, (type and 0xFFFF00) or 0xC0, payload)

    override fun toString() = "DUML set=%02x cmd=%02x flags=%02x id=%04x payload=%s".format(
        cmdSet, cmdId, flags, id, payload.joinToString("") { "%02x".format(it) },
    )

    companion object {
        /** Parses exactly one frame; throws on a bad length, version or CRC. */
        fun decode(b: ByteArray): DjiMessage {
            require(b.size >= 13 && (b[0].toInt() and 0xFF) == 0x55) { "not a DUML frame" }
            val len = b[1].toInt() and 0xFF
            require(len == b.size && b[2].toInt() == 0x04) { "bad DUML length/version" }
            require((b[3].toInt() and 0xFF) == DjiCrc.crc8(b, 3)) { "bad DUML header CRC" }
            require(DjiCrc.crc16(b, len - 2) == ((b[len - 2].toInt() and 0xFF) or ((b[len - 1].toInt() and 0xFF) shl 8))) { "bad DUML CRC" }
            fun u(i: Int) = b[i].toInt() and 0xFF
            return DjiMessage(u(4) or (u(5) shl 8), u(6) or (u(7) shl 8), u(8) or (u(9) shl 8) or (u(10) shl 16), b.copyOfRange(11, len - 2))
        }
    }
}

/** Frames can be split across BLE notifications or share one: feed raw bytes, get whole frames. */
class DumlReassembler {
    private var carry = ByteArray(0)

    fun feed(raw: ByteArray): List<DjiMessage> {
        val buf = carry + raw
        val out = ArrayList<DjiMessage>()
        var off = 0
        while (true) {
            while (off < buf.size && (buf[off].toInt() and 0xFF) != 0x55) off++ // resync
            if (off + 2 > buf.size) break
            val len = buf[off + 1].toInt() and 0xFF
            if (len < 13) { off++; continue }
            if (off + len > buf.size) break
            val frame = runCatching { DjiMessage.decode(buf.copyOfRange(off, off + len)) }.getOrNull()
            if (frame == null) { off++; continue }
            out += frame
            off += len
        }
        carry = if (off >= buf.size) ByteArray(0) else buf.copyOfRange(off, buf.size)
        if (carry.size > 512) carry = ByteArray(0)
        return out
    }
}

/** DJI string field: length byte + UTF-8. */
fun djiString(s: String): ByteArray {
    val b = s.toByteArray(Charsets.UTF_8)
    require(b.size <= 0xFF)
    return byteArrayOf(b.size.toByte()) + b
}

/**
 * The frames for "pair, join this Wi-Fi, stream to this RTMP URL". Byte-identical to djictl's verified Pocket 3
 * flow (via Osmosis), except where noted.
 */
object DjiCommands {
    private const val TO_WIFI = 0x0702 // App(0x02) -> WiFi(0x07)
    private const val TO_CAMERA = 0x0102 // App -> Camera
    private const val TO_DM368 = 0x0802 // App -> DM368 (video transmission)
    private const val TO_SESSION = 0xF002 // App -> type 0x10 / id 7

    // Message ids are big-endian in djictl and LE here: swap so the wire bytes match.
    private fun wireId(id: Int) = ((id and 0xFF) shl 8) or ((id shr 8) and 0xFF)
    private fun type(cmdSet: Int, cmdId: Int) = 0x40 or (cmdSet shl 8) or (cmdId shl 16)

    /** 0x00/0x2b: Mimo's session wake (`04 00`) and keepalive (`01 01`, ~1 s). */
    fun sessionPing(keepalive: Boolean) =
        DjiMessage(TO_SESSION, 0x802B, type(0x00, 0x2B), if (keepalive) byteArrayOf(1, 1) else byteArrayOf(4, 0)).encode()

    /**
     * 0x07/0x45 SetPairingPIN. [identity] is what the camera remembers an approval by (one per install, persisted).
     * Reply `[00][01]` = already paired, `[00][02]` = waiting for approval on the camera, which then arrives as a
     * 0x07/0x46 request.
     */
    fun pair(identity: String, pin: String) =
        DjiMessage(TO_WIFI, 0x8092, type(0x07, 0x45), djiString(identity) + djiString(pin)).encode()

    /** 0x02/0xE1 PrepareToLiveStream stage 1; reply payload[0] == 0 means OK. */
    fun prepareStage1() = DjiMessage(TO_DM368, wireId(0xFEAB), type(0x02, 0xE1), byteArrayOf(0x1A)).encode()

    /** 0x02/0x8E PrepareToLiveStream stage 2. */
    fun prepareStage2() = DjiMessage(TO_DM368, wireId(0xFFAB), type(0x02, 0x8E), byteArrayOf(0x00, 0x01, 0x1C, 0x00)).encode()

    /** 0x07/0x47 ConnectToWiFi; reply payload[0] == 0 means joined. */
    fun connectWifi(ssid: String, password: String) =
        DjiMessage(TO_WIFI, 0x8C19, type(0x07, 0x47), djiString(ssid) + djiString(password)).encode()

    /** Resolution bytes; 1080p is the Pocket 3's livestream maximum. */
    const val RES_480P = 0x47
    const val RES_720P = 0x04
    const val RES_1080P = 0x0A
    const val FPS_30 = 0x03
    const val MAX_URL_BYTES = 228

    /** 0x08/0x78 ConfigureStreaming: resolution, bitrate (kbps), fps and the RTMP URL. Device byte 0x2A (Pocket 3). */
    fun configureStream(resolution: Int, bitrateKbps: Int, fps: Int, url: String): ByteArray {
        val u = url.toByteArray(Charsets.UTF_8)
        require(u.size <= MAX_URL_BYTES) { "RTMP URL too long" }
        val p = ByteArrayOutputStream()
        p.write(byteArrayOf(0x00, 0x2A, 0x00, resolution.toByte(), bitrateKbps.toByte(), (bitrateKbps shr 8).toByte(), 0x02, 0x00, fps.toByte(), 0, 0, 0))
        p.write(byteArrayOf(u.size.toByte(), (u.size shr 8).toByte()))
        p.write(u)
        return DjiMessage(TO_DM368, wireId(0xB3BB), type(0x08, 0x78), p.toByteArray()).encode()
    }

    fun startStream() = DjiMessage(TO_DM368, wireId(0xB4BB), type(0x02, 0x8E), byteArrayOf(0x01, 0x01, 0x1A, 0x00, 0x01, 0x01)).encode()
    fun stopStream() = DjiMessage(TO_DM368, wireId(0xB5BB), type(0x02, 0x8E), byteArrayOf(0x01, 0x01, 0x1A, 0x00, 0x01, 0x02)).encode()

    /** Our answer to the camera's 0x00/0x81 device-info request ("APP"). */
    val APP_DEVICE_INFO: ByteArray =
        byteArrayOf(0x00, 0x41, 0x50, 0x50) + ByteArray(37) + byteArrayOf(0x02) + ByteArray(8) + byteArrayOf(0x02, 0x08) + ByteArray(10)
}

/** DJI's BLE advertisement: company ids and the model it carries (classic id, or the newer product type). */
object DjiAdvert {
    val COMPANY_IDS = setOf(0x08AA, 0xF7AA, 0xE5C0)
    const val POCKET_3 = 0x0020

    /** Model id from a DJI manufacturer payload (company id already stripped), or null. */
    fun modelId(p: ByteArray): Int? {
        if (p.size >= 12 && (p[5].toInt() and 0x04) != 0) {
            val productType = (p[10].toInt() and 0xFF) or ((p[11].toInt() and 0xFF) shl 8)
            PRODUCT_TYPES[productType]?.let { return it }
        }
        if (p.size < 2) return null
        val id = (p[0].toInt() and 0xFF) or ((p[1].toInt() and 0xFF) shl 8)
        return id.takeIf { it != 0 }
    }

    fun name(model: Int?): String = when (model) {
        0x0020 -> "Osmo Pocket 3"
        0x0021 -> "Osmo Pocket 4"
        0x0022 -> "Osmo Pocket 4 Pro"
        0x0014 -> "Osmo Action 4"
        0x0015 -> "Osmo Action 5 Pro"
        0x0018 -> "Osmo Action 6"
        else -> "DJI camera"
    }

    private val PRODUCT_TYPES = mapOf(145 to 0x0020, 219 to 0x0021, 218 to 0x0022, 203 to 0x0014, 235 to 0x0015, 223 to 0x0018)
}
