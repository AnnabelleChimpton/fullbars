package app.irlsupreme.engine.video

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import android.util.Size
import app.irlsupreme.engine.Facing

/**
 * The phone camera's video stabilisation, as the streamer chooses it. Set at stream start; the compositor never
 * changes it mid-shot (switching changes the crop: a visible jump in the field of view).
 *
 *  - [OFF]: none.
 *  - [STANDARD]: CONTROL_VIDEO_STABILIZATION_MODE_ON, where the camera lists it. The default until per-phone
 *    measurements say otherwise.
 *  - [PREVIEW]: CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION (Android 13+, where listed): the mode documented
 *    for preview-class outputs like ours, with a smaller crop and lower latency, but it may steady the picture less.
 */
enum class StabilizationMode { OFF, STANDARD, PREVIEW }

/**
 * Camera choices worked out from what a camera lists, pure so they can be tested (values are the Camera2 constants).
 */
object CameraPolicy {
    const val VIDEO_STAB_OFF = 0 // CONTROL_VIDEO_STABILIZATION_MODE_OFF
    const val VIDEO_STAB_ON = 1 // CONTROL_VIDEO_STABILIZATION_MODE_ON
    const val VIDEO_STAB_PREVIEW = 2 // CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION (API 33)

    /**
     * The stabilisation mode to ask for: [wanted] if the camera lists it (and, for PREVIEW, the phone runs Android 13+),
     * else the next one down. Forcing a mode the camera doesn't list costs heat and crop on mid-tier phones, and a HAL
     * that rejects it would send the camera into its retry loop.
     */
    fun videoStabilization(wanted: StabilizationMode, listed: Set<Int>, sdk: Int): Int {
        var mode = when (wanted) {
            StabilizationMode.OFF -> VIDEO_STAB_OFF
            StabilizationMode.STANDARD -> VIDEO_STAB_ON
            StabilizationMode.PREVIEW -> VIDEO_STAB_PREVIEW
        }
        while (mode != VIDEO_STAB_OFF && !(mode in listed && (mode != VIDEO_STAB_PREVIEW || sdk >= 33))) mode = stepDown(mode, listed)
        return mode
    }

    /** After the camera refused [mode]: PREVIEW, then ON (if listed), then OFF. */
    fun stepDown(mode: Int, listed: Set<Int>): Int = when (mode) {
        VIDEO_STAB_PREVIEW -> if (VIDEO_STAB_ON in listed) VIDEO_STAB_ON else VIDEO_STAB_OFF
        else -> VIDEO_STAB_OFF
    }

    /**
     * The AE target range for a [fps] stream from [ranges] (lower to upper). Above 30 fps only an exact [fps, fps]
     * will do: a variable range lets auto-exposure halve the rate in dim light, and the stream would repeat every
     * frame. At 30 and below an exact range is preferred, else the one reaching [fps] with the highest floor (some
     * phones list no [30, 30]). Null when nothing fits ([fpsFallback] then says what to run at instead).
     */
    fun aeRange(ranges: List<Pair<Int, Int>>, fps: Int): Pair<Int, Int>? {
        ranges.firstOrNull { it.first == fps && it.second == fps }?.let { return it }
        if (fps > 30) return null
        return ranges.filter { it.second == fps }.maxByOrNull { it.first }
    }

    /** The rate to fall back to when [aeRange] finds nothing for [fps]: 30 if that works, else the camera's default. */
    fun fpsFallback(ranges: List<Pair<Int, Int>>, fps: Int): Int =
        if (fps > 30 && aeRange(ranges, 30) != null) 30 else fps

    /** The rates the camera can be held at exactly ([n, n] ranges). */
    fun exactRates(ranges: List<Pair<Int, Int>>): Set<Int> = ranges.filter { it.first == it.second }.map { it.first }.toSet()

    /**
     * Whether a [fps] stream at [width]x[height] can run off this camera: an exact AE range, and a buffer size that
     * can be produced that fast ([CameraGeometry.chooseBufferSize] with each size's minimum frame duration).
     */
    fun supports(ranges: List<Pair<Int, Int>>, sizes: List<Pair<Int, Int>>, minFrameNs: (Pair<Int, Int>) -> Long, width: Int, height: Int, fps: Int): Boolean {
        if (ranges.none { it.first == fps && it.second == fps }) return false
        val chosen = CameraGeometry.chooseBufferSize(sizes, width, height, fps, minFrameNs)
        return minFrameNs(chosen) <= 1_000_000_000L / fps
    }
}

/**
 * What this phone can do, asked before a stream starts (for the settings screen). Opens nothing: it only reads camera
 * characteristics and the codec list, so it's cheap enough for a settings page, but call it off the main thread.
 */
object CameraCapabilities {
    /**
     * Whether the [facing] camera and the hardware H.264 encoder can carry [width]x[height] at [fps]: the camera lists
     * an exact [fps, fps] AE range and a buffer size it can produce that fast, and an encoder supports the size and
     * rate. False on any error. Use it to offer 60 fps only where it will really run.
     */
    fun supports(context: Context, facing: Facing, width: Int, height: Int, fps: Int): Boolean = try {
        cameraSupports(context, facing, width, height, fps) && encoderSupports(width, height, fps)
    } catch (t: Throwable) {
        Log.w("Engine", "capability probe failed (${width}x$height@$fps)", t)
        false
    }

    private fun cameraSupports(context: Context, facing: Facing, width: Int, height: Int, fps: Int): Boolean {
        val cm = context.getSystemService(CameraManager::class.java) ?: return false
        val lens = if (facing == Facing.FRONT) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == lens } ?: return false
        val chars = cm.getCameraCharacteristics(id)
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.map { it.lower to it.upper }.orEmpty()
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)?.map { it.width to it.height }.orEmpty()
        val minFrame = { s: Pair<Int, Int> -> map.getOutputMinFrameDuration(SurfaceTexture::class.java, Size(s.first, s.second)) }
        return CameraPolicy.supports(ranges, sizes, minFrame, width, height, fps)
    }

    private fun encoderSupports(width: Int, height: Int, fps: Int): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.isHardwareAccelerated && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } &&
                runCatching {
                    val v = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                    v.areSizeAndRateSupported(width, height, fps.toDouble()) || v.areSizeAndRateSupported(height, width, fps.toDouble())
                }.getOrDefault(false)
        }
}
