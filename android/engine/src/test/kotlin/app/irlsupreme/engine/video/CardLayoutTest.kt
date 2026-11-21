package app.irlsupreme.engine.video

import app.irlsupreme.engine.video.Slates.Box
import app.irlsupreme.engine.video.Slates.Card
import app.irlsupreme.engine.video.Slates.Family
import app.irlsupreme.engine.video.Slates.Plan
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The four layouts' geometry, the background maths and the accent rules: the pure parts of custom cards. */
class CardLayoutTest {
    private val headCap = 0.72f
    private val measureHead: (String, Float) -> Float = { s, size -> s.length * size * 0.42f }
    private val measureSub: (String, Float) -> Float = { s, size -> s.length * size * 0.45f }
    private fun metrics(w: Int, h: Int) = Slates.Metrics(headCap * 2.4f * Slates.unit(w, h), headCap, measureHead, measureSub)

    private val frames = listOf(1920 to 1080, 1080 to 1920, 1280 to 720)
    /** None, landscape, portrait, a long strip. */
    private val pictures = listOf(0 to 0, 400 to 300, 300 to 600, 1000 to 100)

    private fun plan(w: Int, h: Int, card: Card, layout: CardLayout, pic: Pair<Int, Int> = 0 to 0, headline: String = card.headline) =
        Slates.plan(w, h, card.family, layout, headline, card.sub, pic.first, pic.second, metrics(w, h))

    /** Each line's ink box: its width, from its cap top to its baseline. */
    private fun lineBoxes(p: Plan): List<Box> =
        p.head.map { Box(it.x, it.baseline - headCap * p.headSize, it.x + measureHead(it.text, p.headSize), it.baseline) } +
            p.sub.map { Box(it.x, it.baseline - headCap * p.subSize, it.x + measureSub(it.text, p.subSize), it.baseline) }

    private fun assertBox(expected: Box, actual: Box?) {
        assertNotNull(actual)
        assertArrayEquals(
            floatArrayOf(expected.left, expected.top, expected.right, expected.bottom),
            floatArrayOf(actual!!.left, actual.top, actual.right, actual.bottom), 0.01f,
        )
    }

    private fun overlaps(a: Box, b: Box) = a.left < b.right - 0.5f && b.left < a.right - 0.5f && a.top < b.bottom - 0.5f && b.top < a.bottom - 0.5f
    private fun inside(inner: Box, outer: Box) =
        inner.left >= outer.left - 0.5f && inner.right <= outer.right + 0.5f && inner.top >= outer.top - 0.5f && inner.bottom <= outer.bottom + 0.5f

    @Test fun `every layout keeps the words inside the margins, clear of the tag row, the ruler and the picture`() {
        for ((w, h) in frames) {
            val u = Slates.unit(w, h)
            val ry = Slates.rulerY(w, h)
            val words = Box(8f * u, 8f * u + headCap * 2.4f * u + 6f * u, w - 8f * u, ry - 8f * u)
            for (card in Card.entries) for (layout in CardLayout.entries) for (pic in pictures) {
                val p = plan(w, h, card, layout, pic)
                val what = "$card $layout ${w}x$h picture $pic"
                assertTrue("$what: no headline", p.head.isNotEmpty())
                val lines = lineBoxes(p)
                for (l in lines) assertTrue("$what: line $l outside $words", inside(l, words))
                p.picture?.let { pb ->
                    if (p.pictureCover) {
                        assertBox(Box(0f, 0f, w.toFloat(), h.toFloat()), pb)
                    } else {
                        assertTrue("$what: picture $pb outside the open area", inside(pb, words))
                        for (l in lines) assertFalse("$what: picture $pb over line $l", overlaps(pb, l))
                    }
                }
                p.hero?.let { hb ->
                    assertTrue("$what: glyph $hb off the grid", inside(hb, Box(8f * u, 8f * u, w - 8f * u, ry - 8f * u)))
                    for (l in lines) assertFalse("$what: glyph over line $l", overlaps(hb, l))
                    assertEquals("$what: the glyph is square", hb.width, hb.height, 0.01f)
                }
                assertEquals("$what: only automatic cards have the rule", card.family == Family.AUTOMATIC, p.rule != null)
                p.rule?.let { r ->
                    assertEquals(10f * u, r.width, 0.01f)
                    assertEquals(0.6f * u, r.height, 0.01f)
                    for (l in lines) assertFalse("$what: rule over line $l", overlaps(r, l))
                    assertTrue("$what: the rule is above the words", lines.all { it.top >= r.bottom })
                }
                if (card.family == Family.AUTOMATIC && !p.pictureCover) {
                    assertTrue("$what: an automatic card always shows its glyph or the picture in its place", (p.hero != null) != (p.picture != null))
                }
                assertEquals("$what: only the full-picture layout has a plate", layout == CardLayout.FULL_PICTURE, p.plate != null)
            }
        }
    }

