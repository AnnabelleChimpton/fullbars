package app.irlsupreme.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import srtlakt.abr.DelayPreset
import org.junit.Test

/** The live screen's single health pill must match what viewers actually get. */
class HealthTest {
    private val live = EngineState(
        running = true, connection = ConnectionPhase.LIVE, camera = CameraPhase.LIVE, activeSource = "phone",
        targetVideoBitrate = 6_000_000, qualityCeiling = 6_000_000, measuredVideoBitrate = 6_000_000,
    )

    @Test fun healthyStreamIsGreat() = assertEquals(Health.GREAT, live.health)

    @Test fun beforeTheOutputConnectsItIsConnectingNotGreat() {
        // IDLE: the output is still waiting for the encoders' first config; nothing has reached viewers.
        assertEquals(Health.CONNECTING, live.copy(connection = ConnectionPhase.IDLE).health)
        assertEquals(Health.CONNECTING, live.copy(connection = ConnectionPhase.CONNECTING, reconnectAttempt = 3).health)
        assertEquals(Health.RECONNECTING, live.copy(connection = ConnectionPhase.RECONNECTING).health)
        assertEquals(Health.OFF, live.copy(running = false).health)
    }

    @Test fun cbrUndershootOnASimpleSceneIsNotWeak() {
        // A static scene: the encoder produces far less than its target. Viewers get full quality.
        assertEquals(Health.GREAT, live.copy(measuredVideoBitrate = 900_000).health)
        // Right after Back live the 2 s window still holds the card's tiny bitrate.
        assertEquals(Health.GREAT, live.copy(measuredVideoBitrate = 50_000).health)
    }

    @Test fun weakWhenQualityWasCutOrTheQueueBacksUp() {
        assertEquals(Health.WEAK, live.copy(targetVideoBitrate = 2_900_000).health)
        assertEquals(Health.GREAT, live.copy(targetVideoBitrate = 3_000_000).health)
        assertEquals(Health.WEAK, live.copy(congested = true).health)
        // Ceiling unknown (a bonded stream still ramping up): the bitrate alone says nothing.
        assertEquals(Health.GREAT, live.copy(targetVideoBitrate = 2_500_000, qualityCeiling = 0).health)
    }

    @Test fun theCameraLostSlateIsCameraLost() {
        assertEquals(Health.CAMERA_LOST, live.copy(camera = CameraPhase.LOST).health)
        // The camera says it's fine (or still opening) but viewers see the slate.
        assertEquals(Health.CAMERA_LOST, live.copy(camera = CameraPhase.OPENING, activeSource = "none").health)
        assertEquals(Health.CAMERA_LOST, live.copy(activeSource = "none").health)
        // A scene card is what viewers see, whatever the camera does.
        assertEquals(Health.BRB, live.copy(activeSource = "none", scene = Scene.BRB).health)
    }

    @Test fun hotFromTheSecondThermalRung() {
        assertEquals(Health.GREAT, live.copy(thermalRung = 1).health)
        assertEquals(Health.HOT, live.copy(thermalRung = 2).health)
        assertEquals(Health.HOT, live.copy(thermalRung = 3, targetVideoBitrate = 2_000_000).health)
        assertEquals(Health.GREAT, live.copy(thermalStatus = 1).health)
    }

    @Test fun bondedCongestionIsTheLinksQueueingPastThePresetHeldForTwoTicks() {
        val preset = DelayPreset.BALANCED // backs off past 200 ms
        assertFalse(EngineRules.bondedCongested(200, preset))
        assertTrue(EngineRules.bondedCongested(201, preset))
        assertTrue(EngineRules.bondedCongested(400, DelayPreset.FAST))
        assertFalse(EngineRules.bondedCongested(400, DelayPreset.ROCK_SOLID))

        val hold = TickHold(2)
        val queue = listOf(50, 350, 60, 350, 400, 500, 90, 300)
        val pill = queue.map { hold.update(EngineRules.bondedCongested(it, preset)) }
        // One tick over is a keyframe burst; two in a row is congestion, and one clear tick ends it.
        assertEquals(listOf(false, false, false, false, true, true, false, false), pill)
        assertEquals(Health.WEAK, live.copy(congested = pill[4]).health)
        assertEquals(Health.GREAT, live.copy(congested = pill[3]).health)
    }

    @Test fun aResetClearsTheHold() {
        val hold = TickHold(2)
        hold.update(true)
        hold.reset()
        assertFalse(hold.update(true))
        assertTrue(hold.update(true))
    }
}
