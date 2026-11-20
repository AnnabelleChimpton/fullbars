package app.irlsupreme.engine.audio

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The camera's decoded audio as one continuous stream (pure, unit-tested): a FIFO that the decoder fills in bursts,
 * whenever Wi-Fi delivers, and [AudioPipeline] empties at exactly one chunk per mic chunk.
 *
 * Why not place each decoded block at its own timestamp (the old way): RTMP timestamps are whole milliseconds and an
 * AAC frame is 21.33 ms, and [app.irlsupreme.engine.ingest.IngestClock] steps its mapping by 5-20 ms to follow the
 * Wi-Fi. Every block boundary then became a small overlap (samples skipped) or hole (a sample held), each one a click,
 * and over a jittery feed that was the crackle viewers heard. Here the samples are only ever played in order:
 *
 *  - **Lip sync.** The IngestClock maps camera time to phone time for the video; [pull] is told which camera moment
 *    belongs at the start of each chunk and aligns to it once, by timestamp. After that it plays continuously, and the
 *    sync error (where it is reading vs where the clock says it should be) steers a tiny resample, at most
 *    [MAX_RATE_DEVIATION] (10 cents, which an ear doesn't notice gliding under a voice). That one control absorbs the camera's
 *    clock drift, the mic's clock drift and the IngestClock's small steps, without ever dropping or repeating a sample.
 *    Only an error past [RESYNC_NS] (the clock re-anchored, the camera jumped its timeline) aligns again.
 *  - **Depth.** The jitter allowance is the IngestClock's own (it keeps 100-300 ms spare on the video's worst arrivals,
 *    and the audio travels with the video), measured here in camera time so a burst of arrivals doesn't move it. If
 *    the audio still runs dry, the playout lags the picture by up to [MAX_EXTRA_LAG_NS] more, and gives that back
 *    slowly once arrivals have [LOW_WATER_NS] to spare: a voice a little behind the lips beats a voice that breaks up.
 *  - **Dropouts.** Running dry fades out over [FADE_FRAMES] instead of stopping dead (a hard zero is a click, a held
 *    sample is a click), plays silence, then aligns again and fades back in once there's [RESUME_NS] in hand. A missing
 *    AAC frame (a timestamp gap) is filled with faded silence of the same length, so sync doesn't slip.
 *
 * Not tied to Android: the caller converts to the stream's rate and channels and supplies the clock. Thread-safe: the
 * decoder thread pushes, the capture thread pulls.
 */
