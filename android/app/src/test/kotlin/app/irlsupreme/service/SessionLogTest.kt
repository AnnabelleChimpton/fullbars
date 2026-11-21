package app.irlsupreme.service

import app.irlsupreme.engine.AudioCounters
import app.irlsupreme.engine.CadenceCounters
import app.irlsupreme.engine.ConnectionPhase
import app.irlsupreme.engine.EngineEvent
import app.irlsupreme.engine.EngineState
import app.irlsupreme.engine.HelperHosting
import app.irlsupreme.engine.LinkInfo
import app.irlsupreme.engine.OutputTarget
import app.irlsupreme.engine.Scene
import app.irlsupreme.engine.SessionSummary
import app.irlsupreme.engine.StreamConfig
import app.irlsupreme.engine.audio.ActiveInput
import app.irlsupreme.engine.audio.MicLevel
import app.irlsupreme.engine.ingest.IngestHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset

class SessionLogTest {
    @get:Rule val tmp = TemporaryFolder()

    private val utc: ZoneId = ZoneOffset.UTC
    /** 2026-11-19 16:25:03.250 UTC. */
    private val t0 = 1_795_105_503_250L

    @Test fun `urls keep only scheme and host, and keys and tokens are blanked`() {
        val key = "live_123456789_AbCdEfGhIjKlMnOpQrStUvWxYz0123"
        val s = SessionLogText.scrub(
            "failed rtmp://live.twitch.tv/app/$key?bandwidthtest=true; pair fullbars://pair?v=1&h=1.2.3.4&k=${"a".repeat(43)} " +
                "Authorization: Bearer abc.def key $key https://user:pw@example.com:8443/v1/end\nnext",
        )
        assertFalse(s, s.contains("live_123456789"))
        assertFalse(s, s.contains("aaaaaaaa"))
        assertFalse(s, s.contains("abc.def"))
        assertFalse(s, s.contains("pw@"))
        assertFalse(s, s.contains("bandwidthtest"))
        assertFalse(s, s.contains("1.2.3.4"))
        assertTrue(s, s.contains("rtmp://live.twitch.tv/<redacted>"))
        assertTrue(s, s.contains("fullbars://pair/<redacted>"))
        assertTrue(s, s.contains("https://example.com:8443/<redacted>"))
        assertFalse("one entry, one line", s.contains('\n'))
    }

    @Test fun `ordinary words survive the scrub`() {
        val line = "snap health=GREAT links=[wifi \"Wi-Fi\" up 3200k 45ms; helper \"Pixel 8\" up 500k 80ms battery 76%]"
        assertEquals(line, SessionLogText.scrub(line))
    }

    @Test fun `file names and stamps are local time`() {
        assertEquals("session-20261119-162503.log", SessionLogText.fileName(t0, utc))
        assertEquals("11-19 16:25:03.250", SessionLogText.timestamp(t0, utc))
        assertEquals("session-20261119-172503.log", SessionLogText.fileName(t0, ZoneOffset.ofHours(1)))
    }

    @Test fun `the start says what the stream was, never its key, passphrase or helper password`() {
        val bonded = StreamConfig(
            OutputTarget.Bonded("203.0.113.9", 5000, "live/0123456789abcdef", passphrase = "srt-secret-passphrase",
                helpers = HelperHosting("helperpw123", "OnePlus", "svc")),
        )
        val text = SessionLogText.startLines(start(bonded, test = false)).joinToString("\n")
        assertTrue(text, text.contains("LIVE stream"))
        assertTrue(text, text.contains("your BRB server (port 5000)"))
        assertFalse(text, text.contains("203.0.113.9")) // logs may be shared: no server address
        assertTrue(text, text.contains("helper phones on"))
        assertTrue(text, text.contains("1920x1080 landscape 30 fps"))
        assertTrue(text, text.contains("phone auto level on, level +4 dB"))
        assertTrue(text, text.contains("SRT latency 2000 ms"))
        assertTrue(text, text.contains("mic options: input auto, plugged-in mic level same as phone, low cut auto, leveler off, mono voice off"))
        assertTrue(text, text.contains("frame rate: 30 fps; lip sync: offset 0 ms, automatic off"))
        assertFalse(text, text.contains("srt-secret"))
        assertFalse(text, text.contains("helperpw"))
        assertFalse(text, text.contains("0123456789abcdef"))

        val direct = StreamConfig(OutputTarget.Rtmp("rtmp://live.twitch.tv/app/live_1_secretkey?bandwidthtest=true"), width = 1080, height = 1920)
        val d = SessionLogText.startLines(start(direct, test = true)).joinToString("\n")
        assertTrue(d, d.contains("TEST stream"))
        assertTrue(d, d.contains("direct RTMP to live.twitch.tv"))
        assertTrue(d, d.contains("portrait"))
        assertFalse(d, d.contains("secretkey"))

        val asked60 = SessionLogText.startLines(start(direct, test = false).copy(requestedFps = 60)).joinToString("\n")
        assertTrue(asked60, asked60.contains("frame rate: 30 fps (asked for 60, not offered here)"))
    }

