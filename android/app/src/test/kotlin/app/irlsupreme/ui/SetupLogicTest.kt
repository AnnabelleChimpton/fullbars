package app.irlsupreme.ui

import app.irlsupreme.dji.PocketState
import app.irlsupreme.dji.PocketStep
import app.irlsupreme.dji.PocketSteps
import app.irlsupreme.dji.StepStatus
import app.irlsupreme.service.ExternalCamera.CameraNet
import app.irlsupreme.service.ExternalCamera.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.irlsupreme.engine.SessionSummary
import java.util.Locale

class SetupLogicTest {
    private fun blocker(
        combine: Boolean = false,
        paired: Boolean = false,
        sendToObs: Boolean = false,
        useKey: Boolean = false,
        hasKey: Boolean = false,
        signIn: SignIn = SignIn.SignedIn("streamer"),
        serverOutput: String? = null,
        camera: Kind = Kind.PHONE,
        pocketChosen: Boolean = false,
        cameraNet: CameraNet = CameraNet.PHONE_HOTSPOT,
        hasCameraWifi: Boolean = false,
        hotspotUp: Boolean = true,
    ) = goLiveBlocker(combine, paired, sendToObs, useKey, hasKey, signIn, serverOutput, camera, pocketChosen, cameraNet, hasCameraWifi, hotspotUp)

    @Test fun `signed in, direct, phone camera can go live`() {
        assertNull(blocker())
    }

    @Test fun `paired before signing in, the server would only record, so go live waits for sign-in`() {
        assertEquals(Blocker.SIGN_IN, blocker(combine = true, paired = true, signIn = SignIn.SignedOut, serverOutput = "record"))
        assertEquals(Blocker.ADD_KEY, blocker(combine = true, paired = true, useKey = true, signIn = SignIn.SignedOut, serverOutput = "record"))
        // A server set up on its own to stream to Twitch (or anywhere) still lets the user go live through it.
        assertNull(blocker(combine = true, paired = true, signIn = SignIn.SignedOut, serverOutput = "twitch"))
        // Signed in, the phone sets the server's output itself, whatever it had.
        assertNull(blocker(combine = true, paired = true, serverOutput = "record"))
        // My OBS doesn't need Twitch at all.
        assertNull(blocker(combine = true, paired = true, sendToObs = true, signIn = SignIn.SignedOut, serverOutput = "record"))
    }

    @Test fun `an early tap while the account is being checked can't fall into server-decides mode`() {
        assertEquals(Blocker.CHECKING, blocker(combine = true, paired = true, signIn = SignIn.Checking))
        assertEquals(Blocker.CHECKING, blocker(signIn = SignIn.Checking))
        assertNull(blocker(combine = true, paired = true, sendToObs = true, signIn = SignIn.Checking))
    }

    @Test fun `combine without a server asks to pair first`() {
        assertEquals(Blocker.PAIR_SERVER, blocker(combine = true, paired = false))
    }

    @Test fun `stream key mode needs a key before the permission prompts`() {
        assertEquals(Blocker.ADD_KEY, blocker(useKey = true, hasKey = false, signIn = SignIn.SignedOut))
        assertNull(blocker(useKey = true, hasKey = true, signIn = SignIn.SignedOut))
        assertEquals(Blocker.SIGN_IN, blocker(signIn = SignIn.SignedOut))
    }

    @Test fun `pocket needs a device, the hotspot details and a hotspot that is on`() {
        assertEquals(Blocker.CHOOSE_POCKET, blocker(camera = Kind.POCKET))
        assertEquals(Blocker.CAMERA_WIFI, blocker(camera = Kind.POCKET, pocketChosen = true, hasCameraWifi = false))
        assertEquals(Blocker.HOTSPOT_OFF, blocker(camera = Kind.POCKET, pocketChosen = true, hasCameraWifi = true, hotspotUp = false))
        assertNull(blocker(camera = Kind.POCKET, pocketChosen = true, hasCameraWifi = true, hotspotUp = true))
        // The private hotspot needs neither: the app makes it.
        assertNull(blocker(camera = Kind.POCKET, pocketChosen = true, cameraNet = CameraNet.PRIVATE, hotspotUp = false))
        // Shared Wi-Fi needs the network's details but not the phone's hotspot.
        assertNull(blocker(camera = Kind.POCKET, pocketChosen = true, cameraNet = CameraNet.WIFI, hasCameraWifi = true, hotspotUp = false))
    }

