package app.irlsupreme.ui

import app.irlsupreme.engine.audio.InputChoice
import app.irlsupreme.engine.audio.LowCut
import app.irlsupreme.engine.audio.MicLevel
import app.irlsupreme.service.MicPrefs
import app.irlsupreme.service.MicSettings
import app.irlsupreme.service.RAW_DEFAULT_GAIN_DB
import app.irlsupreme.service.lowCutText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputClipWatchTest {
    /** [seconds] of talking at ~47 readings a second, every [clipEvery]th one clipped at the input (0: none). */
    private fun talk(w: InputClipWatch, fromMs: Long, seconds: Int, clipEvery: Int, rmsDb: Float = -20f, inPeakDb: Float = -3f): Boolean {
        var on = false
        var n = 0
        for (i in 0 until seconds * 47) {
            val clipped = clipEvery > 0 && n++ % clipEvery == 0
            on = w.update(MicLevel(rmsDb, -6f, 0f, inPeakDb = inPeakDb, inClipped = if (clipped) 4 else 0), fromMs + i * 1000L / 47)
        }
        return on
    }

    @Test fun `clipping on a few words in a hundred is enough, after three voiced seconds`() {
        val w = InputClipWatch()
        assertFalse(talk(w, 0, 2, clipEvery = 50)) // not enough voice yet
        assertTrue(talk(w, 2_000, 2, clipEvery = 50)) // ~2% of readings
    }

    @Test fun `clean talk or a single clipped shout doesn't trigger it`() {
        assertFalse(talk(InputClipWatch(), 0, 10, clipEvery = 0))
        val w = InputClipWatch()
        w.update(MicLevel(-20f, -6f, 0f, inPeakDb = 0f, inClipped = 8), 0)
        assertFalse(talk(w, 20, 10, clipEvery = 0))
    }

    @Test fun `a clipped voice the level turned far down still counts as voice`() {
        // The output is quiet (the fader is down) but the input peaks near full scale.
        assertTrue(talk(InputClipWatch(), 0, 5, clipEvery = 20, rmsDb = -50f, inPeakDb = -0.2f))
        assertFalse(talk(InputClipWatch(), 0, 5, clipEvery = 20, rmsDb = -55f, inPeakDb = -40f)) // that's not a voice
    }

    @Test fun `the verdict holds a while, then goes`() {
        val w = InputClipWatch()
        assertTrue(talk(w, 0, 5, clipEvery = 10))
        assertTrue(w.update(MicLevel.SILENT, 5_000 + InputClipWatch.HOLD_MS - 100))
        assertFalse(w.update(MicLevel.SILENT, 5_000 + InputClipWatch.HOLD_MS + 20_000))
    }

    @Test fun `the advice never says turn down, and offers auto level off only where it helps`() {
        val camera = inputClipAdvice(VoiceSource.CAMERA, raw = false, unprocessedOk = true)
        assertFalse(camera.offerAutoLevelOff)
        assertTrue(camera.text.contains("camera"))
        val camcorder = inputClipAdvice(VoiceSource.PHONE, raw = false, unprocessedOk = true)
        assertTrue(camcorder.offerAutoLevelOff)
        assertTrue(camcorder.text.contains("before Fullbars"))
        assertFalse(inputClipAdvice(VoiceSource.PHONE, raw = false, unprocessedOk = false).offerAutoLevelOff)
        assertFalse(inputClipAdvice(VoiceSource.PHONE, raw = true, unprocessedOk = true).offerAutoLevelOff)
        for (a in listOf(camera, camcorder)) assertFalse(a.text.contains("Turn down"))
    }

    @Test fun `an external mic has its own level, starting at 0 dB`() {
        val p = MicPrefs(gainDb = 3, raw = true)
        assertEquals(RAW_DEFAULT_GAIN_DB, p.gainFor(external = false))
        assertEquals(0, p.gainFor(external = true))
        assertEquals(-4, p.copy(externalGainDb = -4).gainFor(external = true))
        assertEquals(InputChoice.AUTO, p.input)
        assertTrue(p.voiceLeveler) // the "Voice" preset is the raw mic's default
        assertFalse(p.monoVoice)
    }

    @Test fun `stored choices read back, and unknown ones fall back`() {
        assertEquals(LowCut.WIND, MicSettings.enumOr("WIND", LowCut.AUTO))
        assertEquals(LowCut.AUTO, MicSettings.enumOr("SOMETHING_OLD", LowCut.AUTO))
        assertEquals(InputChoice.AUTO, MicSettings.enumOr(null, InputChoice.AUTO))
    }

    @Test fun `low cut row values`() {
        assertEquals("Auto (80 Hz)", lowCutText(LowCut.AUTO, raw = true))
        assertEquals("Auto (off)", lowCutText(LowCut.AUTO, raw = false))
        assertEquals("120 Hz", lowCutText(LowCut.WIND, raw = false))
        assertEquals("Off", lowCutText(LowCut.OFF, raw = true))
    }
}
