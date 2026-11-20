package app.irlsupreme.engine.video

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaFormat
import java.nio.ByteBuffer

/**
 * The H.264 encoder settings [VideoEncoder.configureAvc] applies, as plain key/value pairs so the rules (level,
 * colour, what the fallback drops) can be unit-tested without a codec. Every value here must depend only on its
 * arguments: the slate encoder goes through the same function, and the server splices the slate in without
 * transcoding only when its SPS/PPS are byte-identical to the live stream's.
 */
internal object AvcSettings {
    /**
     * Keys the retry leaves out when the encoder rejects the first configure. Profile and level hints are the usual
     * cause; some encoders also refuse colour keys they don't understand.
     */
    val FALLBACK_DROPPED = setOf(
        MediaFormat.KEY_PROFILE,
        MediaFormat.KEY_LEVEL,
        MediaFormat.KEY_COLOR_STANDARD,
        MediaFormat.KEY_COLOR_RANGE,
        MediaFormat.KEY_COLOR_TRANSFER,
    )

    /**
     * H.264 levels from Table A-1: (level constant, max macroblocks per second, max frame size in macroblocks).
     * 4.1 is the floor so 720p and 1080p30 keep the level they have always been encoded at.
     */
    private val LEVELS = arrayOf(
        intArrayOf(CodecProfileLevel.AVCLevel41, 245_760, 8_192),
        intArrayOf(CodecProfileLevel.AVCLevel42, 522_240, 8_704),
        intArrayOf(CodecProfileLevel.AVCLevel5, 589_824, 22_080),
        intArrayOf(CodecProfileLevel.AVCLevel51, 983_040, 36_864),
        intArrayOf(CodecProfileLevel.AVCLevel52, 2_073_600, 36_864),
    )

    /**
     * The lowest level (4.1 or above) whose limits cover [width] x [height] at [fps]. Macroblocks are 16x16 and a
     * partial one counts in full, so 1080 lines are 68 rows: 1080p30 needs 244,800 MB/s (4.1), 1080p60 needs
     * 489,600 (4.2). Asking for 4.1 at 1080p60 makes encoders either reject the format or clamp the frame rate.
     */
    fun level(width: Int, height: Int, fps: Int): Int {
        val frameMbs = ((width + 15) / 16).toLong() * ((height + 15) / 16)
        val mbps = frameMbs * fps
        for (l in LEVELS) if (mbps <= l[1] && frameMbs <= l[2]) return l[0]
        return LEVELS.last()[0] // beyond 5.2: the encoder will refuse it and the fallback lets it pick
    }

    /** Every integer key configureAvc sets, in order. */
    fun keys(width: Int, height: Int, fps: Int, bitrate: Int, keyframeIntervalSec: Int, cbr: Boolean): List<Pair<String, Int>> = listOf(
        MediaFormat.KEY_COLOR_FORMAT to MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
        MediaFormat.KEY_BIT_RATE to bitrate,
        MediaFormat.KEY_FRAME_RATE to fps,
        MediaFormat.KEY_I_FRAME_INTERVAL to keyframeIntervalSec,
        MediaFormat.KEY_BITRATE_MODE to if (cbr) EncoderCapabilities.BITRATE_MODE_CBR else EncoderCapabilities.BITRATE_MODE_VBR,
        MediaFormat.KEY_PROFILE to CodecProfileLevel.AVCProfileHigh,
        MediaFormat.KEY_LEVEL to level(width, height, fps),
        // Say what the picture is: HD video, BT.709, studio range. Without it the VUI carries no colour description
        // and players guess (often BT.601 for anything they treat as SD), which shifts reds and greens slightly.
        MediaFormat.KEY_COLOR_STANDARD to MediaFormat.COLOR_STANDARD_BT709,
        MediaFormat.KEY_COLOR_RANGE to MediaFormat.COLOR_RANGE_LIMITED,
        MediaFormat.KEY_COLOR_TRANSFER to MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
        // No KEY_REPEAT_PREVIOUS_FRAME_AFTER: the compositor renders every tick, and encoder-inserted repeats
        // produced a duplicate timestamp in testing.
        MediaFormat.KEY_LATENCY to 1,
        MediaFormat.KEY_PRIORITY to 0, // realtime
    )