    @Test fun `server decides only without sign-in or key`() {
        assertTrue(serverDecides(true, true, false, false, false, SignIn.SignedOut))
        assertFalse(serverDecides(true, true, false, false, false, SignIn.SignedIn("x")))
        assertFalse(serverDecides(true, true, false, true, true, SignIn.SignedOut))
        assertTrue(serverDecides(true, true, false, true, false, SignIn.SignedIn("x")))
        assertFalse(serverDecides(true, true, true, false, false, SignIn.SignedOut))
        assertFalse(serverDecides(false, true, false, false, false, SignIn.SignedOut))
    }

    @Test fun `1080p only on phones with a media performance class`() {
        assertEquals(Quality.HD720, defaultQuality(0))
        assertEquals(Quality.HD1080, defaultQuality(31))
        assertEquals(Quality.HD1080, defaultQuality(35))
    }

    @Test fun `a pasted server line keeps only the key`() {
        assertEquals("live_123_abc", normalizeStreamKey("rtmps://ingest.global-contribute.live-video.net:443/app/live_123_abc"))
        assertEquals("live_123_abc", normalizeStreamKey("  RTMP://live.twitch.tv/app/live_123_abc "))
        assertEquals("live_123_abc", normalizeStreamKey("live_123_abc"))
        assertEquals("live_12", normalizeStreamKey("live_12")) // typing stays as typed
    }

    @Test fun `the recap only takes credit for drops the BRB server covered`() {
        assertEquals(listOf("No connection drops"), recapDropLines(0, bonded = false))
        assertEquals(listOf("We kept you live through 2 connection drops"), recapDropLines(2, bonded = true))
        val direct = recapDropLines(1, bonded = false)
        assertEquals("Reconnected 1 time. Viewers saw a pause each time.", direct[0])
        assertTrue(direct[1].contains("Signal lost card"))
    }

    @Test fun `stream failures read as plain words`() {
        assertEquals("Twitch refused the stream key.", recapFailure("stream key rejected", bonded = false))
        assertEquals("your server refused the stream. Pair it again.", recapFailure("BRB server rejected the stream", bonded = true))
        assertEquals("your mic isn't working.", recapFailure("microphone unavailable", bonded = false))
        assertFalse(recapFailure("java.net.SocketTimeoutException: timeout", bonded = false).contains("Exception"))
    }

    // --- Home verdict ---

    private fun readings(
        online: Boolean = true,
        upKbps: Int? = 20_000,
        ingestMs: Long? = 38,
        udpBlocked: Boolean = false,
        battery: Int = 100,
        charging: Boolean = false,
        thermal: Int = 0,
        brbPort: Int? = null,
    ) = PreflightReadings(online, "Wi‑Fi", upKbps, ingestMs, udpBlocked, battery, charging, thermal, brbPort)

    private fun preflight(r: PreflightReadings = readings(), q: Quality = Quality.HD1080) = assessPreflight(r, q, 0L, Locale.US)

    @Test fun `ready only when nothing blocks going live and every reading is good`() {
        val v = readyVerdict(null, preflight(), Quality.HD1080)
        assertTrue(v.ready)
        assertEquals("Ready for 1080p", v.text)
    }

    @Test fun `a blocker beats great readings, and its reason is the verdict`() {
        val v = readyVerdict(Blocker.SIGN_IN, preflight(), Quality.HD1080)
        assertFalse(v.ready)
        assertEquals(Blocker.SIGN_IN.text, v.text)
        assertFalse(readyVerdict(Blocker.APPROVING, preflight(), Quality.HD720).ready)
    }

    @Test fun `no verdict while the preflight is still running`() {
        val v = readyVerdict(null, null, Quality.HD1080)
        assertFalse(v.ready)
        assertEquals("Checking your setup…", v.text)
    }

