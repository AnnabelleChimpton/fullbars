package app.irlsupreme.engine.video

import kotlin.math.abs

/**
 * Pure geometry for the phone camera and other sources (no Android types, unit-tested). In short, following
 * Android's "Camera orientations" guide:
 *
 *  - Camera buffers are landscape-shaped (the sensor's long side). For a SurfaceTexture consumer the platform already
 *    rotates them by the sensor orientation: the SurfaceTexture transform matrix yields the image in the device's
 *    NATURAL orientation (portrait on a phone). So the app must not apply the sensor orientation again.
 *  - What is left is the display rotation: a back camera's natural-orientation image is rotated by displayRotation
 *    counterclockwise to be upright on the current screen.
 *  - The result is centre-cropped to the output's aspect ratio. Nothing is ever stretched.
 *
 * Front camera: the platform's transform also mirrors it (the camera service flips it horizontally before rotating,
 * like a mirror; Android 13's default mirror mode does the same). Viewers must see the world the right way round (text
 * readable), so [texTransform] undoes that mirror with `mirror = true`, and the front camera's un-mirrored image then
 * turns the opposite way to the back camera's: clockwise by the display rotation. (That is the old Camera1 rule
 * "front = (sensor + display) mod 360" with the sensor part already done by the platform.) Only the phone's own
 * preview is mirrored back ([mirrorOutput]), so the streamer sees a selfie view.
 *
 * Rotations here are clockwise degrees, 0/90/180/270.
 */
object CameraGeometry {
    private fun norm(deg: Int) = ((deg % 360) + 360) % 360

    /** Size of the image the SurfaceTexture transform yields for a [bufW]x[bufH] camera buffer (natural orientation). */
    fun naturalSize(bufW: Int, bufH: Int, sensorOrientation: Int): Pair<Int, Int> =
        if (norm(sensorOrientation) % 180 == 0) bufW to bufH else bufH to bufW

    /**
     * Clockwise rotation that makes the natural-orientation image upright at [displayRotation]: a back camera's turns
     * counterclockwise by it, a [front] camera's (un-mirrored) clockwise.
     */
    fun rotationForDisplay(displayRotation: Int, front: Boolean = false): Int =
        if (front) norm(displayRotation) else norm(360 - displayRotation)

    /** Whether the upright image at [displayRotation] has the same shape (landscape/portrait) as the output. */
    fun matchesOutput(naturalW: Int, naturalH: Int, displayRotation: Int, outW: Int, outH: Int): Boolean {
        val quarter = rotationForDisplay(displayRotation) % 180 != 0
        val w = if (quarter) naturalH else naturalW
        val h = if (quarter) naturalW else naturalH
        return (w >= h) == (outW >= outH)
    }

    /**
     * The display rotation to assume before the UI reports one: the first (in order 0, 90, 270, 180) that gives the
     * output's shape. Landscape output on a phone -> 90 (the usual landscape); portrait -> 0.
     */
    fun defaultDisplayRotation(naturalW: Int, naturalH: Int, outW: Int, outH: Int): Int =
        intArrayOf(0, 90, 270, 180).first { matchesOutput(naturalW, naturalH, it, outW, outH) }

    /**
     * The camera buffer size to request. Sizes are in sensor orientation (landscape), as StreamConfigurationMap lists
     * them, so the target is the output's long x short side. Exact match first, else the smallest offered size at least
     * that large with the closest aspect ratio, else the largest with the closest aspect ratio.
     */
    fun chooseBufferSize(offered: List<Pair<Int, Int>>, outW: Int, outH: Int): Pair<Int, Int> {
        val tw = maxOf(outW, outH)
        val th = minOf(outW, outH)
        if (offered.isEmpty()) return tw to th
        offered.firstOrNull { it.first == tw && it.second == th }?.let { return it }
        val target = tw.toDouble() / th
        fun aspectErr(s: Pair<Int, Int>) = abs(s.first.toDouble() / s.second - target)
        val bigEnough = offered.filter { it.first >= tw && it.second >= th }
        return if (bigEnough.isNotEmpty()) {
            bigEnough.minWith(compareBy<Pair<Int, Int>>({ aspectErr(it) }, { it.first.toLong() * it.second }))
        } else {
            offered.minWith(compareBy<Pair<Int, Int>>({ aspectErr(it) }, { -it.first.toLong() * it.second }))
        }
    }

