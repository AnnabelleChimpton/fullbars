package app.irlsupreme.engine.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The sums behind importing a sound the streamer picked: channel layout, trimming silence, loudness, the first hit.
 * Pure and allocation-light, so every rule is unit-tested; the decoding itself is [AudioFileDecoder].
 *
 * Everything works on interleaved float (1.0 = full scale) at [RATE] Hz.
 */
object ImportMath {
    /** The rate every imported sound is converted to: the stream's. */
    const val RATE = 48_000

    /** Quieter than this (about -50 dBFS) counts as silence at either end. */
    const val SILENCE = 0.00316f

    /** An alert sound's longest: longer files are cut, with [CUT_FADE_MS] of fade. */
    const val ALERT_MAX_S = 10
    const val CUT_FADE_MS = 50

    /** Fades at a trimmed sound's ends, so cutting silence away never leaves a click. */
    const val EDGE_FADE_MS = 3

    /** Where loudness lands, LUFS: alert sounds a little under a voice, music a voice's level (its volume sits it under). */
    const val ALERT_TARGET_LUFS = -18.0
    const val MUSIC_TARGET_LUFS = -16.0

    /** No sample above this after normalising, dBFS: the limiter has the rest. */
    const val PEAK_CEILING_DB = -1.0

    /** Never turn a quiet file up more than this: it would bring up its noise. */
    const val MAX_BOOST_DB = 24.0

    /** One channel to both sides; two stay two. [mono] holds [frames] samples; [out] gets 2 * [frames]. */
    fun monoToStereo(mono: FloatArray, frames: Int, out: FloatArray) {
        for (f in frames - 1 downTo 0) {
            val v = mono[f]
            out[2 * f] = v
            out[2 * f + 1] = v
        }
    }

    /**
     * The frames worth keeping: from the first to the last frame louder than [threshold] on any channel, as
     * [first, endExclusive), or null when the whole thing is silence.
     */
    fun soundRange(x: FloatArray, frames: Int, channels: Int, threshold: Float = SILENCE): IntRange? {
        var first = -1
        for (f in 0 until frames) if (frameLoud(x, f, channels, threshold)) { first = f; break }
        if (first < 0) return null
        var last = first
        for (f in frames - 1 downTo first) if (frameLoud(x, f, channels, threshold)) { last = f; break }
        return first..last
    }

    fun frameLoud(x: FloatArray, f: Int, channels: Int, threshold: Float): Boolean {
        for (c in 0 until channels) { val v = x[f * channels + c]; if (v > threshold || v < -threshold) return true }
        return false
    }

    /** The largest |sample|. */
    fun peak(x: FloatArray, samples: Int = x.size): Float {
        var p = 0f
        for (i in 0 until samples) { val a = if (x[i] < 0) -x[i] else x[i]; if (a > p) p = a }
        return p
    }

    /**
     * The gain, dB, that brings a sound measured at [lufs] (integrated) with sample peak [peak] (linear) to [target]
     * LUFS, held back so the peak stays under [PEAK_CEILING_DB] and never more than [MAX_BOOST_DB] up. Null for silence.
     */
    fun loudnessGainDb(lufs: Double, peak: Float, target: Double): Double? {
        if (peak <= 0f || lufs < -70.0) return null
        val toTarget = target - lufs
        val toCeiling = PEAK_CEILING_DB - 20 * log10(peak.toDouble())
        return minOf(toTarget, toCeiling, MAX_BOOST_DB)
    }

    fun dbToGain(db: Double): Float = 10.0.pow(db / 20).toFloat()

    /** Multiplies [samples] of [x] by [gain]. */
    fun applyGain(x: FloatArray, samples: Int, gain: Float) { for (i in 0 until samples) x[i] *= gain }

    /**
     * Fades the first [fadeFrames] frames in and the last [fadeOutFrames] out (raised cosine), in place, over a sound
     * [frames] long.
     */
    fun fadeEdges(x: FloatArray, frames: Int, channels: Int, fadeFrames: Int, fadeOutFrames: Int = fadeFrames) {
        val inN = minOf(fadeFrames, frames / 2)
        for (f in 0 until inN) {
            val g = fadeCurve((f + 1).toFloat() / (inN + 1))
            for (c in 0 until channels) x[f * channels + c] *= g
        }
        val outN = minOf(fadeOutFrames, frames / 2)
        for (k in 0 until outN) {
            val f = frames - 1 - k
            val g = fadeCurve((k + 1).toFloat() / (outN + 1))
            for (c in 0 until channels) x[f * channels + c] *= g
        }
    }

    /** A raised cosine from 0 at [t] = 0 to 1 at [t] = 1: flat at both ends, so a fade never starts or ends on a kink. */
    fun fadeCurve(t: Float): Float = when {
        t <= 0f -> 0f
        t >= 1f -> 1f
        else -> (0.5 - 0.5 * cos(PI * t)).toFloat()
    }

