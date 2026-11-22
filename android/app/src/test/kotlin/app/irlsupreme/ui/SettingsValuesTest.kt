package app.irlsupreme.ui

import app.irlsupreme.engine.Facing
import app.irlsupreme.service.BrbStatus
import app.irlsupreme.service.ExternalCamera
import app.irlsupreme.service.HelperState
import app.irlsupreme.service.OverlaySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Settings pages' text models and layout rules. */
class SettingsValuesTest {
    private fun check(checked: Boolean = true, reachable: Boolean = true, udpBlocked: Boolean = false, ms: Long? = 38) =
        BrbCheck(checked = checked, reachable = reachable, udpBlocked = udpBlocked, ms = ms, port = 5000)

    // ---- Index values ----

    @Test fun `twitch row reports the account, not a sentence`() {
        assertEquals("yourchannel", twitchValue(false, false, false, false, SignIn.SignedIn("yourchannel")))
        assertEquals("Not signed in", twitchValue(false, false, false, false, SignIn.SignedOut))
        assertEquals("My OBS", twitchValue(true, false, false, false, SignIn.SignedOut))
        assertEquals("No stream key", twitchValue(false, false, true, false, SignIn.SignedIn("a")))
        assertEquals("Stream key", twitchValue(false, false, true, true, SignIn.SignedOut))
        assertEquals("Set on your server", twitchValue(false, true, false, false, SignIn.SignedOut))
    }

    @Test fun `camera row is short`() {
        assertEquals("Phone", cameraValue(ExternalCamera.Kind.PHONE, null))
        assertEquals("Pocket 3 · not set up", cameraValue(ExternalCamera.Kind.POCKET, null))
        assertEquals("OsmoPocket3-1234", cameraValue(ExternalCamera.Kind.POCKET, "OsmoPocket3-1234"))
    }

    @Test fun `start on offers front, then back, in one word each`() {
        assertEquals("Front", facingTitle(Facing.FRONT))
        assertEquals("Back", facingTitle(Facing.BACK))
    }

    @Test fun `combine row says ready, never the address`() {
        assertEquals("Off", combineValue(false, BrbState.READY))
        assertEquals("On · ready", combineValue(true, brbState(true, true, check())))
        assertEquals("On · not paired", combineValue(true, brbState(true, false, null)))
        assertEquals("On · checking", combineValue(true, brbState(true, true, check(checked = false))))
        assertEquals("On · UDP blocked", combineValue(true, brbState(true, true, check(udpBlocked = true))))
        assertEquals("On · can't reach server", combineValue(true, brbState(true, true, check(reachable = false))))
    }

    @Test fun `only a check that ran can fail`() {
        assertEquals(BrbState.CHECKING, brbState(true, true, null))
        assertEquals(BrbState.CHECKING, brbState(true, true, check(checked = false, reachable = false)))
        assertEquals(BrbState.UNREACHABLE, brbState(true, true, check(reachable = false)))
        assertEquals(BrbState.OFF, brbState(false, true, check()))
        assertEquals(BrbState.UNPAIRED, brbState(false, false, null))
        assertEquals(StateMark.UNCHECKED, brbStatusWord(BrbState.CHECKING, 5000, false).second)
        assertEquals(StateMark.FAILED, brbStatusWord(BrbState.UNREACHABLE, 5000, false).second)
        assertEquals("UDP 5000 blocked", brbStatusWord(BrbState.UDP_BLOCKED, 5000, false).first)
        assertEquals("Paired · ready" to StateMark.VERIFIED, brbStatusWord(BrbState.READY, 5000, true))
    }

    @Test fun `helpers row reports state`() {
        assertEquals("Off", helpersValue(helpingAnother = false, hostingOn = false, combine = true))
        assertEquals("On", helpersValue(helpingAnother = false, hostingOn = true, combine = true))
        assertEquals("On · needs Combine", helpersValue(helpingAnother = false, hostingOn = true, combine = false))
        assertEquals("Helping another phone", helpersValue(helpingAnother = true, hostingOn = true, combine = true))
    }

