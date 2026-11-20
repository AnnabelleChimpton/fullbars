package app.irlsupreme.engine.ingest

/**
 * Maps the camera's clock (RTMP timestamps, ms) onto this phone's monotonic clock (System.nanoTime), plus a jitter
 * allowance, so video and audio come out at a steady pace and in sync whatever the Wi-Fi does. A classic playout
 * buffer:
 *
 *  - **Warm-up.** A camera's delay is unsettled for several seconds after it starts streaming. A real Pocket 3 over
 *    the phone's hotspot: its delay rose by ~0.5-1 s over the first ~6 s, then dropped by ~2.5 s as it caught up.
 *    Anchoring on any single frame in that window either re-anchored repeatedly or left seconds of needless delay.
 *    So for [WARMUP_NS] each frame is shown [jitterMs] after it arrives, and the clock records the *lowest* delay seen
 *    late in the warm-up: the camera's baseline once it has caught up. Holding warm-up frames by the jitter allowance
 *    (rather than showing them on arrival) puts the start-up delay before the first frame is shown, while the card or
 *    the phone camera is still up, so anchoring is a small skip forward instead of a ~0.6 s freeze.
 *  - **Anchor** at that baseline plus [jitterMs].
 *  - **Track** the tightest slack (how early the earliest-due frame arrived) over the last ~10 s and keep it between
 *    [MIN_SPARE_NS] and [MAX_SPARE_NS]: up 10 ms (20 ms when frames were late), down 5 ms. Each step leaves a hole or overlap of that size in the
 *    camera audio's timeline, which [app.irlsupreme.engine.audio.CameraAudio] bridges. This also absorbs camera-
 *    vs-phone clock drift. Only a jump of seconds re-anchors.
 *
 * Camera audio can play during the warm-up on [toLocalNsProvisional], so a reconnect isn't 8 s of the phone mic.
 *
 * Thread-safe: video writes, audio and the engine read.
 */
class IngestClock(private val jitterMs: Long = DEFAULT_JITTER_MS) {
    private val jitterNs = jitterMs * 1_000_000L
    @Volatile private var anchored = false
    /**
     * The mapping: local ns = camera ms * 1e6 + [offsetNs] (slope 1). One field, so a reader on another thread never
     * sees half of a re-anchor.
     */
    @Volatile private var offsetNs = 0L
    /** The warm-up mapping for audio, in the same form; [NONE] until the first frame. */
    @Volatile private var provisionalOffsetNs = NONE
    private var firstFrameNs = 0L
    private var minOffsetNs = Long.MAX_VALUE // lowest (arrival - camera time) seen late in the warm-up
    private var lowestSoFarNs = Long.MAX_VALUE // lowest (arrival - camera time) seen in the whole warm-up
    private var lowestAtNs = 0L // when that lowest arrived
    private var provisionalBaseNs = NONE // the settled lowest delay behind the provisional mapping
    private var firstBaseNs = NONE // the first frame's delay
    private var windowMinSlackNs = Long.MAX_VALUE
    private var windowMinArrivalNs = Long.MAX_VALUE
    private var windowFrames = 0
    private val recentWindowMins = ArrayDeque<Long>() // last HORIZON_WINDOWS windows' tightest slack
    // Last HORIZON_WINDOWS windows' lowest arrival delay, for the baseline behind [presentationSpareNs].
    private val recentArrivalMins = LongArray(HORIZON_WINDOWS)
    private var arrivalMinsCount = 0
    private var arrivalMinsNext = 0

    @Volatile var reanchors = 0
        private set
    /** Diagnostics: the slack (ms) that caused the last re-anchor, and each window's tightest slack (ms). */
    @Volatile var lastReanchorSlackMs = 0L
        private set
    var onWindow: ((minSlackMs: Long) -> Unit)? = null

    val isAnchored: Boolean get() = anchored

    /** Frames have arrived but the clock hasn't anchored yet (frames are shown [jitterMs] after arrival). */
    val inWarmup: Boolean get() = !anchored && provisionalOffsetNs != NONE

    /**
     * How long (ns) the picture is held beyond the camera's baseline delay: the playout buffer's depth as presented.
     * Anchored, that's the mapping in use minus the lowest arrival delay of the last ~10 s; in the warm-up it's
     * [jitterMs] (frames are shown that long after they arrive); 0 before the first frame. Add [captureToArrivalNs]
     * for the camera's whole capture-to-screen delay ([micAlignDelayNs]).
     */
    @Volatile var presentationSpareNs = 0L
        private set

