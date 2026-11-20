package app.irlsupreme.engine.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtmpPoliciesTest {
    // --- frame policy ---

    @Test fun afterAReconnectTheFirstVideoForwardedIsAKeyFrame() {
        val p = RtmpFramePolicy()
        p.onConnected(0)
        var t = 0L
        val forwarded = mutableListOf<Boolean>() // key flag of each forwarded frame
        // P frames first (the encoder hasn't honoured the request yet), then a key frame, then P frames.
        for (key in listOf(false, false, false, true, false, false)) {
            t += 33
            if (p.onVideo(t, key)) forwarded += key
        }
        assertEquals(listOf(true, false, false), forwarded)
        assertEquals(3L, p.selfDropped)
        assertTrue("audio flows at once", p.onAudio())
        // A second connect gates again.
        p.onConnected(t)
        assertFalse(p.onVideo(t + 33, false))
        assertTrue(p.onVideo(t + 66, true))
    }

    @Test fun aMissingKeyframeIsRequestedAgainEverySecond() {
        val p = RtmpFramePolicy()
        p.onConnected(0)
        var requests = 0
        var t = 0L
        while (t < 2_500) {
            t += 33
            p.onVideo(t, false)
            if (p.takeKeyframeRequest()) requests++
        }
        assertEquals(2, requests) // at ~1 s and ~2 s (the connect itself asked at 0)
    }

    @Test fun aDeepBacklogDropsVideoUntilAKeyFrameButKeepsAudio() {
        val p = RtmpFramePolicy()
        p.onConnected(0)
        assertTrue(p.onVideo(10, true))
        var t = 10L
        // The backlog climbs past 1.5 s.
        p.updateBacklog(t, 1_600)
        assertTrue(p.waitingForIdr)
        var audioDropped = 0
        repeat(30) {
            t += 33
            assertFalse("non-key video dropped", p.onVideo(t, false))
            if (!p.onAudio()) audioDropped++
        }
        // A key frame while still above the low mark is held back too: the picture would only freeze behind the queue.
        assertFalse(p.onVideo(t + 1, true))
        assertFalse("no request while still deep", p.takeKeyframeRequest())
        p.updateBacklog(t, 2_900)
        repeat(10) { t += 33; if (!p.onAudio()) audioDropped++ }
        assertEquals("no audio dropped below the hard limit", 0, audioDropped)
        // Drained under 1 s: a key frame is requested, and video resumes only on it.
        p.updateBacklog(t, 800)
        assertTrue(p.takeKeyframeRequest())
        t += 33
        assertFalse(p.onVideo(t, false))
        t += 33
        assertTrue(p.onVideo(t, true))
        assertFalse(p.waitingForIdr)
        t += 33
        assertTrue(p.onVideo(t, false))
    }

    @Test fun pastTheHardLimitItFlushesOnceAndDropsAudioAsOneRun() {
        val p = RtmpFramePolicy()
        p.onConnected(0)
        p.onVideo(10, true)
        assertEquals(RtmpFramePolicy.Action.NONE, p.updateBacklog(500, 2_000))
        assertEquals(RtmpFramePolicy.Action.FLUSH, p.updateBacklog(1_000, 3_200))
        assertTrue(p.takeKeyframeRequest())
        assertTrue("queue cleared: audio flows again", p.onAudio())
        // Still deep (the queue couldn't be cleared yet) right after: no second flush within 2 s, audio dropped as a run.
        assertEquals(RtmpFramePolicy.Action.NONE, p.updateBacklog(1_500, 3_400))
        assertFalse(p.onAudio())
        assertFalse(p.onAudio())
        assertEquals(RtmpFramePolicy.Action.FLUSH, p.updateBacklog(3_000, 3_600))
        p.updateBacklog(3_500, 200)
        assertTrue(p.onAudio())
        assertFalse("video waits for a key frame after a flush", p.onVideo(3_510, false))
        assertTrue(p.onVideo(3_543, true))
    }

    // --- meter ---

    @Test fun meterReadsBacklogAsTimeAndStaysFiniteInAStall() {
        val m = RtmpMeter(30)
        m.update(0, 0, 0, 0, 0)
        // 6 Mbps in, 6 Mbps out, 300 KB queued: 400 ms.
        m.update(1_000, 750_000, 750_000, 300_000, 40)
        assertEquals(6_000_000L, m.throughputBps)
        assertEquals(400, m.queueDelayMs)
        // Nothing leaves for a second: the backlog reads as the media waiting, not as infinity.
        m.update(2_000, 750_000, 1_500_000, 1_050_000, 120)
        assertTrue("finite: ${m.queueDelayMs}", m.queueDelayMs in 1_000..3_000)
        // No byte count: items over fps + 47.
        val n = RtmpMeter(30)
        n.update(0, 0, 0, -1, 154)
        assertEquals(2_000, n.queueDelayMs)
        // A counter that restarts (new connection) isn't negative traffic.
        m.update(3_000, 10, 20, 0, 0)
        assertTrue(m.throughputBps >= 0)
        assertEquals(0, m.queueDelayMs)
    }

    // --- stall watchdog ---

    @Test fun watchdogFiresOnceAtSevenSecondsOfNoProgress() {
        val w = StallWatchdog()
        w.onConnected(0)
        var bytes = 0L
        var fired = -1L
        var fires = 0
        var t = 0L
        // Progress for 10 s, then nothing.
        while (t < 30_000) {
            t += 1_000
            if (t <= 10_000) bytes += 500_000
            val r = w.check(t, encoderProducing = true, bytesSent = bytes)
            if (r >= 0) { fires++; if (fired < 0) fired = t }
        }
        assertEquals(1, fires)
        assertEquals(17_000L, fired)
    }

    @Test fun watchdogWaitsOutTheGraceAndIgnoresSlowProgress() {
        val w = StallWatchdog()
        w.onConnected(0)
        // Nothing at all from the start: not before 7 s (and never inside the 5 s grace).
        for (t in 1_000L..6_000L step 1_000) assertEquals(-1L, w.check(t, true, 0))
        assertEquals(7_000L, w.check(7_000, true, 0))
        // A slow link that still moves a few bytes every few seconds never fires.
        val slow = StallWatchdog()
        slow.onConnected(0)
        var bytes = 0L
        for (t in 1_000L..60_000L step 1_000) {
            if (t % 5_000 == 0L) bytes += 1_000
            assertEquals(-1L, slow.check(t, true, bytes))
        }
        // An encoder that produces nothing isn't a stalled link.
        val idle = StallWatchdog()
        idle.onConnected(0)
        for (t in 1_000L..30_000L step 1_000) assertEquals(-1L, idle.check(t, false, 0))
    }

    // --- reconnect timing ---

    @Test fun backoffIsCappedAtTwoSecondsForTheFirstThirtySeconds() {
        val r = ReconnectPolicy()
        val delays = (0 until 6).map { r.onFailure(it * 2_000L) }
        assertEquals(listOf(500L, 1_000L, 2_000L, 2_000L, 2_000L, 2_000L), delays)
        assertEquals(8_000L, r.onFailure(31_000))
        r.onSuccess()
        assertEquals(500L, r.onFailure(40_000))
        r.resetBackoff()
        assertEquals(500L, r.onFailure(41_000))
    }

    @Test fun aNetworkComingUpStartsAConnectAtOnce() {
        val n = NetworkTrigger()
        assertEquals("not while live", -1L, n.onAvailable(1, 0, reconnecting = false))
        val m = NetworkTrigger()
        assertEquals(0L, m.onAvailable(7, 1_000, reconnecting = true))
    }

    @Test fun repeatedCapabilityUpdatesCauseOneReconnect() {
        val n = NetworkTrigger()
        var triggers = 0
        // A network becomes default (available, then validated right away), then signal updates stream in.
        if (n.onAvailable(5, 0, true) >= 0) triggers++
        repeat(20) { if (n.onCapabilities(5, validated = true, nowMs = 50L + it * 300, reconnecting = true) >= 0) triggers++ }
        assertEquals(1, triggers)
        // Losing validation and getting it back (a captive portal cleared, say) is news again.
        assertEquals(-1L, n.onCapabilities(5, validated = false, nowMs = 10_000, reconnecting = true))
        assertEquals(0L, n.onCapabilities(5, validated = true, nowMs = 12_000, reconnecting = true))
    }

    @Test fun networkTriggersAreRateLimitedNotLost() {
        val n = NetworkTrigger()
        assertEquals(0L, n.onAvailable(1, 0, true))
        // A different network 500 ms later: deferred to the end of the 2 s gap.
        assertEquals(1_500L, n.onAvailable(2, 500, true))
        n.onLost(2)
        assertEquals("the same network coming back is news", 2_000L, n.onAvailable(2, 2_000, true))
    }
}
