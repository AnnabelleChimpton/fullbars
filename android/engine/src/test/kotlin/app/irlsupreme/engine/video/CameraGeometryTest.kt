package app.irlsupreme.engine.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class CameraGeometryTest {
    private fun map(m: FloatArray, u: Float, v: Float) = floatArrayOf(m[0] * u + m[4] * v + m[12], m[1] * u + m[5] * v + m[13])

    @Test fun platformAlreadyAppliesTheSensorOrientation() {
        // A phone's 1920x1080 buffer comes out of the SurfaceTexture transform portrait-shaped.
        assertEquals(1080 to 1920, CameraGeometry.naturalSize(1920, 1080, 90))
        assertEquals(1080 to 1920, CameraGeometry.naturalSize(1920, 1080, 270))
        assertEquals(1920 to 1080, CameraGeometry.naturalSize(1920, 1080, 0)) // landscape-natural tablet/Chromebook
    }

    @Test fun displayRotationIsUndoneCounterclockwise() {
        assertEquals(0, CameraGeometry.rotationForDisplay(0))
        assertEquals(270, CameraGeometry.rotationForDisplay(90))
        assertEquals(180, CameraGeometry.rotationForDisplay(180))
        assertEquals(90, CameraGeometry.rotationForDisplay(270))
    }

    @Test fun onlyRotationsOfTheOutputsShapeAreUsed() {
        // Phone (natural 1080x1920). Landscape output: the two landscape sides only.
        assertFalse(CameraGeometry.matchesOutput(1080, 1920, 0, 1920, 1080))
        assertTrue(CameraGeometry.matchesOutput(1080, 1920, 90, 1920, 1080))
        assertFalse(CameraGeometry.matchesOutput(1080, 1920, 180, 1920, 1080))
        assertTrue(CameraGeometry.matchesOutput(1080, 1920, 270, 1920, 1080))
        // Portrait output: the portrait sides only.
        assertTrue(CameraGeometry.matchesOutput(1080, 1920, 0, 1080, 1920))
        assertFalse(CameraGeometry.matchesOutput(1080, 1920, 90, 1080, 1920))
        assertTrue(CameraGeometry.matchesOutput(1080, 1920, 180, 1080, 1920))
        assertEquals(90, CameraGeometry.defaultDisplayRotation(1080, 1920, 1920, 1080))
        assertEquals(0, CameraGeometry.defaultDisplayRotation(1080, 1920, 1080, 1920))
        assertEquals(0, CameraGeometry.defaultDisplayRotation(1920, 1080, 1920, 1080)) // tablet, landscape output
    }

    // OnePlus 12 back camera, PRIVATE-format outputs (dumpsys media.camera).
    private val op12 = listOf(
        4096 to 3072, 4000 to 3000, 3840 to 2160, 3840 to 1644, 3280 to 2464, 3280 to 1856, 3264 to 2448, 2560 to 1440,
        2520 to 1080, 2376 to 1080, 2160 to 1080, 1920 to 1440, 1920 to 1080, 1600 to 1200, 1600 to 800, 1584 to 720,
        1440 to 1080, 1280 to 960, 1280 to 720, 1080 to 1080, 1024 to 768, 864 to 480, 640 to 480, 640 to 360,
    )

    @Test fun bufferSizeChoice() {
        assertEquals(1920 to 1080, CameraGeometry.chooseBufferSize(op12, 1920, 1080))
        assertEquals(1920 to 1080, CameraGeometry.chooseBufferSize(op12, 1080, 1920)) // portrait output: same sensor size
        assertEquals(1280 to 720, CameraGeometry.chooseBufferSize(op12, 720, 1280))
        assertEquals(1920 to 1080, CameraGeometry.chooseBufferSize(op12, 1600, 900)) // not offered: next 16:9 up
        assertEquals(1920 to 1440, CameraGeometry.chooseBufferSize(listOf(640 to 480, 1920 to 1440, 1440 to 1080), 1920, 1080))
        assertEquals(1280 to 720, CameraGeometry.chooseBufferSize(listOf(640 to 480, 1280 to 720, 1280 to 960), 1920, 1080))
        assertEquals(1920 to 1080, CameraGeometry.chooseBufferSize(emptyList(), 1080, 1920))
    }

    /**
     * The phone, end to end: the natural (portrait) image turned for each display rotation. Landmarks: the natural image's
     * top-centre (0.5, 1) is the world's "up" when the phone is upright. Held in landscape with display rotation 90
     * (turned counterclockwise), world up is the natural image's right edge; at 270 its left edge.
     */
    @Test fun phoneLandscapeAndPortraitAreUpright() {
        // Landscape output, display 90: the output's top-centre must sample the natural image's right edge, centre.
        val l90 = CameraGeometry.texTransform(1080, 1920, CameraGeometry.rotationForDisplay(90), 1920, 1080)
        assertArrayEquals(floatArrayOf(1f, 0.5f), map(l90, 0.5f, 1f), 1e-5f)
        // Display 270: the left edge.
        val l270 = CameraGeometry.texTransform(1080, 1920, CameraGeometry.rotationForDisplay(270), 1920, 1080)
        assertArrayEquals(floatArrayOf(0f, 0.5f), map(l270, 0.5f, 1f), 1e-5f)
        // Portrait output, display 0: the natural image as it is, top at top, full frame (both 9:16).
        val p0 = CameraGeometry.texTransform(1080, 1920, CameraGeometry.rotationForDisplay(0), 1080, 1920)
        assertArrayEquals(floatArrayOf(0.5f, 1f), map(p0, 0.5f, 1f), 1e-5f)
        assertArrayEquals(floatArrayOf(0f, 0f), map(p0, 0f, 0f), 1e-5f)
        assertArrayEquals(floatArrayOf(1f, 1f), map(p0, 1f, 1f), 1e-5f)
        // Landscape at display 90 uses the whole sensor frame too (16:9 into 16:9): corners land on corners.
        val corners = listOf(map(l90, 0f, 0f), map(l90, 1f, 0f), map(l90, 0f, 1f), map(l90, 1f, 1f))
        corners.forEach { p -> p.forEach { assertTrue(abs(it) < 1e-5f || abs(it - 1f) < 1e-5f) } }
    }

    @Test fun fourThreeIsCroppedTopAndBottom() {
        val m = CameraGeometry.texTransform(1440, 1080, 0, 1920, 1080)
        assertArrayEquals(floatArrayOf(0f, 0.125f), map(m, 0f, 0f), 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 0.875f), map(m, 1f, 1f), 1e-6f)
    }

    /** Whatever the source, output and rotation: no stretch (pixel aspect kept), centred, never outside the source. */
    @Test fun neverStretchesAndStaysInside() {
        val srcs = listOf(1080 to 1920, 720 to 1280, 1920 to 1080, 1440 to 1080, 2520 to 1080, 1080 to 1080, 3072 to 4096)
        val outs = listOf(1920 to 1080, 1280 to 720, 1080 to 1920, 720 to 1280)
        for ((sw, sh) in srcs) for ((ow, oh) in outs) for (rot in intArrayOf(0, 90, 180, 270)) {
            val m = CameraGeometry.texTransform(sw, sh, rot, ow, oh)
            val du = hypot(m[0] * sw, m[1] * sh) // source pixels spanned by the output's full width
            val dv = hypot(m[4] * sw, m[5] * sh)
            val what = "${sw}x$sh rot $rot -> ${ow}x$oh"
            assertEquals(what, ow.toFloat() / oh, du / dv, 1e-3f)
            assertEquals(what, 0f, m[0] * sw * m[4] * sw + m[1] * sh * m[5] * sh, 1e-1f) // axes stay perpendicular
            assertArrayEquals(what, floatArrayOf(0.5f, 0.5f), map(m, 0.5f, 0.5f), 1e-6f)
            for (u in floatArrayOf(0f, 1f)) for (v in floatArrayOf(0f, 1f)) {
                assertTrue(what, map(m, u, v).all { it > -1e-5f && it < 1f + 1e-5f })
            }
            // Fills the source in at least one direction (centre-crop, not a smaller window).
            val spanU = abs(m[0]) + abs(m[4]); val spanV = abs(m[1]) + abs(m[5])
            assertTrue(what, abs(spanU - 1f) < 1e-5f || abs(spanV - 1f) < 1e-5f)
        }
    }

    @Test fun frontCameraTurnsTheOtherWay() {
        assertEquals(0, CameraGeometry.rotationForDisplay(0, front = true))
        assertEquals(90, CameraGeometry.rotationForDisplay(90, front = true))
        assertEquals(180, CameraGeometry.rotationForDisplay(180, front = true))
        assertEquals(270, CameraGeometry.rotationForDisplay(270, front = true))
    }

    /**
     * A typical front camera: sensor orientation 270, buffer 1920x1080. The platform hands over a mirrored,
     * natural-orientation (portrait) image M. Viewers must get the un-mirrored world, upright, at every display
     * rotation; the streamer's preview gets the mirror (selfie) view of the same thing.
     */
    @Test fun frontCameraSensor270AtEveryDisplayRotation() {
        val (nw, nh) = CameraGeometry.naturalSize(1920, 1080, 270)
        assertEquals(1080 to 1920, nw to nh)
        for (display in intArrayOf(0, 90, 180, 270)) {
            val landscape = display % 180 != 0
            val (ow, oh) = if (landscape) 1920 to 1080 else 1080 to 1920
            assertTrue("display $display", CameraGeometry.matchesOutput(nw, nh, display, ow, oh))
            val back = CameraGeometry.texTransform(nw, nh, CameraGeometry.rotationForDisplay(display), ow, oh)
            val front = CameraGeometry.texTransform(nw, nh, CameraGeometry.rotationForDisplay(display, front = true), ow, oh, mirror = true)
            // Undoing the mirror then turning clockwise == turning like the back camera then flipping the output.
            assertArrayEquals("display $display", CameraGeometry.mirrorOutput(back), front, 1e-5f)
            // So the selfie preview is exactly what the platform delivered, turned upright.
            assertArrayEquals("display $display", back, CameraGeometry.mirrorOutput(front), 1e-5f)
            // World up stays up: the output's top-centre samples the same point of M as the back-camera maths would.
            assertArrayEquals("display $display", map(back, 0.5f, 1f), map(front, 0.5f, 1f), 1e-5f)
            // ...and left and right are swapped relative to M (the mirror is undone).
            assertArrayEquals("display $display", map(back, 1f, 0.5f), map(front, 0f, 0.5f), 1e-5f)
        }
        // Landmark, landscape at display 90: world up is M's right edge (as for the back camera's image).
        val l90 = CameraGeometry.texTransform(nw, nh, CameraGeometry.rotationForDisplay(90, front = true), 1920, 1080, mirror = true)
        assertArrayEquals(floatArrayOf(1f, 0.5f), map(l90, 0.5f, 1f), 1e-5f)
        // Portrait at display 0: un-mirrored, so the output's left edge samples M's right edge.
        val p0 = CameraGeometry.texTransform(nw, nh, CameraGeometry.rotationForDisplay(0, front = true), 1080, 1920, mirror = true)
        assertArrayEquals(floatArrayOf(1f, 0.5f), map(p0, 0f, 0.5f), 1e-5f)
        assertArrayEquals(floatArrayOf(0.5f, 1f), map(p0, 0.5f, 1f), 1e-5f)
    }

    @Test fun mirroredStillNeverStretches() {
        for (rot in intArrayOf(0, 90, 180, 270)) {
            val m = CameraGeometry.texTransform(1080, 1920, rot, 1920, 1080, mirror = true)
            assertArrayEquals(floatArrayOf(0.5f, 0.5f), map(m, 0.5f, 0.5f), 1e-6f)
            for (u in floatArrayOf(0f, 1f)) for (v in floatArrayOf(0f, 1f)) {
                assertTrue(map(m, u, v).all { it > -1e-5f && it < 1f + 1e-5f })
            }
        }
        val m = CameraGeometry.texTransform(1440, 1080, 0, 1920, 1080)
        val mm = CameraGeometry.mirrorOutput(m)
        for (u in floatArrayOf(0f, 0.25f, 1f)) assertArrayEquals(map(m, 1f - u, 0.3f), map(mm, u, 0.3f), 1e-6f)
    }

    @Test fun externalCameraIsFittedNotStretched() {
        val same = CameraGeometry.fitTransform(1920, 1080, 1920, 1080)
        assertEquals(1f, same[0], 0f); assertEquals(1f, same[5], 0f)
        val intoPortrait = CameraGeometry.fitTransform(1920, 1080, 1080, 1920) // 16:9 camera in a 9:16 stream
        assertEquals(1f, intoPortrait[0], 0f)
        assertEquals((1080f / 1920) / (1920f / 1080), intoPortrait[5], 1e-6f)
        val verticalIntoLandscape = CameraGeometry.fitTransform(1080, 1920, 1920, 1080)
        assertEquals((1080f / 1920) / (1920f / 1080), verticalIntoLandscape[0], 1e-6f)
        assertEquals(1f, verticalIntoLandscape[5], 0f)
        val unknown = CameraGeometry.fitTransform(0, 0, 1920, 1080)
        assertEquals(1f, unknown[0], 0f); assertEquals(1f, unknown[5], 0f)
    }
}

