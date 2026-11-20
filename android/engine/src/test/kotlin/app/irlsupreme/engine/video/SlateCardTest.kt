package app.irlsupreme.engine.video

import app.irlsupreme.engine.Scene
import app.irlsupreme.engine.video.Slates.Card
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlateCardTest {
    @Test fun `every card prints its fixed copy, in sentence case with no filler`() {
        val table = mapOf(
            Card.STARTING_SOON to listOf("STARTING SOON", "Starting soon", "Getting the rig ready. Not long now."),
            Card.BRB to listOf("BRB", "Be right back", "Audio's on. Back in a bit."),
            Card.PRIVACY to listOf("PRIVACY", "Privacy", "Camera and mic off for a moment."),
            Card.ENDING to listOf("ENDING", "That's the stream", "Thanks for watching."),
            Card.WEAK_SIGNAL to listOf("WEAK SIGNAL · AUTO", "Weak signal", "Picture paused to keep the stream up."),
            Card.CAMERA_LOST to listOf("CAMERA LOST · AUTO", "Camera lost", "Reconnecting the camera."),
            Card.SIGNAL_LOST to listOf("SIGNAL LOST · AUTO", "Signal lost", "The stream picks up when the connection's back."),
        )
        assertEquals(Card.entries.toSet(), table.keys)
        for ((card, words) in table) {
            val c = Slates.copy(card, SlateAudio.ON)
            assertEquals(words, listOf(c.tag, c.headline, c.sub))
            assertFalse("$card shouts", "!" in c.headline + c.sub)
            assertEquals("$card headline is sentence case", c.headline.first().uppercase() + c.headline.drop(1).lowercase(), c.headline)
            assertEquals(c.tag.uppercase(), c.tag)
        }
        for (old in listOf("Stay tuned", "Get comfy", "See you next time", "Camera reconnecting", "BE RIGHT BACK")) {
            assertTrue(Card.entries.none { old in it.headline || old in it.sub })
        }
    }

    @Test fun `the footer tells the truth about audio, and BRB only claims audio it has`() {
        assertEquals("AUDIO ON", Slates.copy(Card.BRB, SlateAudio.ON).footer)
        assertEquals("MUTED", Slates.copy(Card.BRB, SlateAudio.MUTED).footer)
        assertEquals("NO LIVE AUDIO", Slates.copy(Card.BRB, SlateAudio.NONE).footer)
        assertEquals("Back in a bit.", Slates.copy(Card.BRB, SlateAudio.MUTED).sub)
        assertEquals("Back in a bit.", Slates.copy(Card.BRB, SlateAudio.NONE).sub)
        // Privacy turns the mic off; the server's Signal lost loop is silent.
        for (a in SlateAudio.entries) {
            assertEquals("MUTED", Slates.copy(Card.PRIVACY, a).footer)
            assertEquals("NO LIVE AUDIO", Slates.copy(Card.SIGNAL_LOST, a).footer)
        }
        assertEquals("AUDIO ON", Slates.copy(Card.WEAK_SIGNAL, SlateAudio.ON).footer)
    }

    @Test fun `layouts and marks differ by word, glyph and layout, never by scene`() {
        assertEquals(Slates.Family.BOOKEND, Card.STARTING_SOON.family)
        assertEquals(Slates.Family.BOOKEND, Card.ENDING.family)
        assertEquals(Slates.Family.CHOSEN, Card.BRB.family)
        assertEquals(Slates.Family.CHOSEN, Card.PRIVACY.family)
        for (c in listOf(Card.WEAK_SIGNAL, Card.CAMERA_LOST, Card.SIGNAL_LOST)) assertEquals(Slates.Family.AUTOMATIC, c.family)
        val full = intArrayOf(BrandDraw.BAR_FULL, BrandDraw.BAR_FULL, BrandDraw.BAR_FULL)
        assertArrayEquals(full, Slates.markBars(Card.BRB))
        assertArrayEquals(full, Slates.markBars(Card.CAMERA_LOST))
        assertEquals(BrandDraw.BAR_AMBER, Slates.markBars(Card.WEAK_SIGNAL)[0])
        assertTrue(Slates.markBars(Card.SIGNAL_LOST).all { it == BrandDraw.BAR_DASHED })
    }

    @Test fun `Signal lost is the server card, not a scene, and scenes map as before`() {
        assertNull(Card.of(Scene.LIVE))
        assertEquals(Card.BRB, Card.of(Scene.BRB))
        assertEquals(Card.PRIVACY, Card.of(Scene.PRIVACY))
        assertEquals(Card.STARTING_SOON, Card.of(Scene.STARTING_SOON))
        assertEquals(Card.ENDING, Card.of(Scene.ENDING))
        assertTrue(Scene.entries.none { Card.of(it) == Card.SIGNAL_LOST })
        assertEquals(SlateAudio.NONE, Slates.shownAudio(Card.SIGNAL_LOST, SlateAudio.ON))
        assertTrue(Card.SIGNAL_LOST.hasCursor)
    }

    @Test fun `the cursor takes 4 even steps per GOP and loops seamlessly`() {
        for (gop in listOf(60, 120)) {
            val steps = (0 until gop).map { Slates.cursorStep(it.toLong(), gop) }
            assertEquals(listOf(0, 1, 2, 3), steps.distinct())
            assertEquals("each step is a quarter of the GOP", List(4) { gop / 4 }, steps.groupingBy { it }.eachCount().values.toList())
            assertEquals(0, gop % Slates.CURSOR_STEPS)
            // A running frame count on the phone steps exactly like the server's looped GOP.
            for (f in 0L until 5L * gop) assertEquals(Slates.cursorStep(f % gop, gop), Slates.cursorStep(f, gop))
        }
        // 30 fps, 2 s GOP: a step every 15 frames.
        assertEquals(listOf(0, 0, 1, 1, 2, 2, 3, 3, 0), listOf(0, 14, 15, 29, 30, 44, 45, 59, 60).map { Slates.cursorStep(it.toLong(), 60) })
        assertEquals(listOf(0.125f, 0.375f, 0.625f, 0.875f), (0..3).map(Slates::cursorFraction))
    }

    @Test fun `no cursor on Weak signal or Privacy, and Weak signal stays still and cheap`() {
        assertFalse(Card.WEAK_SIGNAL.hasCursor)
        assertFalse(Card.PRIVACY.hasCursor)
        assertFalse(Card.WEAK_SIGNAL.moves)
        assertTrue(Card.entries.filter { it != Card.WEAK_SIGNAL && it != Card.PRIVACY }.all { it.hasCursor })
    }

    @Test fun `the cursor stands on the ruler, 1·2u by 3·6u, inside the title-safe margin`() {
        for ((w, h) in listOf(1920 to 1080, 1080 to 1920)) {
            val u = minOf(w, h) / 100f
            for (step in 0..3) {
                val r = Slates.cursorRect(w, h, step)
                assertEquals(1.2f * u, r[2] - r[0], 0.01f)
                assertEquals(3.6f * u, r[3] - r[1], 0.01f)
                assertTrue(r[0] > 8f * u && r[2] < w - 8f * u)
                assertTrue(r[3] in Slates.rulerY(w, h)..Slates.rulerY(w, h) + 0.2f * u)
            }
        }
    }

    @Test fun `grain and pulse repeat with the GOP, one pulse per loop`() {
        assertEquals(Slates.grainSeed(7, 60), Slates.grainSeed(67, 60))
        assertEquals(1f, Slates.pulseGain(0, 60), 1e-6f)
        assertEquals(1f + Slates.PULSE, Slates.pulseGain(15, 60), 1e-4f)
        assertEquals(1f - Slates.PULSE, Slates.pulseGain(45, 60), 1e-4f)
        assertEquals(Slates.pulseGain(10, 60), Slates.pulseGain(70, 60), 1e-6f)
        assertEquals(0.025f, Slates.GRAIN)
    }

    /** A fake face: every character is half the size wide. */
    private val measure: (String, Float) -> Float = { s, size -> s.length * size * 0.5f }

    @Test fun `a headline keeps its size on up to two lines, shrinks to 12u, then takes a third line`() {
        val u = 10.8f
        // Wide frame: one line at 17u.
        assertEquals(Slates.Fit(17f * u, listOf("Be right back")), Slates.fitLines("Be right back", 1747f, 17f * u, 12f * u, measure))
        // Narrow frame: two lines, still 17u.
        val two = Slates.fitLines("Be right back", 100f * 0.5f * 17f * u / 10, 17f * u, 12f * u, measure)
        assertEquals(17f * u, two.size, 0.01f)
        assertEquals(2, two.lines.size)
        // Too long for two lines even at 12u: three lines at 12u.
        val long = "That's the stream for tonight"
        val maxW = 12f * u * 0.5f * 11
        val three = Slates.fitLines(long, maxW, 17f * u, 12f * u, measure)
        assertEquals(12f * u, three.size, 0.01f)
        assertEquals(3, three.lines.size)
        assertTrue(three.lines.all { measure(it, three.size) <= maxW })
        // Between: shrinks below 17u but no lower than 12u to stay on two lines.
        val mid = Slates.fitLines("Be right back", 15f * u * 0.5f * 8, 17f * u, 12f * u, measure)
        assertTrue(mid.size in 12f * u..17f * u)
        assertTrue(mid.lines.size <= 2)
        assertTrue(mid.lines.all { measure(it, mid.size) <= 15f * u * 0.5f * 8 })
    }

    @Test fun `the card headlines fit a portrait 1080 frame within two lines at 12u or more`() {
        val u = 10.8f
        val maxW = 1080 - 16f * u
        // XC 800 averages about 0.42em per character.
        val xc: (String, Float) -> Float = { s, size -> s.length * size * 0.42f }
        for (c in Card.entries) {
            val f = Slates.fitLines(c.headline, maxW, 17f * u, 12f * u, xc)
            assertTrue("${c.headline}: ${f.lines}", f.lines.size <= 2 && f.size >= 12f * u)
        }
    }
}
