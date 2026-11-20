package app.irlsupreme.engine.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderMeterTest {
    @Test fun ratesFpsAndIdrsFromPresentationTimes() {
        val m = EncoderMeter()
        val periodUs = 33_333L
        for (i in 0 until 300) { // 10 s at 30 fps, an IDR every 60 frames
            val key = i % 60 == 0
            m.onFrame(i * periodUs, if (key) 100_000 else 20_000, key)
        }
        assertEquals(30.0, m.framesPerSecond, 0.1)
        assertEquals(100_000, m.lastIdrBytes)
        assertEquals(60 * periodUs / 1_000, m.maxIdrIntervalMs)
        assertEquals(300L, m.frames)
        // Window holds 30 intervals: 29 or 30 P frames plus at most one IDR.
        val pOnly = 20_000L * 8 * 30
        val withIdr = (20_000L * 29 + 100_000) * 8 * 30 / 30
        val bps = m.bitsPerSecond
        assertTrue("$bps", bps in pOnly * 99 / 100..withIdr * 101 / 100)
    }

    @Test fun anIdrGapStillOpenCounts() {
        val m = EncoderMeter()
        m.onFrame(0, 100_000, key = true)
        for (i in 1..150) m.onFrame(i * 33_333L, 20_000, key = false) // 5 s and no second IDR
        assertEquals(5_000L - 1, m.maxIdrIntervalMs) // 150 * 33.333 ms, whole ms
    }

    @Test fun nothingBeforeTheFirstIdr() {
        val m = EncoderMeter()
        for (i in 0 until 10) m.onFrame(i * 33_333L, 20_000, key = false)
        assertEquals(0L, m.maxIdrIntervalMs)
        assertEquals(0, m.lastIdrBytes)
    }
}
