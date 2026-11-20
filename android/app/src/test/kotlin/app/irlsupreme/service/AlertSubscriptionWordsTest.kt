package app.irlsupreme.service

import app.irlsupreme.twitch.SubscriptionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertSubscriptionWordsTest {
    @Test fun `the log says plainly why follow alerts are off`() {
        assertEquals("on", subscriptionWords(SubscriptionStatus.Active))
        assertTrue(subscriptionWords(SubscriptionStatus.NeedsSignIn).contains("sign in again"))
        assertTrue(subscriptionWords(SubscriptionStatus.Failed("Twitch API error 403")).contains("403"))
    }
}
