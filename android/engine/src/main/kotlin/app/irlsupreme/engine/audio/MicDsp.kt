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
 */
data class MicLevel(val rmsDb: Float, val peakDb: Float, val reductionDb: Float) {
    companion object {
        /** The bottom of every meter: quieter than this is "nothing". */
        const val FLOOR_DB = -60f
        val SILENT = MicLevel(FLOOR_DB, FLOOR_DB, 0f)
    }
}

/**
 * The mic's level stage, applied to every chunk before it's encoded (so it also covers a camera's mic standing in for
 * the phone's): the user's gain, then a peak limiter, then the meter.
 *
 * Why a limiter and not just gain: phone mics clip harshly, and raising the level for a quiet street means a shout or a
 * passing bus would. The limiter turns those down to just under full scale for a moment instead, so viewers hear a
 * loud voice rather than distortion. It can't repair audio that clipped before it got here; that's what turning the
 * phone's own auto level off is for.
 *
 * How it limits: it looks [LOOKAHEAD_S] ahead (the audio comes out that much later), so the gain can start coming down
 * before a peak arrives and reach the right level just as it does, over a smooth ramp, then recover over ~150 ms. The
 * first version turned the gain down on the very sample that would have clipped: a step in gain is a click, and on a
 * hot source (the OnePlus camcorder mic peaks near -1 dBFS before any boost) it was stepping on nearly every wave, which
 * is the harsh, crunchy sound. Every output sample is still guaranteed under the -1 dBFS ceiling: the gain applied to
 * a sample is the average of the needed gains over the lookahead, each of which is at most what that sample needs.
 *
 * The range runs to +30 dB because a raw mic is quiet: on a OnePlus 12 the unprocessed source sits ~28 dB under the
 * camcorder one (measured in Settings › Microphone, same room, same moment).
 *
 * Gain changes glide over ~20 ms so dragging the slider doesn't click. Not thread-safe except [gainDb], which the UI
 * sets while the capture thread runs [process].
 */
class MicDsp(sampleRate: Int, private val channels: Int) {
    @Volatile var gainDb: Float = 0f
        set(v) { field = v.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) }

    /** How many frames later a sample comes out than it went in (the lookahead). */
    val latencyFrames: Int = (LOOKAHEAD_S * sampleRate).roundToInt().coerceAtLeast(1)

    private var gain = 1f
    private var env = 1f
    private val glide = (1.0 - exp(-1.0 / (GLIDE_S * sampleRate))).toFloat()
    private val release = exp(-1.0 / (RELEASE_S * sampleRate)).toFloat()

    private val la = latencyFrames
    /** The gained input, [la] frames deep. */
    private val delay = FloatArray(la * channels)
    private var delayPos = 0
    /** The last [la] envelope values and their sum: the smoothing that turns a gain step into a ramp. */
    private val ramp = FloatArray(la) { 1f }
    private var rampPos = 0
    private var rampSum = la.toDouble()
    /** Sliding minimum of the needed gain over the current frame and the [la] before it (a monotonic queue). */
    private val minVal = FloatArray(la + 2)
    private val minIdx = LongArray(la + 2)
    private var minHead = 0
    private var minCount = 0
    private var frameNo = 0L

    /**
     * Applies gain and limiting to [frames] frames of 16-bit little-endian PCM in [pcm], in place, and meters it. The
     * output is the input of [latencyFrames] frames earlier (the first call starts with that much silence).
     * [gainOverrideDb] replaces [gainDb] for this chunk (a camera's audio, already at a normal level, gets 0 dB rather
     * than the boost the user set for the phone mic); the glide still applies, so switching doesn't click.
     */
    fun process(pcm: ByteArray, frames: Int, gainOverrideDb: Float? = null): MicLevel {
        val target = dbToLinear(gainOverrideDb ?: gainDb)
        var peak = 0f
        var sumSq = 0.0
        var minGain = 1f
        for (f in 0 until frames) {
            gain += (target - gain) * glide
            val base = f * channels * 2
            var framePeak = 0f
            for (ch in 0 until channels) {
                val s = sample(pcm, base + ch * 2) * gain
                val a = if (s < 0) -s else s
                if (a > framePeak) framePeak = a
            }
            // The gain this frame needs, the least any frame in the lookahead needs, and a release towards none.
            val need = if (framePeak > CEILING) CEILING / framePeak else 1f
            val windowMin = pushMin(need)
            env = 1f - (1f - env) * release
            if (windowMin < env) env = windowMin
            rampSum += env - ramp[rampPos]
            ramp[rampPos] = env
            rampPos = if (rampPos + 1 == la) 0 else rampPos + 1
            val g = (rampSum / la).toFloat().coerceAtMost(1f)
            if (g < minGain) minGain = g
            val d = delayPos * channels
            for (ch in 0 until channels) {
                val i = base + ch * 2
                val x = sample(pcm, i) * gain
                // Out: the frame from [la] ago, at the gain worked out for it. The clamp only ever trims float error.
                val y = (delay[d + ch] * g).coerceIn(-CEILING, CEILING)
                delay[d + ch] = x
                val out = (y * 32768f).roundToInt().coerceIn(-32768, 32767)
                pcm[i] = (out and 0xff).toByte()
                pcm[i + 1] = (out shr 8).toByte()
                val a = if (y < 0) -y else y
                if (a > peak) peak = a
                sumSq += (y * y).toDouble()
            }
            delayPos = if (delayPos + 1 == la) 0 else delayPos + 1
            frameNo++
        }
        val n = (frames * channels).coerceAtLeast(1)
        return MicLevel(
            rmsDb = toDb(sqrt(sumSq / n).toFloat()),
            peakDb = toDb(peak),
            reductionDb = if (minGain >= 0.9999f) 0f else -20f * log10(minGain),
        )
    }

    /** Adds this frame's needed gain and returns the minimum over it and the [la] frames before it. */
    private fun pushMin(v: Float): Float {
        val cap = minVal.size
        while (minCount > 0 && minVal[(minHead + minCount - 1) % cap] >= v) minCount--
        val at = (minHead + minCount) % cap
        minVal[at] = v; minIdx[at] = frameNo; minCount++
        while (minIdx[minHead] < frameNo - la) { minHead = (minHead + 1) % cap; minCount-- }
        return minVal[minHead]
    }

    private fun sample(pcm: ByteArray, i: Int): Float =
        ((pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)).toShort() / 32768f

    companion object {
        const val MIN_GAIN_DB = -20f
        const val MAX_GAIN_DB = 30f
        /** -1 dBFS: a hair under full scale, so AAC's own overshoot doesn't clip either. */
        val CEILING = dbToLinear(-1f)
        private const val GLIDE_S = 0.02
        private const val RELEASE_S = 0.15
        /** 5 ms: the attack ramp's length; long enough to be smooth at speech frequencies, too short to hear as delay. */
        const val LOOKAHEAD_S = 0.005

        fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)
        fun toDb(linear: Float): Float = if (linear <= 0f) MicLevel.FLOOR_DB else (20f * log10(linear)).coerceAtLeast(MicLevel.FLOOR_DB)
    }
}
