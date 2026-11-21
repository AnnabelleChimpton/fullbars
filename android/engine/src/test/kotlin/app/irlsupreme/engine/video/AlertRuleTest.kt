package app.irlsupreme.engine.video

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertRuleTest {
    @Test fun `at normal size the rule sits where it always has`() {
        val u = 10.8f
        val b = AlertRule.box(100f, 200f, 500f, 160f, u)
        assertEquals(100f + 3f * u, b.left, 0.001f)
        assertEquals(600f - 3f * u, b.right, 0.001f)
        assertEquals(360f - 1.25f * u, b.bottom, 0.001f)
        assertEquals(360f - 1.75f * u, b.top, 0.001f)
        assertEquals(0.4f * u, b.gap, 0.001f)
        assertEquals(0.12f * u, b.line, 0.001f)
    }

    @Test fun `scaled with the alert, the rule keeps its place in it`() {
        val u = 10.8f
        val w = 500f
        val h = 160f
        val normal = AlertRule.box(0f, 0f, w, h, u)
        for (s in listOf(0.8f, 1.25f, 0.93f)) {
            val b = AlertRule.box(40f, 70f, w * s, h * s, u * s)
            // Everything, measured from the alert's corner, is the normal rule times the scale.
            assertEquals(normal.left * s, b.left - 40f, 0.01f)
            assertEquals(normal.right * s, b.right - 40f, 0.01f)
            assertEquals(normal.top * s, b.top - 70f, 0.01f)
            assertEquals(normal.bottom * s, b.bottom - 70f, 0.01f)
            assertEquals(normal.gap * s, b.gap, 0.001f)
            assertEquals(normal.height * s, b.height, 0.001f)
        }
    }
}