    @Test fun `classic is today's card, to the pixel`() {
        for ((w, h) in frames) {
            val u = Slates.unit(w, h)
            val ry = Slates.rulerY(w, h)
            val m = metrics(w, h)
            val p = plan(w, h, Card.BRB, CardLayout.CLASSIC, 400 to 300)
            assertEquals(ry - 30f * u, p.head.last().baseline, 0.01f)
            assertTrue(p.head.all { it.x == 8f * u } && p.sub.all { it.x == 8f * u })
            assertEquals(p.head.last().baseline + 0.7f * p.headSize, p.sub.first().baseline, 0.01f)
            val headTop = p.head.first().baseline - headCap * p.headSize
            val legacy = Slates.pictureBox(400, 300, 8f * u, 8f * u + m.tagCap + 6f * u, headTop - 5f * u, minOf((w - 16f * u) * 0.7f, 64f * u), 40f * u, true, 6f * u)!!
            val pb = p.picture!!
            assertArrayEquals(legacy, floatArrayOf(pb.left, pb.top, pb.right, pb.bottom), 0.01f)

            val a = plan(w, h, Card.SIGNAL_LOST, CardLayout.CLASSIC)
            assertBox(Box(8f * u, 18f * u, 28f * u, 38f * u), a.hero)
            assertBox(Box(8f * u, 42f * u, 18f * u, 42.6f * u), a.rule)
            assertEquals(12f * u, a.headSize, 0.01f)
            assertEquals(42.6f * u + 4f * u + headCap * 12f * u, a.head.first().baseline, 0.01f)
            // The picture takes the glyph's place, top-aligned in the 20u band.
            val ap = plan(w, h, Card.SIGNAL_LOST, CardLayout.CLASSIC, 400 to 300)
            assertNull(ap.hero)
            assertEquals(18f * u, ap.picture!!.top, 0.01f)
        }
    }

    @Test fun `centred puts every line, the picture and the rule on the frame's centre line`() {
        for ((w, h) in frames) {
            val u = Slates.unit(w, h)
            val cx = w / 2f
            for (card in listOf(Card.BRB, Card.ENDING, Card.WEAK_SIGNAL)) {
                val p = plan(w, h, card, CardLayout.CENTERED, 400 to 300)
                for (l in p.head) assertEquals(cx, l.x + measureHead(l.text, p.headSize) / 2, 0.5f)
                for (l in p.sub) assertEquals(cx, l.x + measureSub(l.text, p.subSize) / 2, 0.5f)
                assertEquals(cx, (p.picture!!.left + p.picture!!.right) / 2, 0.5f)
                p.rule?.let { assertEquals(cx, (it.left + it.right) / 2, 0.5f) }
            }
            // Without a picture the words sit in the middle of the open area between the tag row and the ruler.
            val p = plan(w, h, Card.BRB, CardLayout.CENTERED)
            val top = 8f * u + headCap * 2.4f * u + 6f * u
            val bottom = Slates.rulerY(w, h) - 8f * u
            val blockTop = p.head.first().baseline - headCap * p.headSize
            assertEquals((top + bottom) / 2, (blockTop + p.sub.last().baseline) / 2, 0.5f)
        }
    }

