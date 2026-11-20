package app.irlsupreme.engine.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of a card's GIF background: decode size (memory), frame timing and the crop to fill. */
class GifBackgroundTest {
    @Test fun `a small GIF is decoded at its own size, never upscaled`() {
        assertEquals(GifPlan.Size(480, 270), GifPlan.decodeSize(480, 270, 1920, 1080))
        assertEquals(GifPlan.Size(320, 320), GifPlan.decodeSize(320, 320, 1080, 1920))
    }

    @Test fun `a big GIF is decoded just big enough to cover a 720p frame`() {
        // 1080p output: covered at 720p.
        assertEquals(GifPlan.Size(1280, 720), GifPlan.decodeSize(1920, 1080, 1920, 1080))
        assertEquals(GifPlan.Size(720, 1280), GifPlan.decodeSize(1080, 1920, 1080, 1920))
        // A square GIF on a landscape stream must still cover the width: 1280 wide, cropped top and bottom...
        val sq = GifPlan.decodeSize(2000, 2000, 1920, 1080)!!
        // ...but never past the pixel cap, whatever its shape.
        assertTrue(sq.width * sq.height <= GifPlan.MAX_PIXELS)
        assertEquals(sq.width, sq.height)
    }

    @Test fun `a 720p stream decodes no bigger than itself`() {
        assertEquals(GifPlan.Size(1280, 720), GifPlan.decodeSize(2560, 1440, 1280, 720))
        assertEquals(GifPlan.Size(640, 360), GifPlan.decodeSize(1280, 720, 640, 360))
    }

    @Test fun `no shape gets past the memory cap`() {
        for ((w, h) in listOf(8000 to 500, 500 to 8000, 4096 to 4096, 1 to 20000, 3000 to 2000)) {
            val s = GifPlan.decodeSize(w, h, 1920, 1080)!!
            assertTrue("$w x $h -> $s", s.width * s.height <= GifPlan.MAX_PIXELS + s.width + s.height) // rounding
            assertTrue(s.width >= 1 && s.height >= 1)
        }
    }

    @Test fun `no size means no decode`() {
        assertNull(GifPlan.decodeSize(0, 100, 1920, 1080))
        assertNull(GifPlan.decodeSize(100, -1, 1920, 1080))
        assertNull(GifPlan.decodeSize(100, 100, 0, 1080))
    }

    @Test fun `frames follow the GIF's timing but never faster than 15 fps`() {
        // Due in 100 ms: drawn then.
        assertEquals(1100L, GifPlan.nextDrawAtMs(nowMs = 1000, dueMs = 1100, lastDrawMs = 1000, minIntervalMs = 66))
        // A 50 fps GIF asks for a frame in 20 ms: held to 66 ms after the last draw.
        assertEquals(1066L, GifPlan.nextDrawAtMs(nowMs = 1000, dueMs = 1020, lastDrawMs = 1000, minIntervalMs = 66))
        // Hot phone: 5 fps.
        assertEquals(1200L, GifPlan.nextDrawAtMs(nowMs = 1000, dueMs = 1020, lastDrawMs = 1000, minIntervalMs = GifPlan.HOT_INTERVAL_MS))
        // Already late (the draw was slow): straight away after the minimum gap.
        assertEquals(1066L, GifPlan.nextDrawAtMs(nowMs = 1050, dueMs = 900, lastDrawMs = 1000, minIntervalMs = 66))
    }

    @Test fun `a bogus delay can't freeze the loop, and a single frame is drawn once`() {
        assertEquals(1000L + GifPlan.MAX_WAIT_MS, GifPlan.nextDrawAtMs(nowMs = 1000, dueMs = Long.MAX_VALUE, lastDrawMs = 1000, minIntervalMs = 66))
        assertEquals(-1L, GifPlan.nextDrawAtMs(nowMs = 1000, dueMs = -1, lastDrawMs = 1000, minIntervalMs = 66))
    }

    @Test fun `a due time is read as uptime, or as a delay when it can't be one`() {
        val now = 5_000_000L // over an hour of uptime
        assertEquals(now + 40, GifPlan.dueUptimeMs(now + 40, now))
        assertEquals(now - 10, GifPlan.dueUptimeMs(now - 10, now)) // just overdue: draw now
        assertEquals(now + 40, GifPlan.dueUptimeMs(40, now)) // a bare 40 ms delay
        assertEquals(-1L, GifPlan.dueUptimeMs(-1, now))
    }

    @Test fun `a wider GIF is cropped at the sides, a taller one top and bottom`() {
        // 2:1 into 16:9: the middle 88.9% of the width.
        val wide = GifPlan.coverRect(200, 100, 1920, 1080)
        val f = (16f / 9f) / 2f
        assertArrayEquals(floatArrayOf((1 - f) / 2, 0f, (1 + f) / 2, 1f), wide, 1e-5f)
        // Square into portrait 9:16: the middle 56.25% of the width.
        val sq = GifPlan.coverRect(100, 100, 1080, 1920)
        assertArrayEquals(floatArrayOf(0.21875f, 0f, 0.78125f, 1f), sq, 1e-5f)
        // Square into landscape: the middle of the height.
        val land = GifPlan.coverRect(100, 100, 1920, 1080)
        assertArrayEquals(floatArrayOf(0f, 0.21875f, 1f, 0.78125f), land, 1e-5f)
        // Same shape: all of it.
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f), GifPlan.coverRect(1280, 720, 1920, 1080), 1e-5f)
    }

    @Test fun `the cover matrix maps the quad onto the crop, upright like FLIP_V`() {
        val m = GifPlan.coverMatrix(200, 100, 1920, 1080)
        val r = GifPlan.coverRect(200, 100, 1920, 1080)
        fun st(x: Float, y: Float) = floatArrayOf(m[0] * x + m[4] * y + m[12], m[1] * x + m[5] * y + m[13])
        // Quad bottom-left (0,0) samples the crop's bottom-left: u0, and v1 (bitmap rows count down from the top).
        assertArrayEquals(floatArrayOf(r[0], r[3]), st(0f, 0f), 1e-5f)
        // Quad top-right (1,1) samples the crop's top-right.
        assertArrayEquals(floatArrayOf(r[2], r[1]), st(1f, 1f), 1e-5f)
        // Full frame is exactly FLIP_V's mapping: t = 1 - y.
        val full = GifPlan.coverMatrix(1920, 1080, 1920, 1080)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f), full, 1e-6f)
    }
}
