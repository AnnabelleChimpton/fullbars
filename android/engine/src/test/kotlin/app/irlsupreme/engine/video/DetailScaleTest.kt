package app.irlsupreme.engine.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailScaleTest {
    @Test fun snapsToTheOfferedSteps() {
        assertEquals(1f, DetailScale.snap(1f))
        assertEquals(1f, DetailScale.snap(1.7f))
        assertEquals(1f, DetailScale.snap(Float.NaN))
        assertEquals(0.75f, DetailScale.snap(0.75f))
        assertEquals(0.75f, DetailScale.snap(0.8f))
        assertEquals(0.5f, DetailScale.snap(0.5f))
        assertEquals(0.5f, DetailScale.snap(0.1f))
    }

    @Test fun intermediateSizesAreEven() {
        assertArrayEquals(intArrayOf(960, 540), DetailScale.size(1920, 1080, 0.5f))
        assertArrayEquals(intArrayOf(1440, 810), DetailScale.size(1920, 1080, 0.75f))
        assertArrayEquals(intArrayOf(960, 540), DetailScale.size(1280, 720, 0.75f))
        assertArrayEquals(intArrayOf(540, 960), DetailScale.size(1080, 1920, 0.5f))
    }

    @Test fun halfScaleTapsLandOnTexelCentres() {
        // A 1920x1080 source sampled 1:1 (identity matrix) into 960x540: each tap a quarter of a destination pixel
        // (half a source texel) from the centre, so the four bilinear taps read the 2x2 texels exactly.
        val identity = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
        val out = FloatArray(4)
        DetailScale.tapOffsets(identity, 960, 540, 1f, 1f, out)
        assertEquals(0.5f / 1920, out[0], 1e-9f)
        assertEquals(0f, out[1], 0f)
        assertEquals(0f, out[2], 0f)
        assertEquals(0.5f / 1080, out[3], 1e-9f)
        // A quarter turn swaps the axes (texture x follows output y).
        val turned = FloatArray(16).also { it[1] = 1f; it[4] = -1f; it[10] = 1f; it[15] = 1f }
        DetailScale.tapOffsets(turned, 960, 540, 1f, 1f, out)
        assertEquals(0f, out[0], 0f)
        assertEquals(0.5f / 1920, out[1], 1e-9f)
        assertEquals(-0.5f / 1080, out[2], 1e-9f)
    }
}