internal class CameraPlayout(
    private val rate: Int,
    private val channels: Int,
    capacityFrames: Int = rate * 4,
) {
    private val cap = capacityFrames
    private val buf = FloatArray(cap * channels)
    private val frameNs = 1_000_000_000.0 / rate

    /** Absolute frame indexes: [head] is the oldest kept, [tail] one past the newest. */
    private var head = 0L
    private var tail = 0L
    /** Where the next output frame is read, in absolute frames (fractional while resampling). */
    private var readPos = 0.0
    /** Camera time (ns) of frame [tail], i.e. just after the newest sample. */
    private var tailCamNs = 0L
    private var hasData = false
    private var playing = false
    private var errAvgNs = 0.0
    private var extraLagNs = 0L
    private var windowMinDepthNs = Long.MAX_VALUE
    private var windowPulls = 0
    /** Frames into the current fade-in; [FADE_FRAMES] when there's none. */
    private var fadeInPos = FADE_FRAMES

    /** Times the audio ran dry while playing (each one a short faded silence). */
    @Volatile var underruns = 0
        private set
    /** Gaps in the camera's own timeline (a lost AAC frame) filled with faded silence, and how much in total. */
    @Volatile var gapFills = 0
        private set
    @Volatile var gapFillNs = 0L
        private set
    /** Times it aligned again because the clock or the camera's timeline jumped, or it fell 4 s behind. */
    @Volatile var resyncs = 0
        private set
    /** Audio in hand ahead of the read position at the last pull, and the resample rate in ppm (+: playing faster). */
    @Volatile var depthNs = 0L
        private set
    @Volatile var ratePpm = 0
        private set
    /** Where it reads vs the picture (ns, +: audio ahead, -: behind, the extra lag included), smoothed. */
    @Volatile var syncErrorNs = 0L
        private set
    /** Extra lag behind the picture taken on to stop repeated dropouts (ns). */
    val extraLag: Long @Synchronized get() = extraLagNs

    /** A new camera session: forget everything but the counters. */
    @Synchronized
    fun reset() {
        head = 0; tail = 0; readPos = 0.0; tailCamNs = 0
        hasData = false; playing = false; errAvgNs = 0.0; extraLagNs = 0; fadeInPos = FADE_FRAMES
        windowMinDepthNs = Long.MAX_VALUE; windowPulls = 0
        depthNs = 0; ratePpm = 0; syncErrorNs = 0
    }

    /** Decoded audio in the stream's format (interleaved), whose first sample is at camera time [camNs]. */
    fun push(camNs: Long, pcm: ShortArray): Unit = push(camNs, pcm, pcm.size / channels)

    /** The first [count] frames of [pcm] (a reused buffer may hold more). */
    @Synchronized
    fun push(camNs: Long, pcm: ShortArray, count: Int) {
        var frames = count.coerceAtMost(pcm.size / channels)
        if (frames == 0) return
        var from = 0
        var t = camNs
        if (hasData) {
            val d = camNs - tailCamNs
            when {
                // Contiguous: the timestamps are whole ms against 21.33 ms frames, so they wobble; the samples don't.
                abs(d) <= JOIN_NS -> {}
                d > 0 && d <= MAX_FILL_NS -> {
                    // Lost frames: the same length of silence, faded at both edges, keeps the timeline (and sync).
                    val gap = (d / frameNs).roundToInt()
                    fadeOutTail()
                    appendSilence(gap)
                    gapFills++
                    gapFillNs += d
                    t = camNs
                }
                d < 0 && d >= -MAX_FILL_NS -> {
                    // Overlap (frames sent twice): keep what's already queued and drop the repeat.
                    val skip = (-d / frameNs).roundToInt()
                    if (skip >= frames) return
                    from = skip; frames -= skip
                    t = camNs + (skip * frameNs).toLong()
                    fadeOutTail()
                }
                // A different timeline: append it and relabel; the next pull sees the jump and aligns again.
                else -> fadeOutTail()
            }
        }
        val spliced = hasData && abs(camNs - tailCamNs) > JOIN_NS
        val start = tail
        append(pcm, from, frames)
        if (spliced) fadeIn(start)
        hasData = true
        tailCamNs = t + (frames * frameNs).toLong()
    }

    /**
     * Fills [out] ([frames] interleaved frames) with the camera audio that belongs at camera time [targetCamNs] onwards
     * (null: the clock has no mapping yet, so nothing plays), silence where there is none. Returns how many frames
     * came from the camera, so the caller can tell a camera that's there from one that isn't.
     */
    @Synchronized
    fun pull(out: ShortArray, frames: Int, targetCamNs: Long?): Int {
        out.fill(0, 0, frames * channels)
        if (targetCamNs == null || !hasData) {
            playing = false
            if (hasData) dropBefore(tail - rate) // bounded while the clock warms up: keep the last second
            return 0
        }
        val target = targetCamNs - extraLagNs
        if (playing) {
            val err = readCamNs() - target
            if (abs(err) > RESYNC_NS) {
                // The clock or the camera's timeline jumped: fade out what's here and align again from silence.
                val n = render(out, 0, minOf(frames, FADE_FRAMES, canRender(1.0)), 1.0, fadeOut = true)
                playing = false
                resyncs++
                return n
            }
            errAvgNs += (err - errAvgNs) * ERR_SMOOTHING
            val ratio = 1.0 - (GAIN_PER_S * errAvgNs / 1e9).coerceIn(-MAX_RATE_DEVIATION, MAX_RATE_DEVIATION)
            ratePpm = ((ratio - 1.0) * 1e6).roundToInt()
            syncErrorNs = errAvgNs.toLong() - extraLagNs
            val can = canRender(ratio)
            if (can >= frames + FADE_FRAMES) {
                render(out, 0, frames, ratio, fadeOut = false)
                trackDepth()
                return frames
            }
            // Running dry: play what's left, fading out, and wait for enough to come back.
            val n = render(out, 0, minOf(frames, can), ratio, fadeOut = true)
            playing = false
            underruns++
            extraLagNs = (extraLagNs + LAG_STEP_UP_NS).coerceAtMost(MAX_EXTRA_LAG_NS)
            return n
        }
        // Waiting: find the sample that belongs at the start of this chunk.
        val idx = tail - (tailCamNs - target) / frameNs
        val startIdx = maxOf(idx, head.toDouble())
        val lead = ((startIdx - idx).coerceAtLeast(0.0)).toInt() // the camera's audio starts later than this chunk
        if (lead >= frames || (tail - startIdx) * frameNs < (frames - lead) * frameNs + RESUME_NS) {
            dropBefore(floor(idx).toLong() - 1) // anything older than the target can never play
            return 0
        }
        readPos = startIdx
        errAvgNs = 0.0
        fadeInPos = 0
        val n = render(out, lead, frames - lead, 1.0, fadeOut = false)
        playing = true
        trackDepth()
        return n
    }

    /** Camera time (ns) of the read position. */
    private fun readCamNs(): Long = tailCamNs - ((tail - readPos) * frameNs).toLong()

    /** Output frames that can be rendered at [ratio] with real samples on both sides of every interpolation. */
    private fun canRender(ratio: Double): Int {
        val room = tail - 2 - readPos
        return if (room <= 0) 0 else (room / ratio).toInt()
    }

    /**
     * Renders [n] frames into [out] from frame [at] on, advancing [readPos] by [ratio] per frame (4-point Hermite
     * interpolation: flat to well past 10 kHz, unlike linear, so a moving fraction doesn't wobble the highs).
     */
    private fun render(out: ShortArray, at: Int, n: Int, ratio: Double, fadeOut: Boolean): Int {
        val outLen = minOf(n, FADE_FRAMES)
        for (j in 0 until n) {
            val pos = readPos
            val i = floor(pos).toLong()
            val t = (pos - i).toFloat()
            var g = 1f
            // A fade-in carries on into the next pull when this one only had room for its start.
            if (fadeInPos < FADE_FRAMES) g = (++fadeInPos).toFloat() / (FADE_FRAMES + 1)
            if (fadeOut) {
                val left = n - j // this frame and the ones after it
                if (left <= outLen) g = minOf(g, (left - 1).toFloat() / outLen)
            }
            for (ch in 0 until channels) {
                val v = hermite(sample(i - 1, ch), sample(i, ch), sample(i + 1, ch), sample(i + 2, ch), t) * g
                out[(at + j) * channels + ch] = (v * 32768f).roundToInt().coerceIn(-32768, 32767).toShort()
            }
            readPos += ratio
        }
        dropBefore(floor(readPos).toLong() - 1)
        depthNs = ((tail - readPos) * frameNs).toLong().coerceAtLeast(0)
        return n
    }

    /** Every ~10 s: if arrivals always had room to spare, give back some of the extra lag (it costs lip sync). */
    private fun trackDepth() {
        val depth = depthNs - FADE_FRAMES * frameNs.toLong()
        if (depth < windowMinDepthNs) windowMinDepthNs = depth
        if (++windowPulls >= DEPTH_WINDOW_PULLS) {
            if (extraLagNs > 0 && windowMinDepthNs > LOW_WATER_NS + LAG_STEP_DOWN_NS) {
                extraLagNs = (extraLagNs - LAG_STEP_DOWN_NS).coerceAtLeast(0)
            }
            windowMinDepthNs = Long.MAX_VALUE
            windowPulls = 0
        }
    }

    private fun sample(k: Long, ch: Int): Float {
        val c = k.coerceIn(head, tail - 1)
        return buf[(c % cap).toInt() * channels + ch]
    }

    private fun append(pcm: ShortArray, fromFrame: Int, frames: Int) {
        makeRoom(frames)
        for (f in 0 until frames) {
            val slot = ((tail + f) % cap).toInt() * channels
            for (ch in 0 until channels) buf[slot + ch] = pcm[(fromFrame + f) * channels + ch] / 32768f
        }
        tail += frames
    }

    private fun appendSilence(frames: Int) {
        val n = frames.coerceAtMost(cap / 2)
        makeRoom(n)
        for (f in 0 until n) {
            val slot = ((tail + f) % cap).toInt() * channels
            for (ch in 0 until channels) buf[slot + ch] = 0f
        }
        tail += n
    }

    /** Never more than [cap]: the oldest goes, and a reader that lost its place aligns again. */
    private fun makeRoom(frames: Int) {
        val over = tail + frames - head - cap
        if (over <= 0) return
        head += over
        if (playing && readPos < head + 1) { playing = false; resyncs++ }
    }

    private fun dropBefore(k: Long) {
        if (k > head) head = minOf(k, tail)
        if (readPos < head) readPos = head.toDouble()
    }

    /** Fades the newest unread [FADE_FRAMES] to zero before a splice (what's already been played can't change). */
    private fun fadeOutTail() {
        val firstUnread = maxOf(head, floor(readPos).toLong() + 2)
        val n = minOf(FADE_FRAMES.toLong(), tail - firstUnread).toInt()
        if (n <= 0) return
        for (j in 0 until n) {
            val k = tail - n + j
            val g = (n - 1 - j).toFloat() / n
            val slot = (k % cap).toInt() * channels
            for (ch in 0 until channels) buf[slot + ch] *= g
        }
    }

    private fun fadeIn(from: Long) {
        val n = minOf(FADE_FRAMES.toLong(), tail - from).toInt()
        for (j in 0 until n) {
            val g = (j + 1).toFloat() / (n + 1)
            val slot = ((from + j) % cap).toInt() * channels
            for (ch in 0 until channels) buf[slot + ch] *= g
        }
    }

    companion object {
        /** ~5 ms at 48 kHz: long enough not to click, short enough to sound like a gap rather than a fade. */
        const val FADE_FRAMES = 240
        /** Timestamp differences up to this are the ms rounding of 21.33 ms AAC frames, not a gap. */
        const val JOIN_NS = 12_000_000L
        /** Gaps or overlaps in the camera's timeline up to this are patched; beyond, it's a new timeline. */
        const val MAX_FILL_NS = 500_000_000L
        /** Off by more than this, align again rather than steer (the clock re-anchored, or a long stall). */
        const val RESYNC_NS = 200_000_000L
        /** In hand beyond the chunk before playing again after running dry, so it doesn't run dry again at once. */
        const val RESUME_NS = 40_000_000L
        /**
         * 0.6%: the most the resample may speed up or slow down (10 cents). The IngestClock walks its delay down by up
         * to 5 ms a second (0.5%) after a camera connects, so the limit sits just above that.
         */
        const val MAX_RATE_DEVIATION = 0.006
        /** Rate change per second of sync error: 20 ms off is the full 0.5%; a ~4 s loop, steady within a ms. */
        const val GAIN_PER_S = 0.25
        /** Smoothing of the sync error per pull (~0.4 s), so timestamp wobble doesn't wobble the rate. */
        const val ERR_SMOOTHING = 0.05
        const val MAX_EXTRA_LAG_NS = 150_000_000L
        const val LAG_STEP_UP_NS = 30_000_000L
        const val LAG_STEP_DOWN_NS = 10_000_000L
        const val LOW_WATER_NS = 40_000_000L
        /** ~10 s of 1024-frame pulls at 48 kHz. */
        const val DEPTH_WINDOW_PULLS = 470

        fun hermite(xm1: Float, x0: Float, x1: Float, x2: Float, t: Float): Float {
            val c1 = 0.5f * (x1 - xm1)
            val c2 = xm1 - 2.5f * x0 + 2f * x1 - 0.5f * x2
            val c3 = 0.5f * (x2 - xm1) + 1.5f * (x0 - x1)
            return ((c3 * t + c2) * t + c1) * t + x0
        }
    }
}
