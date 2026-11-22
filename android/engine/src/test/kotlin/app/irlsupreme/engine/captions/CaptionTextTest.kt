package app.irlsupreme.engine.captions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionTextTest {
    /** Every character is 10 px wide. */
    private val measure = { s: String -> s.length * 10f }

    @Test fun `a growing guess shows its newest words, wrapped, newest line last`() {
        val t = CaptionText()
        t.partial("okay chat we just got off the train", 0)
        // 120 px a line: 12 characters.
        assertEquals(listOf("the train"), t.lines(1, 120f, measure))
        assertEquals(listOf("just got off", "the train"), t.lines(2, 120f, measure))
        assertEquals(listOf("okay chat we", "just got off", "the train"), CaptionText.wrap(t.shown(), 120f, measure))
    }

    @Test fun `revisions replace the guess, and a segment carries on from it`() {
        val t = CaptionText()
        assertTrue(t.partial("we were", 0))
        assertTrue(t.partial("we're walking", 100))
        assertFalse(t.partial("we're walking", 200))
        assertTrue(t.segment("we're walking down", 300))
        t.partial("to the market", 400)
        assertEquals(listOf("we're", "walking", "down", "to", "the", "market"), t.shown())
    }

    @Test fun `a pause clears the strip and those words don't come back`() {
        val t = CaptionText(clearAfterMs = 4_000)
        t.partial("hold on I need to cross here", 0)
        assertFalse(t.tick(3_999))
        assertTrue(t.tick(4_000))
        assertTrue(t.shown().isEmpty())
        // The same long guess carries on after the pause: only the new words show.
        t.partial("hold on I need to cross here all right", 6_000)
        assertEquals(listOf("all", "right"), t.shown())
    }

    @Test fun `a mute clears at once`() {
        val t = CaptionText()
        t.partial("thanks for the follow", 0)
        assertTrue(t.clear())
        assertTrue(t.shown().isEmpty())
        assertFalse(t.clear())
        t.segment("thanks for the follow", 10)
        assertTrue(t.shown().isEmpty()) // the finished version of what was hidden stays hidden
        t.partial("welcome in", 20)
        assertEquals(listOf("welcome", "in"), t.shown())
    }

    @Test fun `a guess that takes words back never shows hidden ones`() {
        val t = CaptionText()
        t.partial("one two three four", 0)
        t.clear()
        t.partial("one two", 10)
        assertTrue(t.shown().isEmpty())
        t.partial("one two five", 20)
        assertEquals(listOf("five"), t.shown())
    }

    @Test fun `a word too long for a line gets a line of its own`() {
        assertEquals(listOf("a", "supercalifragilistic", "b"), CaptionText.wrap(listOf("a", "supercalifragilistic", "b"), 50f) { it.length * 10f })
    }

    @Test fun `only recent finished words are kept`() {
        val t = CaptionText()
        repeat(300) { t.segment("w$it", it.toLong()) }
        assertEquals("w299", t.shown().last())
        assertEquals(200, t.shown().size)
    }
}