    @Test fun `side by side puts the picture beside the words in landscape and over them in portrait`() {
        val (w, h) = 1920 to 1080
        val u = Slates.unit(w, h)
        val p = plan(w, h, Card.BRB, CardLayout.SIDE_BY_SIDE, 400 to 300)
        val pic = p.picture!!
        assertEquals(8f * u, pic.left, 0.01f)
        assertTrue(p.head.all { it.x == pic.right + 6f * u })
        assertTrue("the picture takes at most half", pic.width <= (w - 16f * u - 6f * u) / 2 + 0.5f)
        // Each side centred in the open area.
        val top = 8f * u + headCap * 2.4f * u + 6f * u
        val bottom = Slates.rulerY(w, h) - 8f * u
        assertEquals((top + bottom) / 2, (pic.top + pic.bottom) / 2, 0.5f)
        // An automatic card without a picture puts a big glyph there.
        val a = plan(w, h, Card.CAMERA_LOST, CardLayout.SIDE_BY_SIDE)
        assertEquals(36f * u, a.hero!!.width, 0.01f)
        assertTrue(a.head.all { it.x == a.hero!!.right + 6f * u })
        assertEquals(a.hero!!.right + 6f * u, a.rule!!.left, 0.01f)

        val (pw, ph) = 1080 to 1920
        val pu = Slates.unit(pw, ph)
        val s = plan(pw, ph, Card.BRB, CardLayout.SIDE_BY_SIDE, 400 to 300)
        assertEquals(8f * pu, s.picture!!.left, 0.01f)
        assertTrue("stacked: the picture over the words", s.picture!!.bottom + 6f * pu <= s.head.first().baseline - headCap * s.headSize + 0.5f)
        assertTrue("and wider than the classic picture", s.picture!!.width > 64f * pu)
        assertTrue(s.head.all { it.x == 8f * pu })
        val sa = plan(pw, ph, Card.SIGNAL_LOST, CardLayout.SIDE_BY_SIDE)
        assertEquals(28f * pu, sa.hero!!.width, 0.01f)
    }

    @Test fun `side by side with nothing to put beside the words is the classic card`() {
        for ((w, h) in frames) for (card in listOf(Card.BRB, Card.STARTING_SOON, Card.PRIVACY)) {
            assertEquals(plan(w, h, card, CardLayout.CLASSIC), plan(w, h, card, CardLayout.SIDE_BY_SIDE))
        }
    }

    @Test fun `full picture fills the frame and keeps the words on a plate on the grid`() {
        for ((w, h) in frames) {
            val u = Slates.unit(w, h)
            val ry = Slates.rulerY(w, h)
            for (card in Card.entries) for (pic in listOf(0 to 0, 400 to 300)) {
                val p = plan(w, h, card, CardLayout.FULL_PICTURE, pic, headline = "Back after this tunnel")
                val plate = p.plate!!
                assertEquals(4f * u, plate.left, 0.01f) // the 4u title-safe edge...
                assertTrue(p.head.all { it.x == 8f * u } && p.sub.all { it.x == 8f * u }) // ...so the words stay on 8u
                assertEquals(ry - 6f * u, plate.bottom, 0.01f) // clear of the ruler's ticks and cursor
                assertTrue(plate.right <= w - 4f * u + 0.01f)
                for (l in lineBoxes(p)) assertTrue("$card: $l off the plate $plate", inside(l, plate))
                p.rule?.let { assertTrue(inside(it, plate)) }
                assertEquals(pic.first > 0, p.pictureCover)
                // Without a picture to fill the frame, an automatic card keeps its glyph so it still says what it is.
                assertEquals(card.family == Family.AUTOMATIC && pic.first == 0, p.hero != null)
            }
        }
    }

    @Test fun `long headlines still fit by line in every layout`() {
        val long = "That's the stream for tonight, thanks"
        for ((w, h) in frames) {
            val u = Slates.unit(w, h)
            for (layout in CardLayout.entries) {
                val p = plan(w, h, Card.ENDING, layout, 400 to 300, headline = long)
                assertTrue("$layout ${w}x$h: ${p.head}", p.head.size <= 3)
                assertTrue(p.headSize <= 17f * u + 0.01f)
                for (l in p.head) assertTrue(l.x >= 8f * u - 0.5f && l.x + measureHead(l.text, p.headSize) <= w - 8f * u + 0.5f)
            }
        }
    }

    @Test fun `a background is cropped to fill the frame, centred and never stretched`() {
        // A square photo behind a landscape card: its full width, the middle 56% of its height.
        val sq = Slates.coverCrop(1000, 1000, 1920, 1080)!!
        assertBox(Box(0f, 218.75f, 1000f, 781.25f), sq)
        assertEquals(1920f / 1080f, sq.width / sq.height, 1e-4f)
        // A portrait photo behind a landscape card, and a landscape GIF behind a portrait card.
        val tall = Slates.coverCrop(1080, 1920, 1920, 1080)!!
        assertEquals(1080f, tall.width, 0.01f)
        assertEquals(1920f / 1080f, tall.width / tall.height, 1e-4f)
        assertEquals(1920f / 2, (tall.top + tall.bottom) / 2, 0.01f)
        val wide = Slates.coverCrop(480, 270, 1080, 1920)!!
        assertEquals(270f, wide.height, 0.01f)
        assertEquals(240f, (wide.left + wide.right) / 2, 0.01f)
        // The same shape: all of it. Small images are scaled up, never letterboxed.
        assertBox(Box(0f, 0f, 480f, 270f), Slates.coverCrop(480, 270, 1920, 1080))
        assertNull(Slates.coverCrop(0, 10, 1920, 1080))
        assertNull(Slates.coverCrop(10, 10, 0, 1080))
    }

