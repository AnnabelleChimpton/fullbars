package app.irlsupreme.service

import app.irlsupreme.engine.video.AlertTier
import app.irlsupreme.twitch.TwitchAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleAlertsTest {
    @Test fun `each pretend alert is the kind it says`() {
        assertTrue(SampleAlert.FOLLOW.alert(1) is TwitchAlert.Follow)
        assertTrue(SampleAlert.SUB.alert(1) is TwitchAlert.Sub)
        assertTrue(SampleAlert.GIFT.alert(1) is TwitchAlert.GiftSubs)
        assertTrue(SampleAlert.BITS.alert(1) is TwitchAlert.Cheer)
        assertTrue(SampleAlert.RAID.alert(1) is TwitchAlert.Raid)
    }

    @Test fun `every send is a new alert, so the queue never drops one as a repeat`() {
        assertNotEquals(SampleAlert.RAID.alert(1).id, SampleAlert.RAID.alert(2).id)
    }

    @Test fun `they draw at the size the real ones do`() {
        assertEquals(AlertTier.QUIET, AlertText.card(SampleAlert.FOLLOW.alert(1)).tier)
        assertEquals(AlertTier.LOUD, AlertText.card(SampleAlert.RAID.alert(1)).tier)
        assertEquals(AlertTier.MEDIUM, AlertText.card(SampleAlert.GIFT.alert(1)).tier)
    }
}