    @Test fun `a snapshot carries health, rates, links, mic and alerts`() {
        val st = EngineState(
            running = true, connection = ConnectionPhase.LIVE, targetVideoBitrate = 6_000_000, qualityCeiling = 6_000_000,
            measuredVideoBitrate = 5_900_000, sentBitrate = 6_400_000, droppedFrames = 12, thermalStatus = 1, thermalRung = 1,
            activeSource = "camera", externalCamera = "1920x1080 · camera audio",
            links = listOf(
                LinkInfo("wifi", "Wi-Fi", up = true, kbps = 3_200, rttMs = 45),
                LinkInfo("helper", "Pixel", up = true, kbps = 500, rttMs = null, batteryPercent = 76, hot = true),
                LinkInfo("cellular", "T-Mobile", up = false, kbps = 0, rttMs = null),
            ),
        )
        val s = SessionLogText.snapshot(st, "mic peak -1.0", alerts = 3)
        assertTrue(s, s.startsWith("snap health=GREAT conn=LIVE scene=LIVE weak=no target=6000k"))
        assertTrue(s, s.contains("sent=6400k"))
        assertTrue(s, s.contains("dropped=12"))
        assertTrue(s, s.contains("heat=1/rung1"))
        assertTrue(s, s.contains("ext=\"1920x1080 · camera audio\""))
        assertTrue(s, s.contains("mic peak -1.0 alerts=3"))
        assertTrue(s, s.contains("[wifi \"Wi-Fi\" up 3200k 45ms q 0ms nak 0.0/s cap 0 proof never; helper \"Pixel\" up 500k rtt? battery 76% hot q 0ms nak 0.0/s cap 0 proof never; cellular \"T-Mobile\" down]"))
        assertFalse("values at their defaults are left out of the snapshot", s.contains("naks=0") || s.contains("encoder=\"\"") || s.contains("NaN"))
    }

    private data class WithNewFields(
        val known: Int, val glitchRepeats: Long, val audioSource: String, val level: Float, val list: List<Int>, val phase: Scene,
    )

    @Test fun `fields added to the state later still reach the snapshot`() {
        val extras = SessionLogText.extras(WithNewFields(1, 7, "camera", -3.04f, listOf(1), Scene.BRB), setOf("known"))
        assertEquals(
            listOf("audioSource" to "\"camera\"", "glitchRepeats" to "7", "level" to "-3.0", "phase" to "BRB"),
            extras,
        )
    }

    @Test fun `changes are logged as they happen, and nothing when nothing changed`() {
        val a = EngineState(running = true, connection = ConnectionPhase.LIVE, links = listOf(LinkInfo("wifi", "Wi-Fi", true, 3000, 40)))
        assertEquals(emptyList<String>(), SessionLogText.changes(a, a.copy(sentBitrate = 1, links = listOf(LinkInfo("wifi", "Wi-Fi", true, 2000, 80)))))

        val b = a.copy(
            connection = ConnectionPhase.RECONNECTING, reconnectAttempt = 2, scene = Scene.BRB,
            links = listOf(LinkInfo("wifi", "Wi-Fi", false, 0, null), LinkInfo("helper", "Pixel", true, 400, 90)),
            lastError = "SRT closed",
        )
        val c = SessionLogText.changes(a, b)
        assertTrue(c.toString(), "health GREAT -> RECONNECTING" in c)
        assertTrue(c.toString(), "connection LIVE -> RECONNECTING (attempt 2)" in c)
        assertTrue(c.toString(), "scene LIVE -> BRB" in c)
        assertTrue(c.toString(), "links wifi \"Wi-Fi\" down, helper \"Pixel\" up" in c)
        assertTrue(c.toString(), "error: SRT closed" in c)
        assertTrue("the first state is compared with an idle one", "health OFF -> GREAT" in SessionLogText.changes(null, a))
    }

