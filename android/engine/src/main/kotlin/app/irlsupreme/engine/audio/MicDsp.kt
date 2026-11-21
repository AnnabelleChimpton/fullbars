package app.irlsupreme.engine.audio

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One chunk's worth of what the mic sends viewers, in dBFS (0 = the loudest a sample can be). [reductionDb] is how far
 * the limiter had to turn the voice down to keep it from clipping: a little on shouts is its job; a lot, all the time,
 * means the level is set too high.
 *
 * [inPeakDb] and [inClipped] describe the voice as it arrived, before any gain: the loudest sample, and how many
 * samples look clipped (at full scale, or flat-topped near it). Clipping there happened in the phone (or the camera)
 * before Fullbars, so turning the level down can't fix it. [levelerDb] is what the voice leveler is adding (+) or
 * taking off (-) right now; 0 when it's off.
 */
data class MicLevel(
    val rmsDb: Float,
    val peakDb: Float,
    val reductionDb: Float,
    val inPeakDb: Float = FLOOR_DB,
    val inClipped: Int = 0,
    val levelerDb: Float = 0f,
) {
    companion object {
        /** The bottom of every meter: quieter than this is "nothing". */
        const val FLOOR_DB = -60f
        val SILENT = MicLevel(FLOOR_DB, FLOOR_DB, 0f)
    }
}

/** The phone mic's low cut (high-pass filter): rumble from wind, handling and traffic, and any DC offset. */
enum class LowCut(val hz: Int) {
    /** 80 Hz on the raw mic (the unprocessed source has no filter of its own); off with the phone's auto level on, whose chain usually filters already. */
    AUTO(-1),
    OFF(0),
    /** 80 Hz: under any voice's fundamental, over most rumble. */
    NORMAL(80),
    /** 120 Hz: for wind. Thins a deep voice a little. */
    WIND(120),
}

/** The cutoff in Hz (0 = off) for [mode] with the mic opened raw ([raw]) or with the phone's auto level. */
fun lowCutHz(mode: LowCut, raw: Boolean): Int = when (mode) {
    LowCut.AUTO -> if (raw) LowCut.NORMAL.hz else 0
    else -> mode.hz
}

/**
 * The mic's level stage, applied to every chunk before it's encoded. It comes in two halves, so a mix of several
 * sources can level each one on its own and limit the sum once:
 *
 * - [micFront] takes the phone mic's chunk: the input meter (before any gain, so clipping that happened in the phone
 *   shows), the low cut, the user's gain (gliding), the voice leveler, and optionally a fold to mono.
 * - [limit] takes the finished voice (whatever the source), adds the alert sounds, and runs the peak limiter and the
 *   meter over the sum, so a chime over a loud voice is turned down with it instead of clipping.
 *
 * [process] does both for a lone mic (the mic check, and tests).
 *
 * Why a limiter and not just gain: phone mics clip harshly, and raising the level for a quiet street means a shout or a
 * passing bus would. The limiter turns those down to just under full scale for a moment instead, so viewers hear a
 * loud voice rather than distortion. It can't repair audio that clipped before it got here; that's what turning the
 * phone's own auto level off is for, and what [MicLevel.inClipped] is there to spot.
 *
 * How it limits: it looks [LOOKAHEAD_S] ahead (the audio comes out that much later), so the gain can start coming down
 * before a peak arrives and reach the right level just as it does, over a smooth ramp, then holds for [HOLD_S] and
 * recovers over ~150 ms. The hold keeps the gain still between the peaks of a low note or a rumble (a 50 Hz wave peaks
 * every 10 ms), which would otherwise wobble the gain at the note's own rate and distort it. The first version turned
 * the gain down on the very sample that would have clipped: a step in gain is a click, and on a hot source (the OnePlus
 * camcorder mic peaks near -1 dBFS before any boost) it was stepping on nearly every wave, which is the harsh, crunchy
 * sound. Every output sample is still guaranteed under the [CEILING]: the gain applied to a sample is the average of
 * the needed gains over the lookahead, each of which is at most what that sample needs.
 *
 * The range runs to +30 dB because a raw mic is quiet: on a OnePlus 12 the unprocessed source sits ~28 dB under the
 * camcorder one (measured in Settings › Microphone, same room, same moment).
 *
 * Gain changes glide over ~20 ms so dragging the slider doesn't click. Not thread-safe except [gainDb], which the UI
 * sets while the capture thread runs the rest. No allocation per chunk.
 */
