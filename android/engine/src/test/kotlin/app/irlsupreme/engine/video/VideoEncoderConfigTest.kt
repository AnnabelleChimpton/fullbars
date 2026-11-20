package app.irlsupreme.engine.video

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class VideoEncoderConfigTest {
    @Test fun levelFollowsMacroblocksPerSecond() {
        assertEquals(CodecProfileLevel.AVCLevel42, AvcSettings.level(1920, 1080, 60)) // 489,600 MB/s
        assertEquals(CodecProfileLevel.AVCLevel41, AvcSettings.level(1920, 1080, 30)) // 244,800
        assertEquals(CodecProfileLevel.AVCLevel41, AvcSettings.level(1280, 720, 60)) // 216,000
        assertEquals(CodecProfileLevel.AVCLevel41, AvcSettings.level(1280, 720, 30)) // never below 4.1: today's SPS
        assertEquals(CodecProfileLevel.AVCLevel41, AvcSettings.level(1080, 1920, 30)) // portrait: same macroblocks
        assertEquals(CodecProfileLevel.AVCLevel51, AvcSettings.level(3840, 2160, 30)) // frame too big for 4.2/5.0 MB/s
        assertEquals(CodecProfileLevel.AVCLevel52, AvcSettings.level(3840, 2160, 60))
    }

    @Test fun partialMacroblocksCountInFull() {
        // 1088 lines is 68 rows, the same as 1080; 1089 is 69 rows, which tips 30 fps past 4.1's limit.
        assertEquals(AvcSettings.level(1920, 1088, 30), AvcSettings.level(1920, 1080, 30))
        assertEquals(CodecProfileLevel.AVCLevel42, AvcSettings.level(1920, 1089, 30))
    }

    @Test fun levelIsDeterministic() {
        // The slate encoder calls the same function: same inputs must give the same keys, or the server's
        // byte-for-byte SPS check fails.
        val a = AvcSettings.keys(1920, 1080, 30, 6_000_000, 2, cbr = true)
        val b = AvcSettings.keys(1920, 1080, 30, 6_000_000, 2, cbr = true)
        assertEquals(a, b)
    }

    @Test fun signalsBt709LimitedRange() {
        val keys = AvcSettings.keys(1920, 1080, 30, 6_000_000, 2, cbr = true).toMap()
        assertEquals(MediaFormat.COLOR_STANDARD_BT709, keys[MediaFormat.KEY_COLOR_STANDARD])
        assertEquals(MediaFormat.COLOR_RANGE_LIMITED, keys[MediaFormat.KEY_COLOR_RANGE])
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, keys[MediaFormat.KEY_COLOR_TRANSFER])
        assertEquals(CodecProfileLevel.AVCProfileHigh, keys[MediaFormat.KEY_PROFILE])
        assertEquals(CodecProfileLevel.AVCLevel41, keys[MediaFormat.KEY_LEVEL])
        assertEquals(6_000_000, keys[MediaFormat.KEY_BIT_RATE])
        assertEquals(30, keys[MediaFormat.KEY_FRAME_RATE])
        assertEquals(2, keys[MediaFormat.KEY_I_FRAME_INTERVAL])
    }

    @Test fun fallbackDropsProfileLevelAndColourOnly() {
        val keys = AvcSettings.keys(1920, 1080, 60, 6_000_000, 2, cbr = false)
        val fb = AvcSettings.fallback(keys).toMap()
        for (k in listOf(
            MediaFormat.KEY_PROFILE, MediaFormat.KEY_LEVEL,
            MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_RANGE, MediaFormat.KEY_COLOR_TRANSFER,
        )) assertFalse("$k should be dropped", k in fb)
        // Everything else the stream depends on stays.
        assertEquals(keys.size - 5, fb.size)
        for (k in listOf(
            MediaFormat.KEY_COLOR_FORMAT, MediaFormat.KEY_BIT_RATE, MediaFormat.KEY_FRAME_RATE,
            MediaFormat.KEY_I_FRAME_INTERVAL, MediaFormat.KEY_BITRATE_MODE, MediaFormat.KEY_LATENCY, MediaFormat.KEY_PRIORITY,
        )) assertTrue("$k should stay", k in fb)
    }

    @Test fun parsesProfileAndLevelFromCsd0() {
        val high41 = SpsInfo.parse(buf(0, 0, 0, 1, 0x67, 0x64, 0x00, 0x29, 0xAC, 0x2B, 0x40))!!
        assertEquals(100, high41.profileIdc)
        assertEquals(41, high41.levelIdc)
        assertEquals("High@4.1", high41.toString())

        val main42 = SpsInfo.parse(buf(0, 0, 1, 0x67, 0x4D, 0x40, 0x2A, 0x95))!! // 3-byte start code
        assertEquals("Main@4.2", main42.toString())

        val cb31 = SpsInfo.parse(buf(0, 0, 0, 1, 0x67, 0x42, 0xC0, 0x1F, 0xDA))!!
        assertEquals("Constrained Baseline@3.1", cb31.toString())

        val level1b = SpsInfo.parse(buf(0, 0, 0, 1, 0x67, 0x42, 0x10, 0x0B, 0x00))!!
        assertEquals("1b", level1b.level)

        val high4 = SpsInfo.parse(buf(0, 0, 0, 1, 0x67, 0x64, 0x00, 0x28, 0x00))!!
        assertEquals("High@4", high4.toString())
    }

    @Test fun skipsNalsBeforeTheSps() {
        // Access unit delimiter first, then the SPS (High, level 5).
        val sps = SpsInfo.parse(buf(0, 0, 0, 1, 0x09, 0xF0, 0, 0, 0, 1, 0x67, 0x64, 0x00, 0x32, 0x00))!!
        assertEquals(100, sps.profileIdc)
        assertEquals(50, sps.levelIdc)
    }

    @Test fun leavesTheBufferPositionAlone() {
        val b = buf(0, 0, 0, 1, 0x67, 0x64, 0x00, 0x29, 0xAC)
        b.position(0)
        SpsInfo.parse(b)
        assertEquals(0, b.position())
        assertEquals(9, b.limit())
    }

    @Test fun noSpsGivesNull() {
        assertNull(SpsInfo.parse(buf(0, 0, 0, 1, 0x68, 0xEE, 0x3C, 0x80))) // a PPS
        assertNull(SpsInfo.parse(buf(0, 0, 0, 1, 0x67, 0x64))) // truncated
        assertNull(SpsInfo.parse(buf()))
    }

    @Test fun describesTheEncoderForTheSessionLog() {
        val sps = SpsInfo.parse(buf(0, 0, 0, 1, 0x67, 0x64, 0x00, 0x29, 0xAC))
        assertEquals("c2.qti.avc.encoder High@4.1 CBR", EncoderInfo("c2.qti.avc.encoder", true, false, "4.1", sps).describe())
        assertEquals("c2.x ?@? VBR", EncoderInfo("c2.x", false, false, "4.1").describe())
        // A clamp the encoder did on its own shows next to what was asked for.
        val clamped = SpsInfo.parse(buf(0, 0, 0, 1, 0x67, 0x64, 0x00, 0x28, 0xAC))
        assertEquals("c2.x High@4 CBR (asked 4.2)", EncoderInfo("c2.x", true, false, "4.2", clamped).describe())
        val base = SpsInfo.parse(buf(0, 0, 0, 1, 0x67, 0x42, 0xC0, 0x1F))
        assertEquals("c2.x Constrained Baseline@3.1 VBR (hints rejected, encoder defaults)",
            EncoderInfo("c2.x", false, true, "4.1", base).describe())
    }

    @Test fun levelNamesMatchTheSpsNames() {
        for ((w, h, fps) in listOf(Triple(1920, 1080, 30), Triple(1920, 1080, 60), Triple(3840, 2160, 30))) {
            val name = AvcSettings.levelName(AvcSettings.level(w, h, fps))
            val idc = when (name) { "4.1" -> 41; "4.2" -> 42; "5.1" -> 51; else -> error(name) }
            assertEquals(name, SpsInfo(100, 0, idc).level)
        }
    }

    private fun buf(vararg b: Int): ByteBuffer = ByteBuffer.wrap(ByteArray(b.size) { b[it].toByte() })
}
