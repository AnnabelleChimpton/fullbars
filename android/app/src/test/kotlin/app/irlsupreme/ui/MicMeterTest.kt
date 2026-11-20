package app.irlsupreme.ui

import app.irlsupreme.engine.audio.MicLevel
import app.irlsupreme.service.MicPrefs
import app.irlsupreme.service.defaultRaw
import app.irlsupreme.service.fadeEdges
import app.irlsupreme.service.gainText
import app.irlsupreme.service.micValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicMeterTest {
    @Test fun `the bar jumps up and falls back slowly`() {
        val b = MeterBallistics()
        assertEquals(-20f, b.update(MicLevel(-20f, -14f, 0f), 0).barDb, 0f)
        // 100 ms of silence: down 2.4 dB, not to the floor.
        assertEquals(-22.4f, b.update(MicLevel.SILENT, 100).barDb, 0.01f)
    }

    @Test fun `the words hold between sentences`() {
        val b = MeterBallistics()
        b.update(MicLevel(-20f, -14f, 0f), 0)
        var r = b.update(MicLevel.SILENT, 1_000)
        assertEquals(MicVerdict.GOOD, micVerdict(r, muted = false))
        // Well after the hold, a long silence reads as silence.
        for (t in 1_100L..9_000L step 100) r = b.update(MicLevel.SILENT, t)
        assertEquals(MicVerdict.SILENT, micVerdict(r, muted = false))
    }

    @Test fun `too loud shows while the limiter works hard, then clears`() {
        val b = MeterBallistics()
        assertTrue(b.update(MicLevel(-6f, -1f, 9f), 0).loud)
        assertTrue(b.update(MicLevel(-20f, -14f, 0f), 1_000).loud)
        assertFalse(b.update(MicLevel(-20f, -14f, 0f), 2_000).loud)
        assertEquals(MicVerdict.LOUD, micVerdict(b.update(MicLevel(-6f, -1f, 7f), 2_100), muted = false))
        assertEquals(MicVerdict.MUTED, micVerdict(b.update(MicLevel(-6f, -1f, 7f), 2_200), muted = true))
    }

    @Test fun `quiet reads as quiet`() {
        val r = MeterBallistics().update(MicLevel(-40f, -33f, 0f), 0)
        assertEquals(MicVerdict.QUIET, micVerdict(r, muted = false))
    }

    @Test fun `segments light by level`() {
        assertEquals(0, litSegments(-60f, 30, -60f))
        assertEquals(15, litSegments(-30f, 30, -60f))
        assertEquals(30, litSegments(0f, 30, -60f))
        assertEquals(8, litSegments(-10f, 10, -50f))
    }

    @Test fun `values read plainly`() {
        assertEquals("+3 dB", gainText(3))
        assertEquals("−6 dB", gainText(-6))
        assertEquals("0 dB", gainText(0))
        assertEquals("+3 dB", micValue(MicPrefs(3)))
        assertEquals("+20 dB · raw", micValue(MicPrefs(3, raw = true)))
        assertEquals("\u22122 dB · raw", micValue(MicPrefs(3, raw = true, rawGainDb = -2)))
        assertEquals(20, MicPrefs(3, raw = true).activeGainDb)
        assertEquals("−14 dB", dbText(-14.4f))
        assertEquals("plus 3 decibels", gainSpoken(3))
    }

    /** Feeds [watch] [seconds] of readings at ~47 a second, every [clampEvery]th one clamped by 5 dB (0 = none). */
    private fun talk(watch: ClampWatch, fromMs: Long, seconds: Int, clampEvery: Int, rmsDb: Float = -20f): Boolean {
        var out = false
        var n = 0
        var t = fromMs
        while (t < fromMs + seconds * 1000L) {
            val clamped = clampEvery > 0 && n % clampEvery == 0
            out = watch.update(MicLevel(rmsDb, -2f, if (clamped) 5f else 0f), t)
            n++
            t += 21
        }
        return out
    }

    @Test fun `clamping on every word reads as too loud, after a few seconds of talking`() {
        val w = ClampWatch()
        // One reading in four clamped (every syllable hitting the limiter): not after 2 s of voice, yes by 4 s.
        assertFalse(talk(w, 0, 2, clampEvery = 4))
        assertTrue(talk(w, 2_000, 2, clampEvery = 4))
    }

    @Test fun `the odd shout, quiet room or silence is not clamping`() {
        // One reading in twenty (a shout now and then).
        assertFalse(talk(ClampWatch(), 0, 10, clampEvery = 20))
        // Clamped readings that aren't a voice (the limiter on a door slam in a silent room) don't count as talking.
        assertFalse(talk(ClampWatch(), 0, 10, clampEvery = 2, rmsDb = -55f))
        assertFalse(talk(ClampWatch(), 0, 10, clampEvery = 0))
    }

    @Test fun `the advice holds a moment after the clamping stops, then clears`() {
        val w = ClampWatch()
        assertTrue(talk(w, 0, 5, clampEvery = 3))
        // A clean level from here on: the hard seconds are still in the window, then the hold runs out.
        assertTrue(talk(w, 5_000, 3, clampEvery = 0))
        assertFalse(talk(w, 8_000, 20, clampEvery = 0))
    }

    @Test fun `the advice points at the right fix`() {
        val auto = clampAdvice(VoiceSource.PHONE, raw = false, gainDb = 4)
        assertTrue(auto.offerAutoLevelOff)
        assertTrue(auto.offerTurnDown)
        assertTrue(auto.text.contains("auto level off"))
        val autoAtMin = clampAdvice(VoiceSource.PHONE, raw = false, gainDb = -20)
        assertFalse(autoAtMin.offerTurnDown)
        assertFalse(autoAtMin.text.contains("Or turn the level down"))
        val raw = clampAdvice(VoiceSource.PHONE, raw = true, gainDb = 20)
        assertFalse(raw.offerAutoLevelOff)
        assertTrue(raw.offerTurnDown)
        // The camera's sound isn't levelled by the fader or the phone's auto level: only the camera can fix it.
        val cam = clampAdvice(VoiceSource.CAMERA, raw = false, gainDb = 4)
        assertFalse(cam.offerAutoLevelOff)
        assertFalse(cam.offerTurnDown)
        assertTrue(cam.text.contains("on the camera"))
    }

    @Test fun `the voice source comes from the camera link status`() {
        assertEquals(VoiceSource.PHONE, voiceSourceOf(null))
        assertEquals(VoiceSource.PHONE, voiceSourceOf("waiting"))
        assertEquals(VoiceSource.PHONE, voiceSourceOf("1920x1080 · phone mic"))
        assertEquals(VoiceSource.CAMERA, voiceSourceOf("1920x1080 · camera audio"))
    }

    @Test fun `auto level starts off only on a fresh install with a flat mic`() {
        assertTrue(defaultRaw(freshInstall = true, unprocessed = true))
        assertFalse(defaultRaw(freshInstall = true, unprocessed = false))
        assertFalse(defaultRaw(freshInstall = false, unprocessed = true))
    }

    @Test fun `the sound check counts down whole seconds`() {
        assertEquals(10, secondsLeft(0, 10_000))
        assertEquals(10, secondsLeft(1, 10_000))
        assertEquals(9, secondsLeft(1_000, 10_000))
        assertEquals(1, secondsLeft(9_999, 10_000))
        assertEquals(0, secondsLeft(12_000, 10_000))
        assertEquals("0:07", clipTime(7.9f))
        assertEquals("0:15", clipTime(15f))
        assertEquals("1:05", clipTime(65f))
    }

    @Test fun `played clips fade in and out without touching the middle`() {
        val pcm = ShortArray(2 * 4_800) { 10_000 }
        fadeEdges(pcm, channels = 2, sampleRate = 48_000)
        assertEquals(0, pcm[0].toInt())
        assertEquals(0, pcm[1].toInt())
        assertEquals(0, pcm[pcm.size - 1].toInt())
        assertEquals(10_000, pcm[pcm.size / 2].toInt())
        // 8 ms at 48 kHz: full level from frame 384 on.
        assertEquals(10_000, pcm[384 * 2].toInt())
        assertTrue(pcm[192 * 2] in 4_000..6_000)
        // A clip too short for two fades still works.
        fadeEdges(ShortArray(3) { 1 }, channels = 1, sampleRate = 48_000)
    }
}
