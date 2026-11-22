package app.irlsupreme.ui

import app.irlsupreme.engine.CaptionPosition
import app.irlsupreme.engine.CaptionSize
import app.irlsupreme.engine.CaptionStatus
import app.irlsupreme.engine.captions.CaptionLanguageSupport
import app.irlsupreme.service.CaptionPrefs
import app.irlsupreme.ui.live.captionsValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptionsLogicTest {
    @Test fun `the live row says off, or how captions are doing`() {
        assertEquals("Off", captionsValue(false, CaptionStatus.LISTENING))
        assertEquals("On", captionsValue(true, null))
        assertEquals("Starting", captionsValue(true, CaptionStatus.STARTING))
        assertEquals("On", captionsValue(true, CaptionStatus.LISTENING))
        assertEquals("Paused · phone hot", captionsValue(true, CaptionStatus.PAUSED))
        assertEquals("Not available", captionsValue(true, CaptionStatus.UNAVAILABLE))
    }

    @Test fun `captions are off by default and a stream gets them only when on`() {
        assertNull(CaptionPrefs().options())
        val o = CaptionPrefs(on = true, language = "fr-FR", size = CaptionSize.LARGE, position = CaptionPosition.TOP, maskOffensive = false).options()!!
        assertEquals("fr-FR", o.language)
        assertEquals(CaptionSize.LARGE, o.size)
        assertEquals(CaptionPosition.TOP, o.position)
        assertEquals(false, o.maskOffensive)
    }

    @Test fun `a language is installed, downloading, downloadable or not offered`() {
        val s = CaptionLanguageSupport(true, installed = listOf("en-US"), pending = listOf("de-DE"), downloadable = listOf("fr-FR"))
        assertEquals(LanguageState.INSTALLED, languageState("en-us", s))
        assertEquals(LanguageState.DOWNLOADING, languageState("de-DE", s))
        assertEquals(LanguageState.DOWNLOADABLE, languageState("fr-FR", s))
        assertEquals(LanguageState.NOT_OFFERED, languageState("xx-YY", s))
    }

    @Test fun `the settings row names the language when on`() {
        assertEquals("Off", captionsSettingValue(CaptionPrefs()))
        assertEquals("On · ${languageLabel("en-US")}", captionsSettingValue(CaptionPrefs(on = true, language = "en-US")))
        assertEquals("Medium", captionSizeTitle(CaptionSize.MEDIUM))
    }
}
