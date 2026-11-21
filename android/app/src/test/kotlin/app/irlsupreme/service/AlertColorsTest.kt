package app.irlsupreme.service

import app.irlsupreme.engine.video.AlertRule
import app.irlsupreme.engine.video.AlertTier
import app.irlsupreme.engine.video.StreamPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertColorsTest {
    private val ink = StreamPalette.INK
    private val bone = StreamPalette.BONE

    @Test fun `the defaults are today's colours`() {
        for (tier in listOf(AlertTier.QUIET, AlertTier.MEDIUM)) {
            val c = AlertColors.resolve(AlertPaint(), tier)
            assertEquals(AlertInk(ink, StreamPalette.ALERT_PLATE_ALPHA, bone, StreamPalette.TEXT2, AlertRule.color(tier), null, null), c)
        }
        val raid = AlertColors.resolve(AlertPaint(), AlertTier.LOUD)
        assertEquals(AlertInk(bone, 1f, ink, ink, AlertRule.color(AlertTier.LOUD), ink, null), raid)
    }

    @Test fun `automatic text picks whichever of Bone and Ink reads better`() {
        assertEquals(ink, AlertColors.resolve(AlertPaint(plate = 0xFFFFE066.toInt()), AlertTier.MEDIUM).text)
        assertEquals(bone, AlertColors.resolve(AlertPaint(plate = 0xFF1F3A5F.toInt()), AlertTier.MEDIUM).text)
        assertEquals(bone, AlertColors.resolve(AlertPaint(plate = 0xFF1F3A5F.toInt()), AlertTier.LOUD).text)
        // The rule follows the text, so it reads on whatever plate.
        assertEquals(ink, AlertColors.resolve(AlertPaint(plate = 0xFFFFE066.toInt()), AlertTier.QUIET).rule)
    }

    @Test fun `automatic text is never hard to read, a chosen one can be`() {
        for (p in AlertColors.PLATE_PRESETS + listOf(0xFF777777.toInt(), 0xFF808080.toInt(), 0xFFFF0000.toInt())) {
            for (tier in AlertTier.entries) assertFalse(ColorMath.toHex(p), AlertColors.hardToRead(AlertPaint(plate = p), tier))
        }
        assertTrue(AlertColors.hardToRead(AlertPaint(text = 0xFF222222.toInt()), AlertTier.MEDIUM))
        assertTrue(AlertColors.hardToRead(AlertPaint(text = 0xFFE8E4DA.toInt()), AlertTier.LOUD))
        assertTrue(AlertColors.hardToRead(AlertPaint(plate = 0xFF3D7BFF.toInt(), text = 0xFF9B6BFF.toInt()), AlertTier.QUIET))
        assertFalse(AlertColors.hardToRead(AlertPaint(plate = bone, text = ink), AlertTier.QUIET))
        assertEquals(1.0, AlertColors.contrast(AlertPaint(plate = ink, text = ink), AlertTier.QUIET), 0.001)
    }

    @Test fun `the warning says the ratio plainly`() {
        assertEquals(
            "Hard to read: the text has 2.1:1 contrast with the plate. Aim for 3:1 or more.",
            app.irlsupreme.ui.contrastWarning(2.06),
        )
    }

    @Test fun `an accent adds a keyline or a stripe in its colour`() {
        val pink = 0xFFFF5FA2.toInt()
        val keyline = AlertColors.resolve(AlertPaint(accent = AlertAccent.KEYLINE, accentColor = pink), AlertTier.MEDIUM)
        assertEquals(pink, keyline.keyline)
        assertNull(keyline.stripe)
        val stripe = AlertColors.resolve(AlertPaint(accent = AlertAccent.STRIPE, accentColor = pink), AlertTier.QUIET)
        assertEquals(pink, stripe.stripe)
        assertNull(stripe.keyline)
        // The raid keeps its Ink keyline with a stripe, and takes the accent's for a keyline.
        assertEquals(ink, AlertColors.resolve(AlertPaint(accent = AlertAccent.STRIPE, accentColor = pink), AlertTier.LOUD).keyline)
        assertEquals(pink, AlertColors.resolve(AlertPaint(accent = AlertAccent.KEYLINE, accentColor = pink), AlertTier.LOUD).keyline)
        // An accent's colour means nothing while it's off.
        assertEquals(AlertColors.resolve(AlertPaint(), AlertTier.QUIET), AlertColors.resolve(AlertPaint(accentColor = pink), AlertTier.QUIET))
    }

    @Test fun `colours are always opaque`() {
        val c = AlertColors.resolve(AlertPaint(plate = 0x00123456, text = 0x10FFFFFF), AlertTier.MEDIUM)
        assertEquals(0xFF123456.toInt(), c.plate)
        assertEquals(0xFFFFFFFF.toInt(), c.text)
    }

    @Test fun `a custom text colour gives a quieter label of the same colour`() {
        val c = AlertColors.resolve(AlertPaint(plate = 0xFF1F3A5F.toInt(), text = 0xFFFFC2DA.toInt()), AlertTier.MEDIUM)
        assertEquals(0xFFC2DA, c.label and 0xFFFFFF)
        assertTrue((c.label ushr 24) in 150..230)
    }
}
