package app.irlsupreme.engine.video

import app.irlsupreme.engine.video.Slates.Card
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The streamer's face, size and colour for a card's words, and their own scenes' names: the pure parts. */
class CardTypeTest {
    private val headCap = 0.72f
    /** A wide face: every character as wide as a mono's (0.6 em), the worst a card's words meet. */
    private val wide: (String, Float) -> Float = { s, size -> s.length * size * 0.6f }
    /** Archivo ExtraCondensed's average. */
    private val narrow: (String, Float) -> Float = { s, size -> s.length * size * 0.42f }
    private val frames = listOf(1920 to 1080, 1080 to 1920, 1280 to 720)

    private fun metrics(w: Int, h: Int, measure: (String, Float) -> Float) =
        Slates.Metrics(headCap * 2.4f * Slates.unit(w, h), headCap, measure, measure)

    @Test fun `a family the phone lacks falls back to Sans, the bundled faces never do`() {
        val none: (String) -> Boolean = { false }
        val all: (String) -> Boolean = { true }
        for (f in CardFont.entries) {
            if (f.bundled) {
                assertNull(CardFont.familyFor(f, none))
                assertNull(CardFont.familyFor(f, all))
            } else {
                assertEquals(CardFont.FALLBACK_FAMILY, CardFont.familyFor(f, none))
                assertEquals(f.family, CardFont.familyFor(f, all))
            }
        }
        // Only the missing family falls back.
        assertEquals("serif", CardFont.familyFor(CardFont.SERIF) { it == "serif" })
        assertEquals(CardFont.FALLBACK_FAMILY, CardFont.familyFor(CardFont.HANDWRITTEN) { it == "serif" })
        assertEquals(
            mapOf(
                CardFont.SERIF to "serif", CardFont.SANS to "sans-serif", CardFont.CONDENSED to "sans-serif-condensed",
                CardFont.CASUAL to "casual", CardFont.HANDWRITTEN to "cursive",
            ),
            CardFont.entries.filter { !it.bundled }.associateWith { it.family },
        )
    }

    @Test fun `fonts and sizes read back from what was stored, unknown values as the default`() {
        for (f in CardFont.entries) assertEquals(f, CardFont.parse(f.name))
        assertEquals(CardFont.FULLBARS, CardFont.parse(null))
        assertEquals(CardFont.FULLBARS, CardFont.parse("COMIC"))
        for (s in CardTextSize.entries) assertEquals(s, CardTextSize.parse(s.name))
        assertEquals(CardTextSize.NORMAL, CardTextSize.parse("HUGE"))
        assertEquals(CardFont.entries.size, CardFont.entries.map { it.label }.toSet().size)
        assertEquals(listOf(0.85f, 1f, 1.2f), CardTextSize.entries.map { it.scale })
        // Headlines bolder than their lines, on every face.
        for (f in CardFont.entries) assertTrue(f.headWeight > f.subWeight)
    }

    @Test fun `Normal is today's card exactly, and the other sizes scale the type`() {
        for ((w, h) in frames) for (layout in CardLayout.entries) for (card in listOf(Card.BRB, Card.STARTING_SOON, Card.CAMERA_LOST)) {
            val m = metrics(w, h, narrow)
            val today = Slates.plan(w, h, card.family, layout, card.headline, card.sub, 0, 0, m)
            assertEquals(today, Slates.plan(w, h, card.family, layout, card.headline, card.sub, 0, 0, m, CardTextSize.NORMAL.scale))
        }
        val (w, h) = 1080 to 1920
        val u = Slates.unit(w, h)
        val m = metrics(w, h, narrow)
        fun headSize(scale: Float) = Slates.plan(w, h, Slates.Family.CHOSEN, CardLayout.CLASSIC, "Lunch", "Back soon", 0, 0, m, scale).headSize
        assertEquals(17f * u, headSize(1f), 0.01f)
        assertEquals(0.85f * 17f * u, headSize(0.85f), 0.01f)
        assertEquals(1.2f * 17f * u, headSize(1.2f), 0.01f)
        val subNormal = Slates.plan(w, h, Slates.Family.CHOSEN, CardLayout.CLASSIC, "Lunch", "Back soon", 0, 0, m).subSize
        val subLarger = Slates.plan(w, h, Slates.Family.CHOSEN, CardLayout.CLASSIC, "Lunch", "Back soon", 0, 0, m, 1.2f).subSize
        assertEquals(1.2f * subNormal, subLarger, 0.01f)
    }

