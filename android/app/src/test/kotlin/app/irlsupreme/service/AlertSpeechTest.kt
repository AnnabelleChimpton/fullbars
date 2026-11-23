package app.irlsupreme.service

import app.irlsupreme.twitch.TwitchAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertSpeechTest {
    private val cheer = TwitchAlert.Cheer("c", "Sam", 500, "you look amazing today")
    private val resub = TwitchAlert.Sub("s", "Kit", "1000", prime = false, months = 14, message = "love this stream")

    @Test fun `each kind gets its own thank-you`() {
        assertEquals("Thank you for the follow, Sam!", AlertSpeech.line(TwitchAlert.Follow("f", "Sam"), AlertSay.THANKS))
        assertEquals("Thank you for subscribing, Kit!", AlertSpeech.line(TwitchAlert.Sub("s", "Kit", "1000", prime = false, months = null), AlertSay.THANKS))
        assertEquals("Thank you for the Prime sub, Kit!", AlertSpeech.line(TwitchAlert.Sub("s", "Kit", "1000", prime = true, months = null), AlertSay.THANKS))
        assertEquals("Thank you for 14 months, Kit!", AlertSpeech.line(resub, AlertSay.THANKS))
        assertEquals("Thank you Ana for the 5 gifted subs!", AlertSpeech.line(TwitchAlert.GiftSubs("g", "Ana", 5, "1000"), AlertSay.THANKS))
        assertEquals("Thank you for the gifted sub!", AlertSpeech.line(TwitchAlert.GiftSubs("g", "Anonymous", 1, "1000"), AlertSay.THANKS))
        assertEquals("Thank you Sam for the 500 bits!", AlertSpeech.line(cheer, AlertSay.THANKS))
        assertEquals("Thank you Jo for the $4.20 tip!", AlertSpeech.line(TwitchAlert.Tip("t", "Jo", "$4.20"), AlertSay.THANKS))
        assertEquals("Welcome, raiders! Thank you Big for the raid!", AlertSpeech.line(TwitchAlert.Raid("r", "Big", 230), AlertSay.THANKS))
        assertNull(AlertSpeech.line(cheer, AlertSay.OFF))
    }

    @Test fun `with their message, the words follow the thank-you`() {
        assertEquals("Thank you Sam for the 500 bits! you look amazing today", AlertSpeech.line(cheer, AlertSay.MESSAGE))
        assertEquals("Thank you for 14 months, Kit! love this stream", AlertSpeech.line(resub, AlertSay.MESSAGE))
        // No words: just the thanks.
        assertEquals("Thank you Sam for the 500 bits!", AlertSpeech.line(cheer.copy(message = null), AlertSay.MESSAGE))
    }

    @Test fun `a blocked message is left out and a blocked name becomes someone`() {
        val bad = cheer.copy(message = "kys lol")
        assertEquals("Thank you Sam for the 500 bits!", AlertSpeech.line(bad, AlertSay.MESSAGE))
        assertTrue(AlertSpeech.messageBlocked(bad, AlertSay.MESSAGE))
        assertFalse(AlertSpeech.messageBlocked(bad, AlertSay.THANKS))
        assertEquals("Thank you someone for the 500 bits!", AlertSpeech.line(cheer.copy(user = "Retard_99"), AlertSay.THANKS))
        assertEquals("Thank you Sam for the 500 bits!", AlertSpeech.line(cheer.copy(message = "pineapple pizza"), AlertSay.MESSAGE, listOf("pineapple pizza")))
    }

    @Test fun `an alert is said only when its kind says it and it's big enough`() {
        val o = AlertKindOptions(say = AlertSay.THANKS, sayFrom = 100)
        assertTrue(AlertFilter.says(cheer, o))
        assertFalse(AlertFilter.says(cheer.copy(bits = 99), o))
        assertFalse(AlertFilter.says(cheer, AlertKindOptions()))
        assertTrue(AlertFilter.says(TwitchAlert.Follow("f", "Sam"), AlertKindOptions(say = AlertSay.THANKS, sayFrom = 100)))
        assertTrue(AlertFilter.says(TwitchAlert.Tip("t", "Jo", "$4.20"), AlertKindOptions(say = AlertSay.THANKS, sayFrom = 400)))
        assertFalse(AlertFilter.says(TwitchAlert.Tip("t", "Jo", "$4.20"), AlertKindOptions(say = AlertSay.THANKS, sayFrom = 500)))
    }

    @Test fun `the say setting is stored, and a message choice only sticks where there's a message`() {
        val bits = AlertKindOptions(say = AlertSay.MESSAGE, sayFrom = 250)
        assertEquals(bits, AlertOptionsCodec.normalize(AlertKind.BITS, bits))
        assertEquals(AlertSay.THANKS, AlertOptionsCodec.normalize(AlertKind.RAID, AlertKindOptions(say = AlertSay.MESSAGE)).say)
        // A follow has no amount: its "from" isn't kept.
        assertEquals(0L, AlertOptionsCodec.normalize(AlertKind.FOLLOW, AlertKindOptions(say = AlertSay.THANKS, sayFrom = 50)).sayFrom)
        assertEquals(emptyMap<String, Any?>(), AlertOptionsCodec.encodeKind(AlertKind.BITS, AlertKindOptions()).filterValues { it != null })
    }
}
