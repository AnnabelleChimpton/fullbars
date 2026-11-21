package app.irlsupreme.engine

import app.irlsupreme.engine.video.CadenceSnapshot
import app.irlsupreme.engine.video.SourceCadence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineRulesTest {
    @Test fun sixtyFpsOnlyForThePhoneCameraAndOnlyWhereItRuns() {
        var asked = 0
        val yes = { _: Int -> asked++; true }
        val no = { _: Int -> asked++; false }
        assertEquals(30 to "", EngineRules.chooseFps(30, phoneCamera = true, supported = no))
        assertEquals(0, asked) // 30 is never probed
        assertEquals(60 to "", EngineRules.chooseFps(60, phoneCamera = true, supported = yes))
        val (fps, why) = EngineRules.chooseFps(60, phoneCamera = true, supported = no)
        assertEquals(30, fps)
        assertTrue(why, why.contains("60 fps") && why.endsWith("using 30"))
        assertEquals(30, EngineRules.chooseFps(60, phoneCamera = false, supported = yes).first)
        assertTrue(EngineRules.chooseFps(60, phoneCamera = false, supported = yes).second.contains("phone camera"))
        assertEquals(30, EngineRules.chooseFps(24, phoneCamera = true, supported = yes).first)
        assertEquals(30, EngineRules.chooseFps(120, phoneCamera = true, supported = yes).first)
    }

    @Test fun theVideoDelayFollowsTheCameraOnScreen() {
        assertEquals(80_000_000L, EngineRules.videoDelayNs("phone", 80_000_000L, 700_000_000L, cameraAudio = false))
        assertEquals(700_000_000L, EngineRules.videoDelayNs("camera", 80_000_000L, 700_000_000L, cameraAudio = false))
        assertEquals(0L, EngineRules.videoDelayNs("none", 80_000_000L, 700_000_000L, cameraAudio = false)) // a card
        assertEquals(0L, EngineRules.videoDelayNs("phone", null, null, cameraAudio = false)) // not measured yet
        assertEquals(0L, EngineRules.videoDelayNs("camera", 1L, null, cameraAudio = false))
        assertEquals(0L, EngineRules.videoDelayNs("phone", -5L, null, cameraAudio = false))
    }

    @Test fun theExternalCamerasOwnSoundIsNeverHeldBack() {
        // Its sound is played out on its picture's clock: already in step. Only the phone mic waits for the picture.
        assertEquals(0L, EngineRules.videoDelayNs("camera", 80_000_000L, 300_000_000L, cameraAudio = true))
        assertEquals(300_000_000L, EngineRules.videoDelayNs("camera", 80_000_000L, 300_000_000L, cameraAudio = false))
    }

    @Test fun theUserOffsetStaysWithinThreeHundredMs() {
        assertEquals(300_000_000L, EngineRules.clampAvOffset(2_000_000_000L))
        assertEquals(-300_000_000L, EngineRules.clampAvOffset(-400_000_000L))
        assertEquals(40_000_000L, EngineRules.clampAvOffset(40_000_000L))
    }

    @Test fun smallBitrateChangesArentWorthAParameterCall() {
        assertFalse(EngineRules.worthApplying(4_080_000, 4_000_000))
        assertTrue(EngineRules.worthApplying(4_081_000, 4_000_000))
        assertTrue(EngineRules.worthApplying(3_900_000, 4_000_000))
    }

    @Test fun aPreLiveMeasurementSeedsOnlyUpwards() {
        assertTrue(EngineRules.worthSeeding(6_000_000, 2_500_000))
        assertFalse(EngineRules.worthSeeding(3_000_000, 2_500_000)) // 75% of it is below the default start
        assertFalse(EngineRules.worthSeeding(0, 2_500_000))
    }

    @Test fun theExternalCameraLine() {
        assertEquals("waiting for the camera", EngineRules.externalCameraStatus("waiting for the camera", 0, 0, 0, false))
        assertEquals("1920x1080 · camera audio", EngineRules.externalCameraStatus("connected", 1920, 1080, 10_000, true))
        assertEquals("1920x1080 · phone mic", EngineRules.externalCameraStatus("connected", 1920, 1080, 10_000, false))
        assertEquals("connected", EngineRules.externalCameraStatus("connected", 0, 0, 3_999, false))
        assertEquals("connected, no video yet", EngineRules.externalCameraStatus("connected", 0, 0, 4_000, false))
        assertNull(EngineRules.externalCameraStatus(null, 0, 0, 0, false))
        assertEquals("camera sends HEVC; set it to H.264", EngineRules.unsupportedVideoStatus("hvc1"))
        assertEquals("camera sends av01; set it to H.264", EngineRules.unsupportedVideoStatus("av01"))
    }

    @Test fun aResumeIsLiveAgainAfterADropNotTheFirstLive() {
        val w = ResumeWatch()
        val seen = listOf(false, false, true, true, false, false, true, true, false, true).map { w.update(it) }
        assertEquals(listOf(false, false, false, false, false, false, true, false, false, true), seen)
    }

    @Test fun preLiveDeliveryNeedsTwoSecondsOfReadings() {
        val r = PreLiveRate()
        assertEquals(0L, r.averageBps(0))
        var t = 0L
        repeat(7) { r.observe(t, 4_000_000); t += 250 } // 0..1500 ms
        assertEquals(0L, r.averageBps(1_500))
        repeat(4) { r.observe(t, 8_000_000); t += 250 } // 1750..2500 ms
        assertEquals((7 * 4_000_000L + 4 * 8_000_000L) / 11, r.averageBps(2_500))
        // Readings older than the 3 s window drop out.
        repeat(12) { r.observe(t, 6_000_000); t += 250 }
        assertEquals(6_000_000L, r.averageBps(t))
    }

    @Test fun oneBitrateLineASecond() {
        val l = RateLimit(1_000)
        assertTrue(l.allow(0))
        assertFalse(l.allow(999))
        assertTrue(l.allow(1_000))
        assertFalse(l.allow(1_500))
    }

    @Test fun cadenceIsFlattenedForTheLog() {
        assertEquals(CadenceCounters(), cadenceCounters(null))
        val c = cadenceCounters(
            CadenceSnapshot(
                phone = SourceCadence(arrivals = 100, latched = 90, repeatTicksWhileLive = 3, sourceGaps = 1, latchedAgeP50Ms = 5f, latchedAgeP95Ms = 9f),
                external = SourceCadence(arrivals = 50, latched = 50, repeatTicksWhileLive = 7, sourceGaps = 2, latchedAgeP50Ms = 4f, latchedAgeP95Ms = 8f),
                tickWorkP50Ms = 4f, tickWorkP99Ms = 12f, tickWorkMaxMs = 20f, lateTicks = 5, skippedTicks = 2, thermalSkips = 0,
                outputFps = 30f, phaseLocked = true, cameraLatencyP50Ms = null, cameraLatencyP95Ms = 90f, externalLateDrops = 4,
            ),
        )
        assertEquals(3, c.phoneRepeatTicks)
        assertEquals(10, c.phoneOverwritten)
        assertEquals(7, c.externalRepeatTicks)
        assertEquals(2, c.externalSourceGaps)
        assertEquals(12f, c.tickP99Ms)
        assertEquals(5, c.lateTicks)
        assertTrue(c.phaseLocked)
        assertTrue(c.cameraLatencyP50Ms.isNaN())
        assertEquals(90f, c.cameraLatencyP95Ms)
        assertEquals(4, c.externalLateDrops)
    }

    @Test fun minimumBitratePerSize() {
        assertEquals(1_000_000, StreamConfig.minBitrateFor(1920, 1080))
        assertEquals(1_000_000, StreamConfig.minBitrateFor(1080, 1920))
        assertEquals(600_000, StreamConfig.minBitrateFor(1280, 720))
        assertEquals(400_000, StreamConfig.minBitrateFor(854, 480))
    }

    @Test fun batteryPerHourNeedsTwoReadingsTenMinutesApart() {
        val s = SessionSummary(0, 3_600_000, 1, 0, 0, 0, 0, 0, null)
        assertNull(s.batteryPctPerHour)
        assertEquals(12.0, s.copy(batteryStartPct = 80, batteryEndPct = 68, batterySpanMs = 3_600_000).batteryPctPerHour!!, 1e-9)
        assertEquals(20.0, s.copy(batteryStartPct = 80, batteryEndPct = 75, batterySpanMs = 900_000).batteryPctPerHour!!, 1e-9)
        assertNull(s.copy(batteryStartPct = 80, batteryEndPct = 79, batterySpanMs = 300_000).batteryPctPerHour)
    }
}