    @Test fun `video row is quality and shape`() {
        assertEquals("1080p · portrait", videoValue(Quality.HD1080, StreamOrientation.PORTRAIT).text)
        // The figure is mono: one span, over "1080p" only.
        val spans = videoValue(Quality.HD1080, StreamOrientation.LANDSCAPE).spanStyles
        assertEquals(1, spans.size)
        assertEquals(0 until 5, spans[0].start until spans[0].end)
    }

    @Test fun `overlays row lists what's on in sentence case`() {
        assertEquals("Alerts", overlaysValue(OverlaySettings(), webValid = false))
        assertEquals("Chat · alerts", overlaysValue(OverlaySettings(chat = true), webValid = false))
        assertEquals("Off", overlaysValue(OverlaySettings(alertsOn = false), webValid = false))
        assertEquals("Silent alerts · text", overlaysValue(OverlaySettings(alertSound = false, textOn = true, text = "hi"), webValid = false))
        // Blank text and an invalid web link aren't on, whatever the switch says.
        assertEquals("Alerts", overlaysValue(OverlaySettings(textOn = true, text = " ", browserOn = true), webValid = false))
        assertEquals("Alerts · web", overlaysValue(OverlaySettings(browserOn = true), webValid = true))
    }

    @Test fun `screen off row`() {
        assertEquals("Restricted", screenOffValue(restricted = true, exempt = true))
        assertEquals("Allowed", screenOffValue(restricted = false, exempt = true))
        assertEquals("Not allowed yet", screenOffValue(restricted = false, exempt = false))
        // Only Allowed is good: the others mean the stream stops about 5 minutes after the screen goes off.
        assertEquals(StateMark.VERIFIED, screenOffMark("Allowed"))
        assertEquals(StateMark.FAILED, screenOffMark("Not allowed yet"))
        assertEquals(StateMark.FAILED, screenOffMark("Restricted"))
    }

    // ---- Combine page ----

    @Test fun `server key values`() {
        val st = BrbStatus(state = "waiting", outputKind = "twitch", outputHost = null, outputTest = true, phoneSlate = true)
        assertEquals("Twitch · test stream", sendsToValue(st))
        assertEquals("Twitch", sendsToValue(st.copy(outputTest = false)))
        assertEquals("Recording on the server", sendsToValue(st.copy(outputKind = "record")))
        assertEquals("rtmp.example", sendsToValue(st.copy(outputKind = "rtmp", outputHost = "rtmp.example")))
        assertEquals("From this phone", brbScreenValue(st))
        assertEquals("Server's default", brbScreenValue(st.copy(phoneSlate = false)))
    }

    @Test fun `route shows at most four tracks, then plus N`() {
        val links = (1..6).map { RouteLink("Link $it", up = true) }
        val r = RouteRules.route(links, BrbState.READY, 38, "Twitch", "test", StateMark.VERIFIED)
        assertEquals(RouteRules.MAX_TRACKS, r.tracks.size)
        assertEquals(2, r.extra)
        val few = RouteRules.route(links.take(2), BrbState.READY, 38, "Twitch", null, StateMark.VERIFIED)
        assertEquals(0, few.extra)
    }

    @Test fun `route values appear only when measured`() {
        assertNull(RouteRules.speed(null))
        assertNull(RouteRules.speed(0))
        assertEquals("~18 Mb/s up", RouteRules.speed(18_200))
        assertEquals("~6 Mb/s up", RouteRules.speed(5_600))
        assertEquals("~%.1f Mb/s up".format(0.6), RouteRules.speed(600))
        assertEquals("Ready · 38 ms", RouteRules.serverValue(BrbState.READY, 38))
        assertEquals("Ready", RouteRules.serverValue(BrbState.READY, null))
        assertNull(RouteRules.serverValue(BrbState.OFF, null))
        assertEquals(StateMark.FAILED, RouteRules.serverMark(BrbState.UNREACHABLE))
        assertEquals(StateMark.UNCHECKED, RouteRules.serverMark(BrbState.CHECKING))
    }