    /**
     * The camera's own delay from capture to arrival at its best (encode, Wi-Fi, RTMP), which this clock can't measure:
     * it only sees arrivals against camera timestamps. A per-camera calibration (a clap test) sets it; 0 until then.
     */
    @Volatile var captureToArrivalNs = 0L

    /**
     * How long the phone mic must be delayed to line up with the external camera's picture as it is drawn:
     * [presentationSpareNs] + [captureToArrivalNs]. Moves in small steps as the clock tracks the Wi-Fi, and jumps on
     * anchoring and re-anchoring; a delay line should slew towards it rather than jump.
     */
    val micAlignDelayNs: Long get() = presentationSpareNs + captureToArrivalNs

    /** Local time (System.nanoTime) of the last anchoring or re-anchoring; 0 before the first. */
    @Volatile var lastAnchorNs = 0L
        private set
    /** Warm-ups completed (one per camera connect that streamed long enough), and their total length (ns). */
    @Volatile var warmups = 0
        private set
    @Volatile var warmupTotalNs = 0L
        private set
    /** Times the video's compressed backlog was thrown away to catch up ([noteBacklogFlush]). */
    @Volatile var backlogFlushes = 0
        private set

    /**
     * Local monotonic time (ns) at which camera time [cameraMs] should be presented, or null before the clock has
     * anchored (only video anchors it).
     */
    fun toLocalNs(cameraMs: Long): Long? {
        if (!anchored) return null
        return cameraMs * 1_000_000L + offsetNs
    }

    /**
     * Like [toLocalNs], but during the warm-up gives a provisional mapping instead of null, so the camera's audio can
     * play from the start: the lowest arrival delay seen so far plus [jitterMs], plus [WARMUP_AUDIO_HEADROOM_NS] until
     * the camera has caught up. Null only before the first frame.
     *
     * Why the lowest and not the latest arrival: the latest moves with every frame's Wi-Fi delay, and the Pocket's
     * delay rises ~15% faster than real time for its first seconds, far more than the audio playout can follow by
     * resampling, so it would align again (a fade and a gap) every few hundred ms. The lowest only moves down, and
     * only once it has stopped moving for [SETTLE_NS], so the Pocket's catch-up (2.5 s of frames in a fraction of a
     * second) is one realignment rather than a stutter, and after it the mapping is the one anchoring will pick: the
     * same baseline plus the same allowance.
     *
     * Why the headroom: a mapping that only moves down can't follow the rise either, and the Pocket's delay rises by
     * 0.5-1 s before it catches up. The jitter allowance alone covered ~0.75 s of that; past it the audio ran dry until
     * the catch-up, seconds of the phone mic in the middle of the warm-up. The headroom covers a 1 s rise. It goes
     * once the lowest delay has dropped by more than the headroom (the camera caught up, so the rise is over); a
     * camera that never catches up keeps it until anchoring, which then moves the audio once, by about the headroom.
     */
    fun toLocalNsProvisional(cameraMs: Long): Long? {
        if (anchored) return cameraMs * 1_000_000L + offsetNs
        val p = provisionalOffsetNs
        return if (p == NONE) null else cameraMs * 1_000_000L + p
    }

    /** How long (ns) this connect has been warming up at [nowNs]; 0 when anchored or before the first frame. */
    @Synchronized
    fun warmupElapsedNs(nowNs: Long): Long = if (anchored || firstFrameNs == 0L) 0L else nowNs - firstFrameNs

    /**
     * The video path threw its compressed backlog away to catch up (it was seconds behind). Counted here so ingest
     * health reads from one place.
     */
    @Synchronized
    fun noteBacklogFlush() { backlogFlushes++ }

    /**
     * Whether a compressed video backlog from camera time [oldestMs] to [newestMs] is hopelessly behind: more than
     * [MAX_BACKLOG_BEYOND_ALLOWANCE_MS] of camera time beyond the jitter allowance. A bound in camera time rather than
     * a frame count, so it means the same at 60 fps as at 30: frames are held [jitterMs] after they arrive in the
     * warm-up, and a camera's catch-up burst (the Pocket's is ~2.5 s) lands on top of that. A count sized for 30 fps
     * would throw a 60 fps camera's catch-up away mid-warm-up and freeze the picture until the next keyframe.
     */
    fun backlogHopeless(oldestMs: Long, newestMs: Long): Boolean =
        newestMs - oldestMs > jitterMs + MAX_BACKLOG_BEYOND_ALLOWANCE_MS

