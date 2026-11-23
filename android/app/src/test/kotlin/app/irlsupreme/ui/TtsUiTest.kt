package app.irlsupreme.ui

import app.irlsupreme.service.TtsNow
import app.irlsupreme.ui.live.notice
import app.irlsupreme.ui.live.ttsNotice
import app.irlsupreme.ui.live.ttsRowValue
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsUiTest {
    private val waiting = TtsNow("Sam", speaking = false, speakAtMs = 0L, waiting = 2)
    private val speaking = waiting.copy(speaking = true)

    @Test fun `the Scenes row says who's up`() {
        assertEquals("Off", ttsRowValue(false, null))
        assertEquals("On", ttsRowValue(true, null))
        assertEquals("Sam's is next", ttsRowValue(true, waiting))
        assertEquals("Saying Sam's", ttsRowValue(true, speaking))
    }

    @Test fun `the notice line names the viewer, below an alert`() {
        assertEquals("TTS from Sam, next · Skip in Scenes", ttsNotice(waiting))
        assertEquals("TTS from Sam", ttsNotice(speaking))
        assertEquals("Gift subs from A, 5 subs", notice(false, null, false, null, null, alert = "Gift subs from A, 5 subs", tts = "TTS from Sam"))
        assertEquals("TTS from Sam", notice(false, null, false, "cam", null, tts = "TTS from Sam"))
    }

    @Test fun `waits and cooldowns read as people say them`() {
        assertEquals("Off", ttsSeconds(0))
        assertEquals("3 s", ttsSeconds(3))
        assertEquals("1 min", ttsSeconds(60))
        assertEquals("2 min", ttsSeconds(120))
    }
}
