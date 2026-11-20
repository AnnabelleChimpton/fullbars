package app.irlsupreme.engine.video

import app.irlsupreme.engine.video.CardBackdrops.Show
import app.irlsupreme.engine.video.Slates.Card
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which cards animate, when a page or GIF is used, and what stands in while they load or after they fail. */
class CardBackdropTest {
    private val phoneCards = listOf(Card.BRB, Card.STARTING_SOON, Card.ENDING, Card.PRIVACY, Card.CAMERA_LOST)

    @Test fun `only the phone-drawn cards animate, the server's cards stay static`() {
        for (c in phoneCards) assertTrue("$c", CardBackdrops.animates(c))
        assertFalse(CardBackdrops.animates(Card.WEAK_SIGNAL))
        assertFalse(CardBackdrops.animates(Card.SIGNAL_LOST))
        for (c in listOf(Card.WEAK_SIGNAL, Card.SIGNAL_LOST)) {
            assertFalse("no page on $c", CardBackdrops.wantsWeb(c, hasUsableUrl = true, held = false, failed = false))
            assertFalse("no GIF on $c", CardBackdrops.wantsGif(c, hasGif = true, failed = false, wantsWeb = false))
        }
    }

    @Test fun `a page is opened unless it's held for heat or failed`() {
        assertTrue(CardBackdrops.wantsWeb(Card.BRB, hasUsableUrl = true, held = false, failed = false))
        assertFalse(CardBackdrops.wantsWeb(Card.BRB, hasUsableUrl = false, held = false, failed = false))
        assertFalse("thermal hold", CardBackdrops.wantsWeb(Card.BRB, hasUsableUrl = true, held = true, failed = false))
        assertFalse("failed to load", CardBackdrops.wantsWeb(Card.BRB, hasUsableUrl = true, held = false, failed = true))
    }

    @Test fun `the GIF plays unless a page replaces the card or it failed`() {
        assertTrue(CardBackdrops.wantsGif(Card.STARTING_SOON, hasGif = true, failed = false, wantsWeb = false))
        assertFalse(CardBackdrops.wantsGif(Card.STARTING_SOON, hasGif = true, failed = false, wantsWeb = true))
        assertFalse(CardBackdrops.wantsGif(Card.STARTING_SOON, hasGif = true, failed = true, wantsWeb = false))
        assertFalse(CardBackdrops.wantsGif(Card.STARTING_SOON, hasGif = false, failed = false, wantsWeb = false))
        // A page held for heat hands back to the GIF.
        val web = CardBackdrops.wantsWeb(Card.BRB, hasUsableUrl = true, held = true, failed = false)
        assertTrue(CardBackdrops.wantsGif(Card.BRB, hasGif = true, failed = false, wantsWeb = web))
    }

    @Test fun `the plain card shows until a page or GIF has a frame`() {
        assertEquals(Show.CARD, CardBackdrops.choose(wantsWeb = true, webReady = false, wantsGif = false, gifReady = false))
        assertEquals(Show.WEB, CardBackdrops.choose(wantsWeb = true, webReady = true, wantsGif = false, gifReady = false))
        assertEquals(Show.CARD, CardBackdrops.choose(wantsWeb = false, webReady = false, wantsGif = true, gifReady = false))
        assertEquals(Show.GIF, CardBackdrops.choose(wantsWeb = false, webReady = false, wantsGif = true, gifReady = true))
        assertEquals(Show.CARD, CardBackdrops.choose(wantsWeb = false, webReady = true, wantsGif = false, gifReady = true))
    }

    @Test fun `card pages are https only`() {
        assertTrue(CardWebSource.usable("https://streamelements.com/overlay/abc/def"))
        assertTrue(CardWebSource.usable("  HTTPS://example.com  "))
        assertFalse(CardWebSource.usable("http://example.com/"))
        assertFalse(CardWebSource.usable("file:///sdcard/page.html"))
        assertFalse(CardWebSource.usable("javascript:alert(1)"))
        assertFalse(CardWebSource.usable("https://"))
        assertFalse(CardWebSource.usable("https:///nohost"))
        assertFalse(CardWebSource.usable("not a url"))
        assertFalse(CardWebSource.usable(""))
        assertFalse(CardWebSource.usable(null))
    }

    @Test fun `logs show a page's scheme and host, never its path, query or user info`() {
        assertEquals("https://streamelements.com", BrowserSource.safeOrigin("https://streamelements.com/overlay/abc/SECRET?token=x"))
        assertEquals("https://example.com:8443", BrowserSource.safeOrigin("https://example.com:8443?k=v#frag"))
        assertEquals("https://host", BrowserSource.safeOrigin("https://user:pass@host/p"))
        assertEquals("(url)", BrowserSource.safeOrigin("no scheme here"))
        assertEquals("Url(https://a.b)", BrowserSource.Content.Url("https://a.b/c?d=e").toString())
    }
}
