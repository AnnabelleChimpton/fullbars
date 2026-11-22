package app.irlsupreme.ui

import app.irlsupreme.engine.Health
import app.irlsupreme.ui.live.MIC_LOUD_HINT
import app.irlsupreme.ui.live.POCKET_HINT
import app.irlsupreme.ui.live.externalCameraNotice
import app.irlsupreme.ui.live.pocketNotice
import app.irlsupreme.dji.PocketState
import app.irlsupreme.ui.live.msToNextSecond
import app.irlsupreme.ui.live.notice
import app.irlsupreme.ui.live.pocketAlarm
import app.irlsupreme.ui.live.pocketHintDue
import app.irlsupreme.ui.live.pocketSpot
import app.irlsupreme.ui.live.timerText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePocketAndTimerTest {
    @Test fun `the timer ticks just after each of its own seconds`() {
        val since = 1_000_250L
        assertEquals(1_005L, msToNextSecond(since, since))
        assertEquals(10L, msToNextSecond(since, since + 995))
        assertEquals("right on a second: the next one", 1_005L, msToNextSecond(since, since + 1_000))
        assertEquals(255L, msToNextSecond(since, since + 750))
        // Waking at that time shows the next second, never the old one again.
        val now = since + 750
        assertEquals("0:00:01", timerText(since, now + msToNextSecond(since, now)))
        assertTrue("before going live it follows the clock's seconds", msToNextSecond(0, 12_345) in 1..1_005)
    }

    @Test fun `the pocket screen line moves every minute and never stays put`() {
        for (m in 0L until 30L) {
            assertNotEquals("minute $m", pocketSpot(m), pocketSpot(m + 1))
            val (x, y) = pocketSpot(m)
            assertTrue(x in 0f..1f && y in 0f..1f)
        }
        assertEquals(pocketSpot(-1), pocketSpot(5))
    }

    @Test fun `the pocket screen brightens for trouble and the end`() {
        assertTrue(pocketAlarm(Health.RECONNECTING, ending = false))
        assertTrue(pocketAlarm(Health.CAMERA_LOST, ending = false))
        assertTrue(pocketAlarm(Health.FAILED, ending = false))
        assertTrue(pocketAlarm(Health.OFF, ending = false))
        assertTrue(pocketAlarm(Health.GREAT, ending = true))
        assertFalse(pocketAlarm(Health.GREAT, ending = false))
        assertFalse(pocketAlarm(Health.WEAK, ending = false))
        assertFalse(pocketAlarm(Health.HOT, ending = false))
    }

    @Test fun `the pocket screen is offered for heat, not for a low battery on a cool phone`() {
        assertTrue(pocketHintDue(thermalRung = 1, thermalStatus = 2, thermalHeadroom = Float.NaN))
        assertTrue(pocketHintDue(thermalRung = 1, thermalStatus = 0, thermalHeadroom = 0.9f))
        assertFalse(pocketHintDue(thermalRung = 1, thermalStatus = 0, thermalHeadroom = 0.5f)) // the battery floor
        assertFalse(pocketHintDue(thermalRung = 1, thermalStatus = 1, thermalHeadroom = Float.NaN))
        assertFalse(pocketHintDue(thermalRung = 0, thermalStatus = 2, thermalHeadroom = 0.9f))
    }

    @Test fun `an external camera with no picture says so instead of a lost link`() {
        assertEquals("Phone camera on air · camera connected, no video yet", externalCameraNotice("connected, no video yet", "phone"))
        assertEquals("Phone camera on air · the camera sends HEVC; set it to H.264", externalCameraNotice("camera sends HEVC; set it to H.264", "phone"))
        assertEquals("Phone camera on air · waiting for the camera", externalCameraNotice("waiting for the camera", "phone"))
        assertEquals("Phone camera on air · camera connecting", externalCameraNotice("connected", "phone"))
        assertEquals("Phone camera on air · camera lost", externalCameraNotice("camera disconnected", "phone"))
        assertNull("on screen: nothing to say", externalCameraNotice("1920x1080 · camera audio", "camera"))
        assertNull(externalCameraNotice(null, "phone"))
    }

    @Test fun `a mic change and the pocket screen offer take their place in the notice line`() {
        assertEquals("External mic disconnected", notice(false, null, false, null, null, micLoud = true, micInput = "External mic disconnected"))
        assertEquals(MIC_LOUD_HINT, notice(false, null, false, null, null, micLoud = true, pocketHint = true))
        assertEquals(POCKET_HINT, notice(false, null, false, "Phone camera on air · camera lost", null, pocketHint = true))
        assertEquals("No audio", notice(false, "No audio", false, null, null, micInput = "External mic disconnected"))
    }

    @Test fun `60 fps says why it can't be chosen`() {
        assertEquals("Phone camera only.", fps60Help(phoneCamera = false, can60 = true))
        assertEquals("Checking this phone…", fps60Help(phoneCamera = true, can60 = null))
        assertEquals("This phone can't film 60 fps at this size.", fps60Help(phoneCamera = true, can60 = false))
        assertTrue(fps60Help(phoneCamera = true, can60 = true).startsWith("Smoother"))
    }

    @Test fun `the sound delay reads with a real minus and in words for TalkBack`() {
        assertEquals("+40 ms", offsetText(40))
        assertEquals("−20 ms", offsetText(-20))
        assertEquals("0 ms", offsetText(0))
        assertEquals("sound 40 milliseconds later", offsetSpoken(40))
        assertEquals("sound 20 milliseconds earlier", offsetSpoken(-20))
        assertEquals("no delay", offsetSpoken(0))
    }

    @Test fun `the Pocket 3 notice leads with what viewers get and keeps steps for when they matter`() {
        // A step that needs you is always in full.
        assertEquals(PocketState.ApproveOnCamera.label, pocketNotice(PocketState.ApproveOnCamera, preview = false, onCamera = true))
        // Preview: the step count, or what going live now would mean.
        assertEquals(PocketState.Connecting.label, pocketNotice(PocketState.Connecting, preview = true, onCamera = true))
        assertEquals("Pocket 3 not ready: you'd go live on the phone camera",
            pocketNotice(PocketState.Reconnecting("Can't reach the Pocket 3. Is it on and close?", 5), preview = true, onCamera = true))
        // Live on the camera scene: the phone camera is on air meanwhile. On a card: just the Pocket.
        assertEquals("Phone camera on air · Pocket 3 connecting", pocketNotice(PocketState.Connecting, preview = false, onCamera = true))
        assertEquals("Pocket 3 connecting", pocketNotice(PocketState.Starting, preview = false, onCamera = false))
        assertEquals("Phone camera on air · Pocket 3 not connected, retrying",
            pocketNotice(PocketState.Reconnecting("Can't reach the Pocket 3. Is it on and close?", 5), preview = false, onCamera = true))
        assertEquals("Pocket 3 didn't start", pocketNotice(PocketState.Failed("No hotspot"), preview = false, onCamera = false))
    }
}