    @Test fun `warnings and failures say why and are never ready`() {
        val weak = readyVerdict(null, preflight(readings(upKbps = 5_000)), Quality.HD1080)
        assertFalse(weak.ready)
        assertTrue(weak.suggest720)
        // At 720p there's nothing to step down to, so no 720p button.
        assertFalse(readyVerdict(null, preflight(readings(thermal = 3), Quality.HD720), Quality.HD720).suggest720)
        assertEquals("No internet connection", readyVerdict(null, preflight(readings(online = false)), Quality.HD1080).text)
        assertEquals("Can't reach Twitch right now", readyVerdict(null, preflight(readings(ingestMs = null)), Quality.HD1080).text)
        assertEquals(
            "UDP 5000 is blocked on your server",
            readyVerdict(null, preflight(readings(udpBlocked = true, brbPort = 5000)), Quality.HD1080).text,
        )
        assertEquals("Ready, but the battery is low", readyVerdict(null, preflight(readings(battery = 12)), Quality.HD1080).text)
    }

    @Test fun `the readout takes a health colour only for a hot phone or a battery under 15 percent`() {
        val calm = preflight().readout
        assertEquals(listOf("Uplink", "Server", "Battery", "Temp"), calm.map { it.label })
        assertTrue(calm.none { it.alert })
        assertEquals("20.0", calm[0].figure)
        assertEquals("Mb/s", calm[0].unit)
        assertEquals("OK", calm[3].figure)
        assertFalse(preflight(readings(battery = 15)).readout[2].alert)
        assertTrue(preflight(readings(battery = 14)).readout[2].alert)
        assertTrue(preflight(readings(thermal = 2)).readout[3].alert) // rung 1
        assertEquals("HOT", preflight(readings(thermal = 3)).readout[3].figure)
        // Unknown values show a dash, never a made-up zero.
        assertEquals("–", preflight(readings(upKbps = null)).readout[0].figure)
        assertEquals("–", preflight(readings(online = false)).readout[1].figure)
    }

    @Test fun `the readout goes two by two at large font scales`() {
        assertEquals(4, readoutColumns(4, 1f))
        assertEquals(4, readoutColumns(4, 1.3f))
        assertEquals(2, readoutColumns(4, 2f))
        assertEquals(2, readoutColumns(2, 2f))
    }

    @Test fun `the preflight stamp counts seconds, then minutes`() {
        assertEquals("checked 12 s ago", checkedAgo(0, 12_400))
        assertEquals("checked 2 min ago", checkedAgo(0, 125_000))
        assertEquals("checked 0 s ago", checkedAgo(5_000, 0))
    }

    @Test fun `the Pocket's blockers use the Camera page's step words`() {
        val steps = PocketSteps.setup(chosen = false, scanning = false, net = CameraNet.PHONE_HOTSPOT, hasNetworkDetails = false, hotspotUp = true)
        assertTrue(Blocker.CHOOSE_POCKET.text.startsWith(steps[0].title)) // "Find your Pocket 3"
        assertTrue("hotspot or Wi‑Fi" in Blocker.CAMERA_WIFI.text) // step 2: "Choose its hotspot or Wi‑Fi"
        // One name per thing: the camera is the Pocket 3, never "your camera for Camera link".
        assertTrue(Blocker.entries.none { "Camera link" in it.text || "your camera" in it.text })
    }

    // --- Pocket 3 setup steps (Camera page) ---

    private fun steps(
        chosen: Boolean = false,
        scanning: Boolean = false,
        net: CameraNet = CameraNet.PHONE_HOTSPOT,
        details: Boolean = false,
        hotspotUp: Boolean = true,
    ) = PocketSteps.setup(chosen, scanning, net, details, hotspotUp)

    @Test fun `setup steps go find, hotspot, go live, with one step to do now`() {
        val s = steps()
        assertEquals(listOf("Find your Pocket 3", "Choose its hotspot or Wi‑Fi", "Go live"), s.map { it.title })
        assertEquals(listOf(StepStatus.NOW, StepStatus.NEXT, StepStatus.NEXT), s.map { it.status })
        assertTrue(s[0].detail!!.contains("Turn the Pocket 3 on and keep it close"))
        // Found: the hotspot is next, and says exactly what's missing.
        val found = steps(chosen = true)
        assertEquals(listOf(StepStatus.DONE, StepStatus.NOW, StepStatus.NEXT), found.map { it.status })
        assertNull(found[0].detail)
        assertEquals("Type your hotspot's name and password below.", found[1].detail)
        assertEquals("Turn on your hotspot.", steps(chosen = true, details = true, hotspotUp = false)[1].detail)
        assertEquals("Type the Wi‑Fi's name and password below.", steps(chosen = true, net = CameraNet.WIFI)[1].detail)
        // Fullbars' own hotspot needs nothing: straight to Go live.
        val ready = steps(chosen = true, net = CameraNet.PRIVATE)
        assertEquals(listOf(StepStatus.DONE, StepStatus.DONE, StepStatus.NOW), ready.map { it.status })
    }

