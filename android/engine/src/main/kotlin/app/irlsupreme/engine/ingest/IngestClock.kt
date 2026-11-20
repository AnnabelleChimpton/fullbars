package app.irlsupreme.engine.ingest

/**
 * Maps the camera's clock (RTMP timestamps, ms) onto this phone's monotonic clock (System.nanoTime), plus a jitter
 * allowance, so video and audio come out at a steady pace and in sync whatever the Wi-Fi does. A classic playout
 * buffer:
 *
 *  - **Warm-up.** A camera's delay is unsettled for several seconds after it starts streaming. A real Pocket 3 over
 *    the phone's hotspot: its delay rose by ~0.5-1 s over the first ~6 s, then dropped by ~2.5 s as it caught up.
 *    Anchoring on any single frame in that window either re-anchored repeatedly or left seconds of needless delay.
 *    So for [WARMUP_NS] frames are shown as they arrive (the caller's [onFrame] returns "now"), audio isn't used
 *    ([toLocalNs] is null), and the clock records the *lowest* delay seen late in the warm-up: the camera's
 *    baseline once it has caught up.
 *  - **Anchor** at that baseline plus [jitterMs].
 *  - **Track** the tightest slack (how early the earliest-due frame arrived) over the last ~10 s and keep it between
 *    [MIN_SPARE_NS] and [MAX_SPARE_NS]: up 10 ms (20 ms when frames were late), down 5 ms. Each step leaves a hole or overlap of that size in the
 *    camera audio's timeline, which [app.irlsupreme.engine.audio.CameraAudio] bridges. This also absorbs camera-
 *    vs-phone clock drift. Only a jump of seconds re-anchors.
 *
 * Thread-safe: video writes, audio reads.
 */
class IngestClock(private val jitterMs: Long = DEFAULT_JITTER_MS) {
    @Volatile private var anchored = false
    @Volatile private var baseLocalNs = 0L
    @Volatile private var baseCameraMs = 0L
    private var firstFrameNs = 0L
    private var minOffsetNs = Long.MAX_VALUE // lowest (arrival - camera time) seen late in the warm-up
    private var windowMinSlackNs = Long.MAX_VALUE
    private var windowFrames = 0
    private val recentWindowMins = ArrayDeque<Long>() // last HORIZON_WINDOWS windows' tightest slack

    @Volatile var reanchors = 0
        private set
    /** Diagnostics: the slack (ms) that caused the last re-anchor, and each window's tightest slack (ms). */
    @Volatile var lastReanchorSlackMs = 0L
        private set
    var onWindow: ((minSlackMs: Long) -> Unit)? = null

    val isAnchored: Boolean get() = anchored

    /**
     * Local monotonic time (ns) at which camera time [cameraMs] should be presented, or null before the clock has
     * anchored (only video anchors it; camera audio waits for that).
     */
    fun toLocalNs(cameraMs: Long): Long? {
        if (!anchored) return null
        return baseLocalNs + (cameraMs - baseCameraMs) * 1_000_000L
    }

    /** Forget the mapping (new camera session). */
    @Synchronized
    fun reset() {
        anchored = false
        firstFrameNs = 0L
        minOffsetNs = Long.MAX_VALUE
    }

    /**
     * Video path: a frame for [cameraMs] arrived at [nowNs]. Returns when to present it (local ns): "now" during the
     * warm-up, then on the anchored schedule.
     */
    @Synchronized
    fun onFrame(cameraMs: Long, nowNs: Long): Long {
        if (!anchored) {
            if (firstFrameNs == 0L) firstFrameNs = nowNs
            val inWarmup = nowNs - firstFrameNs
            if (inWarmup >= WARMUP_NS - BASELINE_WINDOW_NS) {
                val offset = nowNs - cameraMs * 1_000_000L
                if (offset < minOffsetNs) minOffsetNs = offset
            }
            if (inWarmup < WARMUP_NS) return nowNs
            // Anchor so a frame arriving at the baseline delay has [jitterMs] to spare.
            baseCameraMs = cameraMs
            baseLocalNs = cameraMs * 1_000_000L + minOffsetNs + jitterMs * 1_000_000L
            anchored = true
            windowMinSlackNs = Long.MAX_VALUE
            windowFrames = 0
            recentWindowMins.clear()
        }
        val due = baseLocalNs + (cameraMs - baseCameraMs) * 1_000_000L
        val slack = due - nowNs
        if (slack < -LATE_REANCHOR_NS || slack > EARLY_REANCHOR_NS) {
            lastReanchorSlackMs = slack / 1_000_000
            baseCameraMs = cameraMs
            baseLocalNs = nowNs + jitterMs * 1_000_000L
            reanchors++
            windowMinSlackNs = Long.MAX_VALUE
            windowFrames = 0
            return baseLocalNs
        }
        if (slack < windowMinSlackNs) windowMinSlackNs = slack
        if (++windowFrames >= WINDOW_FRAMES) {
            onWindow?.invoke(windowMinSlackNs / 1_000_000)
            // Decide on the tightest slack over ~10 s, not this window alone: Wi-Fi to a Pocket 3 spikes by ~0.5 s
            // every ~5-6 s, and judging quiet windows on their own shrank the delay until each spike landed late.
            recentWindowMins.addLast(windowMinSlackNs)
            while (recentWindowMins.size > HORIZON_WINDOWS) recentWindowMins.removeFirst()
            val tightest = recentWindowMins.min()
            when {
                tightest < 0 -> baseLocalNs += STEP_UP_LATE_NS // frames were late: more delay, quickly
                tightest < MIN_SPARE_NS -> baseLocalNs += STEP_NS
                tightest > MAX_SPARE_NS && recentWindowMins.size == HORIZON_WINDOWS -> baseLocalNs -= STEP_DOWN_NS
            }
            windowMinSlackNs = Long.MAX_VALUE
            windowFrames = 0
        }
        return due
    }

    companion object {
        /** Wi-Fi jitter allowance above the camera's baseline delay (live IRL streams are seconds behind anyway). */
        const val DEFAULT_JITTER_MS = 600L
        const val WARMUP_NS = 8_000_000_000L
        /** The baseline is the lowest delay in the last part of the warm-up, after the camera has caught up. */
        private const val BASELINE_WINDOW_NS = 3_000_000_000L
        private const val WINDOW_FRAMES = 30
        private const val STEP_NS = 10_000_000L
        private const val STEP_UP_LATE_NS = 20_000_000L
        private const val STEP_DOWN_NS = 5_000_000L
        private const val HORIZON_WINDOWS = 10
        private const val MIN_SPARE_NS = 100_000_000L
        private const val MAX_SPARE_NS = 300_000_000L
        private const val LATE_REANCHOR_NS = 1_000_000_000L
        private const val EARLY_REANCHOR_NS = 3_000_000_000L
    }
}
