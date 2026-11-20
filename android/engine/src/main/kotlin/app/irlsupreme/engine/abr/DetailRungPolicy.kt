package app.irlsupreme.engine.abr

/**
 * How much camera detail to draw for the bitrate the stream can afford: a render scale of 1.0, 0.75 or 0.5 for the
 * camera layer (the encoder keeps its size, so the SPS and the slate's splice stay valid; overlays stay sharp).
 * At low bitrates a softer, smaller picture upscaled looks better than a full-size one the encoder has to smear.
 * Pure and clock-free: call [update] on each bitrate tick with the ABR target.
 *
 * Ladder: 1080p is 1.0 at 3.5 Mbps or more, 0.75 from 2 to 3.5, 0.5 below 2; 720p is 1.0 at 2 Mbps or more,
 * 0.75 below (it never goes to 0.5: 360 lines is too soft to be worth it).
 * Hysteresis: a lower rung must be called for [DOWN_HOLD_MS] before it's taken, a higher one [UP_HOLD_MS], and a
 * congestion episode (the target falling, until it has not fallen for [EPISODE_GAP_MS]) moves down at most one rung,
 * so a cut that briefly overshoots doesn't throw the picture two rungs down.
 */
class DetailRungPolicy(val widthClass: WidthClass) {
    enum class WidthClass { P1080, P720 }

    /** The current render scale. */
    var scale: Float = 1.0f
        private set

    private var rung = 0 // index into SCALES
    private var wantSinceMs = Long.MIN_VALUE
    private var wantRung = 0
    private var lastTargetBps = -1
    private var lastFallMs = Long.MIN_VALUE
    private var steppedDownThisEpisode = false

    /** [targetBps]: the ABR's current video target. Returns the render scale to use now. */
    fun update(nowMs: Long, targetBps: Int): Float {
        if (lastTargetBps >= 0 && targetBps < lastTargetBps) {
            if (lastFallMs == Long.MIN_VALUE || nowMs - lastFallMs >= EPISODE_GAP_MS) steppedDownThisEpisode = false
            lastFallMs = nowMs
        }
        lastTargetBps = targetBps
        // No fall for a while (or none at all): no episode, so nothing limits the next step down.
        if (lastFallMs == Long.MIN_VALUE || nowMs - lastFallMs >= EPISODE_GAP_MS) steppedDownThisEpisode = false

        val desired = rungFor(targetBps)
        if (desired == rung) {
            wantSinceMs = Long.MIN_VALUE
            return scale
        }
        // One rung at a time, toward what the bitrate calls for.
        val next = if (desired > rung) rung + 1 else rung - 1
        if (next != wantRung || wantSinceMs == Long.MIN_VALUE) {
            wantRung = next
            wantSinceMs = nowMs
        }
        val down = next > rung
        val held = nowMs - wantSinceMs >= if (down) DOWN_HOLD_MS else UP_HOLD_MS
        if (held && !(down && steppedDownThisEpisode)) {
            rung = next
            scale = SCALES[rung]
            wantSinceMs = Long.MIN_VALUE
            if (down) steppedDownThisEpisode = true
        }
        return scale
    }

    private fun rungFor(bps: Int): Int = when (widthClass) {
        WidthClass.P1080 -> when {
            bps >= FULL_1080_BPS -> 0
            bps >= MID_1080_BPS -> 1
            else -> 2
        }
        WidthClass.P720 -> if (bps >= FULL_720_BPS) 0 else 1
    }

    companion object {
        val SCALES = floatArrayOf(1.0f, 0.75f, 0.5f)
        const val FULL_1080_BPS = 3_500_000
        const val MID_1080_BPS = 2_000_000
        const val FULL_720_BPS = 2_000_000
        const val DOWN_HOLD_MS = 2_000L
        const val UP_HOLD_MS = 5_000L
        /** The ABR's own episode gap: falls closer together than this are one episode. */
        const val EPISODE_GAP_MS = 5_000L

        /** The ladder for a frame size: 1080-class when the short side is 1000 lines or more, else 720-class. */
        fun forFrame(width: Int, height: Int): DetailRungPolicy =
            DetailRungPolicy(if (minOf(width, height) >= 1_000) WidthClass.P1080 else WidthClass.P720)
    }
}
