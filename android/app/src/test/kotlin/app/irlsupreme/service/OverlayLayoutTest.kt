package app.irlsupreme.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OverlayLayoutTest {
    private fun line(id: String, at: Long, cmd: Boolean = false) = ChatLine(id, "Sam", null, "hi", cmd, at)

    @Test fun `corners keep a 4 percent margin, in portrait and landscape`() {
        assertEquals(OverlayLayout.Box(43.2f, 43.2f, 100f, 50f), OverlayLayout.place(Corner.TOP_LEFT, 100f, 50f, 1920, 1080))
        assertEquals(OverlayLayout.Box(1920 - 43.2f - 100f, 1080 - 43.2f - 50f, 100f, 50f), OverlayLayout.place(Corner.BOTTOM_RIGHT, 100f, 50f, 1920, 1080))
        assertEquals(OverlayLayout.Box(1080 - 43.2f - 200f, 43.2f, 200f, 80f), OverlayLayout.place(Corner.TOP_RIGHT, 200f, 80f, 1080, 1920))
    }

    @Test fun `layers sharing a corner stack away from it`() {
        val first = OverlayLayout.place(Corner.TOP_LEFT, 100f, 60f, 1920, 1080)
        val second = OverlayLayout.place(Corner.TOP_LEFT, 300f, 40f, 1920, 1080, used = 60f, gap = 10f)
        assertEquals(first.y + 60f + 10f, second.y, 0.01f)
        val low = OverlayLayout.place(Corner.BOTTOM_LEFT, 100f, 60f, 1920, 1080)
        val above = OverlayLayout.place(Corner.BOTTOM_LEFT, 100f, 30f, 1920, 1080, used = 60f, gap = 10f)
        assertEquals(low.y - 10f - 30f, above.y, 0.01f)
    }

    @Test fun `chat shows the newest six, no commands, nothing over a minute old`() {
        val now = 1_000_000L
        val lines = listOf(line("old", now - 61_000)) + (1..8).map { line("m$it", now - 1_000L * (9 - it)) } +
            line("cmd", now, cmd = true)
        val shown = OverlayLayout.visibleChat(lines, now)
        assertEquals(listOf("m3", "m4", "m5", "m6", "m7", "m8"), shown.map { it.id })
        assertEquals(emptyList<ChatLine>(), OverlayLayout.visibleChat(listOf(line("old", now - 61_000)), now))
    }

    @Test fun `occupied corners are only the layers that are on`() {
        assertEquals(emptySet<Corner>(), OverlayLayout.occupied(OverlaySettings()))
        val all = OverlaySettings(chat = true, textOn = true, text = "hi", imageOn = true)
        assertEquals(setOf(Corner.BOTTOM_LEFT, Corner.TOP_LEFT, Corner.TOP_RIGHT), OverlayLayout.occupied(all))
        assertEquals(setOf(Corner.BOTTOM_LEFT), OverlayLayout.occupied(OverlaySettings(chat = true, textOn = true, text = "  ")))
    }

    @Test fun `an alert sits left on the title-safe margin, 58 percent down on camera and 30 on a card`() {
        val cam = OverlayLayout.alertBox(1080, 1920, onCard = false, occupied = emptySet(), w = 400f, h = 200f)
        assertEquals(43.2f, cam.x, 0.01f)
        assertEquals(1920 * 0.58f, cam.y, 0.01f)
        val card = OverlayLayout.alertBox(1920, 1080, onCard = true, occupied = emptySet(), w = 400f, h = 200f)
        assertEquals(43.2f, card.x, 0.01f)
        assertEquals(1080 * 0.30f, card.y, 0.01f)
    }

    @Test fun `an alert mirrors right when the lower-left is taken`() {
        val b = OverlayLayout.alertBox(1920, 1080, onCard = false, occupied = setOf(Corner.BOTTOM_LEFT), w = 400f, h = 200f)
        assertEquals(1920 - 43.2f - 400f, b.x, 0.01f)
        // Other corners don't move it; with both lower corners taken it stays left.
        assertEquals(43.2f, OverlayLayout.alertBox(1920, 1080, false, setOf(Corner.TOP_LEFT, Corner.BOTTOM_RIGHT), 400f, 200f).x, 0.01f)
        assertEquals(43.2f, OverlayLayout.alertBox(1920, 1080, false, setOf(Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT), 400f, 200f).x, 0.01f)
    }

    @Test fun `a tall alert moves up to stay title-safe, but never into the top 20 percent`() {
        val m = OverlayLayout.margin(1920, 1080)
        val tall = OverlayLayout.alertBox(1920, 1080, onCard = false, occupied = emptySet(), w = 400f, h = 500f)
        assertEquals(1080 - m - 500f, tall.y, 0.01f)
        val huge = OverlayLayout.alertBox(1920, 1080, onCard = false, occupied = emptySet(), w = 400f, h = 1000f)
        assertEquals(1080 * 0.2f, huge.y, 0.01f)
    }

    @Test fun `every alert up to the largest stays title-safe, below the pill zone and off-centre`() {
        for ((w, h) in listOf(1920 to 1080, 1080 to 1920)) {
            val m = OverlayLayout.margin(w, h)
            val aw = w * OverlayLayout.ALERT_MAX_W
            val ah = h * OverlayLayout.ALERT_MAX_H
            for (onCard in listOf(false, true)) for (occ in listOf(emptySet(), setOf(Corner.BOTTOM_LEFT))) {
                val b = OverlayLayout.alertBox(w, h, onCard, occ, aw, ah)
                val at = "$w x $h, card $onCard, $occ"
                assertTrue("top in the pill zone: $at", b.y >= h * 0.2f - 0.01f)
                assertTrue("bottom past title-safe: $at", b.y + ah <= h - m + 0.01f)
                assertTrue("left past title-safe: $at", b.x >= m - 0.01f)
                assertTrue("right past title-safe: $at", b.x + aw <= w - m + 0.01f)
                assertFalse("centred: $at", abs((b.x + aw / 2) - w / 2f) < 1f)
            }
        }
    }

    @Test fun `chat lines are at most 60u wide, u being 1 percent of the short side`() {
        assertEquals(60f, OverlayComposer.CHAT_MAX_W_U)
        assertEquals(10.8f, OverlayLayout.unit(1920, 1080), 0.001f)
        assertEquals(10.8f, OverlayLayout.unit(1080, 1920), 0.001f)
    }
}