    @Test fun `a GIF's first frame is decoded no larger than one 1080p frame`() {
        assertArrayEquals(intArrayOf(480, 270), Slates.backgroundDecodeSize(480, 270))
        assertArrayEquals(intArrayOf(1920, 1080), Slates.backgroundDecodeSize(3840, 2160))
        for ((w, h) in listOf(10_000 to 100, 4000 to 4000, 1 to 50_000, 2000 to 1200)) {
            val s = Slates.backgroundDecodeSize(w, h)
            assertTrue("$w x $h -> ${s.toList()}", s[0].toLong() * s[1] <= Slates.MAX_BACKGROUND_PIXELS)
            assertTrue(s[0] >= 1 && s[1] >= 1)
            assertTrue("never upscaled", s[0] <= w && s[1] <= h)
        }
        assertArrayEquals(intArrayOf(1, 1), Slates.backgroundDecodeSize(0, 0))
    }

    @Test fun `the dim is clamped so a background can never hide the words or blank the card`() {
        assertEquals(0.35f, Slates.dimOf(CardStyle()), 0f)
        assertEquals(0.8f, Slates.dimOf(CardStyle(dim = 1f)), 0f)
        assertEquals(0f, Slates.dimOf(CardStyle(dim = -0.2f)), 0f)
        assertEquals(0.35f, Slates.dimOf(CardStyle(dim = Float.NaN)), 0f)
        assertEquals(0.5f, Slates.dimOf(CardStyle(dim = 0.5f)), 0f)
        // Dim alone changes nothing on screen without a background, so it doesn't make a card custom.
        assertTrue(CardStyle(dim = 0.6f).isDefault)
        assertFalse(CardStyle(layout = CardLayout.CENTERED).isDefault)
        assertFalse(CardStyle(accent = Slates.ACCENTS[0]).isDefault)
        assertFalse(CardStyle(textColor = 0xFF00FF00.toInt()).isDefault)
        assertFalse(CardStyle(textSize = CardTextSize.LARGER).isDefault)
        assertFalse(CardStyle(font = CardFont.SERIF).isDefault)
        assertFalse(CardStyle(title = "Lunch").isDefault)
        assertTrue(CardStyle(textSize = CardTextSize.NORMAL, font = CardFont.FULLBARS).isDefault)
    }

    // --- Accents ------------------------------------------------------------------------------------------------------

    private val health = mapOf(
        "Live green" to 0xFF34E07A.toInt(), "Weak amber" to StreamPalette.WEAK, "Camera lost orange" to StreamPalette.CAMERA_LOST,
        "error red" to 0xFFFF6B6B.toInt(), "light green" to 0xFF137A3E.toInt(), "light amber" to 0xFF8A6100.toInt(),
        "light orange" to 0xFFA84A0F.toInt(), "light red" to 0xFFB42318.toInt(), "Twitch purple" to 0xFF9146FF.toInt(),
    )

    @Test fun `five curated accents, none of them reads as a health colour`() {
        assertEquals(5, Slates.ACCENTS.size)
        assertEquals(5, Slates.ACCENTS.toSet().size)
        for (a in Slates.ACCENTS) {
            assertEquals("opaque", 0xFF, a ushr 24)
            for ((name, hc) in health) {
                assertTrue("%06X is within 30° of hue of the %s".format(a and 0xFFFFFF, name), hueGap(a, hc) >= 30.0)
                assertTrue("%06X is too close to the %s".format(a and 0xFFFFFF, name), deltaE(a, hc) >= 25.0)
            }
            // A small mark that must show on the Ink cards, the Bone bookends and an Ink plate over white.
            assertTrue(contrast(a, StreamPalette.INK) >= 3.0)
            assertTrue(contrast(a, StreamPalette.BONE) >= 3.0)
            assertTrue(contrast(a, 0xFF2A2A29.toInt()) >= 3.0)
        }
        for (i in Slates.ACCENTS.indices) for (j in i + 1 until Slates.ACCENTS.size) {
            assertTrue("accents $i and $j look alike", deltaE(Slates.ACCENTS[i], Slates.ACCENTS[j]) >= 20.0)
        }
    }