class CameraFrameRateTest {
    private val sizes = listOf(3840 to 2160, 1920 to 1080, 1280 to 720)
    /** A phone whose 1080p output tops out at 30 fps; 720p and 4K (oddly) do 60. */
    private val minFrame = { s: Pair<Int, Int> -> if (s == (1920 to 1080)) 33_333_333L else 16_666_666L }

    @Test fun sizesTooSlowForSixtyAreRejected() {
        assertEquals(1920 to 1080, CameraGeometry.chooseBufferSize(sizes, 1920, 1080, 30, minFrame))
        assertEquals(3840 to 2160, CameraGeometry.chooseBufferSize(sizes, 1920, 1080, 60, minFrame))
        // Nothing fast enough: the normal choice rather than none.
        assertEquals(1920 to 1080, CameraGeometry.chooseBufferSize(sizes, 1920, 1080, 120, minFrame))
    }

    @Test fun sixtyNeedsAnExactRange() {
        val ranges = listOf(15 to 30, 30 to 30, 15 to 60)
        assertEquals(null, CameraPolicy.aeRange(ranges, 60))
        assertEquals(30, CameraPolicy.fpsFallback(ranges, 60))
        assertEquals(60 to 60, CameraPolicy.aeRange(ranges + (60 to 60), 60))
        assertEquals(30 to 30, CameraPolicy.aeRange(ranges, 30))
        assertEquals(24 to 30, CameraPolicy.aeRange(listOf(15 to 30, 24 to 30), 30)) // no [30,30]: highest floor, as before
        assertEquals(setOf(30, 60), CameraPolicy.exactRates(ranges + (60 to 60)))
    }

