package app.irlsupreme.ui

import app.irlsupreme.engine.Scene
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
        assertEquals(Card.entries.filter { !it.isCustom }.toSet(), CARD_ORDER.toSet())
        assertEquals(listOf(Card.SIGNAL_LOST, Card.WEAK_SIGNAL, Card.CAMERA_LOST), CARD_ORDER.takeLast(3))
        assertTrue(CARD_ORDER.takeLast(3).all(::isAutomatic))
        assertFalse(CARD_ORDER.take(4).any(::isAutomatic))
        assertEquals("BRB", cardName(Card.BRB, emptyMap()))
        assertEquals("Signal lost", cardName(Card.SIGNAL_LOST, emptyMap()))
    }

    @Test fun `your own scenes sit between the chosen cards and the automatic ones, by their names`() {
        val own = mapOf(Scene.CUSTOM_2 to "Lunch", Scene.CUSTOM_4 to "Q&A")
        val order = cardOrder(own)
        assertEquals(CARD_ORDER.take(4) + listOf(Card.CUSTOM_2, Card.CUSTOM_4) + CARD_ORDER.drop(4), order)
        assertEquals(CARD_ORDER, cardOrder(emptyMap()))
        assertEquals("Lunch", cardName(Card.CUSTOM_2, own))
        assertEquals("Scene 1", cardName(Card.CUSTOM_1, own))
        assertFalse(isAutomatic(Card.CUSTOM_1))
    }

    @Test fun `colours say what they are, and when they could read as a warning`() {
        assertEquals("Teal. Picked so it never reads as a signal colour.", colorHelp(app.irlsupreme.engine.video.Slates.ACCENTS[0], preset = true))
        assertTrue(colorHelp(0xFFFF2020.toInt(), preset = false).contains("warning colour"))
        assertEquals("Blue, your own colour.", colorHelp(0xFF2050FF.toInt(), preset = false))
        assertEquals(listOf("Bone", "Ink"), TEXT_PRESETS.map(::textPresetName))
    }

    @Test fun `the not on camera tag names the screen viewers see`() {
        val before = app.irlsupreme.service.SceneNames.created
        try {
            app.irlsupreme.service.SceneNames.created = mapOf(Scene.CUSTOM_1 to "Lunch")
            assertEquals("Not on camera. Viewers see your Lunch screen", notOnCameraWords(Scene.CUSTOM_1))
            assertEquals("Not on camera. Viewers see your BRB screen", notOnCameraWords(Scene.BRB))
            assertEquals("Not on camera. Viewers see your privacy screen", notOnCameraWords(Scene.PRIVACY))
            assertEquals("Lunch", sceneName(Scene.CUSTOM_1))
            assertEquals("Scene 2", sceneName(Scene.CUSTOM_2))
        } finally {
            app.irlsupreme.service.SceneNames.created = before
        }
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