    /**
     * [chooseBufferSize] for a [fps] stream: sizes the camera can't produce that fast (their minimum frame duration,
     * [minFrameNs], is over a frame period) are left out, so 60 fps never picks a size capped at 30. If that leaves
     * nothing, the choice is made from every size (the camera then runs slower, which beats no picture).
     */
    fun chooseBufferSize(
        offered: List<Pair<Int, Int>>,
        outW: Int,
        outH: Int,
        fps: Int,
        minFrameNs: (Pair<Int, Int>) -> Long,
    ): Pair<Int, Int> {
        val periodNs = 1_000_000_000L / fps
        val fastEnough = offered.filter { minFrameNs(it) <= periodNs }
        return chooseBufferSize(fastEnough.ifEmpty { offered }, outW, outH)
    }

    /**
     * Texture-coordinate transform (column-major 4x4) applied BEFORE the SurfaceTexture's own matrix: it samples a
     * [srcW]x[srcH] source rotated [rotation] degrees clockwise and centre-cropped to fill an [outW]x[outH] output.
     * Output UV -> scale about the centre (crop) -> rotate about the centre -> [mirror] (front camera: undo the
     * platform's horizontal flip) -> source UV.
     */
    fun texTransform(srcW: Int, srcH: Int, rotation: Int, outW: Int, outH: Int, mirror: Boolean = false): FloatArray {
        val quarter = norm(rotation) % 180 != 0
        val upW = if (quarter) srcH else srcW
        val upH = if (quarter) srcW else srcH
        val src = upW.toFloat() / upH
        val dst = outW.toFloat() / outH
        val sx = if (src > dst) dst / src else 1f
        val sy = if (src > dst) 1f else src / dst
        val (c, s) = when (norm(rotation)) {
            90 -> 0f to 1f
            180 -> -1f to 0f
            270 -> 0f to -1f
            else -> 1f to 0f
        }
        // uv' = R * S * (uv - 0.5) + 0.5, R = [c -s; s c]. In GL texture space (v up), showing the picture turned
        // clockwise means sampling the source turned counterclockwise, which is this R.
        val m = FloatArray(16)
        m[0] = c * sx; m[1] = s * sx
        m[4] = -s * sy; m[5] = c * sy
        m[10] = 1f; m[15] = 1f
        if (mirror) { m[0] = -m[0]; m[4] = -m[4] } // u_src -> 1 - u_src, applied last
        m[12] = 0.5f - (m[0] * 0.5f + m[4] * 0.5f)
        m[13] = 0.5f - (m[1] * 0.5f + m[5] * 0.5f)
        return m
    }

    /**
     * [m] (a texture transform as above) with the output flipped left to right: the front camera's selfie view for
     * the phone's own preview. Samples at (1 - u, v) what [m] samples at (u, v).
     */
    fun mirrorOutput(m: FloatArray): FloatArray = m.copyOf().also {
        it[12] = m[12] + m[0]; it[13] = m[13] + m[1]
        it[0] = -m[0]; it[1] = -m[1]
    }

    /**
     * Position transform (column-major 4x4) that fits a [srcW]x[srcH] picture inside an [outW]x[outH] frame without
     * stretching or cropping (black bars). Used for an external camera, whose framing belongs to the camera operator.
     */
    fun fitTransform(srcW: Int, srcH: Int, outW: Int, outH: Int): FloatArray {
        val m = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
        if (srcW <= 0 || srcH <= 0) return m
        val src = srcW.toFloat() / srcH
        val dst = outW.toFloat() / outH
        if (src > dst) m[5] = dst / src else m[0] = src / dst
        return m
    }
}
