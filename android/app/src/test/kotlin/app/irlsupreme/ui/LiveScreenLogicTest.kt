package app.irlsupreme.ui

import app.irlsupreme.engine.AudioInput
import app.irlsupreme.engine.ConnectionPhase
import app.irlsupreme.engine.EngineState
import app.irlsupreme.engine.Health
import app.irlsupreme.engine.LinkInfo
import app.irlsupreme.engine.Scene
import app.irlsupreme.engine.Facing
import app.irlsupreme.service.ChatFeed
import app.irlsupreme.service.ChatLine
import app.irlsupreme.twitch.ChatMessage
import app.irlsupreme.ui.live.FLIP_RESERVE_DP
import app.irlsupreme.ui.live.OncePerStream
import app.irlsupreme.ui.live.chatSheetStatus
import app.irlsupreme.ui.live.facingSpoken
import app.irlsupreme.ui.live.flipped
import app.irlsupreme.ui.live.startFacingChange
import app.irlsupreme.ui.live.FIRST_MUTE_HINT
import app.irlsupreme.ui.live.HOLD_HINT
import app.irlsupreme.ui.live.HoldSession
import app.irlsupreme.ui.live.KEY_LABELS
import app.irlsupreme.ui.live.KeyGlyph
import app.irlsupreme.ui.live.LinkMeter
import app.irlsupreme.ui.live.LinkState
import app.irlsupreme.ui.live.MicCell
import app.irlsupreme.ui.live.PillLook
import app.irlsupreme.ui.live.Rail
import app.irlsupreme.ui.live.TIMER_PENDING
import app.irlsupreme.ui.live.alertWaitMs
import app.irlsupreme.ui.live.bigType
import app.irlsupreme.ui.live.brbKey
import app.irlsupreme.ui.live.direction
import app.irlsupreme.ui.live.endKey
import app.irlsupreme.ui.live.keyHeight
import app.irlsupreme.ui.live.keyNeed
import app.irlsupreme.ui.live.keyWords
import app.irlsupreme.ui.live.linkCells
import app.irlsupreme.ui.live.linkPage
import app.irlsupreme.ui.live.micCell
import app.irlsupreme.ui.live.muteKey
import app.irlsupreme.ui.live.notice
import app.irlsupreme.ui.live.noticeHeight
import app.irlsupreme.ui.live.pillAssertive
import app.irlsupreme.ui.live.pillGrowth
import app.irlsupreme.ui.live.pillModel
import app.irlsupreme.ui.live.goLiveLabel
import app.irlsupreme.ui.live.goLiveSpoken
import app.irlsupreme.ui.live.pillSlot
import app.irlsupreme.ui.live.railColumnWidth
import app.irlsupreme.ui.live.railKeyHeight
import app.irlsupreme.ui.live.railWidths
import app.irlsupreme.ui.live.sceneConsequence
import app.irlsupreme.ui.live.scenesKey
import app.irlsupreme.ui.live.timerLabel
import app.irlsupreme.ui.live.timerSpoken
import app.irlsupreme.ui.live.timerText
import app.irlsupreme.ui.theme.FbMotion
import app.irlsupreme.ui.theme.HapticPattern
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveScreenLogicTest {
    @Test fun `losses, scene changes and going live are felt at once, the rest waits`() {
        assertTrue(announceAtOnce(Health.GREAT, Health.RECONNECTING))
        assertTrue(announceAtOnce(Health.GREAT, Health.FAILED))
        assertTrue(announceAtOnce(Health.GREAT, Health.CAMERA_LOST))
        assertTrue(announceAtOnce(Health.GREAT, Health.BRB))
        assertTrue(announceAtOnce(Health.BRB, Health.GREAT))
        assertTrue(announceAtOnce(Health.BRB, Health.WEAK_SCREEN))
        assertTrue(announceAtOnce(Health.CONNECTING, Health.WEAK))
        assertFalse(announceAtOnce(Health.GREAT, Health.WEAK))
        assertFalse(announceAtOnce(Health.WEAK, Health.GREAT))
        assertFalse(announceAtOnce(Health.RECONNECTING, Health.GREAT))
    }

    @Test fun `each kind of change has its own pattern`() {
        assertEquals(HapticPattern.WENT_LIVE, hapticFor(Health.CONNECTING, Health.GREAT, ownSceneTap = false))
        assertEquals(HapticPattern.WENT_LIVE, hapticFor(Health.CONNECTING, Health.BRB, ownSceneTap = false))
        assertEquals(HapticPattern.RECONNECTING, hapticFor(Health.CONNECTING, Health.FAILED, ownSceneTap = false))
        assertEquals(HapticPattern.RECONNECTING, hapticFor(Health.GREAT, Health.RECONNECTING, ownSceneTap = false))
        assertEquals(HapticPattern.CAMERA_LOST, hapticFor(Health.WEAK, Health.CAMERA_LOST, ownSceneTap = false))
        assertEquals(HapticPattern.CAMERA_LOST, hapticFor(Health.CONNECTING, Health.CAMERA_LOST, ownSceneTap = false))
        assertEquals(HapticPattern.WORSE, hapticFor(Health.GREAT, Health.WEAK, ownSceneTap = false))
        assertEquals(HapticPattern.WORSE, hapticFor(Health.GREAT, Health.HOT, ownSceneTap = false))
        assertEquals(HapticPattern.WORSE, hapticFor(Health.GREAT, Health.WEAK_SCREEN, ownSceneTap = false))
        assertEquals(HapticPattern.RECOVERED, hapticFor(Health.RECONNECTING, Health.GREAT, ownSceneTap = false))
        assertEquals(HapticPattern.RECOVERED, hapticFor(Health.WEAK, Health.GREAT, ownSceneTap = false))
        // A mod's !brb is news: the scene click plays.
        assertEquals(HapticPattern.TOGGLE, hapticFor(Health.GREAT, Health.BRB, ownSceneTap = false))
    }

    @Test fun `our own scene taps already clicked`() {
        assertNull(hapticFor(Health.GREAT, Health.BRB, ownSceneTap = true))
        assertNull(hapticFor(Health.BRB, Health.GREAT, ownSceneTap = true))
        // A mod's !live is news.
        assertEquals(HapticPattern.RECOVERED, hapticFor(Health.BRB, Health.GREAT, ownSceneTap = false))
        assertNull(hapticFor(Health.GREAT, Health.GREAT, ownSceneTap = false))
        assertNull(hapticFor(Health.GREAT, Health.OFF, ownSceneTap = false))
        assertNull(hapticFor(Health.GREAT, Health.CONNECTING, ownSceneTap = false))
    }

    @Test fun `a worse pill change never springs`() {
        for (prev in Health.entries) for (now in Health.entries) {
            for (reduced in listOf(false, true)) {
                val token = pillGrowth(prev, now, reduced)
                if (direction(prev, now) == FbMotion.Change.WORSE) {
                    assertNotEquals("$prev -> $now (reduced=$reduced)", FbMotion.Token.SETTLE, token)
                }
                if (reduced) assertEquals(FbMotion.Token.CUT, token)
            }
        }
        assertEquals(FbMotion.Token.ENTER, pillGrowth(Health.GREAT, Health.WEAK, reduced = false))
        assertEquals(FbMotion.Token.ENTER, pillGrowth(Health.WEAK, Health.RECONNECTING, reduced = false))
        assertEquals(FbMotion.Token.SETTLE, pillGrowth(Health.RECONNECTING, Health.GREAT, reduced = false))
        assertEquals(FbMotion.Token.SETTLE, pillGrowth(Health.CONNECTING, Health.GREAT, reduced = false))
        assertEquals(FbMotion.Change.NEUTRAL, direction(Health.WEAK, Health.HOT))
        assertEquals(FbMotion.Change.NEUTRAL, direction(null, Health.RECONNECTING))
    }

    @Test fun `hold to end completes only after 2000ms of clock time, even with the animator scale at 0`() {
        val t0 = 5_000_000_000L
        val ms = 1_000_000L
        // Reduce motion (animator duration scale 0) used to make animateTo finish on its first frame.
        val hold = HoldSession(t0, reducedMotion = true)
        assertTrue(hold.onFrame(t0 + 16 * ms).isEmpty())
        assertFalse(hold.done)
        val lit = mutableListOf<Int>()
        var t = t0 + 16 * ms
        while (t < t0 + 2_000 * ms) {
            lit += hold.onFrame(t)
            assertFalse("done at ${(t - t0) / ms}ms", hold.done)
            t += 16 * ms
        }
        assertEquals((1..7).toList(), lit)
        assertEquals(listOf(8), hold.onFrame(t0 + 2_000 * ms))
        assertTrue(hold.done)
        assertTrue(hold.onFrame(t0 + 2_500 * ms).isEmpty())
        assertEquals(1f, hold.progress(t0 + 3_000 * ms))
    }

    @Test fun `ticks light one per 250ms and a skipped stretch lights them all in order`() {
        val hold = HoldSession(0L)
        assertEquals(emptyList<Int>(), hold.onFrame(249_000_000L))
        assertEquals(listOf(1), hold.onFrame(250_000_000L))
        assertEquals(listOf(2, 3, 4), hold.onFrame(1_000_000_000L))
        assertEquals(4, hold.lit)
    }

    @Test fun `the guard ends a hold whose frames stopped`() {
        val hold = HoldSession(0L)
        hold.onFrame(500_000_000L)
        assertEquals(listOf(3, 4, 5, 6, 7, 8), hold.onGuard())
        assertTrue(hold.done)
        assertTrue(hold.onGuard().isEmpty())
    }

    @Test fun `link bars step by each link's even share of the target`() {
        // 6 Mb/s over two links: an even share is 3 Mb/s.
        assertEquals(4, LinkMeter.level(3_400, 6_000, 2))
        assertEquals(2, LinkMeter.level(1_200, 6_000, 2))
        assertEquals(1, LinkMeter.level(100, 6_000, 2))
        assertEquals(0, LinkMeter.level(10, 6_000, 2))
        assertEquals(4, LinkMeter.level(6_000, 6_000, 1))
        assertEquals("1.2", LinkMeter.mbps(1_200))
    }

    private val att = LinkInfo("cellular", "AT&T", up = true, kbps = 1_200, rttMs = 125)
    private val wifi = LinkInfo("wifi", "Wi-Fi", up = true, kbps = 3_400, rttMs = 30)

    @Test fun `a link at nothing or still connecting is never 0_0 and never coloured`() {
        val idle = linkCells(listOf(att.copy(kbps = 0), wifi), 6_000_000, 0, weak = false, connecting = false, reconnecting = false)
        assertEquals(LinkState.CONNECTING, idle[0].state)
        assertEquals("···", idle[0].value)
        assertEquals(0, idle[0].level)
        assertEquals(LinkState.FINE, idle[1].state)
        assertEquals("AT&T Mb/s", idle[0].label)
        // Even when the stream is weak, a link with nothing to show isn't blamed for it.
        val weak = linkCells(listOf(att.copy(kbps = 0), wifi), 6_000_000, 0, weak = true, connecting = false, reconnecting = false)
        assertEquals(LinkState.CONNECTING, weak[0].state)
        listOf(idle, weak).flatten().forEach { assertNotEquals("0.0", it.value) }
    }

    @Test fun `links listed but not yet registered before the first lock show dots and no hue`() {
        // BondedOutput lists the networks at once; srtla still has them REGISTERING or DISCONNECTED (up = false).
        val registering = listOf(att.copy(up = false, kbps = 0), wifi.copy(up = false, kbps = 0))
        val cells = linkCells(registering, 6_000_000, 0, weak = false, connecting = true, reconnecting = false)
        assertTrue(cells.all { it.state == LinkState.CONNECTING })
        assertTrue(cells.all { it.value == "···" && it.level == 0 })
        assertTrue(cells.none { it.state == LinkState.DOWN || it.state == LinkState.WEAK })
        assertEquals("AT&T connecting", cells[0].spoken)
        // One link up early, still before the lock: it's connecting too, not a number yet.
        val oneUp = linkCells(listOf(att, wifi.copy(up = false)), 6_000_000, 0, weak = false, connecting = true, reconnecting = false)
        assertTrue(oneUp.all { it.state == LinkState.CONNECTING })
        // After the lock the same down link is DOWN.
        val after = linkCells(registering, 6_000_000, 0, weak = false, connecting = false, reconnecting = false)
        assertTrue(after.all { it.state == LinkState.DOWN })
    }

    @Test fun `weak turns the slow link amber, down is DOWN, reconnecting downs them all`() {
        val fine = linkCells(listOf(att, wifi), 6_000_000, 0, weak = false, connecting = false, reconnecting = false)
        assertEquals(listOf(LinkState.FINE, LinkState.FINE), fine.map { it.state })
        val weak = linkCells(listOf(att, wifi), 6_000_000, 0, weak = true, connecting = false, reconnecting = false)
        assertEquals(listOf(LinkState.WEAK, LinkState.FINE), weak.map { it.state })
        // Neither below half its share: the slower one is still named.
        val bothOk = linkCells(listOf(att.copy(kbps = 2_900), wifi), 6_000_000, 0, weak = true, connecting = false, reconnecting = false)
        assertEquals(listOf(LinkState.WEAK, LinkState.FINE), bothOk.map { it.state })
        val down = linkCells(listOf(att.copy(up = false), wifi), 6_000_000, 0, weak = false, connecting = false, reconnecting = false)
        assertEquals(LinkState.DOWN, down[0].state)
        assertEquals("DOWN", down[0].value)
        val gone = linkCells(listOf(att, wifi), 6_000_000, 0, weak = false, connecting = false, reconnecting = true)
        assertTrue(gone.all { it.state == LinkState.DOWN })
    }

    @Test fun `a direct stream shows one uplink column`() {
        val cells = linkCells(emptyList(), 6_000_000, 5_800_000, weak = false, connecting = false, reconnecting = false)
        assertEquals(1, cells.size)
        assertEquals("UPLINK Mb/s", cells[0].label)
        assertEquals("5.8", cells[0].value)
        val connecting = linkCells(emptyList(), 6_000_000, 0, weak = false, connecting = true, reconnecting = false)
        assertEquals(LinkState.CONNECTING, connecting[0].state)
    }

    @Test fun `three links page through pairs with an index`() {
        val cells = linkCells(listOf(att, wifi, att.copy(name = "Pixel 7", kind = "helper")), 6_000_000, 0, false, false, false)
        val (first, i1) = linkPage(cells, 0)
        assertEquals(listOf("AT&T Mb/s", "WI-FI Mb/s"), first.map { it?.label })
        assertEquals("1/2", i1)
        val (second, i2) = linkPage(cells, 1)
        assertEquals("PIXEL 7 Mb/s", second[0]?.label)
        assertNull(second[1])
        assertEquals("2/2", i2)
        assertNull(linkPage(cells.take(2), 0).second)
    }

    @Test fun `the key label for each state`() {
        assertEquals("CANCEL", endKey(cancel = true).label)
        assertEquals(KeyGlyph.X, endKey(cancel = true).glyph)
        assertFalse(endKey(cancel = true).band)
        assertEquals("END", endKey(cancel = false).label)
        assertEquals(KeyGlyph.STOP, endKey(cancel = false).glyph)
        assertTrue(endKey(cancel = false).band)
        assertEquals("SCENES", scenesKey(open = false).label)
        assertTrue(scenesKey(open = true).engaged)
        assertEquals("MUTE", muteKey(muted = false).label)
        assertFalse(muteKey(muted = false).engaged)
        assertEquals("UNMUTE", muteKey(muted = true).label)
        assertEquals(KeyGlyph.MIC_OFF, muteKey(muted = true).glyph)
        assertTrue(muteKey(muted = true).engaged)
        assertEquals("BRB", brbKey(Scene.LIVE).label)
        for (s in listOf(Scene.BRB, Scene.PRIVACY, Scene.STARTING_SOON, Scene.ENDING)) {
            assertEquals("BACK LIVE", brbKey(s).label)
            assertEquals(KeyGlyph.CAMERA, brbKey(s).glyph)
            assertTrue(brbKey(s).engaged)
        }
    }

    @Test fun `at 200 percent each key grows to its widest word and the rail still fits 360dp`() {
        // Archivo Condensed Bold caps at 22dp (11sp under Android 14's non-linear 200%), no tracking:
        // CANCEL 72.6, SCENES 71.9, UNMUTE 77.3, BACK 50.0.
        assertEquals(listOf(listOf("CANCEL", "END"), listOf("SCENES"), listOf("MUTE", "UNMUTE"), listOf("BRB", "BACK LIVE")), KEY_LABELS)
        assertEquals(listOf("BRB", "BACK", "LIVE"), keyWords(KEY_LABELS[3]))
        val needs = floatArrayOf(keyNeed(72.6f, true), keyNeed(71.9f, true), keyNeed(77.3f, true), keyNeed(50.0f, true))
        assertArrayEquals(floatArrayOf(78f, 77f, 83f, 55f), needs, 0.01f)
        val w = railWidths(360f - 24f, needs)
        assertArrayEquals(floatArrayOf(78f, 77f, 83f, 70f), w, 0.01f)
        // Every key is at least its base width and at least its widest word, and the four still fit without scrolling.
        floatArrayOf(Rail.END, Rail.SCENES, Rail.MUTE, Rail.MIN).forEachIndexed { i, min -> assertTrue(w[i] >= min && w[i] >= needs[i]) }
        assertTrue(w.sum() + Rail.END_GAP + Rail.GAP * 2 <= 336f)
        // At normal type every word is under the base width, so the rail is unchanged.
        val small = floatArrayOf(keyNeed(44.2f, false), keyNeed(43.9f, false), keyNeed(46.6f, false), keyNeed(30.3f, false))
        assertArrayEquals(railWidths(360f - 24f), railWidths(360f - 24f, small), 0.01f)
    }

    @Test fun `a wrapped key label grows all the keys instead of being clipped`() {
        // One 14dp line at normal type fits the 60dp key; one 26dp line at 200% fits 84dp.
        assertEquals(60f, railKeyHeight(false, 14f), 0f)
        assertEquals(84f, railKeyHeight(true, 26f), 0f)
        // BACK LIVE wrapped to two 26dp lines: 6 + 24 + 4 + 52 + 6 = 92, plus 1dp against rounding.
        assertEquals(93f, railKeyHeight(true, 52f), 0f)
        assertTrue(railKeyHeight(true, 52f) > keyHeight(true))
        // The landscape column widens only when a word needs more than its 72dp inside.
        assertEquals(96f, railColumnWidth(floatArrayOf(53f, 52f, 56f, 40f)), 0f)
        assertEquals(107f, railColumnWidth(floatArrayOf(78f, 77f, 83f, 56f)), 0f)
    }

    @Test fun `the rail never shrinks a key`() {
        assertArrayEquals(floatArrayOf(64f, 76f, 76f, 144f), railWidths(412f - 24f), 0.01f)
        assertArrayEquals(floatArrayOf(64f, 76f, 76f, 92f), railWidths(360f - 24f), 0.01f)
        assertEquals(56f, railWidths(260f)[3], 0.01f)
        assertFalse(bigType(1.3f))
        assertTrue(bigType(1.5f))
        assertEquals(60f, keyHeight(false), 0f)
        assertEquals(84f, keyHeight(true), 0f)
        assertEquals(32f, noticeHeight(false), 0f)
        assertEquals(56f, noticeHeight(true), 0f)
        assertEquals(388f, pillSlot(412f), 0f)
        assertEquals(420f, pillSlot(900f), 0f)
    }

    @Test fun `the flip button takes its room from the pill's row, never from under it`() {
        // 412dp phone: 388 less the 48dp button and its 8dp gap.
        assertEquals(332f, pillSlot(412f, FLIP_RESERVE_DP), 0f)
        assertEquals(280f, pillSlot(360f, FLIP_RESERVE_DP), 0f)
        // A tablet still caps the pill; the button sits further right.
        assertEquals(420f, pillSlot(900f, FLIP_RESERVE_DP), 0f)
        assertEquals(0f, pillSlot(40f, FLIP_RESERVE_DP), 0f)
    }

    @Test fun `a flip goes to the other side and says the side it landed on`() {
        assertEquals(Facing.FRONT, flipped(Facing.BACK))
        assertEquals(Facing.BACK, flipped(Facing.FRONT))
        assertEquals("Front camera", facingSpoken(Facing.FRONT))
        assertEquals("Back camera", facingSpoken(Facing.BACK))
    }

    @Test fun `a stream starts on the chosen side of the phone camera`() {
        assertEquals(Facing.FRONT, startFacingChange(Facing.FRONT, Facing.BACK, phoneCamera = true))
        assertEquals(Facing.BACK, startFacingChange(Facing.BACK, Facing.FRONT, phoneCamera = true))
        assertNull(startFacingChange(Facing.FRONT, Facing.FRONT, phoneCamera = true))
        assertNull(startFacingChange(Facing.FRONT, Facing.BACK, phoneCamera = false))
    }

    @Test fun `the start side is applied once per stream, even when the screen is rebuilt`() {
        val once = OncePerStream()
        // First stream in the process: no finished session yet.
        assertTrue(once.claim(null))
        assertFalse(once.claim(null)) // the live screen rebuilt mid-stream: a flip since then must stand
        // The first stream ended; the next one gets the start side again, once.
        val ended = Any()
        assertTrue(once.claim(ended))
        assertFalse(once.claim(ended))
        // A finished session equal in value but a different stream still counts as new.
        assertTrue(once.claim(Any()))
    }

    @Test fun `the MIC column shows the most important thing`() {
        assertEquals(MicCell.Muted, micCell(muted = true, AudioInput.SILENCED, 5))
        assertEquals(MicCell.NoAudio, micCell(muted = false, AudioInput.SILENCED, 5))
        assertEquals(MicCell.Battery(12), micCell(muted = false, AudioInput.OK, 12))
        assertEquals(MicCell.On, micCell(muted = false, AudioInput.OK, 15))
        assertEquals(MicCell.On, micCell(muted = false, AudioInput.OK, null))
    }

    @Test fun `the notice slot takes one line, in priority order`() {
        assertEquals(HOLD_HINT, notice(true, "Mic in use by another app", true, "Camera link · Pairing…", "x"))
        assertEquals("Mic in use by another app", notice(false, "Mic in use by another app", true, null, null))
        assertEquals(FIRST_MUTE_HINT, notice(false, null, true, "Camera link · Pairing…", null))
        assertEquals("Camera link · Pairing…", notice(false, null, false, "Camera link · Pairing…", "x"))
        assertEquals("x", notice(false, null, false, null, "x"))
        assertNull(notice(false, null, false, null, null))
    }

    @Test fun `the timer reads whole minutes to TalkBack`() {
        assertEquals("CONNECTING", timerLabel(0, isTest = false))
        assertEquals("TEST", timerLabel(1, isTest = true))
        assertEquals("LIVE", timerLabel(1, isTest = false))
        assertEquals(TIMER_PENDING, timerText(0, 99))
        assertEquals("0:24:18", timerText(1_000, 1_000 + (24 * 60 + 18) * 1_000L))
        assertEquals("1:00:00", timerText(0 + 1, 1 + 3_600_000L))
        assertEquals("Live for 24 minutes", timerSpoken(1_000, 1_000 + (24 * 60 + 59) * 1_000L, isTest = false))
        assertEquals("Test for 1 minute", timerSpoken(1_000, 1_000 + 61_000L, isTest = true))
        assertEquals("Not live yet", timerSpoken(0, 5, isTest = false))
    }

    @Test fun `alerts wait 1500ms behind an assertive announcement`() {
        assertEquals(0L, alertWaitMs(10_000, 0))
        assertEquals(1_000L, alertWaitMs(10_500, 10_000))
        assertEquals(0L, alertWaitMs(11_500, 10_000))
    }

    @Test fun `scene rows say what viewers get`() {
        assertEquals("Your camera", sceneConsequence(Scene.LIVE, muted = false))
        assertEquals("Card · they hear you", sceneConsequence(Scene.BRB, muted = false))
        assertEquals("Card · mic off", sceneConsequence(Scene.ENDING, muted = true))
        assertEquals("Card · camera and mic off", sceneConsequence(Scene.PRIVACY, muted = false))
    }

    private fun live(vararg links: LinkInfo) = EngineState(running = true, connection = ConnectionPhase.LIVE, liveSinceMs = 1, links = links.toList())

    @Test fun `the pill grows to the full slot only for trouble, and speaks losses assertively`() {
        assertEquals(PillLook.GREAT, pillModel(live(), Health.GREAT, false, false, false).look)
        assertFalse(PillLook.GREAT.fullSlot)
        val weak = pillModel(live(), Health.WEAK, false, false, false)
        assertEquals(PillLook.WEAK, weak.look)
        assertTrue(weak.look.fullSlot)
        assertEquals("Live · Weak signal", weak.headline)
        assertEquals("Quality lowered", weak.second)
        assertTrue(PillLook.CAMERA_LOST.fullSlot)
        assertTrue(PillLook.RECONNECTING.fullSlot)
        assertFalse(PillLook.SCENE.fullSlot)
        val reconnecting = pillModel(live(att), Health.RECONNECTING, false, bonded = true, ending = false)
        assertEquals("Viewers see your Signal lost screen", reconnecting.second)
        assertTrue(pillAssertive(Health.RECONNECTING))
        assertTrue(pillAssertive(Health.CAMERA_LOST))
        assertFalse(pillAssertive(Health.WEAK))
        assertEquals("Test · Great", pillModel(live(), Health.GREAT, isTest = true, bonded = false, ending = false).headline)
        val connecting = pillModel(EngineState(running = true), Health.CONNECTING, false, false, false)
        assertEquals(PillLook.CONNECTING, connecting.look)
        assertEquals("Connecting", connecting.headline)
        assertEquals("Not live yet", connecting.second)
    }

    @Test fun `privacy with the mic back on is the capped privacy pill, not amber`() {
        val state = live().copy(scene = Scene.PRIVACY, micMuted = false)
        val p = pillModel(state, Health.BRB, false, false, false)
        assertEquals(PillLook.PRIVACY_MIC_ON, p.look)
        assertEquals("Privacy · mic ON", p.headline)
        assertFalse(p.look.lightFill)
        val brb = pillModel(live().copy(scene = Scene.BRB), Health.BRB, false, false, false)
        assertEquals(PillLook.SCENE, brb.look)
        assertEquals("Viewers see your BRB screen and hear you", brb.second)
    }

    @Test fun `chat is hidden from the program exactly where the compositor drops layers`() {
        assertFalse(programHidesChat(Scene.LIVE, weakSignal = false, overlayOnCards = false))
        assertTrue(programHidesChat(Scene.LIVE, weakSignal = true, overlayOnCards = true))
        assertTrue(programHidesChat(Scene.PRIVACY, weakSignal = false, overlayOnCards = true))
        assertTrue(programHidesChat(Scene.BRB, weakSignal = false, overlayOnCards = false))
        assertFalse(programHidesChat(Scene.BRB, weakSignal = false, overlayOnCards = true))
        // On a card the card is what's drawn, weak signal or not.
        assertFalse(programHidesChat(Scene.STARTING_SOON, weakSignal = true, overlayOnCards = true))
    }

    private fun line(id: String, atMs: Long, command: Boolean = false) = ChatLine(id, "Name", null, "text $id", command, atMs)

    @Test fun `old lines age out and the last command always stays`() {
        val now = 1_000_000L
        val lines = listOf(
            line("old", now - CHAT_MAX_AGE_MS - 1),
            line("cmd", now - 60_000, command = true),
            line("a", now - 5_000), line("b", now - 4_000), line("c", now - 3_000), line("d", now - 2_000),
        )
        assertEquals(listOf("cmd", "a", "b", "c", "d"), tickerLines(lines, now, showChat = true).map { it.id })
        assertEquals(listOf("cmd"), tickerLines(lines, now, showChat = false).map { it.id })
        assertEquals(listOf("c", "d"), tickerLines(lines.filter { !it.isCommand }, now, showChat = true, max = 2).map { it.id })
        assertEquals(emptyList<String>(), tickerLines(lines, now + CHAT_MAX_AGE_MS, showChat = true).map { it.id })
    }

    @Test fun `the chat sheet can scroll back through at least 100 lines, keeping the newest`() {
        ChatFeed.clear()
        try {
            repeat(ChatFeed.KEEP + 30) { i ->
                ChatFeed.add(
                    ChatMessage("m$i", "b", "c$i", "login", "Name", null, "a long message $i", emptyList(), "text", null, null),
                    isCommand = false,
                )
            }
            val kept = ChatFeed.lines.value
            assertTrue(ChatFeed.KEEP >= 100)
            assertEquals(ChatFeed.KEEP, kept.size)
            assertEquals("m${ChatFeed.KEEP + 29}", kept.last().id)
            assertEquals("m30", kept.first().id)
        } finally {
            ChatFeed.clear()
        }
    }

    @Test fun `the chat sheet only states a status worth saying`() {
        assertNull(chatSheetStatus(null))
        assertNull(chatSheetStatus("Chat connected"))
        assertEquals("Chat reconnecting…", chatSheetStatus("Chat reconnecting…"))
    }

    @Test fun `the preview says nobody sees it yet, and its key says what starts`() {
        val preview = EngineState(running = true, preview = true)
        val pill = pillModel(preview, Health.CONNECTING, isTest = false, bonded = false, ending = false)
        assertEquals("Preview · not live", pill.headline)
        assertEquals("Only you can see this", pill.second)
        assertFalse(pill.look.fullSlot)
        // Ending wins: Cancel in the preview still says Ending.
        assertEquals(PillLook.ENDING, pillModel(preview, Health.CONNECTING, false, false, ending = true).look)
        assertEquals("PREVIEW", timerLabel(0, isTest = false, preview = true))
        assertEquals("CONNECTING", timerLabel(0, isTest = false, preview = false))
        assertEquals("GO LIVE", goLiveLabel(isTest = false))
        assertEquals("START TEST", goLiveLabel(isTest = true))
        assertEquals("Go live on camera", goLiveSpoken(isTest = false, startOnCard = false))
        assertEquals("Start the test on your Starting soon screen", goLiveSpoken(isTest = true, startOnCard = true))
    }
}