    @Test fun `the end gives the reason and the recap`() {
        val s = SessionSummary(t0, t0 + 4_100_000, t0 + 20_000, 2, 1, 3_100_000_000, 2, 1_000_000, null)
        val lines = SessionLogText.endLines("you ended the stream", s, alerts = 4, zone = utc)
        assertEquals("end: you ended the stream", lines[0])
        assertTrue(lines[1], lines[1].startsWith("summary: live 1h 08m 00s from 11-19 16:25:23.250, running 1h 08m 20s"))
        assertTrue(lines[1], lines[1].contains("sent 3.10 GB"))
        assertTrue(lines[1], lines[1].contains("lowest bitrate 1000k, alerts shown 4"))
        assertEquals("summary: none (the engine wasn't running)", SessionLogText.endLines("the stream didn't start", null, 0, utc)[1])
    }

    @Test fun `the mic window reports its peak, average and how often the limiter held it down`() {
        val w = MicWindow()
        assertNull(w.take())
        w.add(MicLevel(-20f, -1f, 6f))
        w.add(MicLevel(-16f, -3f, 0.2f))
        w.add(MicLevel(-18f, -2f, 2f))
        w.add(MicLevel(-22f, -9f, 0f))
        assertEquals("mic peak -1.0 rms -18.4 voice -18.4 limiting 50% max 6.0dB in-peak -60.0 clip 0 leveler 0.0", w.take())
        assertNull("each window starts afresh", w.take())
    }

    @Test fun `the mic window averages power, not decibels, and keeps a mean of the voice alone`() {
        val w = MicWindow()
        w.add(MicLevel(-20f, -6f, 0f))
        w.add(MicLevel(-60f, -50f, 0f))
        // A loud chunk and a silent one average to about -23 dB (half the power), not -40 dB; the voice alone is -20 dB.
        val line = w.take()!!
        assertTrue(line, line.contains("rms -23.0 voice -20.0"))
        w.add(MicLevel(-60f, -55f, 0f))
        assertTrue("nobody spoke", w.take()!!.contains("voice none"))
    }

    @Test fun `the mic window carries what came in clipped and the leveler`() {
        val w = MicWindow()
        w.add(MicLevel(-20f, -1f, 0f, inPeakDb = -0.1f, inClipped = 3, levelerDb = 2f))
        w.add(MicLevel(-22f, -4f, 0f, inPeakDb = -2f, inClipped = 2, levelerDb = 4.5f))
        val line = w.take()!!
        assertTrue(line, line.endsWith("in-peak -0.1 clip 5 leveler 4.5"))
    }

    @Test fun `engine events become lines, with bitrate changes at most one a second`() {
        val ev = EventLines()
        assertEquals("srtla: link down Wi-Fi (uplink dead)", ev.line(0, EngineEvent.Link("srtla: link down Wi-Fi (uplink dead)")))
        assertEquals("abr 6000 -> 4500 kbps: down (queue 400 ms)", ev.line(100, EngineEvent.Bitrate("abr 6000 -> 4500 kbps: down (queue 400 ms)")))
        assertNull("a burst of bitrate lines is still held back", ev.line(400, EngineEvent.Bitrate("abr 4500 -> 4400 kbps: hold")))
        assertEquals("heat rung 0 -> 1 (status 2)", ev.line(950, EngineEvent.Note("heat rung 0 -> 1 (status 2)")))
        // The engine sends one a second; arriving a little early must not cost the line.
        assertEquals("abr 4400 -> 4600 kbps: up", ev.line(1_099, EngineEvent.Bitrate("abr 4400 -> 4600 kbps: up")))
        assertEquals("abr 4600 -> 4800 kbps: up", ev.line(2_098, EngineEvent.Bitrate("abr 4600 -> 4800 kbps: up")))
        val mic = EngineEvent.MicInput(ActiveInput(7, android.media.AudioDeviceInfo.TYPE_USB_DEVICE, "USB mic"), "External mic connected")
        assertEquals("mic input USB mic: External mic connected", ev.line(1_200, mic))
    }