    /** Everything worth showing about ingest health, read at [nowNs]. */
    fun health(nowNs: Long): IngestHealth = IngestHealth(
        anchored = anchored,
        warmupElapsedMs = warmupElapsedNs(nowNs) / 1_000_000,
        warmups = warmups,
        warmupTotalMs = warmupTotalNs / 1_000_000,
        sinceAnchorMs = lastAnchorNs.let { if (it == 0L) -1L else (nowNs - it) / 1_000_000 },
        reanchors = reanchors,
        spareMs = presentationSpareNs / 1_000_000,
        backlogFlushes = backlogFlushes,
    )

    /** Forget the mapping (new camera session). The counters carry on. */
    @Synchronized
    fun reset() {
        anchored = false
        provisionalOffsetNs = NONE
        presentationSpareNs = 0L
        firstFrameNs = 0L
        minOffsetNs = Long.MAX_VALUE
        lowestSoFarNs = Long.MAX_VALUE
        provisionalBaseNs = NONE
        firstBaseNs = NONE
        clearArrivalMins()
    }

    /**
     * Video path: a frame for [cameraMs] arrived at [nowNs]. Returns when to present it (local ns): [jitterMs] after
     * arrival during the warm-up, then on the anchored schedule.
     */
    @Synchronized
    fun onFrame(cameraMs: Long, nowNs: Long): Long {
        val arrivalNs = nowNs - cameraMs * 1_000_000L // this frame's delay, as far as the camera's clock can say
        if (!anchored) {
            if (firstFrameNs == 0L) firstFrameNs = nowNs
            val sinceFirstNs = nowNs - firstFrameNs
            if (sinceFirstNs >= WARMUP_NS - BASELINE_WINDOW_NS) {
                if (arrivalNs < minOffsetNs) minOffsetNs = arrivalNs
            }
            if (sinceFirstNs < WARMUP_NS) {
                trackProvisional(arrivalNs, nowNs)
                presentationSpareNs = jitterNs
                return nowNs + jitterNs
            }
            // Anchor so a frame arriving at the baseline delay has [jitterMs] to spare.
            offsetNs = minOffsetNs + jitterNs
            anchored = true
            warmups++
            warmupTotalNs += sinceFirstNs
            lastAnchorNs = nowNs
            windowMinSlackNs = Long.MAX_VALUE
            windowMinArrivalNs = Long.MAX_VALUE
            windowFrames = 0
            recentWindowMins.clear()
            clearArrivalMins()
            updateSpare()
        }
        val due = cameraMs * 1_000_000L + offsetNs
        val slack = due - nowNs
        if (slack < -LATE_REANCHOR_NS || slack > EARLY_REANCHOR_NS) {
            lastReanchorSlackMs = slack / 1_000_000
            offsetNs = arrivalNs + jitterNs
            reanchors++
            lastAnchorNs = nowNs
            // A new timeline (or a stall of seconds): the old baseline means nothing now. Start again from this frame.
            minOffsetNs = arrivalNs
            clearArrivalMins()
            windowMinSlackNs = Long.MAX_VALUE
            windowMinArrivalNs = Long.MAX_VALUE
            windowFrames = 0
            updateSpare()
            return nowNs + jitterNs
        }
        if (slack < windowMinSlackNs) windowMinSlackNs = slack
        if (arrivalNs < windowMinArrivalNs) windowMinArrivalNs = arrivalNs
        if (++windowFrames >= WINDOW_FRAMES) {
            onWindow?.invoke(windowMinSlackNs / 1_000_000)
            // Decide on the tightest slack over ~10 s, not this window alone: Wi-Fi to a Pocket 3 spikes by ~0.5 s
            // every ~5-6 s, and judging quiet windows on their own shrank the delay until each spike landed late.
            recentWindowMins.addLast(windowMinSlackNs)
            while (recentWindowMins.size > HORIZON_WINDOWS) recentWindowMins.removeFirst()
            val tightest = recentWindowMins.min()
            when {
                tightest < 0 -> offsetNs += STEP_UP_LATE_NS // frames were late: more delay, quickly
                tightest < MIN_SPARE_NS -> offsetNs += STEP_NS
                tightest > MAX_SPARE_NS && recentWindowMins.size == HORIZON_WINDOWS -> offsetNs -= STEP_DOWN_NS
            }
            recentArrivalMins[arrivalMinsNext] = windowMinArrivalNs
            arrivalMinsNext = (arrivalMinsNext + 1) % HORIZON_WINDOWS
            if (arrivalMinsCount < HORIZON_WINDOWS) arrivalMinsCount++
            updateSpare()
            windowMinSlackNs = Long.MAX_VALUE
            windowMinArrivalNs = Long.MAX_VALUE
            windowFrames = 0
        }
        return due
    }

