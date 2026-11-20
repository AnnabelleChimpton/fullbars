package app.irlsupreme.ui.theme

import android.os.VibrationEffect.Composition
import android.view.HapticFeedbackConstants
import app.irlsupreme.engine.video.BrandDraw
import app.irlsupreme.ui.MarkBar
import app.irlsupreme.ui.Meter
import app.irlsupreme.ui.StateMark
import app.irlsupreme.ui.ValueLayout
import app.irlsupreme.ui.footnoteMark
import app.irlsupreme.ui.theme.FbMotion.Change
import app.irlsupreme.ui.theme.FbMotion.Token
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules behind the shared UI primitives: motion, haptics, the value-row layout, meters and marks. */
class FoundationTest {
    // --- Motion ---

    @Test fun `a worse change never springs`() {
        assertEquals(Token.ENTER, FbMotion.growthFor(Change.WORSE, reduced = false))
        assertEquals(Token.SETTLE, FbMotion.growthFor(Change.BETTER, reduced = false))
        assertEquals(Token.SETTLE, FbMotion.growthFor(Change.NEUTRAL, reduced = false))
        Change.entries.forEach { assertEquals(Token.CUT, FbMotion.growthFor(it, reduced = true)) }
    }

    @Test fun `worse rolls the headline down, better rolls it up`() {
        assertEquals(1, FbMotion.rollSign(Change.WORSE))
        assertEquals(-1, FbMotion.rollSign(Change.BETTER))
        assertEquals(0, FbMotion.rollSign(Change.NEUTRAL))
        assertEquals(6, FbMotion.ROLL_DP)
    }

    @Test fun `tick is two hard steps`() {
        val e = FbMotion.TickEasing
        assertEquals(0f, e.transform(0f), 0f)
        assertEquals(0.5f, e.transform(0.1f), 0f)
        assertEquals(0.5f, e.transform(0.49f), 0f)
        assertEquals(1f, e.transform(0.5f), 0f)
        assertEquals(1f, e.transform(1f), 0f)
        assertEquals(FbMotion.TICK_MS, FbMotion.TICK_HOLD_MS * 2)
    }

    @Test fun `the four tokens keep their values`() {
        assertEquals(120, FbMotion.TICK_MS)
        assertEquals(280, FbMotion.ENTER_MS)
        assertEquals(180, FbMotion.EXIT_MS)
        assertEquals(0.85f, FbMotion.SETTLE_DAMPING, 0f)
        assertEquals(700f, FbMotion.SETTLE_STIFFNESS, 0f)
        // Enter decelerates (fast start), Exit accelerates (slow start).
        assertTrue(FbMotion.Enter.transform(0.25f) > 0.5f)
        assertTrue(FbMotion.Exit.transform(0.25f) < 0.25f)
    }

    // --- Haptics ---

    @Test fun `each pattern keeps its composition`() {
        fun s(p: Int, scale: Float, delay: Int = 0) = FbHaptics.Step(p, scale, delay)
        assertEquals(listOf(s(Composition.PRIMITIVE_TICK, 0.4f)), FbHaptics.steps(HapticPattern.KEY))
        assertEquals(listOf(s(Composition.PRIMITIVE_CLICK, 0.7f)), FbHaptics.steps(HapticPattern.TOGGLE))
        assertEquals(
            listOf(s(Composition.PRIMITIVE_QUICK_RISE, 0.5f), s(Composition.PRIMITIVE_CLICK, 1f, 90)),
            FbHaptics.steps(HapticPattern.WENT_LIVE),
        )
        assertEquals(
            listOf(s(Composition.PRIMITIVE_LOW_TICK, 0.8f), s(Composition.PRIMITIVE_LOW_TICK, 0.8f, 110)),
            FbHaptics.steps(HapticPattern.WORSE),
        )
        assertEquals(
            listOf(s(Composition.PRIMITIVE_LOW_TICK, 0.8f), s(Composition.PRIMITIVE_LOW_TICK, 0.8f, 90), s(Composition.PRIMITIVE_LOW_TICK, 0.8f, 90)),
            FbHaptics.steps(HapticPattern.CAMERA_LOST),
        )
        assertEquals(
            listOf(s(Composition.PRIMITIVE_QUICK_FALL, 1f), s(Composition.PRIMITIVE_LOW_TICK, 1f, 60)),
            FbHaptics.steps(HapticPattern.RECONNECTING),
        )
        assertEquals(
            listOf(s(Composition.PRIMITIVE_QUICK_RISE, 0.4f), s(Composition.PRIMITIVE_TICK, 0.8f, 60)),
            FbHaptics.steps(HapticPattern.RECOVERED),
        )
        assertEquals(listOf(s(Composition.PRIMITIVE_TICK, 0.5f), s(Composition.PRIMITIVE_TICK, 0.5f, 70)), FbHaptics.steps(HapticPattern.ALERT))
        assertEquals(listOf(s(Composition.PRIMITIVE_THUD, 1f)), FbHaptics.steps(HapticPattern.HOLD_DONE))
        assertEquals(listOf(s(Composition.PRIMITIVE_LOW_TICK, 0.4f)), FbHaptics.steps(HapticPattern.HOLD_RELEASED))
        assertEquals(listOf(s(Composition.PRIMITIVE_TICK, 0.6f)), FbHaptics.steps(HapticPattern.COPIED))
        assertEquals(listOf(s(Composition.PRIMITIVE_CLICK, 0.8f)), FbHaptics.steps(HapticPattern.SIGNED_IN))
    }