    @Test fun `the snapshot carries each link's queue and resends, and the new state fields`() {
        val st = EngineState(
            running = true, connection = ConnectionPhase.LIVE,
            links = listOf(
                LinkInfo("wifi", "Wi-Fi", up = true, kbps = 3_200, rttMs = 45, queueMs = 120, nakRate = 2.5, capPackets = 300, overCap = 4),
                LinkInfo(
                    "cell", "Mobile data", up = true, kbps = 400, rttMs = 70, capPackets = 80, proofAgoMs = 140,
                    localDrops = 3, priority = "secondary", bytesSent = 12_400_000,
                ),
            ),
            provenLinks = 2, clientSendDrops = 5,
            abrAction = "hold (draining)", queueMs = 120, naks = 42, overhead = 1.152, thermalHeadroom = 0.81f, phoneBatteryPct = 64,
            encoder = "c2.qti.avc.encoder High@4.1 CBR", videoDelayMs = 80, avDelayMs = 0,
            cadence = CadenceCounters(phoneRepeatTicks = 3, tickP99Ms = 9.25f),
            ingest = IngestHealth(true, 0, 1, 2_000, 30_000, 0, 120, 0),
            micInput = ActiveInput(1, android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC, "Phone mic"),
        )
        val s = SessionLogText.snapshot(st, null, alerts = 0, screenOn = false, pocketScreen = true)
        assertTrue(
            s,
            s.contains(
                "screen=off pocketScreen links=[wifi \"Wi-Fi\" up 3200k 45ms q 120ms nak 2.5/s cap 300 over 4 proof never; " +
                    "cell \"Mobile data\" up 400k 70ms q 0ms nak 0.0/s cap 80 proof 140ms secondary drops 3 sent 12.4MB]",
            ),
        )
        assertTrue(s, s.contains(" provenLinks=2"))
        assertTrue(s, s.contains(" clientSendDrops=5"))
        assertTrue(s, s.contains(" queueMs=120"))
        assertTrue(s, s.contains(" naks=42"))
        assertTrue(s, s.contains(" overhead=1.152"))
        assertTrue(s, s.contains(" abrAction=\"hold (draining)\""))
        assertTrue(s, s.contains(" thermalHeadroom=0.8"))
        assertTrue(s, s.contains(" phoneBatteryPct=64"))
        assertTrue(s, s.contains(" encoder=\"c2.qti.avc.encoder High@4.1 CBR\""))
        assertTrue(s, s.contains(" videoDelayMs=80 "))
        assertTrue(s, s.contains(" cadence.phoneRepeatTicks=3"))
        assertTrue(s, s.contains(" cadence.tickP99Ms=9.3") || s.contains(" cadence.tickP99Ms=9.2"))
        assertTrue(s, s.contains(" ingest.spareMs=120"))
        assertTrue(s, s.contains(" micInput=\"Phone mic\"/BUILT_IN"))
        assertFalse("nested values only through their own prefixes", s.contains(" cadence=") || s.contains(" ingest="))
        assertTrue("a true flag is kept", SessionLogText.snapshot(st.copy(batteryCharging = true), null, 0).contains(" batteryCharging=true"))
    }

    @Test fun `the encoders are logged once they are known`() {
        val a = EngineState(running = true)
        val b = a.copy(encoder = "c2.qti.avc.encoder High@4.1 CBR", audio = AudioCounters(aacEncoder = "c2.android.aac.encoder"))
        val c = SessionLogText.changes(a, b)
        assertTrue(c.toString(), "video encoder c2.qti.avc.encoder High@4.1 CBR" in c)
        assertTrue(c.toString(), "audio encoder c2.android.aac.encoder" in c)
        assertEquals(emptyList<String>(), SessionLogText.changes(b, b))
    }