    @Test fun `any opaque accent is used, the presets and the streamer's own alike`() {
        for (a in Slates.ACCENTS) assertEquals(a, Slates.accentOf(CardStyle(accent = a)))
        assertNull(Slates.accentOf(CardStyle()))
        // A colour close to a health hue is the streamer's choice: the page warns, the card draws it.
        for (hc in health.values) assertEquals(hc, Slates.accentOf(CardStyle(accent = hc)))
        assertNull(Slates.accentOf(CardStyle(accent = Slates.ACCENTS[0] and 0x00FFFFFF))) // the same hue, transparent
        assertNull(Slates.accentOf(CardStyle(accent = 0x80FF0000.toInt())))
    }

    @Test fun `the words' own colour is used when opaque, else the card's own`() {
        assertNull(Slates.textColorOf(CardStyle()))
        assertEquals(0xFFFFD000.toInt(), Slates.textColorOf(CardStyle(textColor = 0xFFFFD000.toInt())))
        assertNull(Slates.textColorOf(CardStyle(textColor = 0x00FFD000)))
    }

    @Test fun `the cursor is Ink on a Bone bookend, Bone elsewhere, and the accent when one is set`() {
        assertEquals(StreamPalette.INK, Slates.cursorColor(Card.STARTING_SOON, CardStyle()))
        assertEquals(StreamPalette.INK, Slates.cursorColor(Card.ENDING, CardStyle()))
        assertEquals(StreamPalette.BONE, Slates.cursorColor(Card.BRB, CardStyle()))
        assertEquals(StreamPalette.BONE, Slates.cursorColor(Card.SIGNAL_LOST, CardStyle()))
        // Over the compositor's moving background a bookend takes the Ink card's colours.
        assertEquals(StreamPalette.BONE, Slates.cursorColor(Card.ENDING, CardStyle(), CardLayer.FOREGROUND))
        val teal = Slates.ACCENTS[0]
        for (c in Card.entries) assertEquals(teal, Slates.cursorColor(c, CardStyle(accent = teal)))
        // Any opaque accent the streamer picked, even one the page warned about; a transparent one is ignored.
        assertEquals(StreamPalette.WEAK, Slates.cursorColor(Card.BRB, CardStyle(accent = StreamPalette.WEAK)))
        assertEquals(StreamPalette.BONE, Slates.cursorColor(Card.BRB, CardStyle(accent = StreamPalette.WEAK and 0xFFFFFF)))
        assertTrue(Slates.showsImagery(CardStyle(), CardLayer.FOREGROUND))
        assertFalse(Slates.showsImagery(CardStyle(layout = CardLayout.FULL_PICTURE)))
    }

    // CIELAB (D65) and WCAG luminance, enough to judge colours the way the palette was chosen.

    private fun lin(c: Int): Double { val v = c / 255.0; return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
    private fun rgb(c: Int) = Triple(lin((c shr 16) and 255), lin((c shr 8) and 255), lin(c and 255))
    private fun luminance(c: Int) = rgb(c).let { (r, g, b) -> 0.2126 * r + 0.7152 * g + 0.0722 * b }
    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
    private fun lab(c: Int): DoubleArray {
        val (r, g, b) = rgb(c)
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
        fun f(t: Double) = if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116
        return doubleArrayOf(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }
    private fun deltaE(a: Int, b: Int): Double {
        val p = lab(a); val q = lab(b)
        return Math.sqrt((p[0] - q[0]) * (p[0] - q[0]) + (p[1] - q[1]) * (p[1] - q[1]) + (p[2] - q[2]) * (p[2] - q[2]))
    }
    private fun hue(c: Int) = lab(c).let { (Math.toDegrees(Math.atan2(it[2], it[1])) + 360) % 360 }
    private fun hueGap(a: Int, b: Int): Double { val d = abs(hue(a) - hue(b)); return minOf(d, 360 - d) }

    @Test fun `the colour maths agrees with known figures`() {
        assertEquals(17.24, contrast(StreamPalette.BONE, StreamPalette.INK), 0.02)
        assertNotNull(lab(StreamPalette.WEAK))
        assertTrue(hueGap(0xFFFF0000.toInt(), 0xFFFF0000.toInt()) < 1e-9)
    }
}
