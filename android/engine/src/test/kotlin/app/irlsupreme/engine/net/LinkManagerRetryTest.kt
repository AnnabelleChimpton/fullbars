package app.irlsupreme.engine.net

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkManagerRetryTest {
    @Test fun `a network that can't look the server up is retried quickly, then every 10 s`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 10_000L, 10_000L, 10_000L), (0..5).map(LinkManager::retryDelayMs))
    }
}
