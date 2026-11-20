package app.irlsupreme.engine.video

import app.irlsupreme.engine.video.Slates.Card
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardStyleTest {
    @After fun clear() { Slates.setStyles(emptyMap()) }

    @Test fun `your words replace the card's, but the label and the sound line never change`() {
        val style = CardStyle.of("Tunnel ahead", "Back in 2 min", null)
        val c = Slates.copy(Card.SIGNAL_LOST, SlateAudio.ON, style)
        assertEquals("Tunnel ahead", c.headline)
        assertEquals("Back in 2 min", c.sub)
        assertEquals("SIGNAL LOST · AUTO", c.tag)
        assertEquals(Slates.footerLabel(SlateAudio.NONE), c.footer) // the server's loop is silent, whatever you write
        val p = Slates.copy(Card.PRIVACY, SlateAudio.ON, CardStyle.of("Bathroom break", null, null))
        assertEquals("PRIVACY", p.tag)
        assertEquals(Slates.footerLabel(SlateAudio.MUTED), p.footer)
    }

    @Test fun `blank words keep the card's own, including BRB's honest audio line`() {
        val blank = CardStyle.of("   ", "", null)
        assertTrue(blank.isDefault)
        assertEquals("Be right back", Slates.copy(Card.BRB, SlateAudio.ON, blank).headline)
        assertEquals("Audio's on. Back in a bit.", Slates.copy(Card.BRB, SlateAudio.ON, blank).sub)
        assertEquals("Back in a bit.", Slates.copy(Card.BRB, SlateAudio.MUTED, blank).sub)
        // Only the headline set: the line under it is still the card's.
        assertEquals("Thanks for watching.", Slates.copy(Card.ENDING, SlateAudio.ON, CardStyle.of("Bye!", null, null)).sub)
    }

    @Test fun `setting styles reports what changed and bumps the version so cached cards redraw`() {
        val v0 = Slates.version
        val changed = Slates.setStyles(mapOf(Card.BRB to CardStyle.of("Snack run", null, null), Card.ENDING to CardStyle.DEFAULT))
        assertEquals(setOf(Card.BRB), changed)
        assertNotEquals(v0, Slates.version)
        assertEquals("Snack run", Slates.style(Card.BRB).headline)
        val v1 = Slates.version
        assertEquals(emptySet<Card>(), Slates.setStyles(mapOf(Card.BRB to CardStyle.of("Snack run", null, null))))
        assertEquals(v1, Slates.version) // nothing changed, nothing to redraw
        assertEquals(setOf(Card.BRB), Slates.setStyles(emptyMap()))
        assertEquals(CardStyle.DEFAULT, Slates.style(Card.BRB))
    }

    @Test fun `a picture fits its space, keeps its shape and sits on the headline`() {
        // A 400x200 picture in a band 100..300 px tall, at most 300 px wide: limited by width.
        assertArrayEquals(floatArrayOf(10f, 150f, 310f, 300f), Slates.pictureBox(400, 200, 10f, 100f, 300f, 300f, 1000f, true, 20f), 0.01f)
        // A tall one is limited by the band's height, top-aligned when asked.
        assertArrayEquals(floatArrayOf(0f, 100f, 50f, 200f), Slates.pictureBox(100, 200, 0f, 100f, 200f, 500f, 1000f, false, 20f), 0.01f)
        // maxH caps the band.
        assertArrayEquals(floatArrayOf(0f, 260f, 40f, 300f), Slates.pictureBox(100, 100, 0f, 100f, 300f, 500f, 40f, true, 20f), 0.01f)
        // No room (a long headline on a landscape card): no picture rather than a sliver.
        assertNull(Slates.pictureBox(100, 100, 0f, 100f, 110f, 500f, 1000f, true, 20f))
        assertNull(Slates.pictureBox(0, 100, 0f, 0f, 500f, 500f, 1000f, true, 20f))
    }
}