    @Test fun `a hotspot set up before the Pocket is found is done, and finding is now`() {
        val s = steps(details = true)
        assertEquals(listOf(StepStatus.NOW, StepStatus.DONE, StepStatus.NEXT), s.map { it.status })
    }

    @Test fun `finding again while one is chosen is the step to do now`() {
        val s = steps(chosen = true, scanning = true, net = CameraNet.PRIVATE)
        assertEquals(StepStatus.NOW, s[0].status)
        assertTrue(s[0].detail!!.startsWith("Looking over Bluetooth"))
    }

    @Test fun `Go live is the step to do exactly when Home has no Pocket blocker`() {
        for (chosen in listOf(true, false)) for (net in CameraNet.entries) for (details in listOf(true, false)) for (up in listOf(true, false)) {
            val goLiveNow = steps(chosen, false, net, details, up)[2].status == StepStatus.NOW
            val blocked = blocker(camera = Kind.POCKET, pocketChosen = chosen, cameraNet = net, hasCameraWifi = details, hotspotUp = up) != null
            assertEquals("chosen=$chosen net=$net details=$details up=$up", !blocked, goLiveNow)
        }
    }

    @Test fun `the live screen numbers each step of starting the Pocket`() {
        assertEquals("1 of 5 · Starting the hotspot", PocketState.StartingHotspot.label)
        assertEquals("2 of 5 · Tap OK on the Pocket 3", PocketState.ApproveOnCamera.label)
        assertEquals("3 of 5 · Pocket 3 joining the hotspot", PocketState.JoiningWifi(hotspot = true).label)
        assertEquals("3 of 5 · Pocket 3 joining the Wi‑Fi", PocketState.JoiningWifi(hotspot = false).label)
        assertEquals("4 of 5 · Pocket 3 starting its stream", PocketState.Starting.label)
        assertEquals("Turn on Bluetooth · trying again", PocketState.Reconnecting("Turn on Bluetooth", 2, PocketStep.BLUETOOTH).label)
        assertEquals("Can't reach the Pocket 3. Is it on and close? · trying again", PocketState.Reconnecting("Can't reach the Pocket 3. Is it on and close?", 4).label)
        // The Camera page lists the same five, in the same order.
        assertEquals(5, PocketStep.entries.size)
        assertEquals("The Pocket 3 joins the Wi‑Fi", PocketStep.JOIN.title(CameraNet.WIFI))
        assertEquals("Fullbars starts its hotspot", PocketStep.HOTSPOT.title(CameraNet.PRIVATE))
        assertTrue(PocketStep.BLUETOOTH.guide(CameraNet.PRIVATE).contains("tap OK on the Pocket 3"))
    }

    // --- Recap ---

    private fun summary(
        liveMs: Long = 82_000,
        drops: Int = 0,
        encoder: Int = 0,
        bytes: Long = 48_000_000,
        heat: Int = 0,
        error: String? = null,
    ) = SessionSummary(
        startedAtMs = 1_000, endedAtMs = 4_000 + liveMs, liveSinceMs = if (liveMs == 0L) 0 else 4_000,
        recoveredDrops = drops, encoderRecoveries = encoder, bytesSent = bytes, peakThermalStatus = heat,
        lowestBitrate = 0, lastError = error,
    )

    @Test fun `data sent reads in MB under 1 GB and never as 0_00 GB`() {
        assertEquals("0 MB", recapBytes(0, Locale.US))
        assertEquals("<1 MB", recapBytes(400_000, Locale.US))
        assertEquals("48 MB", recapBytes(48_000_000, Locale.US))
        assertEquals("999 MB", recapBytes(999_700_000, Locale.US))
        assertEquals("1.0 GB", recapBytes(1_000_000_000, Locale.US))
        assertEquals("1.4 GB", recapBytes(1_420_000_000, Locale.US))
    }

