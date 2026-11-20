package app.irlsupreme.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperRelayGiveUpTest {
    @Test fun `on the streaming phone's hotspot the relay never gives up`() {
        assertFalse(relayGivesUp(onHotspot = true, fromMdns = false, identified = false, failures = 1, maxFailures = 3))
        assertFalse(relayGivesUp(onHotspot = true, fromMdns = true, identified = true, failures = 10, maxFailures = 3))
    }

    @Test fun `on other Wi-Fi an unconfirmed gateway guess gives up at once`() {
        assertTrue(relayGivesUp(onHotspot = false, fromMdns = false, identified = false, failures = 1, maxFailures = 3))
    }

    @Test fun `on other Wi-Fi a known phone gives up after 3 failures`() {
        assertFalse(relayGivesUp(onHotspot = false, fromMdns = true, identified = false, failures = 2, maxFailures = 3))
        assertTrue(relayGivesUp(onHotspot = false, fromMdns = true, identified = false, failures = 3, maxFailures = 3))
        assertFalse(relayGivesUp(onHotspot = false, fromMdns = false, identified = true, failures = 2, maxFailures = 3))
        assertTrue(relayGivesUp(onHotspot = false, fromMdns = false, identified = true, failures = 3, maxFailures = 3))
    }
}
