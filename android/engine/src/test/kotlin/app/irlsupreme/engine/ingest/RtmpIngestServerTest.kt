package app.irlsupreme.engine.ingest

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RtmpIngestServerTest {
    private val events = CopyOnWriteArrayList<String>()
    private val video = CopyOnWriteArrayList<Triple<ByteArray, Long, Boolean>>()
    private var sps: ByteArray? = null
    private var asc: ByteArray? = null
    private val ended = CountDownLatch(1)
    private val listener = object : IngestListener {
        override fun onPublish(session: Int, name: String, remote: String) { events += "publish $session $name" }
        override fun onVideoConfig(session: Int, sps: ByteArray, pps: ByteArray) { this@RtmpIngestServerTest.sps = sps }
        override fun onVideo(session: Int, annexB: ByteArray, dtsMs: Long, ptsMs: Long, keyframe: Boolean) {
            video += Triple(annexB, ptsMs, keyframe)
        }
        override fun onAudioConfig(session: Int, asc: ByteArray) { this@RtmpIngestServerTest.asc = asc }
        override fun onAudio(session: Int, aac: ByteArray, ptsMs: Long) { events += "audio $ptsMs ${aac.size}" }
        override fun onUnsupportedVideo(session: Int, fourcc: String) { events += "unsupported $session $fourcc" }
        override fun onEnd(session: Int, reason: String) { events += "end $session $reason"; ended.countDown() }
    }
    private var server: RtmpIngestServer? = null

    @After fun tearDown() { server?.stop() }

    /** A minimal publisher, like the Pocket 3 or ffmpeg. */
    private class Client(port: Int) {
        val socket = Socket("127.0.0.1", port).apply { soTimeout = 5000 }
        val input = DataInputStream(socket.getInputStream())
        val writer = ChunkWriter(socket.getOutputStream())
        val reader = ChunkReader(input)

        fun handshake() {
            val out = socket.getOutputStream()
            out.write(3); out.write(ByteArray(1536)); out.flush()
            assertEquals(3, input.readUnsignedByte())
            val s1 = ByteArray(1536).also { input.readFully(it) }
            input.readFully(ByteArray(1536)) // S2
            out.write(s1); out.flush() // C2
        }

        fun command(vararg v: Any?) = writer.write(3, RtmpType.COMMAND_AMF0, 0, 0, Amf0.encode(*v))

        /** Reads until a command named [name] arrives; returns its decoded values. */
        fun await(name: String): List<Any?> {
            while (true) {
                val m = reader.readMessage()
                if (m.typeId == RtmpType.SET_CHUNK_SIZE) reader.chunkSize = readU32(m.payload, 0).toInt()
                if (m.typeId == RtmpType.COMMAND_AMF0) {
                    val v = Amf0.decodeAll(m.payload)
                    if (v.firstOrNull() == name) return v
                }
            }
        }

        fun publish(app: String, stream: String): List<Any?> {
            handshake()
            command("connect", 1.0, mapOf("app" to app, "type" to "nonprivate", "tcUrl" to "rtmp://x/$app"))
            await("_result")
            command("releaseStream", 2.0, null, stream)
            command("FCPublish", 3.0, null, stream)
            command("createStream", 4.0, null)
            await("_result")
            writer.write(2, RtmpType.SET_CHUNK_SIZE, 0, 0, u32(4096))
            writer.chunkSize = 4096
            writer.write(8, RtmpType.COMMAND_AMF0, 1, 0, Amf0.encode("publish", 5.0, null, stream, "live"))
            return await("onStatus")
        }

        fun close() = socket.close()
    }

    private val spsBytes = byteArrayOf(0x67, 0x64, 0x00, 0x28, 0xAC.toByte())
    private val ppsBytes = byteArrayOf(0x68, 0xEE.toByte(), 0x3C, 0x80.toByte())

    private fun avcSeqHeader(): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0x17, 0, 0, 0, 0)) // keyframe+AVC, seq header, cts 0
        write(byteArrayOf(1, 0x64, 0, 0x28, 0xFF.toByte(), 0xE1.toByte())) // config v1, length size 4, 1 SPS
        write(byteArrayOf(0, spsBytes.size.toByte())); write(spsBytes)
        write(byteArrayOf(1, 0, ppsBytes.size.toByte())); write(ppsBytes)
    }.toByteArray()

    private fun avcFrame(key: Boolean, ctsMs: Int, nalSizes: List<Int>): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(if (key) 0x17 else 0x27, 1, (ctsMs shr 16).toByte(), (ctsMs shr 8).toByte(), ctsMs.toByte()))
        for ((i, n) in nalSizes.withIndex()) {
            write(u32(n.toLong()))
            write(ByteArray(n) { (if (it == 0) (if (key && i == 0) 0x65 else 0x41) else i) .toByte() })
        }
    }.toByteArray()

    @Test
    fun `camera publishes H264 and AAC and every frame arrives in Annex B with its timestamps`() {
        server = RtmpIngestServer(0, listener, path = "live/cam").start()
        val c = Client(server!!.localPort)
        val status = c.publish("live", "cam")
        assertEquals("NetStream.Publish.Start", (status[3] as Map<*, *>)["code"])
        c.writer.write(6, RtmpType.VIDEO, 1, 0, avcSeqHeader())
        c.writer.write(4, RtmpType.AUDIO, 1, 0, byteArrayOf(0xAF.toByte(), 0, 0x11, 0x90.toByte())) // AAC config
        c.writer.write(6, RtmpType.VIDEO, 1, 40, avcFrame(true, 66, listOf(9000, 40)))  // > chunk size: multi-chunk
        c.writer.write(4, RtmpType.AUDIO, 1, 41, byteArrayOf(0xAF.toByte(), 1) + ByteArray(300))
        c.writer.write(6, RtmpType.VIDEO, 1, 73, avcFrame(false, 33, listOf(1200)))
        c.writer.write(6, RtmpType.VIDEO, 1, 0x1000000 + 5, avcFrame(false, 0, listOf(10))) // extended timestamp
        c.command("FCUnpublish", 6.0, null, "cam")
        assertTrue(ended.await(5, TimeUnit.SECONDS))

        assertEquals("publish 1 live/cam", events.first())
        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + spsBytes, sps)
        assertArrayEquals(byteArrayOf(0x11, 0x90.toByte()), asc)
        assertEquals(3, video.size)
        val (first, pts, key) = video[0]
        assertTrue(key)
        assertEquals(40L + 66, pts)
        assertEquals(4 + 9000 + 4 + 40, first.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65), first.copyOfRange(0, 5))
        assertEquals(73L + 33, video[1].second)
        assertEquals(0x1000005L, video[2].second)
        assertTrue(events.contains("audio 41 300"))
        assertTrue(events.last().startsWith("end 1 camera stopped"))
    }

    /** An Enhanced RTMP video tag: IsExHeader, [frameType], [packetType], optional ModEx prefix, FourCC, body. */
    private fun exTag(frameType: Int, packetType: Int, fourcc: String, body: ByteArray, modEx: ByteArray? = null): ByteArray =
        ByteArrayOutputStream().apply {
            if (modEx != null) {
                write(0x80 or (frameType shl 4) or 7) // ModEx first...
                write(modEx.size - 1); write(modEx)
                write((0 shl 4) or packetType) // ...then the real packet type (mod-ex type 0: timestamp offset)
            } else {
                write(0x80 or (frameType shl 4) or packetType)
            }
            write(fourcc.toByteArray(Charsets.US_ASCII))
            write(body)
        }.toByteArray()

    private fun streamOne(vararg tags: ByteArray) {
        server = RtmpIngestServer(0, listener).start()
        val c = Client(server!!.localPort)
        c.publish("live", "cam")
        var ts = 0L
        for (t in tags) { c.writer.write(6, RtmpType.VIDEO, 1, ts, t); ts += 33 }
        c.command("FCUnpublish", 6.0, null, "cam")
        assertTrue(ended.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `an Enhanced RTMP HEVC camera is reported once and never decoded as H264`() {
        val hevcFrame = byteArrayOf(0, 0, 0) + u32(5) + byteArrayOf(0x26, 1, 2, 3, 4)
        streamOne(
            exTag(1, 0, "hvc1", ByteArray(23)), // HEVCDecoderConfigurationRecord (contents don't matter here)
            exTag(1, 1, "hvc1", hevcFrame),
            exTag(2, 3, "hvc1", u32(5) + byteArrayOf(2, 1, 2, 3, 4)),
        )
        assertEquals(listOf("unsupported 1 hvc1"), events.filter { it.startsWith("unsupported") })
        assertEquals(null, sps)
        assertTrue(video.isEmpty())
    }

    @Test
    fun `a ModEx packet is not misparsed as AVC`() {
        // ModEx's packet type is 7, the legacy AVC codec id: read as legacy FLV it would be "AVC".
        streamOne(exTag(1, 1, "hvc1", byteArrayOf(0, 0, 0) + u32(3) + byteArrayOf(0x26, 1, 2), modEx = byteArrayOf(0, 0, 1)))
        assertTrue(video.isEmpty())
        assertEquals(listOf("unsupported 1 hvc1"), events.filter { it.startsWith("unsupported") })
    }

    @Test
    fun `Enhanced RTMP H264 is decoded like the legacy form, ModEx prefix and all`() {
        val cfg = avcSeqHeader().copyOfRange(5, avcSeqHeader().size)
        streamOne(
            exTag(1, 0, "avc1", cfg),
            exTag(1, 1, "avc1", byteArrayOf(0, 0, 66) + avcFrame(true, 0, listOf(20)).copyOfRange(5, 5 + 24)),
            exTag(2, 3, "avc1", avcFrame(false, 0, listOf(10)).copyOfRange(5, 5 + 14), modEx = byteArrayOf(0, 0, 1)),
        )
        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + spsBytes, sps)
        assertEquals(2, video.size)
        assertEquals(33L + 66, video[0].second) // dts 33 + composition time 66
        assertTrue(video[0].third)
        assertEquals(66L, video[1].second) // CodedFramesX: no composition time
        assertTrue(events.none { it.startsWith("unsupported") })
    }

    @Test
    fun `a legacy non-AVC codec id is reported once`() {
        streamOne(byteArrayOf(0x1C, 0, 0, 0, 0, 1), byteArrayOf(0x2C, 1, 0, 0, 0, 1)) // id 12: the unofficial HEVC
        assertEquals(listOf("unsupported 1 hvc1"), events.filter { it.startsWith("unsupported") })
        assertTrue(video.isEmpty())
    }

    @Test
    fun `a reconnecting camera replaces the old session`() {
        server = RtmpIngestServer(0, listener).start()
        val a = Client(server!!.localPort)
        a.publish("live", "x")
        val b = Client(server!!.localPort)
        b.publish("live", "x")
        assertTrue("the older session ends", ended.await(5, TimeUnit.SECONDS))
        assertTrue(events.contains("publish 2 live/x"))
        assertTrue(events.any { it.startsWith("end 1") })
        b.close(); a.close()
    }

    @Test
    fun `a publish to another path is refused`() {
        server = RtmpIngestServer(0, listener, path = "live/secret").start()
        val c = Client(server!!.localPort)
        val status = c.publish("live", "guess")
        assertEquals("NetStream.Publish.BadName", (status[3] as Map<*, *>)["code"])
        Thread.sleep(200)
        assertTrue(events.none { it.startsWith("publish") })
        c.close()
    }

    @Test
    fun `chunk reader handles every header format and interleaving`() {
        val bytes = ByteArrayOutputStream()
        val w = ChunkWriter(bytes).apply { chunkSize = 10 }
        w.write(4, 8, 1, 1000, ByteArray(25) { 1 }) // fmt 0 + two fmt-3 continuations
        // Hand-made: fmt 1 (delta 20, new length/type) and fmt 2 (delta 30) and fmt 3 (repeat delta) on csid 4.
        bytes.write(byteArrayOf(0x44, 0, 0, 20, 0, 0, 3, 9)); bytes.write(byteArrayOf(7, 7, 7))
        bytes.write(byteArrayOf(0x84.toByte(), 0, 0, 30)); bytes.write(byteArrayOf(8, 8, 8))
        bytes.write(byteArrayOf(0xC4.toByte())); bytes.write(byteArrayOf(9, 9, 9))
        val r = ChunkReader(DataInputStream(bytes.toByteArray().inputStream())).apply { chunkSize = 10 }
        val m1 = r.readMessage()
        assertEquals(1000L, m1.timestamp); assertEquals(25, m1.payload.size)
        val m2 = r.readMessage()
        assertEquals(1020L, m2.timestamp); assertEquals(9, m2.typeId)
        assertEquals(1050L, r.readMessage().timestamp)
        assertEquals(1080L, r.readMessage().timestamp)
    }

    @Test
    fun `amf0 round trip`() {
        val v = Amf0.decodeAll(Amf0.encode("connect", 1.0, mapOf("app" to "live", "n" to 3.0, "ok" to true), null, listOf(1.0, "a")))
        assertEquals("connect", v[0])
        assertEquals(mapOf("app" to "live", "n" to 3.0, "ok" to true), v[2])
        assertEquals(null, v[3])
        assertEquals(listOf(1.0, "a"), v[4])
        assertNotNull(RtmpIngestServer.parseAvcConfig(avcSeqHeader(), 5))
    }
}
