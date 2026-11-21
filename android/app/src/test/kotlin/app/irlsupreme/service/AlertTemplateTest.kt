package app.irlsupreme.service

import app.irlsupreme.twitch.TwitchAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertTemplateTest {
    private val sub = TwitchAlert.Sub("s", "kit", "1000", false, 14, message = "two years!")
    private val newSub = TwitchAlert.Sub("s", "kit", "1000", false, null)
    private val gift = TwitchAlert.GiftSubs("g", "Moe", 5, "1000")
    private val bits = TwitchAlert.Cheer("c", "kit", 500, message = "nice view")
    private val raid = TwitchAlert.Raid("r", "BigStreamer", 230)
    private val tip = TwitchAlert.Tip("t", "kit", "€4,20")

    private fun message(a: TwitchAlert, template: String) = AlertText.card(a, AlertLook(message = template), AlertKindOptions()).message
    private fun label(a: TwitchAlert, template: String) = AlertText.card(a, AlertLook(label = template), AlertKindOptions()).label

    @Test fun `each kind fills its own placeholders`() {
        assertEquals("kit, 14 months: two years!", message(sub, "{name}, {months} months: {message}"))
        assertEquals("kit, month 1", message(newSub, "{name}, month {months}"))
        assertEquals("Moe gifted 5 (5 subs)", message(gift, "{name} gifted {count} ({amount})"))
        assertEquals("kit cheered 500 bits: nice view", message(bits, "{name} cheered {amount}: {message}"))
        assertEquals("BigStreamer brought 230", message(raid, "{name} brought {viewers}"))
        assertEquals("kit tipped €4,20", message(tip, "{name} tipped {amount}"))
        assertEquals("kit", message(TwitchAlert.Follow("f", "kit"), "{name}"))
    }

    @Test fun `unknown and empty placeholders go, and so do the spaces they leave`() {
        assertEquals("Thanks kit!", message(TwitchAlert.Follow("f", "kit"), "Thanks  {name} {amount} {viewers}!"))
        assertEquals("kit", message(newSub, "{message} — {name}"))
        assertEquals("hi kit", message(TwitchAlert.Follow("f", "kit"), "hi {nobody} {name} {months}"))
        assertEquals("kit", message(bits.copy(message = null), "{name}: {message}"))
        assertEquals("kit cheered", message(bits.copy(message = null), "{name} cheered ({message})"))
        // A template that ends up empty shows the plain name, as a blank one does.
        val c = AlertText.card(TwitchAlert.Follow("f", "kit"), AlertLook(message = "{message}"), AlertKindOptions())
        assertNull(c.message)
    }

    @Test fun `placeholders are any case and may have spaces inside`() {
        assertEquals("kit 14", message(sub, "{NAME} { Months }"))
    }

    @Test fun `what a viewer wrote is never read for placeholders, and stays on one line`() {
        val sneaky = bits.copy(message = "{name}{amount}\nsecond\tline")
        assertEquals("{name}{amount} second line", message(sneaky, "{message}"))
        val long = bits.copy(message = "x".repeat(500))
        assertEquals(AlertTemplate.MAX_VIEWER_MESSAGE, message(long, "{message}")!!.length)
    }

    @Test fun `labels are templates too, in caps`() {
        assertEquals("RAID BY BIGSTREAMER", label(raid, "raid by {name}"))
        assertEquals("FOLLOW", label(TwitchAlert.Follow("f", "kit"), "{message}"))
    }

    @Test fun `an amount shown in the words isn't shown again on the card`() {
        val r = AlertText.card(raid, AlertLook(message = "{viewers} with {name}"), AlertKindOptions())
        assertNull(r.amount)
        assertNull(r.amountUnit)
        assertNull(AlertText.card(gift, AlertLook(message = "{count} from {name}"), AlertKindOptions()).amount)
        assertNull(AlertText.card(bits, AlertLook(label = "{amount}!"), AlertKindOptions()).amount)
        assertEquals("230", AlertText.card(raid, AlertLook(message = "{name}"), AlertKindOptions()).amount)
        assertTrue(AlertTemplate.showsAmount("{months}", AlertKind.SUB))
        assertFalse(AlertTemplate.showsAmount("{months}", AlertKind.BITS))
        assertFalse(AlertTemplate.showsAmount(null, AlertKind.BITS))
    }

    @Test fun `with nothing set the card is as it always was`() {
        for (s in SampleAlert.entries) {
            val a = s.alert(1)
            val c = AlertText.card(a, AlertLook(), AlertKindOptions())
            assertNull(c.message)
            assertEquals(AlertKind.of(a).defaultLabel, c.label)
            assertEquals(AlertPaint(), c.paint)
            assertEquals(AlertLength.NORMAL, c.length)
        }
    }

    @Test fun `each kind's page lists what it can use`() {
        for (k in AlertKind.entries) assertTrue(k.name, "{name}" in alertPlaceholders(k))
        assertEquals(listOf("{name}", "{months}", "{message}"), alertPlaceholders(AlertKind.SUB))
        assertEquals(listOf("{name}", "{viewers}"), alertPlaceholders(AlertKind.RAID))
        assertTrue("{count}" in alertPlaceholders(AlertKind.GIFT))
        assertTrue("{amount}" in alertPlaceholders(AlertKind.TIP))
        assertTrue("{message}" in alertPlaceholders(AlertKind.BITS))
    }
}