    @Test fun sixtyIsSupportedOnlyWithARangeAndAFastEnoughSize() {
        val fast = { _: Pair<Int, Int> -> 16_666_666L }
        assertTrue(CameraPolicy.supports(listOf(60 to 60), sizes, fast, 1920, 1080, 60))
        assertFalse(CameraPolicy.supports(listOf(30 to 60), sizes, fast, 1920, 1080, 60))
        assertFalse(CameraPolicy.supports(listOf(60 to 60), listOf(1920 to 1080), minFrame, 1920, 1080, 60))
    }

    @Test fun stabilisationStepsDownToWhatsListed() {
        val on = CameraPolicy.VIDEO_STAB_ON
        val off = CameraPolicy.VIDEO_STAB_OFF
        val preview = CameraPolicy.VIDEO_STAB_PREVIEW
        assertEquals(preview, CameraPolicy.videoStabilization(StabilizationMode.PREVIEW, setOf(off, on, preview), 33))
        assertEquals(on, CameraPolicy.videoStabilization(StabilizationMode.PREVIEW, setOf(off, on, preview), 32))
        assertEquals(on, CameraPolicy.videoStabilization(StabilizationMode.PREVIEW, setOf(off, on), 34))
        assertEquals(off, CameraPolicy.videoStabilization(StabilizationMode.STANDARD, setOf(off), 34))
        assertEquals(on, CameraPolicy.videoStabilization(StabilizationMode.STANDARD, setOf(off, on), 31))
        assertEquals(off, CameraPolicy.videoStabilization(StabilizationMode.OFF, setOf(off, on, preview), 34))
        // Refused at run time: PREVIEW, then ON, then OFF.
        assertEquals(on, CameraPolicy.stepDown(preview, setOf(off, on, preview)))
        assertEquals(off, CameraPolicy.stepDown(preview, setOf(off, preview)))
        assertEquals(off, CameraPolicy.stepDown(on, setOf(off, on)))
    }
}
