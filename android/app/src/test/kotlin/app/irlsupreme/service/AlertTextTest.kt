package app.irlsupreme.service

import app.irlsupreme.engine.audio.AlertSounds
import app.irlsupreme.engine.video.AlertTier
import app.irlsupreme.twitch.TwitchAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AlertTextTest {
    private data class Drawn(val label: String, val name: String, val amount: String?, val unit: String? = null)

    private fun drawn(a: TwitchAlert) = AlertText.card(a).let { Drawn(it.label, it.name, it.amount, it.amountUnit) }

    @Test fun `labels are caps nouns and amounts are figures`() {
        assertEquals(Drawn("FOLLOW", "NewFan", null), drawn(TwitchAlert.Follow("1", "NewFan")))
        assertEquals(Drawn("SUB", "KitIRL", null), drawn(TwitchAlert.Sub("1", "KitIRL", "1000", prime = false, months = null)))
        assertEquals(Drawn("SUB", "KitIRL", "Prime"), drawn(TwitchAlert.Sub("1", "KitIRL", "1000", prime = true, months = null)))
        assertEquals(Drawn("SUB", "KitIRL", "Tier 2"), drawn(TwitchAlert.Sub("1", "KitIRL", "2000", prime = false, months = 1)))
        assertEquals(Drawn("RESUB", "KitIRL", "12 months"), drawn(TwitchAlert.Sub("1", "KitIRL", "1000", prime = false, months = 12)))
        assertEquals(Drawn("RESUB", "KitIRL", "14 months · Tier 2"), drawn(TwitchAlert.Sub("1", "KitIRL", "2000", prime = false, months = 14)))
        assertEquals(Drawn("GIFT SUBS", "Anonymous", "5 subs"), drawn(TwitchAlert.GiftSubs("1", "Anonymous", 5, "1000")))
        assertEquals(Drawn("GIFT SUBS", "Moe", "1 sub"), drawn(TwitchAlert.GiftSubs("1", "Moe", 1, "1000")))
        assertEquals(Drawn("CHEER", "KitIRL", "500 bits"), drawn(TwitchAlert.Cheer("1", "KitIRL", 500)))
        assertEquals(Drawn("CHEER", "KitIRL", "1 bit"), drawn(TwitchAlert.Cheer("1", "KitIRL", 1)))
        assertEquals(Drawn("TIP", "Styler", "$5.00"), drawn(TwitchAlert.Tip("1", "Styler", "$5.00")))
        assertEquals(Drawn("RAID", "BigStreamer", "230", "viewers"), drawn(TwitchAlert.Raid("1", "BigStreamer", 230)))
        assertEquals(Drawn("RAID", "Tiny", "1", "viewer"), drawn(TwitchAlert.Raid("1", "Tiny", 1)))
    }

    @Test fun `no label shouts`() {
        val all = listOf(
            TwitchAlert.Follow("1", "a"), TwitchAlert.Sub("1", "a", "1000", false, 3), TwitchAlert.GiftSubs("1", "a", 2, "1000"),
            TwitchAlert.Cheer("1", "a", 5), TwitchAlert.Tip("1", "a", "$1"), TwitchAlert.Raid("1", "a", 3),
        ).map { AlertText.card(it) }
        for (c in all) {
            assertFalse(c.label, '!' in c.label)
            assertEquals(c.label, c.label.uppercase())
        }
    }

    @Test fun `a tip shows who and how much, never its message`() {
        val card = AlertText.card(TwitchAlert.Tip("1", "Styler", "$4.20"))
        assertEquals(listOf("TIP", "Styler", "$4.20"), listOf(card.label, card.name, card.amount))
        assertNull(AlertText.card(TwitchAlert.Tip("1", "Styler", " ")).amount)
    }

    @Test fun `the tier follows the chime`() {
        assertEquals(AlertSounds.Style.SOFT, AlertText.card(TwitchAlert.Follow("1", "a")).style)
        assertEquals(AlertTier.QUIET, AlertText.card(TwitchAlert.Follow("1", "a")).tier)
        for (a in listOf(
            TwitchAlert.Sub("1", "a", "1000", false, null), TwitchAlert.GiftSubs("1", "a", 2, "1000"),
            TwitchAlert.Cheer("1", "a", 5), TwitchAlert.Tip("1", "a", "$1"),
        )) assertEquals(a.toString(), AlertTier.MEDIUM, AlertText.card(a).tier)
        assertEquals(AlertSounds.Style.BIG, AlertText.card(TwitchAlert.Raid("1", "a", 3)).style)
        assertEquals(AlertTier.LOUD, AlertText.card(TwitchAlert.Raid("1", "a", 3)).tier)
    }

    @Test fun `each tier stays up for its dwell`() {
        assertEquals(4_000L, AlertText.card(TwitchAlert.Follow("1", "a")).dwellMs)
        assertEquals(6_000L, AlertText.card(TwitchAlert.Cheer("1", "a", 5)).dwellMs)
        assertEquals(7_200L, AlertText.card(TwitchAlert.Raid("1", "a", 3)).dwellMs)
        // The raid's 12-step rule loses a segment every 600 ms.
        assertEquals(600L, AlertTiming.dwellMs(AlertTier.LOUD) / 12)
    }

    @Test fun `TalkBack hears a sentence, not the caps label`() {
        assertEquals("Raid from BigStreamer, 230 viewers", AlertText.card(TwitchAlert.Raid("1", "BigStreamer", 230)).spoken)
        assertEquals("New follower, NewFan", AlertText.card(TwitchAlert.Follow("1", "NewFan")).spoken)
        assertEquals("Tip from Styler, $4.20", AlertText.card(TwitchAlert.Tip("1", "Styler", "$4.20")).spoken)
        assertEquals("New sub from KitIRL", AlertText.card(TwitchAlert.Sub("1", "KitIRL", "1000", false, null)).spoken)
        assertEquals("Resub from KitIRL, 14 months, Tier 2", AlertText.card(TwitchAlert.Sub("1", "KitIRL", "2000", false, 14)).spoken)
        assertEquals("Gift subs from Moe, 1 sub", AlertText.card(TwitchAlert.GiftSubs("1", "Moe", 1, "1000")).spoken)
        assertEquals("Cheer from KitIRL, 100 bits", AlertText.card(TwitchAlert.Cheer("1", "KitIRL", 100)).spoken)
    }
}
