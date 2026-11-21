package app.irlsupreme.service

import android.net.wifi.WifiManager
import app.irlsupreme.engine.CameraInput
import app.irlsupreme.engine.OutputTarget
import app.irlsupreme.engine.StreamConfig
import app.irlsupreme.engine.audio.InputChoice
import app.irlsupreme.engine.audio.LowCut
import app.irlsupreme.ui.Quality
import app.irlsupreme.ui.StreamOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSettingsRulesTest {
    @Test fun `60 fps is asked for only with the phone camera and only where the phone can film it`() {
        var asked = 0
        val yes = { asked++; true }
        assertEquals(60, StreamRules.fps(60, phoneCamera = true, supported = yes))
        assertEquals(30, StreamRules.fps(60, phoneCamera = true) { false })
        assertEquals(30, StreamRules.fps(60, phoneCamera = false, supported = yes))
        assertEquals(30, StreamRules.fps(30, phoneCamera = true, supported = yes))
        assertEquals("the phone is only asked when it matters", 1, asked)
        assertEquals("anything else falls back to 30", 30, StreamRules.fps(48, phoneCamera = true, supported = yes))
    }

    @Test fun `each quality preset gets its own lowest bitrate, and 1080p keeps 1 Mbps`() {
        for (o in StreamOrientation.entries) {
            val (w1080, h1080) = o.size(Quality.HD1080)
            val (w720, h720) = o.size(Quality.HD720)
            assertEquals(o.name, 1_000_000, StreamRules.minBitrate(w1080, h1080))
            assertEquals(o.name, 600_000, StreamRules.minBitrate(w720, h720))
        }
        val c = StreamRules.config(OutputTarget.Rtmp("rtmp://x/app/k"), StreamChoices(1280, 720, Quality.HD720.bitrate), fps = 30)
        assertEquals(600_000, c.minVideoBitrate)
        assertEquals(Quality.HD720.bitrate, c.videoBitrate)
    }

    @Test fun `the delay choice becomes the bonded stream's SRT latency, with Fast kept off the list`() {
        assertEquals(2_000, StreamRules.bonded("h", 5000, "live/x", null, StreamDelay.BALANCED, null).latencyMs)
        assertEquals(4_000, StreamRules.bonded("h", 5000, "live/x", "pw", StreamDelay.ROCK_SOLID, null).latencyMs)
        assertEquals(listOf(2_000, 4_000), StreamDelay.entries.map { it.latencyMs })
        assertTrue("the paired server waits out reordering", StreamRules.bonded("h", 5000, "s", null, StreamDelay.BALANCED, null).receiverReorderTolerant)
        assertFalse("mobile data shares the load as before", StreamRules.bonded("h", 5000, "s", null, StreamDelay.BALANCED, null).cellularAsSecondary)
    }

    @Test fun `the config carries the mic options, sound quality and offset as the settings show them`() {
        val mic = MicPrefs(gainDb = 3, raw = true, rawGainDb = 18, input = InputChoice.EXTERNAL, externalGainDb = -4, lowCut = LowCut.WIND,
            voiceLeveler = true, monoVoice = true)
        val c = StreamRules.config(
            OutputTarget.Rtmp("rtmp://x/app/k"),
            StreamChoices(1920, 1080, 6_000_000, fps = 60, camera = CameraInput.Phone, mic = mic, audio = AudioQuality.HIGH, avOffsetMs = 47),
            fps = 60,
        )
        assertTrue("the stream reads the options now", STREAM_USES_MIC_OPTIONS)
        assertEquals(60, c.fps)
        assertEquals(3f, c.micGainDb)
        assertTrue(c.rawMic)
        assertEquals(18f, c.rawMicGainDb)
        assertEquals(InputChoice.EXTERNAL, c.micInput)
        assertEquals(-4f, c.externalMicGainDb)
        assertEquals(LowCut.WIND, c.lowCut)
        assertTrue(c.voiceLeveler)
        assertTrue(c.monoVoice)
        assertEquals(160_000, c.audioBitrate)
        assertTrue(c.audioBitrate in StreamConfig.AUDIO_BITRATES)
        assertEquals("on the 10 ms grid", 50_000_000L, c.avOffsetNs)
        assertFalse("automatic lip sync stays off unless asked for", c.autoLipSync)
        assertEquals(128_000, StreamRules.config(OutputTarget.Rtmp("rtmp://x"), StreamChoices(1920, 1080, 6_000_000), 30).audioBitrate)
    }

    @Test fun `the offset stays within 300 ms either way, in 10 ms steps`() {
        assertEquals(0, StreamRules.avOffsetMs(0))
        assertEquals(300, StreamRules.avOffsetMs(1_000))
        assertEquals(-300, StreamRules.avOffsetMs(-305, allowEarlier = true))
        assertEquals(10, StreamRules.avOffsetMs(14))
        assertEquals(-20, StreamRules.avOffsetMs(-21, allowEarlier = true))
        assertEquals(StreamConfig.MAX_AV_OFFSET_NS, StreamRules.AV_OFFSET_MAX_MS * 1_000_000L)
    }

    @Test fun `the sound can't be set early unless automatic lip sync is on`() {
        // Without automatic lip sync the total delay can't go below 0, so an earlier setting would do nothing.
        assertEquals(0, StreamRules.avOffsetMs(-120))
        assertEquals(0, StreamRules.avOffsetMinMs(allowEarlier = false))
        assertEquals(-300, StreamRules.avOffsetMinMs(allowEarlier = true))
        val early = StreamChoices(1920, 1080, 6_000_000, avOffsetMs = -120)
        assertEquals(0L, StreamRules.config(OutputTarget.Rtmp("rtmp://x"), early, 30).avOffsetNs)
        assertEquals(-120_000_000L, StreamRules.config(OutputTarget.Rtmp("rtmp://x"), early.copy(autoLipSync = true), 30).avOffsetNs)
    }

    private class FakeLock(val mode: Int) : WifiLocks.Lock {
        override var isHeld = false
        var acquires = 0
        override fun acquire() { isHeld = true; acquires++ }
        override fun release() { isHeld = false }
    }

    @Test fun `the high performance Wi-Fi lock is taken and let go with the low latency one`() {
        val made = mutableListOf<FakeLock>()
        val locks = WifiLocks { mode, _ -> FakeLock(mode).also { made += it } }
        locks.acquire("test")
        @Suppress("DEPRECATION")
        assertEquals(setOf(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WifiManager.WIFI_MODE_FULL_HIGH_PERF), made.map { it.mode }.toSet())
        assertTrue(made.all { it.isHeld })
        assertTrue(locks.isHeld)
        locks.acquire("test")
        assertEquals("a second acquire holds the same two", 2, made.size)
        locks.release()
        assertTrue(made.none { it.isHeld })
        assertFalse(locks.isHeld)
    }

    @Test fun `a lock Android refuses doesn't stop the other`() {
        val made = mutableListOf<FakeLock>()
        val locks = WifiLocks { mode, _ ->
            if (mode == WifiManager.WIFI_MODE_FULL_LOW_LATENCY) throw SecurityException("no") else FakeLock(mode).also { made += it }
        }
        locks.acquire("test")
        assertEquals(1, made.size)
        assertTrue(made.single().isHeld)
        locks.release()
        assertFalse(made.single().isHeld)
    }

    @Test fun `the keyframe backstop asks when the server waits for one, at most once a second`() {
        val b = KeyframeBackstop()
        assertFalse(b.onStatus(0, "live", needKeyframe = false))
        assertTrue(b.onStatus(100, "slate", needKeyframe = true))
        assertFalse("the flag can linger a moment", b.onStatus(500, "slate", needKeyframe = true))
        assertTrue(b.onStatus(1_100, "slate", needKeyframe = true))
        assertTrue("the end of a Weak signal episode counts too", b.onStatus(2_200, "weak", needKeyframe = true))
        assertEquals(3, b.requests)
    }

    @Test fun `the keyframe backstop polls fast while a slate is on air or the phone just had trouble`() {
        val b = KeyframeBackstop()
        assertEquals(2_000, b.nextPollMs(0))
        b.onStatus(0, "slate", needKeyframe = false)
        assertEquals(400, b.nextPollMs(0))
        b.onStatus(1_000, "live", needKeyframe = false)
        assertEquals(2_000, b.nextPollMs(1_000))
        b.phone(5_000, trouble = true)
        assertEquals(400, b.nextPollMs(5_000))
        assertEquals("for ten seconds after", 400, b.nextPollMs(14_999))
        assertEquals(2_000, b.nextPollMs(15_000))
    }

    @Test fun `a refused or mismatched slate is sent again, three times quickly, then rarely`() {
        val r = SlateRetry()
        assertFalse("not compared yet", r.onStatus(0, matches = null, rejected = null))
        assertFalse("matches", r.onStatus(0, matches = true, rejected = null))
        // The server reports a mismatch from 1 s on; the phone polls every second.
        val sentAt = mutableListOf<Long>()
        for (t in 1_000L..450_000L step 1_000L) {
            if (r.onStatus(t, matches = false, rejected = null)) { r.sent(t); sentAt += t }
        }
        // Held 5 s first (the server checks at the next live keyframe), then 15, 30 and 60 s apart, then every 5 min.
        assertEquals(listOf(6_000L, 21_000L, 51_000L, 111_000L, 411_000L), sentAt)
        assertEquals(5, r.tries)
        // A good report starts over; a refusal counts as bad too.
        assertFalse(r.onStatus(460_000, matches = true, rejected = null))
        assertEquals(0, r.tries)
        assertFalse(r.onStatus(461_000, matches = null, rejected = "not H.264"))
        assertTrue(r.onStatus(466_000, matches = null, rejected = "not H.264"))
    }
}