    @Test fun `the recap gives minutes at each heat rung, battery per hour and screen time`() {
        val s = SessionSummary(
            t0, t0 + 3_600_000, t0 + 10_000, 0, 0, 1_000_000_000, 2, 2_000_000, null,
            thermalRungMs = listOf(1_800_000, 1_500_000, 300_000, 0), batteryStartPct = 90, batteryEndPct = 72, batterySpanMs = 3_600_000,
        )
        val lines = SessionLogText.endLines("you ended the stream", s, alerts = 0, zone = utc, screenOffMs = 1_200_000, pocketScreenMs = 300_000)
        val recap = lines.last()
        assertEquals(
            "recap: heat rung 0 30.0 min, rung 1 25.0 min, rung 2 5.0 min, rung 3 0.0 min; battery 90% -> 72%, 18.0%/h; " +
                "screen off 20m 00s, pocket screen 5m 00s",
            recap,
        )
        val short = s.copy(batterySpanMs = 60_000)
        assertTrue(SessionLogText.recap(short, 0, 0).contains("battery 90% -> 72%, too short for a rate"))
        assertTrue(SessionLogText.recap(s.copy(batteryStartPct = -1), 0, 0).contains("battery not read"))
    }

    @Test fun `time on adds up the stretches something was on`() {
        val t = TimeOn()
        t.reset(0, on = false)
        t.set(1_000, true)
        t.set(1_500, true) // no change
        t.set(4_000, false)
        t.set(10_000, true)
        assertEquals(3_000 + 2_000, t.totalMs(12_000))
        t.reset(20_000, on = true)
        assertEquals(500, t.totalMs(20_500))
    }

    @Test fun `lines land on disk as they are written, with the header first`() {
        val dir = tmp.newFolder("logs")
        val log = SessionLog(dir, t0, utc, clock = { t0 + 1_000 })
        log.line("one")
        log.awaitWritten()
        val f = File(dir, "session-20261119-162503.log")
        assertEquals(f, log.file)
        val text = f.readText()
        assertTrue(text, text.startsWith("11-19 16:25:04.250 Fullbars session log, started 2026-11-19 16:25:03 +00:00"))
        assertTrue("flushed before close", text.endsWith("11-19 16:25:04.250 one\n"))
        log.close()
        log.line("after close")
        log.awaitWritten()
        assertFalse(f.readText().contains("after close"))
    }

    @Test fun `only the newest five logs are kept`() {
        val dir = tmp.newFolder("logs")
        for (i in 0 until 7) {
            SessionLog(dir, t0 + i * 60_000L, utc).apply { line("stream $i"); close(); awaitWritten() }
        }
        File(dir, "notes.txt").writeText("not a log")
        SessionLog.prune(dir, 5)
        val names = dir.list()!!.sorted()
        assertEquals(6, names.size)
        assertTrue("other files are left alone", "notes.txt" in names)
        assertEquals("the oldest two went", "session-20261119-162703.log", names.first { it.startsWith("session-") })
    }

    @Test fun `two streams in one second get their own files`() {
        val dir = tmp.newFolder("logs")
        SessionLog(dir, t0, utc).apply { close(); awaitWritten() }
        val second = SessionLog(dir, t0 + 500, utc).apply { close(); awaitWritten() }
        assertEquals("session-20261119-162503-2.log", second.file?.name)
    }

    @Test fun `a full log drops snapshots but keeps the end`() {
        val dir = tmp.newFolder("logs")
        val log = SessionLog(dir, t0, utc, maxBytes = 70_000)
        repeat(200) { log.line("snap " + "x".repeat(100)) }
        log.essential("end: you ended the stream")
        log.close()
        log.awaitWritten()
        val text = log.file!!.readText()
        assertTrue(text.length <= 70_000)
        assertTrue(text, text.contains("log full"))
        assertTrue(text.trimEnd().endsWith("end: you ended the stream"))
    }

    @Test fun `what Android says later goes into that stream's log`() {
        val files = tmp.newFolder("files")
        SessionLog(SessionLog.dir(files), t0, utc).apply { line("snap"); close(); awaitWritten() }
        SessionLog.appendLater(files, t0, "after the stream: Android ended the app, reason 13", utc)
        val text = File(SessionLog.dir(files), "session-20261119-162503.log").readText()
        assertTrue(text, text.trimEnd().endsWith("after the stream: Android ended the app, reason 13"))
        SessionLog.appendLater(files, t0 + 5_000, "no such stream", utc) // nothing to add to: no file made
        assertEquals(1, SessionLog.dir(files).list()!!.size)
    }

    private fun start(c: StreamConfig, test: Boolean) = SessionStart(
        config = c, test = test, cameraKind = "phone", pocket = null, startFacing = "back",
        mic = MicPrefs(gainDb = 4), app = "1.0 (1)", device = "OnePlus CPH2581, Android 16",
    )
}
