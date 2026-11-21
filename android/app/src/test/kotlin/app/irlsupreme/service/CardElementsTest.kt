package app.irlsupreme.service

import app.irlsupreme.engine.Scene
import app.irlsupreme.engine.video.CardLayout
import app.irlsupreme.engine.video.Slates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardElementsTest {
    private val brb = Slates.Card.BRB
    private val plain = CardLook()

    @Test fun `clock reads m colon ss, then h colon mm colon ss from an hour`() {
        assertEquals("0:00", LiveElements.clock(0))
        assertEquals("0:09", LiveElements.clock(9))
        assertEquals("4:32", LiveElements.clock(4 * 60 + 32))
        assertEquals("59:59", LiveElements.clock(3599))
        assertEquals("1:00:00", LiveElements.clock(3600))
        assertEquals("1:04:05", LiveElements.clock(3600 + 4 * 60 + 5))
    }

    @Test fun `clock is never negative and stops at 99 hours`() {
        assertEquals("0:00", LiveElements.clock(-5))
        assertEquals("99:59:59", LiveElements.clock(1_000_000))
    }

    @Test fun `countdown rounds up so the first and last seconds both show`() {
        assertEquals(TimerReadout("BACK IN", "5:00"), LiveElements.countdown(brb, 5, 0))
        assertEquals("5:00", LiveElements.countdown(brb, 5, 999).figure)
        assertEquals("4:59", LiveElements.countdown(brb, 5, 1000).figure)
        assertEquals("2:10", LiveElements.countdown(brb, 5, (2 * 60 + 50) * 1000L).figure)
        assertEquals("0:01", LiveElements.countdown(brb, 5, 5 * 60_000L - 1).figure)
    }

    @Test fun `countdown ends calmly at 0 00 and stays there, never negative`() {
        val end = LiveElements.countdown(brb, 5, 5 * 60_000L)
        assertNull(end.figure)
        assertEquals("Back any moment", end.words)
        assertEquals(end, LiveElements.countdown(brb, 5, 3 * 3_600_000L))
        assertEquals("Starting any moment", LiveElements.countdown(Slates.Card.STARTING_SOON, 1, 60_000L).words)
        assertEquals("Ending any moment", LiveElements.countdown(Slates.Card.ENDING, 1, 61_000L).words)
    }

    @Test fun `each card says what its countdown means`() {
        assertEquals("STARTING IN", LiveElements.countdown(Slates.Card.STARTING_SOON, 10, 0).label)
        assertEquals("BACK IN", LiveElements.countdown(brb, 10, 0).label)
        assertEquals("ENDING IN", LiveElements.countdown(Slates.Card.ENDING, 10, 0).label)
    }

    @Test fun `time away counts up in whole seconds with the card's own label`() {
        assertEquals(TimerReadout("AWAY", "3:12"), LiveElements.away(brb, (3 * 60 + 12) * 1000L + 999))
        assertEquals("WAITING", LiveElements.away(Slates.Card.STARTING_SOON, 0).label)
        assertEquals("ENDED", LiveElements.away(Slates.Card.ENDING, 0).label)
        assertEquals("LOST FOR", LiveElements.away(Slates.Card.CAMERA_LOST, 0).label)
        assertEquals("0:00", LiveElements.away(brb, -40).figure)
    }

    @Test fun `elements only on the cards the phone draws and the engine lays the overlay over`() {
        val all = CardElements(countdownMinutes = 5, showTimeAway = true, showChat = true)
        for (c in listOf(Slates.Card.STARTING_SOON, Slates.Card.BRB, Slates.Card.ENDING, Slates.Card.CAMERA_LOST)) {
            assertTrue(c.name, LiveElements.plan(c, all, "@you", 0, false, plain) != null)
        }
        // Your own scenes get everything BRB does, counting down to coming back.
        for (c in Slates.Card.entries.filter { it.isCustom }) {
            val p = LiveElements.plan(c, all, "@you", 0, false, plain)
            assertTrue(c.name, p != null && p.timers.size == 2 && p.chat)
            assertEquals("BACK IN", p!!.timers[0].label)
            assertEquals(Slates.Card.CUSTOM_1, LiveElements.cardOnScreen(Scene.CUSTOM_1, false, "phone"))
        }
        // Server-looped (Signal lost, and Weak signal's upload) and the cards the engine keeps bare.
        for (c in listOf(Slates.Card.SIGNAL_LOST, Slates.Card.WEAK_SIGNAL, Slates.Card.PRIVACY)) {
            assertNull(c.name, LiveElements.plan(c, all, "@you", 0, false, plain))
        }
    }

    @Test fun `camera lost never promises when it ends, but counts how long it's been`() {
        val p = LiveElements.plan(Slates.Card.CAMERA_LOST, CardElements(countdownMinutes = 5, showTimeAway = true), "", 0, false, plain)!!
        assertEquals(listOf("LOST FOR"), p.timers.map { it.label })
        assertNull(LiveElements.plan(Slates.Card.CAMERA_LOST, CardElements(countdownMinutes = 5), "", 0, false, plain))
    }

    @Test fun `countdown comes before time away, and only a timer ticks`() {
        val p = LiveElements.plan(brb, CardElements(countdownMinutes = 2, showTimeAway = true), "", 10_000, false, plain)!!
        assertEquals(listOf(TimerReadout("BACK IN", "1:50"), TimerReadout("AWAY", "0:10")), p.timers)
        assertTrue(p.ticking)
        val still = LiveElements.plan(brb, CardElements(showChat = true), "@you everywhere", 10_000, false, plain)!!
        assertFalse(still.ticking)
    }

    @Test fun `nothing set means nothing drawn`() {
        assertNull(LiveElements.plan(brb, CardElements(), "", 0, false, plain))
        assertNull(LiveElements.plan(brb, CardElements(), "   ", 0, false, plain))
    }

    @Test fun `card chat only when the streamer's chat isn't already on the card`() {
        assertTrue(LiveElements.plan(brb, CardElements(showChat = true), "", 0, userChatShown = false, look = plain)!!.chat)
        assertNull(LiveElements.plan(brb, CardElements(showChat = true), "", 0, userChatShown = true, look = plain))
    }

    @Test fun `socials line is one tidy line, cut to length, and left off web pages`() {
        assertEquals("@you everywhere", LiveElements.socialsLine("  @you \n  everywhere "))
        assertNull(LiveElements.socialsLine(" \t "))
        val long = LiveElements.socialsLine("x".repeat(100))!!
        assertEquals(LiveElements.MAX_SOCIALS, long.length)
        assertTrue(long.endsWith("…"))
        assertNull(LiveElements.plan(brb, CardElements(), "@you", 0, false, CardLook(plated = true, web = true)))
        assertEquals("@you", LiveElements.plan(brb, CardElements(), "@you", 0, false, plain)!!.socials)
    }

    @Test fun `plates only where something other than the plain field could be under the elements`() {
        assertEquals(CardLook(), LiveElements.look(brb, background = false, picture = false, layout = CardLayout.CLASSIC, web = false))
        assertEquals(
            CardLook(bookend = true, layout = CardLayout.CENTERED),
            LiveElements.look(Slates.Card.ENDING, false, false, CardLayout.CENTERED, false),
        )
        // CLASSIC's picture reaches 70% of a portrait card's text width, under the timer block.
        assertTrue(LiveElements.look(brb, background = false, picture = true, layout = CardLayout.CLASSIC, web = false).plated)
        assertEquals("Back soon", LiveElements.look(brb, false, false, CardLayout.CLASSIC, false, headline = "Back soon").headline)
        assertTrue(LiveElements.look(brb, background = true, picture = false, layout = CardLayout.CLASSIC, web = false).plated)
        assertTrue(LiveElements.look(brb, false, picture = true, layout = CardLayout.FULL_PICTURE, web = false).plated)
        assertTrue(LiveElements.look(brb, false, picture = true, layout = CardLayout.SIDE_BY_SIDE, web = false).plated)
        val web = LiveElements.look(Slates.Card.STARTING_SOON, false, false, CardLayout.CLASSIC, web = true)
        assertTrue(web.plated && web.web && !web.bookend)
    }

    @Test fun `the card on screen follows the scene, then weak signal, then a lost camera`() {
        assertEquals(brb, LiveElements.cardOnScreen(Scene.BRB, weakSignal = true, activeSource = "none"))
        assertEquals(Slates.Card.WEAK_SIGNAL, LiveElements.cardOnScreen(Scene.LIVE, weakSignal = true, activeSource = "none"))
        assertEquals(Slates.Card.CAMERA_LOST, LiveElements.cardOnScreen(Scene.LIVE, weakSignal = false, activeSource = "none"))
        assertNull(LiveElements.cardOnScreen(Scene.LIVE, weakSignal = false, activeSource = "phone"))
        assertEquals(Slates.Card.PRIVACY, LiveElements.cardOnScreen(Scene.PRIVACY, false, "phone"))
    }

    @Test fun `card clock counts from when this card appeared and restarts on a change`() {
        val clock = CardClock()
        assertEquals(0L, clock.update(null, 1_000))
        assertEquals(0L, clock.update(brb, 5_000))
        assertEquals(2_500L, clock.update(brb, 7_500))
        assertEquals(0L, clock.update(Slates.Card.PRIVACY, 8_000))
        assertEquals(0L, clock.update(null, 9_000))
        assertEquals(0L, clock.update(brb, 10_000))
        assertEquals(1_000L, clock.update(brb, 11_000))
    }

    @Test fun `redraws land on the card's whole seconds, and on its fives when hot`() {
        assertEquals(1000L, LiveElements.msToNextTick(0))
        assertEquals(1L, LiveElements.msToNextTick(4_999))
        assertEquals(750L, LiveElements.msToNextTick(12_250))
        assertEquals(1000L, LiveElements.tickPeriodMs(0))
        assertEquals(1000L, LiveElements.tickPeriodMs(1))
        assertEquals(5000L, LiveElements.tickPeriodMs(2))
        assertEquals(5000L, LiveElements.tickPeriodMs(3))
        assertEquals(2_750L, LiveElements.msToNextTick(12_250, LiveElements.tickPeriodMs(2)))
    }

    @Test fun `hot, the switch waits out a whole overlay upload interval`() {
        assertEquals(120L, LiveElements.switchSettleMs(0))
        assertEquals(120L, LiveElements.switchSettleMs(1))
        // From rung 2 the engine uploads at most every 5 s: the wait covers that plus a few frames at 24 fps.
        for (rung in 2..3) assertTrue(LiveElements.switchSettleMs(rung) >= LiveElements.HOT_UPLOAD_MS + 3 * 1000 / 24)
    }

    @Test fun `the switch waits from the start of the run of card-safe pushes`() {
        val hot = LiveElements.switchSettleMs(2)
        // The newest push still has Live-only layers: a full wait from the next card-safe one.
        assertEquals(hot, LiveElements.switchWaitMs(null, 10_000, hot))
        // Live -> BRB while hot: the card-only bitmap goes up at 0 with the switch off ...
        assertEquals(hot, LiveElements.switchWaitMs(0, 0, hot))
        // ... and the cool settle (120 ms) is no longer enough: the old Live bitmap may still be on the texture.
        assertEquals(hot - 120, LiveElements.switchWaitMs(0, 120, hot))
        assertFalse(LiveElements.safeSwitch(true, LiveElements.switchWaitMs(0, 120, hot) == 0L))
        // A ticking timer pushing card-safe bitmaps meanwhile doesn't restart it; once the interval is out, it flips.
        assertEquals(0L, LiveElements.switchWaitMs(0, hot, hot))
        assertTrue(LiveElements.safeSwitch(true, LiveElements.switchWaitMs(0, hot + 900, hot) == 0L))
        // A run that started long ago (the engine began with no overlay) is ready at once.
        assertEquals(0L, LiveElements.switchWaitMs(Long.MIN_VALUE / 2, 5_000, hot))
    }

    @Test fun `the on-cards switch flips on only once the engine holds a card-safe bitmap`() {
        assertTrue(LiveElements.overlayOnCards(userOnCards = true, cardElementsDrawn = false))
        assertTrue(LiveElements.overlayOnCards(userOnCards = false, cardElementsDrawn = true))
        assertFalse(LiveElements.overlayOnCards(userOnCards = false, cardElementsDrawn = false))
        assertFalse(LiveElements.safeSwitch(wanted = true, heldBitmapFitForCards = false))
        assertTrue(LiveElements.safeSwitch(wanted = true, heldBitmapFitForCards = true))
        assertFalse(LiveElements.safeSwitch(wanted = false, heldBitmapFitForCards = true))
    }

    @Test fun `the web overlay is held only while a chosen card shows its own elements without on cards`() {
        assertTrue(LiveElements.holdBrowser(brb, userOnCards = false, cardElementsDrawn = true))
        assertFalse(LiveElements.holdBrowser(brb, userOnCards = true, cardElementsDrawn = true))
        assertFalse(LiveElements.holdBrowser(brb, userOnCards = false, cardElementsDrawn = false))
        assertFalse(LiveElements.holdBrowser(null, userOnCards = false, cardElementsDrawn = true))
        // Camera lost stands in for Live: the page is drawn there anyway.
        assertFalse(LiveElements.holdBrowser(Slates.Card.CAMERA_LOST, userOnCards = false, cardElementsDrawn = true))
    }

    @Test fun `an alert on a card keeps left of the timers and chat column`() {
        val b = OverlayLayout.alertBox(1920, 1080, onCard = true,
            occupied = LiveElements.alertOccupied(setOf(Corner.BOTTOM_LEFT), rightColumnTaken = true), w = 400f, h = 200f)
        assertEquals(43.2f, b.x, 0.01f)
        assertEquals(setOf(Corner.BOTTOM_LEFT), LiveElements.alertOccupied(setOf(Corner.BOTTOM_LEFT), rightColumnTaken = false))
    }

    @Test fun `zones sit on the card's 8u margins, under the mark and above the ruler`() {
        // 1920x1080: u = 10.8 px; the ruler is 8u from the bottom.
        assertEquals(1920 - 86.4f, CardZones.right(1920, 1080), 0.01f)
        assertEquals(86.4f, CardZones.left(1920, 1080), 0.01f)
        assertEquals(16 * 10.8f, CardZones.timerTop(1920, 1080, topRightBottom = null, gap = 16f), 0.01f)
        assertEquals(300f + 16f, CardZones.timerTop(1920, 1080, topRightBottom = 300f, gap = 16f), 0.01f)
        assertEquals(16 * 10.8f, CardZones.timerTop(1920, 1080, topRightBottom = 50f, gap = 16f), 0.01f)
        assertEquals(1080 - 8 * 10.8f - 5.5f * 10.8f, CardZones.socialsBaseline(1920, 1080), 0.01f)
        assertEquals(46 * 10.8f, CardZones.chatMaxWidth(1080, 1920), 0.01f)
    }

    private val sizes = listOf(1920 to 1080, 1080 to 1920, 1280 to 720, 720 to 1280)

    @Test fun `on camera lost chat ends above the hero's rule, clear of its headline`() {
        val lost = LiveElements.look(Slates.Card.CAMERA_LOST, false, false, CardLayout.CLASSIC, false, "Camera lost")
        for ((w, h) in sizes) {
            val u = Slates.unit(w, h)
            val floor = CardZones.chatFloor(w, h, lost, Slates.Family.AUTOMATIC, headlineTop = null)
            // The rule sits at 42u and the headline's cap starts about 46.6u down; the floor is 40u.
            assertEquals(40f * u, floor, 0.01f)
            assertTrue(floor <= 42f * u - 2f * u)
        }
        // Portrait: the column keeps right of the hero's picture (up to 48u), so a two-line message can't reach it.
        val u = Slates.unit(1080, 1920)
        assertEquals(42f * u, CardZones.chatMaxWidth(1080, 1920, Slates.Family.AUTOMATIC), 0.01f)
        assertTrue(CardZones.right(1080, 1920) - CardZones.chatMaxWidth(1080, 1920, Slates.Family.AUTOMATIC) >= 50f * u - 0.01f)
        // Landscape has room: the usual width.
        assertEquals(46f * u, CardZones.chatMaxWidth(1920, 1080, Slates.Family.AUTOMATIC), 0.01f)
    }

    @Test fun `on a classic card chat ends above the measured headline, or the highest one can reach`() {
        val classic = CardLook(layout = CardLayout.CLASSIC, headline = "Be right back")
        for ((w, h) in sizes) {
            val u = Slates.unit(w, h)
            val worst = CardZones.classicHeadlineTopWorst(w, h)
            val floor = CardZones.chatFloor(w, h, classic, Slates.Family.CHOSEN, headlineTop = null)
            assertTrue(floor <= worst - 2f * u + 0.01f)
            assertTrue(floor <= h * CardZones.CHAT_FLOOR)
            // Any real fit (two lines at 17u, three at 12u, cap at most 0.75 of the size) starts no higher than the worst.
            for ((lines, size) in listOf(1 to 17f, 2 to 17f, 2 to 14f, 3 to 12f, 3 to 10f)) {
                val top = CardZones.classicHeadlineTop(w, h, lines, size * u, 0.72f * size * u)
                assertTrue("${w}x$h $lines@$size", top >= worst - 0.01f)
            }
        }
        // Landscape: a long headline climbs to about 31u, so the floor is about 29u ...
        val u = Slates.unit(1920, 1080)
        assertEquals(28.9f, CardZones.chatFloor(1920, 1080, classic, Slates.Family.BOOKEND, null) / u, 0.05f)
        // ... while a measured one-line headline (cap top at ~49.8u) leaves chat down to 2u above it.
        val oneLine = CardZones.classicHeadlineTop(1920, 1080, 1, 17f * u, 12.2f * u)
        assertEquals(49.8f, oneLine / u, 0.05f)
        assertEquals(oneLine - 2f * u, CardZones.chatFloor(1920, 1080, classic, Slates.Family.CHOSEN, oneLine), 0.01f)
        // Portrait keeps chat in the top half, far above the headline.
        assertEquals(960f, CardZones.chatFloor(1080, 1920, classic, Slates.Family.CHOSEN, null), 0.01f)
    }

    @Test fun `in the other layouts chat stops at the top band they keep free, and over a page at half height`() {
        for (layout in listOf(CardLayout.CENTERED, CardLayout.SIDE_BY_SIDE, CardLayout.FULL_PICTURE)) {
            for ((w, h) in sizes) {
                val floor = CardZones.chatFloor(w, h, CardLook(plated = true, layout = layout), Slates.Family.CHOSEN, null)
                assertEquals(CardZones.TOP_BAND_FLOOR_U * Slates.unit(w, h), floor, 0.01f)
            }
        }
        assertEquals(540f, CardZones.chatFloor(1920, 1080, CardLook(plated = true, web = true), Slates.Family.CHOSEN, null), 0.01f)
    }

    @Test fun `the chat column is dropped when fewer than two lines fit`() {
        val u = 10.8f
        val row = 6f * u
        val gap = 0.8f * u
        assertTrue(CardZones.chatFits(16f * u, 16f * u + 2 * row + gap, row, gap))
        assertFalse(CardZones.chatFits(16f * u, 16f * u + 2 * row + gap - 1f, row, gap))
        // Landscape BRB with a long headline and a timer block (to about 25u): no room, so no column.
        val classic = CardLook(headline = "A much longer headline that wraps onto three lines")
        assertFalse(CardZones.chatFits(25f * u, CardZones.chatFloor(1920, 1080, classic, Slates.Family.CHOSEN, null), row, gap))
    }

    @Test fun `the timer block starts below the mark in both orientations`() {
        for ((w, h) in listOf(1920 to 1080, 1080 to 1920, 1280 to 720, 720 to 1280)) {
            val u = Slates.unit(w, h)
            // The mark's foot: its top 1u above the tag's 8u line, 6u tall.
            assertTrue(CardZones.timerTop(w, h, null, 0f) >= 13f * u + 2f * u)
            // The socials line's descenders stay above the ruler's 3.6u cursor.
            assertTrue(CardZones.socialsBaseline(w, h) + 1f * u < Slates.rulerY(w, h) - 3.6f * u)
        }
    }
}
