package app.irlsupreme.engine.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalHoldTest {
    private val ms = 1_000_000L
    private val now = 100_000 * ms

    @Test fun neverWithoutAFrame() {
        assertFalse(SceneRules.holdExternal(now, 0L, 10, now))
    }

    @Test fun plainSilenceHoldsForASecond() {
        assertTrue(SceneRules.holdExternal(now, now - 900 * ms, 0, 0L))
        assertFalse(SceneRules.holdExternal(now, now - 1_100 * ms, 0, 0L))
    }

    /** The anchoring step: ~0.6 s with no new frame, phone camera still open from the warm-up. No flash. */
    @Test fun aRecentAnchorOrQueuedFramesHoldLonger() {
        assertTrue(SceneRules.holdExternal(now, now - 1_500 * ms, 0, now - 1_000 * ms))
        assertFalse(SceneRules.holdExternal(now, now - 1_500 * ms, 0, now - 2_500 * ms))
        assertTrue(SceneRules.holdExternal(now, now - 2_000 * ms, 18, 0L))
    }

    @Test fun neverPastTheHoldLimit() {
        assertFalse(SceneRules.holdExternal(now, now - 3_100 * ms, 18, now - 100 * ms))
    }
}
