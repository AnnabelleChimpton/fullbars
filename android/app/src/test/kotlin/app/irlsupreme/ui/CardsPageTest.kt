package app.irlsupreme.ui

import app.irlsupreme.engine.video.CardLayout
import app.irlsupreme.engine.video.Slates.Card
import app.irlsupreme.service.CardBackground
import app.irlsupreme.service.CardElements
import app.irlsupreme.service.CardRules
import app.irlsupreme.service.CardText
import app.irlsupreme.service.cardsValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardsPageTest {
    @Test fun `every card can be customised, the automatic ones last`() {
        assertEquals(Card.entries.toSet(), CARD_ORDER.toSet())
        assertEquals(listOf(Card.SIGNAL_LOST, Card.WEAK_SIGNAL, Card.CAMERA_LOST), CARD_ORDER.takeLast(3))
        assertTrue(CARD_ORDER.takeLast(3).all(::isAutomatic))
        assertFalse(CARD_ORDER.take(4).any(::isAutomatic))
        assertEquals("BRB", cardName(Card.BRB))
        assertEquals("Signal lost", cardName(Card.SIGNAL_LOST))
    }

    @Test fun `the settings row counts your cards`() {
        assertEquals("Default", cardsValue(emptyMap()))
        assertEquals("Default", cardsValue(mapOf(Card.BRB to CardText(" ", ""))))
        assertEquals("1 custom", cardsValue(mapOf(Card.BRB to CardText(hasPicture = true))))
        assertEquals("2 custom", cardsValue(mapOf(Card.BRB to CardText("Hi"), Card.ENDING to CardText(sub = "Bye"))))
    }

    @Test fun `the settings row counts any change viewers see, and a lone socials line`() {
        assertEquals("1 custom", cardsValue(mapOf(Card.BRB to CardText(layout = CardLayout.CENTERED))))
        assertEquals("1 custom", cardsValue(mapOf(Card.BRB to CardText(elements = CardElements(showChat = true)))))
        assertEquals("Default", cardsValue(mapOf(Card.BRB to CardText(dimPercent = 80))))
        assertEquals("Socials line", cardsValue(emptyMap(), "@kit everywhere"))
        assertEquals("Default", cardsValue(emptyMap(), "  "))
        assertEquals("1 custom", cardsValue(mapOf(Card.BRB to CardText(background = CardBackground.GIF)), "@kit"))
    }

    @Test fun `every layout has a name and one help line`() {
        assertEquals(CardLayout.entries.size, CardLayout.entries.map(::layoutName).toSet().size)
        CardLayout.entries.forEach { assertTrue(layoutHelp(it).endsWith(".")) }
        assertEquals("Classic", layoutName(CardLayout.CLASSIC))
    }

    @Test fun `keys read plainly`() {
        assertEquals(listOf("Off", "20%", "35%", "50%", "65%", "80%"), CardRules.DIM_STEPS.map(::dimLabel))
        assertEquals("Off", countdownLabel(0))
        assertEquals("15 min", countdownLabel(15))
        assertEquals("GIF, looping", backgroundValue(Card.BRB, CardBackground.GIF))
        assertEquals("GIF, first frame", backgroundValue(Card.WEAK_SIGNAL, CardBackground.GIF))
        assertEquals("Picture", backgroundValue(Card.SIGNAL_LOST, CardBackground.STILL))
    }
}
