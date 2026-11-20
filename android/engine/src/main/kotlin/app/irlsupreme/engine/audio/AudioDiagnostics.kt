package app.irlsupreme.engine.audio

import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The audio path's finer health, for the session log: what the counters in [app.irlsupreme.engine.AudioCounters]
 * don't say. Counts go up for the whole stream; the rest are the latest values.
 */
data class AudioDiagnostics(
    /** Chunks whose voice arrived clipped (in the phone or camera, before Fullbars' gain), and the clipped samples. */
    val inputClipChunks: Long = 0,
    val inputClips: Long = 0,
    /** Samples of the final mix at full scale; 0 unless something is wrong (the limiter covers chimes too). */
    val postMixClips: Long = 0,
    /** Chunks of silence encoded to close gaps in the mic's timeline. */
    val gapFillChunks: Int = 0,
    /** Chunks timed without an AudioRecord timestamp. */
    val fallbackChunks: Long = 0,
    /** How far chunk timestamps landed from where they were expected: <0.5, <1, <2, <5, >=5 ms. */
    val ptsJitter: List<Long> = emptyList(),
    /** The voice leveler's gain now (dB) and the lip-sync delay applied (ns). */
    val levelerDb: Float = 0f,
    val avDelayNs: Long = 0,
    /** The phone mic's channels over the first seconds of the session (null until measured, or for mono). */
    val stereo: StereoStats? = null,
    /** The AAC encoder in use, its output format, and its priming delay in frames (-1: it didn't say). */
    val encoderName: String? = null,
    val encoderFormat: String? = null,
    val encoderDelayFrames: Int = -1,
    /** The mic the recording is really using. */
    val activeInput: ActiveInput? = null,
) {
    /** One line for the session log. */
    fun line(): String = buildString {
        append(String.format(Locale.ROOT, "audio in-clip %d/%d post-clip %d gap-fill %d fallback %d jitter %s leveler %.1fdB av %dms",
            inputClipChunks, inputClips, postMixClips, gapFillChunks, fallbackChunks, ptsJitter.joinToString("/"), levelerDb, avDelayNs / 1_000_000))
        stereo?.let { append(String.format(Locale.ROOT, " L %.1f R %.1f corr %.2f", it.rmsLDb, it.rmsRDb, it.correlation)) }
        encoderName?.let { append(" enc $it") }
        if (encoderDelayFrames >= 0) append(" delay $encoderDelayFrames")
        activeInput?.let { append(" input ${it.name} (${inputTypeName(it.type)})") }
    }
}

/** Per-channel level and how alike the two channels are: whether a stereo phone mic is really one voice twice. */
data class StereoStats(val rmsLDb: Float, val rmsRDb: Float, val correlation: Float)

/**
 * Measures a stereo mic's two channels over the first [seconds] it hears (pure, unit-tested): each one's RMS and their
 * correlation. A big level difference or a low correlation means the phone's two mics hear the voice differently (one
 * covered by a hand, or facing away), which a mono fold would fix. Only measures; changes nothing.
 */
internal class StereoProbe(private val sampleRate: Int, private val seconds: Int = 10) {
    private var n = 0L
    private var sl = 0.0
    private var sr = 0.0
    private var sll = 0.0
    private var srr = 0.0
    private var slr = 0.0

    /** Done measuring. */
    val done: Boolean get() = n >= seconds.toLong() * sampleRate

    /** Adds [frames] stereo frames of 16-bit PCM, until [seconds] are in. */
    @Synchronized
    fun add(pcm: ByteArray, frames: Int) {
        if (done) return
        for (f in 0 until frames) {
            val l = pcm16At(pcm, f * 4).toDouble()
            val r = pcm16At(pcm, f * 4 + 2).toDouble()
            sl += l; sr += r; sll += l * l; srr += r * r; slr += l * r
        }
        n += frames
    }

    /** The result so far, or null before anything was measured. Any thread. */
    @Synchronized
    fun stats(): StereoStats? {
        if (n == 0L) return null
        val vl = sll / n - (sl / n) * (sl / n)
        val vr = srr / n - (sr / n) * (sr / n)
        val cov = slr / n - (sl / n) * (sr / n)
        val corr = if (vl <= 0 || vr <= 0) 0.0 else cov / sqrt(vl * vr)
        fun db(ms: Double) = if (ms <= 0) MicLevel.FLOOR_DB else (10 * log10(ms) - 20 * log10(32768.0)).toFloat().coerceAtLeast(MicLevel.FLOOR_DB)
        return StereoStats(db(sll / n), db(srr / n), corr.toFloat())
    }

    @Synchronized
    fun reset() { n = 0; sl = 0.0; sr = 0.0; sll = 0.0; srr = 0.0; slr = 0.0 }
}