    /**
     * Where the sound lands, in frames from its start: the first frame within 12 dB of the loudest, then the peak of
     * that hit (within 10 ms). An alert sound starts this much before the alert is fully shown, as the chimes do, so
     * the hit and the picture arrive together. 0 for silence; at most a second.
     */
    fun attackFrame(x: FloatArray, frames: Int, channels: Int, rate: Int = RATE): Int {
        val p = peak(x, frames * channels)
        if (p <= 0f) return 0
        val thr = p * 0.25f
        var first = -1
        for (f in 0 until frames) if (frameLoud(x, f, channels, thr)) { first = f; break }
        if (first < 0) return 0
        var best = first
        var bestV = 0f
        val until = minOf(frames, first + rate / 100)
        for (f in first until until) for (c in 0 until channels) {
            val v = x[f * channels + c].let { if (it < 0) -it else it }
            if (v > bestV) { bestV = v; best = f }
        }
        return minOf(best, rate)
    }

    /** [frames] at [rate] in whole milliseconds. */
    fun framesToMs(frames: Long, rate: Int = RATE): Long = frames * 1000 / rate

    /** Float to 16-bit PCM, rounding, saturating. */
    fun toShorts(x: FloatArray, samples: Int): ShortArray =
        ShortArray(samples) { (x[it] * 32768f).roundToInt().coerceIn(-32768, 32767).toShort() }

    /**
     * Equal-power crossfade weights at step [i] of [n], the outgoing's and the incoming's. Their squares always add
     * to one, so two unrelated sounds keep their loudness through the seam.
     */
    fun seamOut(i: Int, n: Int): Float = cos((i + 0.5) / n * (PI / 2)).toFloat()

    fun seamIn(i: Int, n: Int): Float = sin((i + 0.5) / n * (PI / 2)).toFloat()
}

/**
 * Integrated loudness (ITU-R BS.1770: K-weighting, 400 ms blocks with 75% overlap, the -70 LUFS absolute and -10 LU
 * relative gates), fed a block at a time so a long file never needs to be in memory. 48 kHz only, which is what every
 * import is converted to first. Keeps one number per 100 ms: about 9,000 for 15 minutes.
 */
class LoudnessMeter(private val channels: Int) {
    // The standard's two K-weighting stages for 48 kHz: a high shelf, then a high pass.
    private val sb0 = 1.53512485958697; private val sb1 = -2.69169618940638; private val sb2 = 1.19839281085285
    private val sa1 = -1.69065929318241; private val sa2 = 0.73248077421585
    private val ha1 = -1.99004745483398; private val ha2 = 0.99007225036621
    private val x1 = DoubleArray(channels); private val x2 = DoubleArray(channels)
    private val y1 = DoubleArray(channels); private val y2 = DoubleArray(channels)
    private val u1 = DoubleArray(channels); private val u2 = DoubleArray(channels)
    private val z1 = DoubleArray(channels); private val z2 = DoubleArray(channels)

    private val step = ImportMath.RATE / 10
    private var stepSum = 0.0
    private var stepFrames = 0
    private var steps = DoubleArray(256)
    private var stepCount = 0
    private var allSum = 0.0
    private var allFrames = 0L

    fun add(x: FloatArray, frames: Int) {
        for (f in 0 until frames) {
            var p = 0.0
            for (c in 0 until channels) {
                val xin = x[f * channels + c].toDouble()
                val y = sb0 * xin + sb1 * x1[c] + sb2 * x2[c] - sa1 * y1[c] - sa2 * y2[c]
                x2[c] = x1[c]; x1[c] = xin; y2[c] = y1[c]; y1[c] = y
                val z = y - 2 * u1[c] + u2[c] - ha1 * z1[c] - ha2 * z2[c]
                u2[c] = u1[c]; u1[c] = y; z2[c] = z1[c]; z1[c] = z
                p += z * z
            }
            stepSum += p
            allSum += p
            allFrames++
            if (++stepFrames == step) {
                if (stepCount == steps.size) steps = steps.copyOf(steps.size * 2)
                steps[stepCount++] = stepSum
                stepSum = 0.0
                stepFrames = 0
            }
        }
    }

    /** Integrated loudness so far, LUFS; a sound shorter than one block is measured whole. -200 for silence. */
    fun integratedLufs(): Double {
        val blocks = (stepCount - 3).coerceAtLeast(0)
        if (blocks == 0) return lufs(if (allFrames > 0) allSum / allFrames else 0.0)
        val power = DoubleArray(blocks) { b -> (steps[b] + steps[b + 1] + steps[b + 2] + steps[b + 3]) / (4.0 * step) }
        var sum = 0.0; var n = 0
        for (p in power) if (lufs(p) > -70) { sum += p; n++ }
        if (n == 0) return -200.0
        val rel = lufs(sum / n) - 10
        sum = 0.0; n = 0
        for (p in power) if (lufs(p) > -70 && lufs(p) > rel) { sum += p; n++ }
        return if (n == 0) -200.0 else lufs(sum / n)
    }

    private fun lufs(ms: Double) = if (ms <= 0) -200.0 else -0.691 + 10 * log10(ms)
}
