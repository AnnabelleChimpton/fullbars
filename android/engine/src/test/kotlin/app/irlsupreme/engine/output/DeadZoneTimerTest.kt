package app.irlsupreme.engine.output

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
}