    /** [keys] minus what the retry drops. */
    fun fallback(keys: List<Pair<String, Int>>): List<Pair<String, Int>> = keys.filter { it.first !in FALLBACK_DROPPED }

    /** "4.1" for a CodecProfileLevel.AVCLevel41 constant, for logs. */
    fun levelName(level: Int): String = when (level) {
        CodecProfileLevel.AVCLevel41 -> "4.1"
        CodecProfileLevel.AVCLevel42 -> "4.2"
        CodecProfileLevel.AVCLevel5 -> "5"
        CodecProfileLevel.AVCLevel51 -> "5.1"
        CodecProfileLevel.AVCLevel52 -> "5.2"
        else -> "0x" + Integer.toHexString(level)
    }
}

/**
 * The profile and level an H.264 SPS actually declares: what the encoder produced, which can differ from what was
 * asked for (Codec2 encoders clamp silently, and the configure fallback drops the request altogether).
 */
class SpsInfo(val profileIdc: Int, val constraintFlags: Int, val levelIdc: Int) {
    /** The name ffprobe would print, e.g. "High", "Constrained Baseline". */
    val profile: String
        get() = when (profileIdc) {
            66 -> if (constraintFlags and 0x40 != 0) "Constrained Baseline" else "Baseline"
            77 -> "Main"
            88 -> "Extended"
            100 -> "High"
            110 -> "High 10"
            122 -> "High 4:2:2"
            244 -> "High 4:4:4"
            else -> "profile $profileIdc"
        }

    /** "4.1", "4", or "1b" (level_idc 11 with constraint_set3 in the Baseline family, or level_idc 9). */
    val level: String
        get() = when {
            levelIdc == 9 || levelIdc == 11 && profileIdc in BASELINE_FAMILY && constraintFlags and 0x10 != 0 -> "1b"
            levelIdc % 10 == 0 -> "${levelIdc / 10}"
            else -> "${levelIdc / 10}.${levelIdc % 10}"
        }

    override fun toString() = "$profile@$level"

    companion object {
        private val BASELINE_FAMILY = setOf(66, 77, 88)

        /**
         * Reads profile_idc, the constraint flags and level_idc from the first SPS NAL in [csd] (MediaCodec's csd-0:
         * Annex B, start code first). Uses absolute reads, so [csd]'s position is untouched and it can still be passed
         * on. Null when there is no SPS.
         */
        fun parse(csd: ByteBuffer): SpsInfo? {
            val start = csd.position()
            val end = csd.limit()
            var i = start
            var nal = -1
            while (i + 3 < end) {
                if (csd.get(i).toInt() == 0 && csd.get(i + 1).toInt() == 0 && csd.get(i + 2).toInt() == 1) {
                    if (csd.get(i + 3).toInt() and 0x1f == 7) { nal = i + 3; break }
                    i += 3
                } else {
                    i++
                }
            }
            // A bare NAL with no start code (not what Android hands over, but cheap to accept).
            if (nal < 0 && end - start >= 4 && csd.get(start).toInt() and 0x1f == 7) nal = start
            if (nal < 0 || nal + 3 >= end) return null
            return SpsInfo(
                csd.get(nal + 1).toInt() and 0xff,
                csd.get(nal + 2).toInt() and 0xff,
                csd.get(nal + 3).toInt() and 0xff,
            )
        }
    }
}

/**
 * What the live encoder is, for the session log: e.g. "c2.qti.avc.encoder High@4.1 CBR". [sps] is null until the
 * encoder hands over its first SPS. [fallback] means the first configure was rejected and the encoder chose its own
 * profile, level and colour signalling.
 */
data class EncoderInfo(
    val codecName: String,
    val cbr: Boolean,
    val fallback: Boolean,
    /** The level asked for, e.g. "4.1"; the SPS shows what was produced. */
    val requestedLevel: String,
    val sps: SpsInfo? = null,
) {
    fun describe(): String = buildString {
        append(codecName).append(' ')
        append(sps?.toString() ?: "?@?").append(' ')
        append(if (cbr) "CBR" else "VBR")
        if (sps != null && sps.level != requestedLevel && !fallback) append(" (asked ").append(requestedLevel).append(')')
        if (fallback) append(" (hints rejected, encoder defaults)")
    }

    override fun toString() = describe()
}
