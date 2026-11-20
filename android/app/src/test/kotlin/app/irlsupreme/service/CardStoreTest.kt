package app.irlsupreme.service

import app.irlsupreme.engine.video.CardLayout
import app.irlsupreme.engine.video.Slates
import app.irlsupreme.engine.video.Slates.Card
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class CardStoreTest {
    private val accents = Slates.ACCENTS

    /** Applies a written map the way SharedPreferences does: null removes the key. */
    private fun store(into: MutableMap<String, Any?>, card: Card, t: CardText) {
        CardPrefs.write(card, t).forEach { (k, v) -> if (v == null) into.remove(k) else into[k] = v }
    }

    private fun roundTrip(card: Card, t: CardText): CardText {
        val m = HashMap<String, Any?>()
        store(m, card, t)
        return CardPrefs.read(card, m::get, t.hasPicture, t.background, accents)
    }

    @Test fun `every setting survives a round trip through settings`() {
        val t = CardText(
            headline = "Grabbing food", sub = "Ten minutes", hasPicture = true, background = CardBackground.GIF,
            dimPercent = 65, layout = CardLayout.SIDE_BY_SIDE, accent = accents[2],
            elements = CardElements(countdownMinutes = 10, showTimeAway = true, showChat = true),
            webUrl = "https://streamelements.com/overlay/abc/def?token=x",
        )
        assertEquals(t, roundTrip(Card.BRB, t))
    }

    @Test fun `an untouched card leaves nothing in settings and reads back as the default`() {
        val m = HashMap<String, Any?>()
        store(m, Card.ENDING, CardText())
        assertTrue(m.isEmpty())
        assertEquals(CardText(), CardPrefs.read(Card.ENDING, m::get, false, CardBackground.NONE, accents))
    }

    @Test fun `going back to a default removes the key`() {
        val m = HashMap<String, Any?>()
        store(m, Card.BRB, CardText(layout = CardLayout.CENTERED, elements = CardElements(showChat = true)))
        assertEquals(2, m.size)
        store(m, Card.BRB, CardText())
        assertTrue(m.isEmpty())
    }

    @Test fun `cards keep their own keys, and the old headline keys are still read`() {
        val m = HashMap<String, Any?>()
        store(m, Card.BRB, CardText(headline = "a"))
        store(m, Card.PRIVACY, CardText(headline = "b"))
        assertEquals("a", m["card_brb_headline"])
        assertEquals("b", CardPrefs.read(Card.PRIVACY, m::get, false, CardBackground.NONE, accents).headline)
        assertTrue(CardPrefs.FIELDS.map { CardPrefs.key(Card.BRB, it) }.containsAll(m.keys.filter { it.startsWith("card_brb_") }))
    }

    @Test fun `wrong types and stray values read as safe defaults`() {
        val m = mapOf<String, Any?>(
            "card_brb_dim" to "lots", "card_brb_layout" to "DIAGONAL", "card_brb_countdown" to 999,
            "card_brb_chat" to "yes", "card_brb_accent" to 0xFFFF0000.toInt(), "card_brb_headline" to "x".repeat(100),
        )
        val t = CardPrefs.read(Card.BRB, m::get, false, CardBackground.NONE, accents)
        assertEquals(CardRules.DEFAULT_DIM, t.dimPercent)
        assertEquals(CardLayout.CLASSIC, t.layout)
        assertEquals(60, t.elements.countdownMinutes)
        assertFalse(t.elements.showChat)
        assertTrue(t.accent in accents) // a red that isn't curated snaps to the nearest curated accent
        assertEquals(CardStore.MAX_HEADLINE, t.headline.length)
    }

    @Test fun `dim snaps to an offered step and tops out at the engine's 80 percent`() {
        assertEquals(35, CardRules.snapDim(33))
        assertEquals(0, CardRules.snapDim(-5))
        assertEquals(80, CardRules.snapDim(100))
        assertEquals(80, CardRules.DIM_STEPS.max())
    }

    @Test fun `accents snap to the curated set and are named by hue`() {
        accents.forEach { assertEquals(it, CardRules.snapAccent(it, accents)) }
        assertNull(CardRules.snapAccent(0xFF123456.toInt(), emptyList()))
        assertEquals("Sky", CardRules.accentName(0xFF6EC1FF.toInt()))
        assertEquals("Lilac", CardRules.accentName(0xFFB49CFF.toInt()))
        assertEquals("Pink", CardRules.accentName(0xFFFF8FB1.toInt()))
        assertEquals("Sand", CardRules.accentName(0xFFE6C79C.toInt()))
        assertEquals("Mint", CardRules.accentName(0xFF9DE0D2.toInt()))
        assertEquals("Grey", CardRules.accentName(0xFF808080.toInt()))
        // Every curated accent has a distinct name, so TalkBack can tell them apart.
        assertEquals(accents.size, accents.map(CardRules::accentName).toSet().size)
        assertEquals(app.irlsupreme.engine.video.Slates.ACCENTS.size, app.irlsupreme.engine.video.Slates.ACCENT_NAMES.size)
        assertEquals(app.irlsupreme.engine.video.Slates.ACCENT_NAMES, app.irlsupreme.engine.video.Slates.ACCENTS.map(CardRules::accentName))
    }

    @Test fun `web addresses must be https with a real host`() {
        assertNull(CardRules.webUrlProblem(""))
        assertNull(CardRules.webUrlProblem("  https://streamelements.com/overlay/abc?key=1  "))
        assertNull(CardRules.webUrlProblem("HTTPS://Example.com"))
        assertNotNull(CardRules.webUrlProblem("http://example.com"))
        assertNotNull(CardRules.webUrlProblem("example.com"))
        assertNotNull(CardRules.webUrlProblem("https://"))
        assertNotNull(CardRules.webUrlProblem("https://localhost/x"))
        assertNotNull(CardRules.webUrlProblem("https://exa mple.com"))
        assertNotNull(CardRules.webUrlProblem("https://user:pw@example.com"))
        assertNotNull(CardRules.webUrlProblem("https://example.com/" + "a".repeat(CardRules.MAX_WEB_URL)))
        assertNotNull(CardRules.webUrlProblem("javascript:alert(1)"))
        assertEquals("https://example.com/s", cardWebUrl(" https://example.com/s "))
        assertNull(cardWebUrl("http://example.com"))
    }

    @Test fun `the preview names only the host, never the path or its keys`() {
        assertEquals("streamelements.com", CardRules.webHost("https://streamelements.com/overlay/abc?token=secret"))
        assertEquals("example.com", CardRules.webHost("https://www.example.com/"))
        assertEquals("", CardRules.webHost("not a url"))
    }

    @Test fun `the server's cards stay still, everything else can move`() {
        for (c in Card.entries) {
            val server = c == Card.SIGNAL_LOST || c == Card.WEAK_SIGNAL
            assertEquals(server, CardRules.serverLooped(c))
            assertEquals(!server, CardRules.loopsGif(c))
            assertEquals(!server, CardRules.canUseWebPage(c))
            assertEquals(!server, CardRules.hasLiveElements(c))
        }
        assertEquals(setOf(Card.STARTING_SOON, Card.BRB, Card.PRIVACY), Card.entries.filter(CardRules::hasCountdown).toSet())
        assertEquals("Starting in 4:32", CardRules.countdownExample(Card.STARTING_SOON))
        assertEquals("Back in 2:10", CardRules.countdownExample(Card.BRB))
    }

    @Test fun `the stream only gets the elements a card can show`() {
        val all = CardElements(countdownMinutes = 5, showTimeAway = true, showChat = true)
        assertEquals(all, CardRules.effective(Card.BRB, all))
        assertEquals(all.copy(countdownMinutes = 0), CardRules.effective(Card.ENDING, all))
        assertEquals(all.copy(countdownMinutes = 0), CardRules.effective(Card.CAMERA_LOST, all))
        assertEquals(CardElements(), CardRules.effective(Card.SIGNAL_LOST, all))
        assertEquals(CardElements(), CardRules.effective(Card.WEAK_SIGNAL, all))
    }

    @Test fun `the engine style carries everything, minus what the server's cards can't do`() {
        val gif = File("bg_brb.gif")
        val t = CardText(
            headline = "  ", sub = "Back soon", dimPercent = 50, layout = CardLayout.FULL_PICTURE, accent = accents[0],
            webUrl = "https://example.com/scene",
        )
        val brb = CardPrefs.style(Card.BRB, t, null, null, gif)
        assertNull(brb.headline) // blank keeps the card's own
        assertEquals("Back soon", brb.sub)
        assertEquals(0.5f, brb.dim, 1e-6f)
        assertEquals(CardLayout.FULL_PICTURE, brb.layout)
        assertEquals(accents[0], brb.accent)
        assertEquals(gif, brb.backgroundGif)
        assertEquals("https://example.com/scene", brb.webUrl)
        val lost = CardPrefs.style(Card.SIGNAL_LOST, t, null, null, gif)
        assertNull(lost.backgroundGif)
        assertNull(lost.webUrl)
        assertEquals(CardLayout.FULL_PICTURE, lost.layout)
        assertNull(CardPrefs.style(Card.BRB, t.copy(webUrl = "http://example.com"), null, null, null).webUrl)
        assertTrue(CardPrefs.style(Card.BRB, CardText(), null, null, null).isDefault)
    }

    @Test fun `a card is custom when anything viewers see changed`() {
        assertFalse(CardText().isCustom)
        assertFalse(CardText(dimPercent = 80).isCustom) // no background: dim changes nothing
        assertFalse(CardText(webUrl = "not yet").isCustom)
        assertTrue(CardText(background = CardBackground.STILL).isCustom)
        assertTrue(CardText(layout = CardLayout.CENTERED).isCustom)
        assertTrue(CardText(accent = accents[1]).isCustom)
        assertTrue(CardText(elements = CardElements(showTimeAway = true)).isCustom)
        assertTrue(CardText(webUrl = "https://example.com").isCustom)
    }

    @Test fun `still backgrounds shrink to one 1080p frame, never grow`() {
        assertEquals(1920 to 1080, CardRules.backgroundSize(4032, 2268))
        assertEquals(1080 to 1920, CardRules.backgroundSize(3024, 5376))
        val (sw, sh) = CardRules.backgroundSize(3000, 3000)
        assertEquals(sw, sh)
        assertTrue(sw.toLong() * sh <= CardRules.MAX_BACKGROUND_PIXELS + 2 * sw)
        assertEquals(800 to 600, CardRules.backgroundSize(800, 600))
        val (pw, ph) = CardRules.backgroundSize(10000, 500) // a panorama: the long side caps first
        assertEquals(1920, pw)
        assertEquals(96, ph)
    }

    // --- GIFs ------------------------------------------------------------------------------------------------------

    /** A minimal GIF: [frames] 1x1 images of [w]x[h] logical size, optionally with a global colour table. */
    private fun gif(w: Int, h: Int, frames: Int, globalTable: Boolean = true, trailer: Boolean = true): ByteArray {
        val o = ByteArrayOutputStream()
        o.write("GIF89a".toByteArray())
        o.write(byteArrayOf((w and 0xFF).toByte(), (w shr 8).toByte(), (h and 0xFF).toByte(), (h shr 8).toByte()))
        o.write(if (globalTable) 0x80 else 0x00); o.write(0); o.write(0)
        if (globalTable) o.write(ByteArray(6)) // 2 colours
        // A NETSCAPE loop extension, as real animated GIFs have.
        o.write(byteArrayOf(0x21, 0xFF.toByte(), 11)); o.write("NETSCAPE2.0".toByteArray()); o.write(byteArrayOf(3, 1, 0, 0, 0))
        repeat(frames) {
            o.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0, 10, 0, 0, 0)) // graphic control: 100 ms
            o.write(byteArrayOf(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0x80.toByte())) // 1x1 with a 2-colour local table
            o.write(ByteArray(6))
            o.write(byteArrayOf(2, 2, 0x4C, 0x01, 0)) // LZW min size 2, one 2-byte sub-block, end
        }
        if (trailer) o.write(0x3B)
        return o.toByteArray()
    }

    @Test fun `a GIF's size and frames are read without decoding`() {
        assertEquals(GifInfo(480, 270, 12), GifScan.inspect(gif(480, 270, 12)))
        assertEquals(GifInfo(300, 300, 1), GifScan.inspect(gif(300, 300, 1, globalTable = false)))
        assertEquals(GifInfo(64, 64, 3), GifScan.inspect(gif(64, 64, 3, trailer = false)))
        val damaged = gif(64, 64, 3)
        assertEquals(2, GifScan.inspect(damaged.copyOf(damaged.size - 8))!!.frames) // a cut-off tail keeps the whole frames
        assertNull(GifScan.inspect(gif(64, 64, 0)))
        assertNull(GifScan.inspect("\u0089PNG\r\n\u001a\n".toByteArray()))
        assertNull(GifScan.inspect(ByteArray(0)))
        assertTrue(GifScan.isGif("GIF87a".toByteArray()))
        assertFalse(GifScan.isGif("GIF88a".toByteArray()))
    }

    @Test fun `GIFs over the caps are turned away with the reason`() {
        assertEquals(BackgroundResult.OK, CardRules.gifCheck(2_000_000, GifInfo(1280, 720, 40)))
        assertEquals(BackgroundResult.OK, CardRules.gifCheck(2_000_000, GifInfo(720, 1280, 40)))
        assertEquals(BackgroundResult.TOO_BIG, CardRules.gifCheck(CardRules.MAX_GIF_BYTES + 1L, GifInfo(100, 100, 2)))
        assertEquals(BackgroundResult.TOO_LARGE, CardRules.gifCheck(1000, GifInfo(1281, 100, 2)))
        assertEquals(BackgroundResult.TOO_LARGE, CardRules.gifCheck(1000, GifInfo(1000, 1000, 2)))
        assertEquals(BackgroundResult.UNREADABLE, CardRules.gifCheck(1000, null))
        assertEquals(BackgroundResult.UNREADABLE, CardRules.gifCheck(1000, GifInfo(0, 10, 2)))
        assertNull(CardRules.backgroundError(BackgroundResult.OK))
        BackgroundResult.entries.filter { it != BackgroundResult.OK }.forEach { r ->
            val msg = CardRules.backgroundError(r)!!
            assertFalse(msg.contains('!'))
            assertTrue(msg.endsWith("."))
        }
    }
}
