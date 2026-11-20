package app.irlsupreme.service

import app.irlsupreme.twitch.TwitchAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertStylesTest {
    @Test fun `templates fill in the name and amount, any case, tidily`() {
        assertEquals("kit just joined the crew!", fillAlertTemplate("{name} just joined the crew!", "kit", null))
        assertEquals("Thanks kit for 500 bits", fillAlertTemplate("Thanks {NAME} for {Amount}", "kit", "500 bits"))
        assertEquals("kit raided", fillAlertTemplate("{name} raided {amount}", "kit", null))
        assertTrue(templateHasAmount("x {AMOUNT}"))
        assertFalse(templateHasAmount("{name} only"))
    }

    @Test fun `a custom look replaces the label and the name line, keeps the size class`() {
        val raid = TwitchAlert.Raid("r", "BigStreamer", 230)
        val c = AlertText.card(raid, AlertLook(label = "Incoming", message = "{name} brought the party"))
        assertEquals("INCOMING", c.label)
        assertEquals("BigStreamer brought the party", c.message)
        assertEquals("BigStreamer", c.name)
        assertEquals("230", c.amount) // not in the message, so the count line stays
        assertEquals(AlertText.card(raid, AlertLook()).tier, c.tier)
    }

    @Test fun `an amount shown in the message isn't shown twice`() {
        val c = AlertText.card(TwitchAlert.Cheer("c", "kit", 500), AlertLook(message = "{name} threw {amount}!"))
        assertEquals("kit threw 500 bits!", c.message)
        assertNull(c.amount)
        assertNull(c.amountUnit)
    }

    @Test fun `blank or no look is the default card`() {
        val c = AlertText.card(TwitchAlert.Follow("f", "kit"), AlertLook())
        assertEquals("FOLLOW", c.label)
        assertNull(c.message)
        assertNull(c.picture)
    }

    @Test fun `TalkBack still hears what kind it is, whatever the label`() {
        val c = AlertText.card(TwitchAlert.Raid("r", "Big", 2), AlertLook(label = "Incoming", message = "party time"))
        assertEquals("Raid from Big, 2 viewers. party time", AlertText.spoken(c))
        assertEquals("New follower, kit", AlertText.spoken(AlertText.card(TwitchAlert.Follow("f", "kit"), AlertLook())))
    }

    @Test fun `every alert kind maps to its customisation`() {
        assertEquals(AlertKind.entries.map { it.name }.toSet(), SampleAlert.entries.map { it.name }.toSet())
        SampleAlert.entries.forEach { s -> assertEquals(s.name, AlertKind.of(s.alert(1)).name) }
    }
}
