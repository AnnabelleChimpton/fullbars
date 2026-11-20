package app.irlsupreme.engine.audio

import kotlin.math.log10
import kotlin.math.pow

/**
 * A slow voice leveler for the raw mic: it rides the level the way a sound engineer would, so a voice that moves from
 * arm's length to a shout across the street, or a streamer who turns away, stays about as loud for viewers. A fixed
 * fader can't fit IRL distances; the limiter only catches peaks.
 *
 * How: it measures the last ~400 ms of the voice the way a loudness meter does (BS.1770 K-weighting, so "loud" means
 * what the ear hears, not bass energy), and moves a gain towards the one that puts that at [TARGET_LUFS], by at most
 * [MAX_BOOST_DB] up or [MAX_CUT_DB] down. It moves slowly ([RISE_DB_S] up, [FALL_DB_S] down, in dB so a step is
 * equally gentle at any level), so it follows a voice's level, never its syllables, and needs no lookahead (no added
 * latency).
 *
 * What it must not do is turn the room up between sentences. So it also keeps the quiet end of the recent short-chunk
 * levels (a low percentile over [FLOOR_WINDOW_S]): with someone talking, that's the pauses between words, well under
 * the 400 ms level; with only steady noise, it's about the same as the 400 ms level. The gain freezes while the 400 ms
 * level is within [FREEZE_ABOVE_FLOOR_DB] of that floor, so steady noise, a long pause or an empty street never gets
 * boosted.
 *
 * Feed-forward: the measurement is taken before the leveler's own gain, so the gain can't chase itself. Not
 * thread-safe; the capture thread owns it. No allocation after construction.
 */
internal class VoiceLeveler(private val sampleRate: Int, private val channels: Int) {
    private val kw = Biquad.kWeighting(sampleRate, channels)

    /** The gain now, dB (0 = untouched). Read by the stats from other threads. */
    @Volatile var gainDb = 0f
        private set

    /** Per-chunk K-weighted energy (sum of squares over channels) and frame counts, newest at [blockPos - 1]. */
    private val blockEnergy = DoubleArray(BLOCKS)
    private val blockFrames = IntArray(BLOCKS)
    private var blockPos = 0

    /** Histogram of short-chunk levels over the last [FLOOR_WINDOW_S], in 1 dB bins from [HIST_FLOOR_DB] to 0. */
    private val hist = IntArray(-HIST_FLOOR_DB.toInt() + 1)
    private val histRing = IntArray(HIST_RING)
    private var histPos = 0
    private var histCount = 0

    /** The loudness the gain last aimed at (LUFS), and the floor (dB): for tests and the session log. */
    var loudnessLufs = HIST_FLOOR_DB
        private set
    var floorDb = HIST_FLOOR_DB
        private set
    /** Whether the last chunk held the gain because it was too close to the noise floor. */
    var frozen = true
        private set

    /** Whether the last chunk was levelled: switching off forgets what was measured. */
    private var wasEnabled = false

    /** Back to untouched: a new session, or a new mic (whose level has nothing to do with the last one's). */
    fun reset() {
        gainDb = 0f
        kw.first.reset(); kw.second.reset()
        forget()
    }

    /** Drops the loudness and noise-floor history, so a later start measures fresh audio, not minutes-old audio. */
    private fun forget() {
        blockEnergy.fill(0.0); blockFrames.fill(0); blockPos = 0
        hist.fill(0); histRing.fill(0); histPos = 0; histCount = 0
        loudnessLufs = HIST_FLOOR_DB; floorDb = HIST_FLOOR_DB; frozen = true
    }