    @Test fun `the verdict leads with the honest drop line`() {
        assertEquals("Held the whole way.", recapVerdict(0, bonded = true))
        assertEquals("Held the whole way.", recapVerdict(0, bonded = false))
        assertEquals("We kept you live through 2 connection drops.", recapVerdict(2, bonded = true))
        assertEquals("We kept you live through 1 connection drop.", recapVerdict(1, bonded = true))
        assertEquals("Reconnected 3 times. Viewers saw a pause each time.", recapVerdict(3, bonded = false))
    }

    @Test fun `stats are DROPS, SENT and LINKS, with TEMP only after the first thermal rung`() {
        val cells = recapCells(summary(), links = 2, locale = Locale.US)
        assertEquals(listOf("Connection drops", "Sent", "Links"), cells.map { it.label })
        assertEquals("48", cells[1].figure)
        assertEquals("MB", cells[1].unit)
        assertEquals(listOf("Connection drops", "Sent"), recapCells(summary(), links = null, locale = Locale.US).map { it.label })
        assertEquals("warm", recapCells(summary(heat = 2), links = 1, locale = Locale.US).last().figure)
        assertEquals("hot", recapCells(summary(heat = 4), links = 1, locale = Locale.US).last().figure)
        // The count-up starts from zero and lands on the real figures.
        assertEquals("0", recapCells(summary(drops = 3), links = 1, progress = 0f, locale = Locale.US)[0].figure)
        assertEquals("3", recapCells(summary(drops = 3), links = 1, progress = 1f, locale = Locale.US)[0].figure)
    }

    @Test fun `nothing in the recap says time(s)`() {
        assertNull(encoderLine(0))
        assertFalse(encoderLine(1)!!.contains("(s)"))
        assertFalse(encoderLine(2)!!.contains("(s)"))
        assertFalse(recapAnnouncement(summary(drops = 1), wasTest = true, bonded = true).contains("(s)"))
    }

    @Test fun `duration reads like the live timer and is spoken in words`() {
        assertEquals("0:01:22", formatDuration(82_000))
        assertEquals("1:12:05", formatDuration(4_325_000))
        assertEquals("1 minute 22 seconds", spokenDuration(82_000))
        assertEquals("1 hour 12 minutes", spokenDuration(4_325_000))
        assertEquals("1 second", spokenDuration(1_000))
    }

    @Test fun `DONE folds the recap into one line`() {
        assertEquals("0:01:22 · no drops", lastStreamValue(summary()))
        assertEquals("0:01:22 · 1 drop", lastStreamValue(summary(drops = 1)))
        assertEquals("0:01:22 · 2 drops", lastStreamValue(summary(drops = 2)))
        assertEquals("didn't start", lastStreamValue(summary(liveMs = 0, error = "timeout")))
    }

    @Test fun `the announcement says the verdict, the time and the totals`() {
        assertEquals(
            "Held the whole way. Live for 1 minute 22 seconds. 0 drops, 48 MB sent.",
            recapAnnouncement(summary(), wasTest = false, bonded = true),
        )
        assertTrue(recapAnnouncement(summary(liveMs = 0, error = "stream key rejected"), wasTest = true, bonded = false).startsWith("The stream didn't start"))
    }

    @Test fun `only a completed test with no drops and no failure is clean`() {
        assertTrue(isCleanTest(summary(), wasTest = true))
        assertFalse(isCleanTest(summary(), wasTest = false))
        assertFalse(isCleanTest(summary(drops = 1), wasTest = true))
        assertFalse(isCleanTest(summary(encoder = 1), wasTest = true))
        assertFalse(isCleanTest(summary(error = "timeout"), wasTest = true))
        assertFalse(isCleanTest(summary(liveMs = 0), wasTest = true))
    }

    @Test fun `a preview closed before going live has no recap, a failed start still does`() {
        assertTrue(cancelledPreview(summary(liveMs = 0, bytes = 0)))
        assertFalse(cancelledPreview(summary(liveMs = 0, bytes = 0, error = "Couldn't reach Twitch")))
        assertFalse(cancelledPreview(summary()))
    }
}