    /**
     * Warm-up: the audio's mapping follows the lowest delay so far, once that has settled, with headroom until the
     * camera has caught up (see [toLocalNsProvisional]).
     */
    private fun trackProvisional(arrivalNs: Long, nowNs: Long) {
        if (arrivalNs < lowestSoFarNs) { lowestSoFarNs = arrivalNs; lowestAtNs = nowNs }
        if (provisionalBaseNs == NONE) {
            provisionalBaseNs = arrivalNs
            firstBaseNs = arrivalNs
        } else if (lowestSoFarNs < provisionalBaseNs && nowNs - lowestAtNs >= SETTLE_NS) {
            provisionalBaseNs = lowestSoFarNs
        }
        val caughtUp = provisionalBaseNs < firstBaseNs - WARMUP_AUDIO_HEADROOM_NS
        provisionalOffsetNs = provisionalBaseNs + jitterNs + if (caughtUp) 0L else WARMUP_AUDIO_HEADROOM_NS
    }

    private fun clearArrivalMins() { arrivalMinsCount = 0; arrivalMinsNext = 0 }

    /**
     * Anchored: spare = mapping minus baseline. The baseline is the lowest arrival delay over the last ~10 s (so camera
     * clock drift doesn't leave it stale), and no higher than the anchor's own until that much history exists.
     */
    private fun updateSpare() {
        var base = if (arrivalMinsCount < HORIZON_WINDOWS) minOffsetNs else Long.MAX_VALUE
        for (i in 0 until arrivalMinsCount) base = minOf(base, recentArrivalMins[i])
        presentationSpareNs = if (base == Long.MAX_VALUE) jitterNs else (offsetNs - base).coerceAtLeast(0L)
    }

    companion object {
        /** Wi-Fi jitter allowance above the camera's baseline delay (live IRL streams are seconds behind anyway). */
        const val DEFAULT_JITTER_MS = 600L
        const val WARMUP_NS = 8_000_000_000L
        /** The baseline is the lowest delay in the last part of the warm-up, after the camera has caught up. */
        private const val BASELINE_WINDOW_NS = 3_000_000_000L
        /** A lower warm-up delay moves the audio's provisional mapping only after it has held this long. */
        const val SETTLE_NS = 300_000_000L
        /**
         * Extra delay on the audio's provisional mapping until the camera catches up: with the jitter allowance (and
         * the playout's own 150 ms of extra lag) it covers a rise of 1 s, the most seen from a Pocket 3.
         */
        const val WARMUP_AUDIO_HEADROOM_NS = 500_000_000L
        /** See [backlogHopeless]. */
        const val MAX_BACKLOG_BEYOND_ALLOWANCE_MS = 4_000L
        private const val WINDOW_FRAMES = 30
        private const val STEP_NS = 10_000_000L
        private const val STEP_UP_LATE_NS = 20_000_000L
        private const val STEP_DOWN_NS = 5_000_000L
        private const val HORIZON_WINDOWS = 10
        private const val MIN_SPARE_NS = 100_000_000L
        private const val MAX_SPARE_NS = 300_000_000L
        private const val LATE_REANCHOR_NS = 1_000_000_000L
        private const val EARLY_REANCHOR_NS = 3_000_000_000L
        private const val NONE = Long.MIN_VALUE
    }
}

/** A snapshot of [IngestClock]'s health counters, for the engine's state and the session log. */
data class IngestHealth(
    val anchored: Boolean,
    /** How long the current connect has been warming up (ms); 0 when anchored. */
    val warmupElapsedMs: Long,
    val warmups: Int,
    val warmupTotalMs: Long,
    /** Since the last anchoring or re-anchoring (ms); -1 before the first. */
    val sinceAnchorMs: Long,
    val reanchors: Int,
    /** The picture's delay beyond the camera's baseline (ms): see [IngestClock.presentationSpareNs]. */
    val spareMs: Long,
    val backlogFlushes: Int,
)