    @Test fun `route reads as one sentence`() {
        val two = listOf(RouteLink("Wi‑Fi", true, 18_000), RouteLink("AT&T", true, 6_000))
        assertEquals(
            "Path: phone, 2 links, your server ready, Twitch.",
            RouteRules.describe(RouteRules.route(two, BrbState.READY, 38, "Twitch", null, StateMark.VERIFIED)),
        )
        val oneDown = listOf(RouteLink("Wi‑Fi", true), RouteLink("AT&T", false))
        assertEquals(
            "Path: phone, 1 of 2 links up, your server not checked yet, Twitch, test.",
            RouteRules.describe(RouteRules.route(oneDown, BrbState.CHECKING, null, "Twitch", "test", StateMark.VERIFIED)),
        )
    }

    // ---- Video ----

    @Test fun `quality rows carry mono figures`() {
        assertEquals("6 Mb/s · %.1f GB/h".format(Quality.HD1080.gbPerHour), qualityValue(Quality.HD1080))
        assertTrue(qualityValue(Quality.HD720).startsWith("3.5 Mb/s") || qualityValue(Quality.HD720).startsWith("3,5 Mb/s"))
        assertEquals("16:9", orientationValue(StreamOrientation.LANDSCAPE))
        assertEquals("9:16", orientationValue(StreamOrientation.PORTRAIT))
        StreamOrientation.entries.forEach { assertTrue(orientationHelp(it).endsWith(".")) }
    }

    // ---- Licences ----

    @Test fun `font notices list both OFL fonts without the dash rules`() {
        val ofl = "Copyright 2020 The Archivo Project Authors\n\n-----------------------------------------\nSIL OPEN FONT LICENSE Version 1.1\n" +
            "-----------------------------------------\n\nPREAMBLE\nThe goals of the Open Font License"
        val blocks = noticeBlocks(fontNotices(ofl, ofl))
        val headings = blocks.filterIsInstance<NoticeBlock.Heading>().map { it.text }
        assertEquals(listOf("Fonts (SIL Open Font License 1.1)", "Archivo", "JetBrains Mono"), headings)
        assertFalse(blocks.filterIsInstance<NoticeBlock.Para>().any { it.text.contains("---") })
        assertEquals("", fontNotices(null, null))
    }

    // ---- Helpers ----

    @Test fun `helper states are red only when something is verifiably wrong`() {
        assertEquals(StateMark.FAILED, helpingText(HelperState.WrongPassword, null).mark)
        assertEquals(StateMark.FAILED, helpingText(HelperState.NoMobileData, null).mark)
        assertEquals(StateMark.UNCHECKED, helpingText(HelperState.Searching("Pixel"), null).mark)
        assertEquals(StateMark.UNCHECKED, helpingText(HelperState.WaitingToGoLive(""), null).mark)
        assertEquals("Waiting for the streaming phone to go live", helpingText(HelperState.WaitingToGoLive(""), null).status)
        assertEquals("Helping Pixel 7", helpingText(HelperState.Helping("Pixel 7", true, 4200, 318), null).status)
        assertTrue(helpingText(HelperState.WifiOff, "Pixel 7").line.contains("Pixel 7's hotspot"))
    }

    @Test fun `helper texts keep to one line, the rest goes to a footnote`() {
        val all = listOf(
            HelperState.NoWifi, HelperState.WifiOff, HelperState.WaitingToGoLive("A"), HelperState.JoiningHotspot("A"),
            HelperState.NoMobileData, HelperState.Searching("A"), HelperState.Connecting("A"), HelperState.WrongPassword,
            HelperState.Helping("A", true, 1000, 1), HelperState.Helping("A", false, 0, 0),
        )
        all.forEach { s ->
            val t = helpingText(s, null)
            assertTrue("$s: ${t.line}", t.line.length <= 90)
            assertFalse("$s ends with !", t.line.endsWith("!"))
        }
        assertEquals("%.1f Mb/s · 318 MB".format(4.2), helpingSent(4200, 318))
    }

    // ---- Overlays preview ----

    @Test fun `BRB preview keeps corner layers only with on-cards`() {
        val s = OverlaySettings(chat = true, textOn = true, text = "hi", imageOn = true, onCards = false)
        assertEquals(s, previewLayers(s, PreviewOn.CAMERA))
        val brb = previewLayers(s, PreviewOn.BRB)
        assertFalse(brb.chat || brb.textOn || brb.imageOn)
        assertTrue(brb.alertsOn)
        assertEquals(s.copy(onCards = true), previewLayers(s.copy(onCards = true), PreviewOn.BRB))
    }
}
