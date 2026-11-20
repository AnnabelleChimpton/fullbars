package app.irlsupreme.engine.video

import app.irlsupreme.engine.audio.AlertSounds
import app.irlsupreme.engine.video.Slates.Card
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Motion on stream: crossfades, alert wipes, the time-left rule and the chime's timing. */
class OnStreamMotionTest {
    @Test fun `camera and chosen cards crossfade, while automatic cards, Privacy and heat cut`() {
        assertTrue(SceneRules.crossfades(null, Card.BRB, 0))
        assertTrue(SceneRules.crossfades(Card.BRB, null, 1))
        assertTrue(SceneRules.crossfades(null, Card.STARTING_SOON, 0))
        assertTrue(SceneRules.crossfades(Card.ENDING, Card.BRB, 0))
        assertTrue("back from Privacy fades", SceneRules.crossfades(Card.PRIVACY, null, 0))
        assertFalse("into Privacy cuts: the camera is off now", SceneRules.crossfades(null, Card.PRIVACY, 0))
        assertFalse(SceneRules.crossfades(null, Card.WEAK_SIGNAL, 0))
        assertFalse(SceneRules.crossfades(Card.WEAK_SIGNAL, null, 0))
        assertFalse(SceneRules.crossfades(Card.CAMERA_LOST, Card.BRB, 0))
        for (rung in 2..3) assertFalse(SceneRules.crossfades(null, Card.BRB, rung))
        assertFalse(SceneRules.crossfades(Card.BRB, Card.BRB, 0))
        assertEquals(280L, SceneRules.CROSSFADE_MS)
    }

    @Test fun `alerts show on Live, BRB, Starting soon and Ending, and are held everywhere else`() {
        for (shown in listOf(null, Card.BRB, Card.STARTING_SOON, Card.ENDING)) assertTrue("$shown", SceneRules.alertsAllowed(shown))
        for (held in listOf(Card.PRIVACY, Card.WEAK_SIGNAL, Card.CAMERA_LOST, Card.SIGNAL_LOST)) {
            assertFalse("$held", SceneRules.alertsAllowed(held))
        }
    }

    @Test fun `tiers follow the chime`() {
        assertEquals(AlertTier.QUIET, AlertTier.of(AlertSounds.Style.SOFT))
        assertEquals(AlertTier.MEDIUM, AlertTier.of(AlertSounds.Style.BRIGHT))
        assertEquals(AlertTier.LOUD, AlertTier.of(AlertSounds.Style.BIG))
    }

    @Test fun `a raid wipes in over 280ms, dwells 7200ms losing a segment every 600ms, wipes out in 180ms`() {
        val t = AlertTier.LOUD
        val dwell = 7_200L
        assertEquals(0f, AlertMotion.reveal(t, dwell, 0), 0f)
        val early = AlertMotion.reveal(t, dwell, 70)
        assertTrue("Enter decelerates: a quarter of the time shows over a quarter", early > 0.25f && early < 1f)
        assertEquals(1f, AlertMotion.reveal(t, dwell, 280), 0f)
        assertEquals(1f, AlertMotion.reveal(t, dwell, 280 + 7_199), 0f)
        assertEquals(12, AlertMotion.ruleRemaining(t, dwell, 280))
        assertEquals(12, AlertMotion.ruleRemaining(t, dwell, 280 + 599))
        assertEquals(11, AlertMotion.ruleRemaining(t, dwell, 280 + 600))
        assertEquals(1, AlertMotion.ruleRemaining(t, dwell, 280 + 7_199))
        assertEquals(0, AlertMotion.ruleRemaining(t, dwell, 280 + 7_200))
        val late = AlertMotion.reveal(t, dwell, 280 + 7_200 + 45)
        assertTrue("Exit accelerates: most of it is still there a quarter of the way in", late > 0.75f && late < 1f)
        assertEquals(0f, AlertMotion.reveal(t, dwell, 280 + 7_200 + 180), 1e-6f)
        assertFalse(AlertMotion.done(t, dwell, 280 + 7_200 + 179))
        assertTrue(AlertMotion.done(t, dwell, 280 + 7_200 + 180))
        assertEquals(280 + 7_200 + 180 + 400L, AlertMotion.slotMs(t, dwell))
    }

    @Test fun `a follow wipes in faster, in 180ms`() {
        assertEquals(180L, AlertMotion.enterMs(AlertTier.QUIET))
        assertEquals(280L, AlertMotion.enterMs(AlertTier.MEDIUM))
        assertEquals(1f, AlertMotion.reveal(AlertTier.QUIET, 4_000, 180), 0f)
        assertTrue(AlertMotion.reveal(AlertTier.MEDIUM, 6_000, 180) < 1f)
    }

    @Test fun `the reveal only grows on the way in and only shrinks on the way out`() {
        var last = -1f
        for (ms in 0L..280L step 10) {
            val r = AlertMotion.reveal(AlertTier.MEDIUM, 6_000, ms)
            assertTrue(r >= last); last = r
        }
        for (ms in 6_280L..6_460L step 10) {
            val r = AlertMotion.reveal(AlertTier.MEDIUM, 6_000, ms)
            assertTrue(r <= last); last = r
        }
    }

    @Test fun `exit now wipes out from where it is`() {
        val t = AlertTier.LOUD
        val half = AlertMotion.reveal(t, 7_200, 100)
        assertEquals(half, AlertMotion.reveal(t, 7_200, 100, exitAtMs = 100), 1e-6f)
        assertTrue(AlertMotion.reveal(t, 7_200, 190, exitAtMs = 100) < half)
        assertTrue(AlertMotion.done(t, 7_200, 280, exitAtMs = 100))
    }

    @Test fun `hot phones cut in and out, and the rule still steps`() {
        val t = AlertTier.LOUD
        assertEquals(1f, AlertMotion.reveal(t, 7_200, 0, cut = true), 0f)
        assertEquals(1f, AlertMotion.reveal(t, 7_200, 280 + 7_199, cut = true), 0f)
        assertEquals(0f, AlertMotion.reveal(t, 7_200, 280 + 7_200, cut = true), 0f)
        assertTrue(AlertMotion.done(t, 7_200, 280 + 7_200, cut = true))
        assertEquals(6, AlertMotion.ruleRemaining(t, 7_200, 280 + 3_600))
    }

    @Test fun `the chime starts early by its attack, so the attack lands on the first fully revealed frame`() {
        for (style in AlertSounds.Style.entries) {
            val tier = AlertTier.of(style)
            val delay = AlertMotion.chimeDelayMs(tier, style)
            assertEquals(AlertMotion.enterMs(tier), delay + AlertSounds.attackMs(style))
            assertTrue(delay >= 0)
        }
    }

    @Test fun `the easing curves match their cubic-beziers`() {
        val enter = AlertMotion.enterEase
        val exit = AlertMotion.exitEase
        assertEquals(0f, enter.transform(0f), 0f)
        assertEquals(1f, enter.transform(1f), 0f)
        // cubic-bezier(0.2, 0, 0, 1) at x = 0.5 is 0.878; (0.4, 0, 1, 1) at 0.5 is 0.325.
        assertEquals(0.878f, enter.transform(0.5f), 0.005f)
        assertEquals(0.325f, exit.transform(0.5f), 0.005f)
    }
}
