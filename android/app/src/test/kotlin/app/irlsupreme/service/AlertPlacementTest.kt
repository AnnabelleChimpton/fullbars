package app.irlsupreme.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPlacementTest {
    private val frames = listOf(1920 to 1080, 1080 to 1920, 1280 to 720, 720 to 1280)

    /** The largest alert drawn at [size] on a [w] x [h] frame (OverlayComposer.alertBitmap's box). */
    private fun largest(w: Int, h: Int, size: AlertSize) =
        w * OverlayLayout.ALERT_MAX_W * size.factor to h * OverlayLayout.ALERT_MAX_H * size.factor

    @Test fun `every place keeps the largest alert inside the safe margin, both ways up, at every size`() {
        for ((w, h) in frames) for (size in AlertSize.entries) for (place in AlertPlace.entries) {
            val m = OverlayLayout.margin(w, h)
            val (aw, ah) = largest(w, h, size)
            for (onCard in listOf(false, true)) {
                val b = AlertPlacement.box(place, w, h, onCard, emptySet(), aw, ah)
                val at = "$place $size $w x $h card $onCard"
                assertTrue("left: $at", b.x >= m - 0.01f)
                assertTrue("top: $at", b.y >= m - 0.01f)
                assertTrue("right: $at", b.x + aw <= w - m + 0.01f)
                assertTrue("bottom: $at", b.y + ah <= h - m + 0.01f)
            }
        }
    }

    @Test fun `fixed places sit where they say, both ways up`() {
        for ((w, h) in frames) {
            val m = OverlayLayout.margin(w, h)
            val aw = 300f
            val ah = 120f
            fun box(p: AlertPlace) = AlertPlacement.box(p, w, h, false, emptySet(), aw, ah)
            assertEquals(OverlayLayout.Box(m, m, aw, ah), box(AlertPlace.TOP_LEFT))
            assertEquals(OverlayLayout.Box((w - aw) / 2, m, aw, ah), box(AlertPlace.TOP_CENTER))
            assertEquals(OverlayLayout.Box(w - m - aw, m, aw, ah), box(AlertPlace.TOP_RIGHT))
            assertEquals(OverlayLayout.Box((w - aw) / 2, (h - ah) / 2, aw, ah), box(AlertPlace.CENTER))
            assertEquals(OverlayLayout.Box(m, h - m - ah, aw, ah), box(AlertPlace.BOTTOM_LEFT))
            assertEquals(OverlayLayout.Box((w - aw) / 2, h - m - ah, aw, ah), box(AlertPlace.BOTTOM_CENTER))
            assertEquals(OverlayLayout.Box(w - m - aw, h - m - ah, aw, ah), box(AlertPlace.BOTTOM_RIGHT))
        }
    }

    @Test fun `a fixed place ignores the other layers and the card, Automatic keeps today's rules`() {
        val busy = setOf(Corner.BOTTOM_LEFT, Corner.TOP_RIGHT)
        assertEquals(
            AlertPlacement.box(AlertPlace.BOTTOM_LEFT, 1920, 1080, false, emptySet(), 400f, 200f),
            AlertPlacement.box(AlertPlace.BOTTOM_LEFT, 1920, 1080, true, busy, 400f, 200f),
        )
        for ((w, h) in frames) for (onCard in listOf(false, true)) for (occ in listOf(emptySet(), busy)) {
            assertEquals(
                OverlayLayout.alertBox(w, h, onCard, occ, 400f, 200f),
                AlertPlacement.box(AlertPlace.AUTO, w, h, onCard, occ, 400f, 200f),
            )
        }
    }

    @Test fun `the page can say which corners a fixed place may cover`() {
        assertEquals(emptySet<Corner>(), AlertPlacement.covers(AlertPlace.AUTO))
        assertEquals(setOf(Corner.TOP_LEFT), AlertPlacement.covers(AlertPlace.TOP_LEFT))
        assertEquals(setOf(Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT), AlertPlacement.covers(AlertPlace.BOTTOM_CENTER))
    }

    @Test fun `sizes scale the whole alert, Normal being today's`() {
        assertEquals(1f, AlertSize.NORMAL.factor)
        assertTrue(AlertSize.SMALL.factor < 1f && AlertSize.LARGE.factor > 1f)
    }
}
