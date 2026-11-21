package app.irlsupreme.service

import app.irlsupreme.twitch.TwitchAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AlertFilterTest {
    private val follow = TwitchAlert.Follow("f", "kit")
    private fun sub(months: Int? = null, prime: Boolean = false) = TwitchAlert.Sub("s", "kit", "1000", prime, months)
    private fun verdict(a: TwitchAlert, o: AlertKindOptions = AlertKindOptions()) = AlertFilter.verdict(a, o)

    @Test fun `by default every alert shows`() {
        val all = listOf(
            follow, sub(), sub(12, prime = true), TwitchAlert.GiftSubs("g", "kit", 1, "1000"), TwitchAlert.Cheer("c", "kit", 1),
            TwitchAlert.Raid("r", "kit", 0), TwitchAlert.Tip("t", "kit", "$0.01"), TwitchAlert.Tip("t", "kit", "no number"),
        )
        for (a in all) assertEquals(a.toString(), AlertVerdict.SHOW, verdict(a))
    }

    @Test fun `a kind turned off never shows`() {
        assertEquals(AlertVerdict.TURNED_OFF, verdict(follow, AlertKindOptions(on = false)))
        assertEquals(AlertVerdict.TURNED_OFF, verdict(TwitchAlert.Raid("r", "kit", 5000), AlertKindOptions(on = false)))
    }

    @Test fun `bits, gift subs and raids show from their minimum up`() {
        val atLeast100 = AlertKindOptions(minimum = 100)
        assertEquals(AlertVerdict.BELOW_MINIMUM, verdict(TwitchAlert.Cheer("c", "kit", 99), atLeast100))
        assertEquals(AlertVerdict.SHOW, verdict(TwitchAlert.Cheer("c", "kit", 100), atLeast100))
        val atLeast5 = AlertKindOptions(minimum = 5)
        assertEquals(AlertVerdict.BELOW_MINIMUM, verdict(TwitchAlert.GiftSubs("g", "kit", 4, "1000"), atLeast5))
        assertEquals(AlertVerdict.SHOW, verdict(TwitchAlert.GiftSubs("g", "kit", 5, "1000"), atLeast5))
        assertEquals(AlertVerdict.BELOW_MINIMUM, verdict(TwitchAlert.Raid("r", "kit", 4), atLeast5))
        assertEquals(AlertVerdict.SHOW, verdict(TwitchAlert.Raid("r", "kit", 230), atLeast5))
    }

    @Test fun `a tip's minimum is its own currency's number`() {
        val atLeast5 = AlertKindOptions(minimum = 500)
        assertEquals(AlertVerdict.BELOW_MINIMUM, verdict(TwitchAlert.Tip("t", "kit", "$4.99"), atLeast5))
        assertEquals(AlertVerdict.SHOW, verdict(TwitchAlert.Tip("t", "kit", "$5.00"), atLeast5))
        assertEquals(AlertVerdict.SHOW, verdict(TwitchAlert.Tip("t", "kit", "€5,00"), atLeast5))
        assertEquals(AlertVerdict.SHOW, verdict(TwitchAlert.Tip("t", "kit", "¥500"), atLeast5))
        assertEquals(AlertVerdict.BELOW_MINIMUM, verdict(TwitchAlert.Tip("t", "kit", "2.50 GBP"), atLeast5))
        // A tip whose amount can't be read isn't dropped on a guess.
        assertEquals(AlertVerdict.SHOW, verdict(TwitchAlert.Tip("t", "kit", "a lot"), atLeast5))
    }

    @Test fun `subs can skip Prime, and can show only resubs of so many months`() {
        val noPrime = AlertKindOptions(skipPrime = true)
        assertEquals(AlertVerdict.BELOW_MINIMUM, verdict(sub(prime = true), noPrime))
        assertEquals(AlertVerdict.BELOW_MINIMUM, verdict(sub(24, prime = true), noPrime))
        assertEquals(AlertVerdict.SHOW, verdict(sub(), noPrime))
        val sixMonths = AlertKindOptions(resubMonths = 6)
        assertEquals(AlertVerdict.BELOW_MINIMUM, verdict(sub(), sixMonths))
        assertEquals(AlertVerdict.BELOW_MINIMUM, verdict(sub(5), sixMonths))
        assertEquals(AlertVerdict.SHOW, verdict(sub(6), sixMonths))
        assertEquals(AlertVerdict.SHOW, verdict(sub(30, prime = true), sixMonths))
        assertEquals(AlertVerdict.BELOW_MINIMUM, verdict(sub(30, prime = true), sixMonths.copy(skipPrime = true)))
    }

    @Test fun `follows have no minimum`() {
        assertEquals(AlertVerdict.SHOW, verdict(follow, AlertKindOptions(minimum = 1000, resubMonths = 5, skipPrime = true)))
    }

    @Test fun `the log says the kind and why, never who`() {
        assertEquals("alert bits: filtered (below minimum)", AlertFilter.logLine(AlertKind.BITS, AlertVerdict.BELOW_MINIMUM))
        assertEquals("alert follow: filtered (turned off)", AlertFilter.logLine(AlertKind.FOLLOW, AlertVerdict.TURNED_OFF))
        for (k in AlertKind.entries) assertFalse(AlertFilter.logLine(k, AlertVerdict.BELOW_MINIMUM).contains("kit"))
    }

    @Test fun `tip amounts read in any common format`() {
        assertEquals(420L, TipAmount.hundredths("$4.20"))
        assertEquals(500L, TipAmount.hundredths("€5,00"))
        assertEquals(123456L, TipAmount.hundredths("$1,234.56"))
        assertEquals(123456L, TipAmount.hundredths("1.234,56 €"))
        assertEquals(123400L, TipAmount.hundredths("1,234 USD"))
        assertEquals(50000L, TipAmount.hundredths("¥500"))
        assertEquals(550L, TipAmount.hundredths("CA$5.5"))
        assertEquals(123456L, TipAmount.hundredths("1 234,56 kr"))
        assertNull(TipAmount.hundredths("free"))
    }

    @Test fun `a typed tip minimum reads back as typed`() {
        assertEquals(0L, TipAmount.parseMinimum(""))
        assertEquals(500L, TipAmount.parseMinimum("5"))
        assertEquals(250L, TipAmount.parseMinimum("2.5"))
        assertEquals(250L, TipAmount.parseMinimum("2,50"))
        assertNull(TipAmount.parseMinimum("five"))
        assertNull(TipAmount.parseMinimum("-3"))
        assertEquals("5", TipAmount.format(500))
        assertEquals("2.50", TipAmount.format(250))
        assertEquals("0.05", TipAmount.format(5))
    }
}