    @Test fun `larger words in a wide face still fit the frame on every card and layout`() {
        val headlines = listOf(
            "Be right back",
            "Grabbing lunch at the market down the road", // long, many words
            "Supercalifragilisticexpialidociousnessly", // one 40-character word
            "W".repeat(40),
        )
        val subs = listOf("Back soon.", "x".repeat(90), "A line that runs on and on about where we are and when we'll be back here")
        for ((w, h) in frames) {
            val u = Slates.unit(w, h)
            val words = Slates.Box(8f * u, 8f * u + headCap * 2.4f * u + 6f * u, w - 8f * u, Slates.rulerY(w, h) - 8f * u)
            for (card in Card.entries) for (layout in CardLayout.entries) for (size in CardTextSize.entries) {
                for (headline in headlines) for (sub in subs) for (pic in listOf(0 to 0, 400 to 300)) {
                    val m = metrics(w, h, wide)
                    val p = Slates.plan(w, h, card.family, layout, headline, sub, pic.first, pic.second, m, size.scale)
                    val what = "$card $layout $size ${w}x$h '$headline' / '${sub.take(12)}'"
                    assertEquals("$what: words lost", headline.split(' ').filter { it.isNotEmpty() }, p.head.flatMap { it.text.split(' ') })
                    for (l in p.head) {
                        val right = l.x + wide(l.text, p.headSize)
                        assertTrue("$what: '${l.text}' runs off ($right > ${words.right})", l.x >= words.left - 0.5f && right <= words.right + 0.5f)
                        assertTrue("$what: '${l.text}' under the tag row", l.baseline - headCap * p.headSize >= words.top - 0.5f)
                    }
                    for (l in p.sub) {
                        assertTrue("$what: sub '${l.text}' runs off", l.x >= words.left - 0.5f && l.x + wide(l.text, p.subSize) <= words.right + 0.5f)
                    }
                    val last = (p.sub.lastOrNull() ?: p.head.last()).baseline
                    assertTrue("$what: words reach the ruler ($last > ${words.bottom})", last <= words.bottom + 0.5f)
                }
            }
        }
    }

    @Test fun `a word too long for any line shrinks until it fits, never cut off`() {
        val word = "M".repeat(40)
        val f = Slates.fitLines(word, 500f, 180f, 130f, wide)
        assertEquals(listOf(word), f.lines)
        assertTrue(wide(word, f.size) <= 500f)
        // Two long words: each fits its own line.
        val two = Slates.fitLines("$word $word", 500f, 180f, 130f, wide)
        assertTrue(two.lines.all { wide(it, two.size) <= 500f })
        assertEquals(2, two.lines.size)
        // Nothing to fit is nothing drawn.
        assertTrue(Slates.fitLines("", 500f, 180f, 130f, wide).lines.isEmpty())
    }

    @Test fun `your own scene prints its name as the tag and the headline until you write one`() {
        val named = CardStyle(title = "Q&A")
        val c = Slates.copy(Card.CUSTOM_2, SlateAudio.ON, named)
        assertEquals("Q&A", c.tag)
        assertEquals("Q&A", c.headline)
        assertEquals("", c.sub)
        assertEquals("AUDIO ON", c.footer)
        assertEquals("LUNCH", Slates.copy(Card.CUSTOM_1, SlateAudio.MUTED, CardStyle(title = "Lunch")).tag)
        assertEquals("MUTED", Slates.copy(Card.CUSTOM_1, SlateAudio.MUTED, CardStyle(title = "Lunch")).footer)
        val own = Slates.copy(Card.CUSTOM_1, SlateAudio.ON, CardStyle(headline = "Eating", sub = "Back at 2", title = "Lunch"))
        assertEquals(listOf("LUNCH", "Eating", "Back at 2"), listOf(own.tag, own.headline, own.sub))
        // Unnamed (or a blank name): the slot's own name.
        assertEquals("SCENE 3", Slates.copy(Card.CUSTOM_3, SlateAudio.ON, CardStyle(title = "  ")).tag)
        assertEquals("Scene 4", Slates.customName(Card.CUSTOM_4, CardStyle()))
        // A name means nothing on the built-in cards.
        assertEquals("BRB", Slates.copy(Card.BRB, SlateAudio.ON, CardStyle(title = "Lunch")).tag)
        // Your own scenes are chosen cards: alerts and moving backgrounds as on BRB.
        for (card in Card.entries.filter { it.isCustom }) {
            assertTrue(SceneRules.alertsAllowed(card))
            assertTrue(CardBackdrops.animates(card))
            assertTrue(SceneRules.crossfades(null, card, 0))
        }
    }
}
