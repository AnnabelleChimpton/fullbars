package app.irlsupreme.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.irlsupreme.engine.video.StreamPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tokens' stated contrast ratios, computed with real WCAG relative luminance rather than trusted.
 * Over-video pairs are measured against the worst case: the 88% Ink scrim composited over pure white (#2A2A29).
 */
class TokenContrastTest {
    private val white = 0xFFFFFFFF.toInt()

    private fun ratio(fg: Color, bg: Color) = Wcag.contrast(fg, bg)
    private fun ratio(fg: Color, bg: Int) = Wcag.contrast(fg.toArgb(), bg)

    /** Asserts the stated ratio (to two decimals) and the WCAG floor it has to clear. */
    private fun assertRatio(name: String, actual: Double, stated: Double, floor: Double) {
        assertEquals("$name ratio", stated, actual, 0.006)
        assertTrue("$name is ${"%.2f".format(actual)}:1, under $floor:1", actual >= floor)
    }

    @Test fun `the scrim over white composites to the worst-case grey`() {
        val worst = Wcag.over(Steady.Ink.toArgb(), OverVideo.SCRIM_ALPHA, white)
        assertEquals(OverVideo.worstCase.toArgb(), worst)
        assertEquals(0xFF2A2A29.toInt(), worst)
        assertEquals(OverVideo.SCRIM_ALPHA, OverVideo.scrim.alpha, 0.003f)
    }

    @Test fun `every over-video pair passes against the scrim over white`() {
        val worst = Wcag.over(Steady.Ink.toArgb(), OverVideo.SCRIM_ALPHA, white)
        assertRatio("text", ratio(OverVideo.text, worst), 12.74, 4.5)
        assertRatio("text2", ratio(OverVideo.text2, worst), 8.52, 4.5)
        assertRatio("key outline", ratio(OverVideo.keyOutline, worst), 3.96, 3.0) // non-text: 3:1
        assertRatio("key outline on Ink", ratio(OverVideo.keyOutline, Steady.Ink), 5.36, 3.0)
        assertRatio("engaged key", ratio(OverVideo.keyEngagedContent, OverVideo.keyEngagedFill), 17.24, 4.5)
        assertRatio("pill keyline vs white", ratio(OverVideo.keyline, white), 19.44, 3.0)
        assertRatio("Great on scrim", ratio(LiveHealth.great, worst), 8.27, 4.5)
        assertRatio("Weak on scrim", ratio(LiveHealth.weak, worst), 8.91, 4.5)
        assertRatio("Camera lost on scrim", ratio(LiveHealth.cameraLost, worst), 6.13, 4.5)
        assertRatio("Reconnecting on scrim", ratio(LiveHealth.reconnecting, worst), 5.18, 4.5)
        assertRatio("Privacy on scrim", ratio(LiveHealth.privacy, worst), 12.83, 4.5)
    }

    @Test fun `Graphite is retired over video because it fails on today's scrim`() {
        // Today's Ink 80% scrim over white; Graphite measured 4.25–4.32 there, under AA.
        val oldWorst = Wcag.over(0xFF0D0D0D.toInt(), 0.80f, white)
        assertTrue(ratio(Steady.Graphite, oldWorst) < 4.5)
    }

    @Test fun `opaque pill fills keep their text readable`() {
        assertRatio("Ink on Weak", ratio(LiveHealth.onHealth, LiveHealth.weak), 12.05, 4.5)
        assertRatio("Ink on Camera lost", ratio(LiveHealth.onHealth, LiveHealth.cameraLost), 8.29, 4.5)
        assertRatio("Reconnecting on Ink", ratio(LiveHealth.reconnecting, Steady.Ink), 7.01, 4.5)
        assertRatio("Great on Ink", ratio(LiveHealth.great, Steady.Ink), 11.19, 4.5)
        assertRatio("Bone on Ink", ratio(Steady.Bone, Steady.Ink), 17.24, 4.5)
    }

    @Test fun `app dark text pairs`() {
        val c = DarkFb
        assertEquals(Color(0xFF0D0D0C), Steady.Ink)
        assertRatio("text on bg", ratio(c.text, c.bg), 17.24, 4.5)
        assertRatio("text2 on bg", ratio(c.text2, c.bg), 7.58, 4.5)
        assertRatio("text2 on s2", ratio(c.text2, c.s2), 6.71, 4.5)
        assertRatio("text2 on s3", ratio(c.text2, c.s3), 6.18, 4.5)
        assertRatio("glyphOff on bg", ratio(c.glyphOff, c.bg), 7.61, 3.0)
        assertRatio("CTA", ratio(c.onCta, c.cta), 17.24, 4.5)
        assertRatio("error on bg", ratio(c.error, c.bg), 7.01, 4.5)
        assertRatio("Twitch button", ratio(c.onTwitch, c.twitch), 4.64, 4.5)
        // ruleStrong is decorative-plus-label: about 2:1, and never the only cue.
        assertEquals(2.1, ratio(c.ruleStrong, c.bg), 0.01)
    }

    @Test fun `app light text pairs`() {
        val c = LightFb
        assertRatio("text on bg", ratio(c.text, c.bg), 17.24, 4.5)
        assertRatio("text2 on bg", ratio(c.text2, c.bg), 5.40, 4.5)
        assertRatio("text2 on s2", ratio(c.text2, c.s2), 4.84, 4.5)
        assertTrue(ratio(c.text2, c.s3) >= 4.5)
        assertRatio("CTA", ratio(c.onCta, c.cta), 17.24, 4.5)
        assertRatio("error on bg", ratio(c.error, c.bg), 5.83, 4.5)
        assertRatio("onAir on bg", ratio(c.onAir, c.bg), 5.83, 3.0)
        listOf(LightHealth.great, LightHealth.weak, LightHealth.cameraLost, LightHealth.reconnecting).forEach {
            assertTrue("light health ${it.toArgb().toUInt().toString(16)} on Bone", ratio(it, c.bg) >= 4.8 - 0.005)
        }
    }

    @Test fun `health colours are exactly the brand values`() {
        assertEquals(Color(0xFF34E07A), LiveHealth.great)
        assertEquals(Color(0xFFFFC233), LiveHealth.weak)
        assertEquals(Color(0xFFFF8A3D), LiveHealth.cameraLost)
        assertEquals(Color(0xFFFF6B6B), LiveHealth.reconnecting)
        assertEquals(Color(0xFFF2F2F2), LiveHealth.privacy)
        assertEquals(Color(0xFF137A3E), LightHealth.great)
        assertEquals(Color(0xFF8A6100), LightHealth.weak)
        assertEquals(Color(0xFFA84A0F), LightHealth.cameraLost)
        assertEquals(Color(0xFFB42318), LightHealth.reconnecting)
    }

    @Test fun `stream palette matches the app tokens and passes on its plates`() {
        assertEquals(Steady.Ink.toArgb(), StreamPalette.INK)
        assertEquals(Steady.Bone.toArgb(), StreamPalette.BONE)
        assertEquals(OverVideo.text2.toArgb(), StreamPalette.TEXT2)
        assertEquals(OverVideo.keyOutline.toArgb(), StreamPalette.TICK)
        assertEquals(LiveHealth.weak.toArgb(), StreamPalette.WEAK)
        assertEquals(LiveHealth.cameraLost.toArgb(), StreamPalette.CAMERA_LOST)
        val alertPlate = Wcag.over(StreamPalette.INK, StreamPalette.ALERT_PLATE_ALPHA, white)
        assertEquals(0xFF20201F.toInt(), alertPlate)
        assertTrue(Wcag.contrast(StreamPalette.BONE, alertPlate) >= 4.5)
        assertTrue(Wcag.contrast(StreamPalette.TEXT2, alertPlate) >= 4.5)
        val chatPlate = Wcag.over(StreamPalette.INK, StreamPalette.CHAT_PLATE_ALPHA, white)
        assertEquals(12.74, Wcag.contrast(StreamPalette.BONE, chatPlate), 0.006)
        assertEquals(8.52, Wcag.contrast(StreamPalette.TEXT2, chatPlate), 0.006)
        assertEquals(0x66123456, StreamPalette.withAlpha(0xFF123456.toInt(), 0.4f))
    }

    @Test fun `contrast maths agrees with the WCAG reference points`() {
        assertEquals(21.0, Wcag.contrast(0xFF000000.toInt(), white), 1e-9)
        assertEquals(1.0, Wcag.contrast(white, white), 1e-9)
        assertEquals(1.0, Wcag.luminance(white), 1e-9)
        assertEquals(0.0, Wcag.luminance(0xFF000000.toInt()), 1e-9)
    }
}