    @Test fun `patterns that mean different things feel different`() {
        val all = HapticPattern.entries.map { FbHaptics.steps(it) }
        // COPIED (TICK 0.6) and KEY (TICK 0.4) differ by weight; nothing else may share a composition.
        assertEquals(all.size, all.toSet().size)
    }

    @Test fun `the fallback keeps today's View constants`() {
        assertEquals(listOf(FbHaptics.Fallback(HapticFeedbackConstants.KEYBOARD_TAP)), FbHaptics.fallback(HapticPattern.KEY))
        assertEquals(listOf(FbHaptics.Fallback(HapticFeedbackConstants.REJECT)), FbHaptics.fallback(HapticPattern.WORSE))
        assertEquals(listOf(FbHaptics.Fallback(HapticFeedbackConstants.LONG_PRESS)), FbHaptics.fallback(HapticPattern.RECONNECTING))
        assertEquals(2, FbHaptics.fallback(HapticPattern.ALERT).size)
        assertTrue(FbHaptics.fallback(HapticPattern.HOLD_RELEASED).isEmpty())
    }

    @Test fun `hold steps get firmer from 0_2 toward 0_6`() {
        val scales = (1..FbHaptics.HOLD_STEPS).map { FbHaptics.holdStepScale(it) }
        assertEquals(0.2f + 0.4f / 7, scales.first(), 1e-6f)
        assertEquals(0.6f, scales.last(), 1e-6f)
        assertEquals(scales.sorted(), scales)
    }

    // --- Value rows ---

    @Test fun `a value wider than 55 percent of the row stacks`() {
        assertFalse(ValueLayout.stacks(labelWidth = 200, valueWidth = 500, rowWidth = 1000, gap = 40))
        assertTrue(ValueLayout.stacks(labelWidth = 100, valueWidth = 560, rowWidth = 1000, gap = 40))
    }

    @Test fun `a value that collides with its label stacks even under 55 percent`() {
        assertTrue(ValueLayout.stacks(labelWidth = 500, valueWidth = 500, rowWidth = 1000, gap = 40))
        assertFalse(ValueLayout.stacks(labelWidth = 400, valueWidth = 500, rowWidth = 1000, gap = 40))
    }

    @Test fun `state marks say what they mean and only failure is a failure`() {
        assertEquals("Checked", StateMark.VERIFIED.spoken)
        assertEquals("Not checked yet", StateMark.UNCHECKED.spoken)
        assertEquals("Failed", StateMark.FAILED.spoken)
        assertEquals("Optional", StateMark.OPTIONAL.spoken)
        assertNull(StateMark.NONE.spoken)
    }

    @Test fun `footnote marks are superscripts`() {
        assertEquals("¹", footnoteMark(1))
        assertEquals("²", footnoteMark(2))
        assertEquals("³", footnoteMark(3))
        assertEquals("(4)", footnoteMark(4))
    }

    // --- Meter and mark ---

    @Test fun `the meter fills from the short end and clamps`() {
        assertEquals(listOf(false, false, false, false), Meter.filled(0))
        assertEquals(listOf(true, true, false, false), Meter.filled(2))
        assertEquals(listOf(true, true, true, true), Meter.filled(9))
        assertEquals(listOf(false, false, false, false), Meter.filled(-1))
        assertEquals(18f, Meter.width.value, 0f) // 4 × 3dp + 3 × 2dp
        assertEquals(14f, Meter.height.value, 0f)
    }

    @Test fun `the recap mark loses one bar per drop, up to three`() {
        assertArrayEquals(intArrayOf(1, 1, 1), MarkBar.forDrops(0))
        assertArrayEquals(intArrayOf(0, 1, 1), MarkBar.forDrops(1))
        assertArrayEquals(intArrayOf(0, 0, 1), MarkBar.forDrops(2))
        assertArrayEquals(intArrayOf(0, 0, 0), MarkBar.forDrops(3))
        assertArrayEquals(intArrayOf(0, 0, 0), MarkBar.forDrops(12))
    }

    @Test fun `app and engine agree on mark bar states`() {
        assertEquals(BrandDraw.BAR_EMPTY, MarkBar.EMPTY)
        assertEquals(BrandDraw.BAR_FULL, MarkBar.FULL)
        assertEquals(BrandDraw.BAR_AMBER, MarkBar.AMBER)
        assertEquals(BrandDraw.BAR_DASHED, MarkBar.DASHED)
        assertNotEquals(MarkBar.EMPTY, MarkBar.DASHED)
    }
}