class MicDsp(private val sampleRate: Int, private val channels: Int) {
    @Volatile var gainDb: Float = 0f
        set(v) { field = v.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) }

    /** How many frames later a sample comes out than it went in (the lookahead). */
    val latencyFrames: Int = (LOOKAHEAD_S * sampleRate).roundToInt().coerceAtLeast(1)

    // --- Front: the phone mic on its own ---

    private var gain = 1f
    private val glide = (1.0 - exp(-1.0 / (GLIDE_S * sampleRate))).toFloat()
    private val lowCut = Biquad(channels)
    private var lowCutAt = 0
    private val leveler = VoiceLeveler(sampleRate, channels)

    /** Input meter of the chunk being processed: the loudest sample and the clipped-looking ones (before gain). */
    private var inPeak = 0
    private var inClipped = 0
    private val runPrev = IntArray(channels)
    private val runLen = IntArray(channels)

    // --- Back: the limiter ---

    private val la = latencyFrames
    private val release = exp(-1.0 / (RELEASE_S * sampleRate)).toFloat()
    private val hold = (HOLD_S * sampleRate).roundToInt()
    /** The voice and the alert sound, [la] frames deep: kept apart so the meter can show the voice alone. */
    private val delay = FloatArray(la * channels)
    private val delayFx = FloatArray(la * channels)
    /** The voice the meter reads, as deep: the voice itself, or what it was before a mute or duck ([limit]'s meter). */
    private val delayMeter = FloatArray(la * channels)
    private var delayPos = 0
    /** The gain the sum gets. */
    private val gainSum = LimiterGain(la, hold, release)
    /**
     * The gain the voice alone would have got: only for [MicLevel.reductionDb], so a chime over speech doesn't read
     * as the level being set too hot (turning the mic down can't help with a chime).
     */
    private val gainVoice = LimiterGain(la, hold, release)
    private var frameNo = 0L

    /** Scratch for [process]. */
    private var scratch = FloatArray(0)

    /** The leveler's gain now (dB): what [MicLevel.levelerDb] reports. */
    val levelerDb: Float get() = leveler.gainDb

    /**
     * Applies gain and limiting to [frames] frames of 16-bit little-endian PCM in [pcm], in place, and meters it. The
     * output is the input of [latencyFrames] frames earlier (the first call starts with that much silence).
     * [gainOverrideDb] replaces [gainDb] for this chunk (the raw mic's own level); the glide still applies, so a
     * change doesn't click. [lowCutHz] (0 = off), [leveler] and [mono] as in [micFront].
     */
    fun process(
        pcm: ByteArray,
        frames: Int,
        gainOverrideDb: Float? = null,
        lowCutHz: Int = 0,
        leveler: Boolean = false,
        mono: Boolean = false,
    ): MicLevel {
        val n = frames * channels
        if (scratch.size < n) scratch = FloatArray(n)
        meterInput(pcm, frames)
        micFront(pcm, frames, scratch, gainOverrideDb, lowCutHz, leveler, mono)
        return limit(scratch, null, frames, pcm)
    }

    /**
     * Measures [pcm] as it arrived, before any gain, for the [MicLevel] the next [limit] returns: the loudest sample,
     * and samples that look clipped. Clipped means at full scale (|x| >= [CLIP_LEVEL]), or part of a flat top: 3 or
     * more samples in a row on one channel within 1 LSB of each other above -0.5 dBFS, which is what a clipper that
     * stops a hair under full scale (or a gain stage after one) leaves. Soft-clipping auto-gain shows neither; a
     * clean low note this loud (under ~50 Hz) can just about pass for a flat top, which a voice never is.
     */
    internal fun meterInput(pcm: ByteArray, frames: Int) {
        var peak = 0
        var clipped = 0
        for (f in 0 until frames) {
            for (ch in 0 until channels) {
                val s = pcm16At(pcm, (f * channels + ch) * 2)
                val a = if (s < 0) -s else s
                if (a > peak) peak = a
                val d = s - runPrev[ch]
                runLen[ch] = if (a >= FLAT_LEVEL && d >= -1 && d <= 1) runLen[ch] + 1 else if (a >= FLAT_LEVEL) 1 else 0
                runPrev[ch] = s
                if (a >= CLIP_LEVEL || runLen[ch] >= FLAT_RUN) clipped++
            }
        }
        inPeak = peak
        inClipped = clipped
    }

    /**
     * The phone mic's half: [pcm] (16-bit) into [out] (float, 1.0 = full scale, interleaved), through the low cut at
     * [lowCutHz] (0 = off), the gain (gliding to [gainOverrideDb], or [gainDb] when null), the voice leveler when
     * [leveler], and, with [mono] on a stereo stream, (L+R)/2 on both channels. Run it on every chunk of the mic,
     * heard or not, so the filter, glide and leveler stay continuous and a switch back to the mic doesn't jump.
     */
    internal fun micFront(pcm: ByteArray, frames: Int, out: FloatArray, gainOverrideDb: Float?, lowCutHz: Int, leveler: Boolean, mono: Boolean) {
        if (lowCutHz != lowCutAt) {
            if (lowCutHz > 0) lowCut.setHighPass(sampleRate, lowCutHz.toDouble())
            lowCut.reset()
            lowCutAt = lowCutHz
        }
        val target = dbToLinear(gainOverrideDb ?: gainDb)
        val filter = lowCutHz > 0
        for (f in 0 until frames) {
            gain += (target - gain) * glide
            val base = f * channels
            for (ch in 0 until channels) {
                var x = pcm16At(pcm, (base + ch) * 2) / 32768f
                if (filter) x = lowCut.process(x.toDouble(), ch).toFloat()
                out[base + ch] = x * gain
            }
            if (mono && channels == 2) {
                val m = (out[base] + out[base + 1]) * 0.5f
                out[base] = m; out[base + 1] = m
            }
        }
        this.leveler.process(out, frames, leveler)
    }

    /**
     * The mic reopened (a new source starts mid-sound, faded in): the low cut forgets the old one, so it doesn't ring,
     * and the leveler starts again from 0 dB. The new source can be ~28 dB hotter (the phone's auto level back on),
     * and a boost left over from a quiet raw mic would hit it as a burst.
     */
    internal fun micReopened() { lowCut.reset(); leveler.reset() }

    /**
     * The limiter and meter: [x] is the voice (float, interleaved; any level, the limiter brings it under the
     * [CEILING]), [fx] the alert sound to add to it (null: none). Writes [frames] frames of 16-bit PCM to [out], the
     * sum of [latencyFrames] frames earlier. The meter reads the voice alone (at the gain the sum got), so a chime
     * doesn't show as the mic's level, and [MicLevel.reductionDb] is what the voice alone would have needed (the sum
     * itself is always limited as a whole). [meter], if given, is the voice to meter instead of [x]: the voice before
     * it was muted or ducked under music, so the meter still shows the mic working.
     */
    internal fun limit(x: FloatArray, fx: FloatArray?, frames: Int, out: ByteArray, meter: FloatArray? = null): MicLevel {
        var peak = 0f
        var sumSq = 0.0
        var minGain = 1f
        for (f in 0 until frames) {
            val base = f * channels
            var framePeak = 0f
            var voicePeak = 0f
            for (ch in 0 until channels) {
                val v = x[base + ch]
                val s = if (fx != null) v + fx[base + ch] else v
                val a = if (s < 0) -s else s
                if (a > framePeak) framePeak = a
                val mv = if (meter != null) meter[base + ch] else v
                val av = if (mv < 0) -mv else mv
                if (av > voicePeak) voicePeak = av
            }
            val need = if (framePeak > CEILING) CEILING / framePeak else 1f
            val g = gainSum.next(need, frameNo)
            // Without a chime the voice's own gain is the same; with one, it's what the voice alone would have needed.
            val needVoice = if (voicePeak > CEILING) CEILING / voicePeak else 1f
            val gv = gainVoice.next(needVoice, frameNo)
            if (gv < minGain) minGain = gv
            val d = delayPos * channels
            for (ch in 0 until channels) {
                val v = delay[d + ch]
                val e = delayFx[d + ch]
                val m = delayMeter[d + ch]
                // Out: the frame from [la] ago, at the gain worked out for it. The clamp only ever trims float error.
                val y = ((v + e) * g).coerceIn(-CEILING, CEILING)
                delay[d + ch] = x[base + ch]
                delayFx[d + ch] = if (fx != null) fx[base + ch] else 0f
                delayMeter[d + ch] = if (meter != null) meter[base + ch] else x[base + ch]
                val o = (y * 32768f).roundToInt().coerceIn(-32768, 32767)
                val i = (base + ch) * 2
                out[i] = (o and 0xff).toByte()
                out[i + 1] = (o shr 8).toByte()
                val voice = m * g
                val a = if (voice < 0) -voice else voice
                if (a > peak) peak = a
                sumSq += (voice * voice).toDouble()
            }
            delayPos = if (delayPos + 1 == la) 0 else delayPos + 1
            frameNo++
        }
        val n = (frames * channels).coerceAtLeast(1)
        val level = MicLevel(
            rmsDb = toDb(sqrt(sumSq / n).toFloat()),
            peakDb = toDb(peak),
            reductionDb = if (minGain >= 0.9999f) 0f else -20f * log10(minGain),
            inPeakDb = toDb(inPeak / 32768f),
            inClipped = inClipped,
            levelerDb = leveler.gainDb,
        )
        inPeak = 0
        inClipped = 0
        return level
    }

    /**
     * The limiter's gain, frame by frame: the least gain any frame in the lookahead and hold needs, a release towards
     * none, and an [la]-frame moving average that turns a gain step into a ramp. No allocation after construction.
     */
    private class LimiterGain(private val la: Int, private val hold: Int, private val release: Float) {
        private var env = 1f
        /** The last [la] envelope values and their sum. */
        private val ramp = FloatArray(la) { 1f }
        private var rampPos = 0
        private var rampSum = la.toDouble()
        /** Sliding minimum of the needed gain over the current frame and the [la] + [hold] before it (a monotonic queue). */
        private val minVal = FloatArray(la + hold + 2)
        private val minIdx = LongArray(la + hold + 2)
        private var minHead = 0
        private var minCount = 0

        /** Frame [frameNo] needs at most [need] (1 = none); returns the gain for the frame [la] before it. */
        fun next(need: Float, frameNo: Long): Float {
            val windowMin = pushMin(need, frameNo)
            env = 1f - (1f - env) * release
            if (windowMin < env) env = windowMin
            rampSum += env - ramp[rampPos]
            ramp[rampPos] = env
            rampPos = if (rampPos + 1 == la) 0 else rampPos + 1
            return (rampSum / la).toFloat().coerceAtMost(1f)
        }

        private fun pushMin(v: Float, frameNo: Long): Float {
            val cap = minVal.size
            while (minCount > 0 && minVal[(minHead + minCount - 1) % cap] >= v) minCount--
            val at = (minHead + minCount) % cap
            minVal[at] = v; minIdx[at] = frameNo; minCount++
            while (minIdx[minHead] < frameNo - la - hold) { minHead = (minHead + 1) % cap; minCount-- }
            return minVal[minHead]
        }
    }

    companion object {
        const val MIN_GAIN_DB = -20f
        const val MAX_GAIN_DB = 30f
        /**
         * -1.5 dBFS: under full scale with room for what the samples don't show, the peaks between them and AAC's own
         * overshoot at 128 kbps, so the encoded stream doesn't clip either.
         */
        val CEILING = dbToLinear(-1.5f)
        private const val GLIDE_S = 0.02
        private const val RELEASE_S = 0.15
        /** 5 ms: the attack ramp's length; long enough to be smooth at speech frequencies, too short to hear as delay. */
        const val LOOKAHEAD_S = 0.005
        /** 12 ms: longer than half the period of anything down to ~42 Hz, so the gain holds still between its peaks. */
        const val HOLD_S = 0.012
        /** An input sample this big is at full scale (-0.01 dBFS). */
        const val CLIP_LEVEL = 32704
        /** -0.5 dBFS: a flat top above this counts as clipping. */
        private const val FLAT_LEVEL = 30934
        private const val FLAT_RUN = 3

        fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)
        fun toDb(linear: Float): Float = if (linear <= 0f) MicLevel.FLOOR_DB else (20f * log10(linear)).coerceAtLeast(MicLevel.FLOOR_DB)
    }
}

/** Sample at byte offset [i] of 16-bit little-endian PCM. */
internal fun pcm16At(pcm: ByteArray, i: Int): Int = ((pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
