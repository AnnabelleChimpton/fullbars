package app.irlsupreme.service

import app.irlsupreme.engine.CameraPhase
import app.irlsupreme.engine.ConnectionPhase
import app.irlsupreme.engine.Scene
import app.irlsupreme.engine.audio.AlertSounds
import app.irlsupreme.engine.video.AlertTier
import app.irlsupreme.twitch.ChatConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamServiceRulesTest {
    @Test fun `chat that stops for good says what to do, never the raw reason`() {
        assertEquals("Chat is off. Sign in to Twitch again after the stream.",
            ModReplies.chatStatus(ChatConnectionState.Stopped("Twitch sign-in required: refresh token invalid")))
        assertEquals("Chat connected", ModReplies.chatStatus(ChatConnectionState.Connected))
        assertEquals("Chat reconnecting…", ModReplies.chatStatus(ChatConnectionState.Reconnecting(2, "timeout")))
        assertEquals("Chat connecting…", ModReplies.chatStatus(ChatConnectionState.Connecting))
    }

    @Test fun `a mod's Live keeps the streamer's own mute and says so`() {
        assertEquals("Back live", ModReplies.backLive(micStillOff = false))
        assertEquals("Back live (mic still off)", ModReplies.backLive(micStillOff = true))
    }

    @Test fun `restart camera names what it restarts`() {
        assertEquals("Restarting the Pocket feed", ModReplies.restartCam(pocket = true, externalShown = true))
        assertEquals("Restarting the phone camera", ModReplies.restartCam(pocket = false, externalShown = true))
        assertEquals("Restarting camera", ModReplies.restartCam(pocket = false, externalShown = false))
    }

    @Test fun `a mistyped command gets one hint per 10 s`() {
        val c = HintCooldown()
        assertTrue(c.allow("end", 0))
        assertFalse(c.allow("end", 9_999))
        assertTrue("other commands have their own cooldown", c.allow("quality", 5_000))
        assertTrue(c.allow("end", 10_000))
    }

    private fun allowed(scene: Scene, weak: Boolean = false, camera: CameraPhase = CameraPhase.LIVE, connection: ConnectionPhase = ConnectionPhase.LIVE) =
        AlertHold.allowed(scene, weak, camera, connection)

    @Test fun `alerts are held on Privacy, Weak signal and Camera lost`() {
        assertFalse(allowed(Scene.PRIVACY))
        assertFalse(allowed(Scene.LIVE, weak = true))
        assertFalse(allowed(Scene.LIVE, camera = CameraPhase.LOST))
    }

    @Test fun `alerts are held while viewers see no stream`() {
        for (phase in listOf(ConnectionPhase.CONNECTING, ConnectionPhase.RECONNECTING, ConnectionPhase.FAILED, ConnectionPhase.IDLE)) {
            assertFalse(phase.name, allowed(Scene.LIVE, connection = phase))
            assertFalse(phase.name, allowed(Scene.BRB, connection = phase))
        }
    }

    @Test fun `alerts show on Live and the chosen cards, which cover the automatic ones`() {
        assertTrue(allowed(Scene.LIVE))
        for (scene in listOf(Scene.BRB, Scene.STARTING_SOON, Scene.ENDING)) {
            assertTrue(scene.name, allowed(scene))
            // Viewers see the chosen card, not Weak signal or Camera lost, so the alert still lands.
            assertTrue(scene.name, allowed(scene, weak = true, camera = CameraPhase.LOST))
        }
        assertFalse(allowed(Scene.PRIVACY, weak = false, camera = CameraPhase.LIVE))
    }

    @Test fun `alerts sit higher only on the full-frame chosen cards`() {
        assertEquals(setOf(Scene.BRB, Scene.STARTING_SOON, Scene.ENDING), Scene.entries.filter { AlertHold.onCard(it) }.toSet())
    }

    @Test fun `the chime starts so its attack lands on the first full frame`() {
        assertEquals(280L, AlertTiming.enterMs(AlertTier.LOUD, cut = false))
        assertEquals(280L, AlertTiming.enterMs(AlertTier.MEDIUM, cut = false))
        assertEquals(180L, AlertTiming.enterMs(AlertTier.QUIET, cut = false))
        assertEquals(272L, AlertTiming.chimeDelayMs(280L, attackMs = 8))
        assertEquals(172L, AlertTiming.chimeDelayMs(180L, attackMs = 8))
        // An attack longer than the wipe can't start in the past: it starts now.
        assertEquals(0L, AlertTiming.chimeDelayMs(180L, attackMs = 400))
        for (style in AlertSounds.Style.entries) {
            val enter = AlertTiming.enterMs(AlertTier.of(style), cut = false)
            assertEquals(style.name, enter, AlertTiming.chimeDelayMs(enter, AlertSounds.attackMs(style)) + AlertSounds.attackMs(style))
        }
    }

    @Test fun `on a hot phone alerts cut in and out, and the chime starts at once`() {
        assertFalse(AlertTiming.cut(1))
        assertTrue(AlertTiming.cut(2))
        assertEquals(0L, AlertTiming.enterMs(AlertTier.LOUD, cut = true))
        assertEquals(0L, AlertTiming.exitMs(cut = true))
        assertEquals(180L, AlertTiming.exitMs(cut = false))
        assertEquals(0L, AlertTiming.chimeDelayMs(0L, attackMs = 8))
        assertEquals(400L, AlertTiming.GAP_MS)
    }

    @Test fun `held alerts keep the newest 20, oldest first`() {
        val b = AlertBacklog<Int>(AlertQueue.MAX_WAITING)
        for (i in 1..25) b.add(i)
        assertEquals(20, b.size)
        assertEquals(6, b.peek())
        assertEquals((6..25).toList(), generateSequence { b.poll() }.toList())
        assertEquals(null, b.poll())
    }
}
