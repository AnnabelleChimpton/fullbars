package app.irlsupreme.engine.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadZoneTimerTest {
    @Test fun `no links for the whole window is a dead zone, and a link ends it`() {
        val t = DeadZoneTimer(1_500)
        assertFalse(t.update(10_000, 2))
        assertFalse(t.update(10_500, 0))
        assertFalse(t.update(11_400, 0))
        assertTrue(t.update(11_500, 0))
        assertTrue(t.update(40_000, 0))
        assertFalse(t.update(40_250, 1))
        assertFalse(t.update(41_000, 0))
    }

    @Test fun `the first reading never claims a dead zone`() {
        val t = DeadZoneTimer(1_500)
        assertFalse(t.update(50_000, 0))
        assertTrue(t.update(51_500, 0))
    }

    @Test fun `with proof ages, the warning comes 1_5 s after the server last heard us, while links are still connected`() {
        val t = DeadZoneTimer(1_500)
        val lastProof = 20_000L
        // Polled every 250 ms; the server stops hearing us at 20 s, links stay CONNECTED (they time out only at 24 s).
        var now = 19_000L
        val fired = ArrayList<Long>()
        while (now <= 23_000) {
            val proofAgo = if (now <= lastProof) 30L else now - lastProof
            if (t.update(now, connectedLinks = 2, freshestProofAgoMs = proofAgo)) fired += now
            now += 250
        }
        assertEquals(21_500L, fired.first())
        // Heard again: over at once.
        assertFalse(t.update(23_250, 2, 10))
    }
}
