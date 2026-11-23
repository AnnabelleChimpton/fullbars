package app.irlsupreme.engine.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCameraLostTest {
    private val ms = 1_000_000L
    private val now = 1_000_000 * ms
    private val hold = 500 * ms

    @Test fun framesStoppingIsALoss() {
        val live = now - 60_000 * ms
        assertFalse(SceneRules.phoneCameraLost(now, now - 400 * ms, live, hold))
        assertTrue(SceneRules.phoneCameraLost(now, now - 600 * ms, live, hold))
    }

    /**
     * The 2026-11-22 stream: the standby camera closed while the Pocket was steady, then reopened for a Pocket dropout.
     * Its last frame was 80 s old, and for the ~0.5 s before the first new one the pill said "Camera lost".
     */
    @Test fun aReopenedCameraIsntLostBeforeItsFirstFrame() {
        val reopenedAt = now - 300 * ms
        assertFalse(SceneRules.phoneCameraLost(now, now - 80_000 * ms, reopenedAt, hold))
        // Once it has sent a frame, it's judged by that frame again.
        assertTrue(SceneRules.phoneCameraLost(now, reopenedAt + 10 * ms, reopenedAt, 200 * ms))
    }

    @Test fun aCameraThatNeverSendsAFrameIsLostAfterTheGrace() {
        assertFalse(SceneRules.phoneCameraLost(now, 0L, now - 1_900 * ms, hold))
        assertTrue(SceneRules.phoneCameraLost(now, 0L, now - 2_100 * ms, hold))
        assertTrue(SceneRules.phoneCameraLost(now, now - 80_000 * ms, now - 2_100 * ms, hold))
    }

    @Test fun neverLostBeforeItWentLive() {
        assertFalse(SceneRules.phoneCameraLost(now, 0L, 0L, hold))
    }
}
