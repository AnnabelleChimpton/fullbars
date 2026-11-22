package app.irlsupreme.engine.captions

import app.irlsupreme.engine.CaptionPosition
import app.irlsupreme.engine.CaptionSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class VoiceFeedTest {
    private fun run(inRate: Int, channels: Int, seconds: Double, audible: Boolean = true, signal: (Double) -> Double): ShortArray {
        val ring = ShortRing(VoiceDownsampler.OUT_RATE * 4)
        val d = VoiceDownsampler(inRate, ring)
        val frames = 480
        val total = (inRate * seconds).toInt()
        var n = 0
        val buf = FloatArray(frames * channels)
        while (n < total) {
            for (f in 0 until frames) for (c in 0 until channels) buf[f * channels + c] = signal((n + f).toDouble() / inRate).toFloat()
            d.onVoice(buf, frames, channels, audible)
            n += frames
        }
        val out = ShortArray(ring.available)
        ring.read(out)
        return out
    }

    private fun rms(s: ShortArray, from: Int = s.size / 4) = sqrt(s.drop(from).sumOf { it.toDouble() * it } / (s.size - from)) / 32768

    @Test fun `48 kHz stereo comes out as 16 kHz mono at the same level`() {
        val out = run(48_000, 2, 1.0) { t -> 0.5 * sin(2 * PI * 440 * t) }
        assertEquals(16_000.0, out.size.toDouble(), 2.0)
        assertEquals(0.5 / sqrt(2.0), rms(out), 0.01)
    }

    @Test fun `44_1 kHz resamples to 16 kHz too`() {
        val out = run(44_100, 1, 1.0) { t -> 0.5 * sin(2 * PI * 1000 * t) }
        // Whole 480-frame chunks: 92 of them, 44 160 frames in.
        assertEquals(44_160 * 16_000.0 / 44_100, out.size.toDouble(), 2.0)
        assertEquals(0.5 / sqrt(2.0), rms(out), 0.02)
    }

    @Test fun `what would fold back above 8 kHz is filtered out`() {
        // 12 kHz would alias to 4 kHz at 16 kHz without the low-pass.
        val out = run(48_000, 1, 1.0) { t -> 0.5 * sin(2 * PI * 12_000 * t) }
        assertTrue("rms ${rms(out)}", rms(out) < 0.05)
    }

    @Test fun `a voice viewers can't hear reaches the recogniser as silence`() {
        val out = run(48_000, 1, 0.5, audible = false) { t -> 0.5 * sin(2 * PI * 440 * t) }
        assertTrue(out.all { abs(it.toInt()) == 0 })
    }

    @Test fun `a full ring drops new samples rather than blocking`() {
        val ring = ShortRing(4)
        repeat(4) { assertTrue(ring.write(it.toShort())) }
        assertTrue(!ring.write(9))
        val out = ShortArray(4)
        assertEquals(4, ring.read(out))
        assertEquals(listOf<Short>(0, 1, 2, 3), out.toList())
    }

    @Test fun `a backlog is skipped down to the newest samples`() {
        val ring = ShortRing(10)
        repeat(8) { ring.write(it.toShort()) }
        ring.skipTo(3)
        val out = ShortArray(10)
        assertEquals(3, ring.read(out))
        assertEquals(listOf<Short>(5, 6, 7), out.take(3))
    }

    @Test fun `the strip sits above the bottom margin and grows upwards`() {
        val one = CaptionGeometry.top(CaptionPosition.BOTTOM, CaptionSize.MEDIUM, 1, 1920, 1080)
        val two = CaptionGeometry.top(CaptionPosition.BOTTOM, CaptionSize.MEDIUM, 2, 1920, 1080)
        // u = 10.8: margin 13u = 140.4, a line 5u x 1.32 = 71.3.
        assertEquals((1080 - 140.4 - 71.28).toInt(), one)
        assertTrue(two < one)
        assertEquals(140, CaptionGeometry.top(CaptionPosition.TOP, CaptionSize.LARGE, 2, 1920, 1080))
        // Clear of the cards' ruler cursor, whose top is 11.6u up from the bottom.
        val bottomOfStrip = one + 5f * 10.8f * 1.32f
        assertTrue(bottomOfStrip <= 1080 - 11.6f * 10.8f)
        // Lines keep to 70% of a landscape frame, 86% of a portrait one (less the plate padding).
        assertEquals(1920 * 0.70f - 2 * 1.6f * 10.8f, CaptionGeometry.maxTextWidth(1920, 1080), 0.5f)
        assertEquals(1080 * 0.86f - 2 * 1.6f * 10.8f, CaptionGeometry.maxTextWidth(1080, 1920), 0.5f)
    }
}