    /**
     * Levels [frames] interleaved frames of [x] (float, 1.0 = full scale) in place. With [enabled] false it measures
     * nothing and glides any gain it had back to 0 dB, so switching it off doesn't jump either.
     *
     * A chunk of digital silence ([DIGITAL_SILENCE_LUFS]), such as the silence that fills a gap in the timeline,
     * holds the gain and isn't measured: counted, it would drag the noise floor down and unfreeze the gain on the
     * room's own noise.
     */
    fun process(x: FloatArray, frames: Int, enabled: Boolean) {
        val from = gainDb
        val chunkS = frames.toFloat() / sampleRate
        if (wasEnabled && !enabled) forget()
        wasEnabled = enabled
        if (enabled) {
            var e = 0.0
            for (f in 0 until frames) {
                for (ch in 0 until channels) {
                    val k = kw.second.process(kw.first.process(x[f * channels + ch].toDouble(), ch), ch)
                    e += k * k
                }
            }
            val chunkDb = lufs(e, frames)
            if (chunkDb <= DIGITAL_SILENCE_LUFS) {
                frozen = true
            } else {
                blockEnergy[blockPos] = e
                blockFrames[blockPos] = frames
                blockPos = (blockPos + 1) % BLOCKS
                loudnessLufs = shortTermLufs()
                pushFloor(chunkDb, frames)
                floorDb = lowPercentileDb()
                frozen = loudnessLufs < floorDb + FREEZE_ABOVE_FLOOR_DB || loudnessLufs <= SILENCE_LUFS
            }
            if (!frozen) {
                val want = (TARGET_LUFS - loudnessLufs).coerceIn(-MAX_CUT_DB, MAX_BOOST_DB)
                gainDb = if (want > gainDb) minOf(want, gainDb + RISE_DB_S * chunkS) else maxOf(want, gainDb - FALL_DB_S * chunkS)
            }
        } else if (gainDb != 0f) {
            gainDb = if (gainDb > 0f) maxOf(0f, gainDb - FALL_DB_S * chunkS) else minOf(0f, gainDb + RISE_DB_S * chunkS)
        }
        if (from == 0f && gainDb == 0f) return
        // A straight line from the last chunk's gain to this one's across the chunk: no step at the boundary.
        val g0 = 10f.pow(from / 20f)
        val g1 = 10f.pow(gainDb / 20f)
        val step = (g1 - g0) / frames
        var g = g0
        for (f in 0 until frames) {
            g += step
            for (ch in 0 until channels) x[f * channels + ch] *= g
        }
    }

    /** Loudness of the newest blocks covering at least 400 ms. */
    private fun shortTermLufs(): Float {
        val need = (WINDOW_S * sampleRate).toInt()
        var e = 0.0
        var n = 0
        var i = blockPos
        for (k in 0 until BLOCKS) {
            i = if (i == 0) BLOCKS - 1 else i - 1
            if (blockFrames[i] == 0) break
            e += blockEnergy[i]; n += blockFrames[i]
            if (n >= need) break
        }
        return lufs(e, n)
    }

    /** BS.1770: -0.691 + 10 log10(sum over channels of each channel's mean square). */
    private fun lufs(energy: Double, frames: Int): Float {
        if (frames <= 0 || energy <= 0.0) return HIST_FLOOR_DB
        return (-0.691 + 10 * log10(energy / frames)).toFloat().coerceAtLeast(HIST_FLOOR_DB)
    }

    private fun pushFloor(db: Float, frames: Int) {
        val bin = (db - HIST_FLOOR_DB).toInt().coerceIn(0, hist.size - 1)
        // Window by time: chunks are normally all the same size, so a ring of the last ~3 s of them.
        val window = (FLOOR_WINDOW_S * sampleRate / frames.coerceAtLeast(1)).toInt().coerceIn(1, HIST_RING)
        while (histCount >= window) {
            val old = histRing[(histPos - histCount + HIST_RING) % HIST_RING]
            hist[old]--
            histCount--
        }
        histRing[histPos] = bin
        histPos = (histPos + 1) % HIST_RING
        hist[bin]++
        histCount++
    }

    private fun lowPercentileDb(): Float {
        val want = (histCount * FLOOR_PERCENTILE).toInt().coerceAtLeast(1)
        var seen = 0
        for (b in hist.indices) {
            seen += hist[b]
            if (seen >= want) return HIST_FLOOR_DB + b
        }
        return 0f
    }

    companion object {
        const val TARGET_LUFS = -18f
        const val MAX_BOOST_DB = 12f
        const val MAX_CUT_DB = 10f
        const val RISE_DB_S = 3f
        const val FALL_DB_S = 6f
        const val WINDOW_S = 0.4f
        const val FLOOR_WINDOW_S = 3f
        const val FLOOR_PERCENTILE = 0.1f
        const val FREEZE_ABOVE_FLOOR_DB = 6f
        /** Below this nothing is worth levelling (a muted or dead input). */
        const val SILENCE_LUFS = -70f
        /**
         * A chunk this quiet is digital silence (gap fill, a muted input), not a quiet room: even a raw mic's own
         * noise sits well above it once its level is applied. Far under [SILENCE_LUFS], so a quiet room still counts.
         */
        const val DIGITAL_SILENCE_LUFS = -90f
        private const val HIST_FLOOR_DB = -100f
        /** Enough chunks for 400 ms at any chunk size down to 256 frames at 48 kHz. */
        private const val BLOCKS = 80
        /** Enough chunks for [FLOOR_WINDOW_S] at 1024-frame chunks, with room for smaller ones. */
        private const val HIST_RING = 600
    }
}
