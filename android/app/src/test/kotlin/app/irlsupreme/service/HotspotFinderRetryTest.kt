package app.irlsupreme.service

import org.junit.Assert.assertEquals
import org.junit.Test

class HotspotFinderRetryTest {
    @Test fun `a refused Bluetooth scan is retried after 5 s, 15 s, then every minute`() {
        assertEquals(listOf(5_000L, 15_000L, 60_000L, 60_000L), (0..3).map(HotspotFinder::retryDelayMs))
    }
}
