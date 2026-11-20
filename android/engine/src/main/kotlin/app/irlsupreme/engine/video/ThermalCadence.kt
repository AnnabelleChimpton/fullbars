package app.irlsupreme.engine.video

/**
 * What the thermal ladder does to frame timing, pure so it can be tested. The rule throughout: lower rates are even
 * (every frame the same length), never "skip every 5th tick", and stabilisation is never touched mid-shot (turning
 * it off changes the crop, a visible jump in the field of view).
 *
 *  - Rung 2+: the output rate steps down evenly. With the phone camera on air and an exact fixed AE range offered
 *    for a lower rate ([reducedCameraFps]), the camera and the output both run at it, so the sensor and ISP do less
 *    work too. Otherwise the output runs at half rate, an exact divisor of the source (a 30 fps camera into 15 fps
 *    drops every other frame, evenly).
 *  - The web overlay is latched less often from rung 1 ([browserLatchEvery]); at rung 3 it's closed and its last
 *    frame stays up as a still picture (the compositor keeps a copy), so the layout doesn't jump.
 *  - The preview gets every other frame from rung 1 ([previewEvery]) unless the screen is being touched (only once
 *    the app reports touches).
 */
internal object ThermalCadence {
    /** The rung from which the output rate is reduced. */
    const val REDUCED_RATE_RUNG = 2

    /**
     * The fixed rate (fps) the phone camera should drop to at the reduced rung, from the exact [fps, fps] AE ranges
     * it offers ([exactRates]), or 0 when none fits. 30 fps streams prefer 20 (shown on a 60 Hz screen with no
     * pulldown judder) over 24; 60 fps streams use 30.
     */
    fun reducedCameraFps(fps: Int, exactRates: Collection<Int>): Int {
        val candidates = if (fps >= 50) intArrayOf(fps / 2) else intArrayOf(20, 24)
        return candidates.firstOrNull { it < fps && it in exactRates } ?: 0
    }

    /**
     * The full output rate: [fps], unless the phone camera is on air and can't hold it exactly ([cameraFps], e.g. 30
     * for a 60 fps stream on a phone with no [60, 60] range), when the output runs at the camera's rate if that divides
     * evenly, so frames aren't shown twice in an uneven pattern.
     */
    fun baseFps(fps: Int, phoneOnAir: Boolean, cameraFps: Int): Int =
        if (phoneOnAir && cameraFps in 1 until fps && fps % cameraFps == 0) cameraFps else fps

    /**
     * The output rate for a [fps] stream at thermal [rung]. [phoneOnAir]: the phone camera is what's on stream;
     * [cameraFpsInUse]: the reduced rate the phone camera has actually switched to (0 if it hasn't, or can't).
     */
    fun outputFps(fps: Int, rung: Int, phoneOnAir: Boolean, cameraFpsInUse: Int): Int = when {
        rung < REDUCED_RATE_RUNG -> fps
        phoneOnAir && cameraFpsInUse in 1 until fps -> cameraFpsInUse
        else -> (fps / 2).coerceAtLeast(1)
    }

    fun periodNs(fps: Int): Long = 1_000_000_000L / fps

    /** Latch the web overlay on every Nth tick: all at rung 0, every 2nd at rung 1, every 4th from rung 2. */
    fun browserLatchEvery(rung: Int): Int = when {
        rung <= 0 -> 1
        rung == 1 -> 2
        else -> 4
    }

    /**
     * Offer the preview every Nth tick: every other one from rung 1 when [halving] is on, all while the streamer is
     * touching the screen. [halving] is off until the app reports touches, so a warm phone's preview never drops to
     * half rate under a finger it can't see.
     */
    fun previewEvery(rung: Int, halving: Boolean, touched: Boolean): Int = if (halving && rung >= 1 && !touched) 2 else 1

    /**
     * While the output runs below the encoder's configured rate, the encoder's own GOP (counted in frames) stretches
     * out, so the compositor asks for a keyframe each [intervalNs] of PTS: the server keeps getting one about every
     * keyframe interval. True when one is due at [nowNs].
     */
    fun keyframeDue(nowNs: Long, lastForcedNs: Long, intervalNs: Long): Boolean = nowNs - lastForcedNs >= intervalNs
}
