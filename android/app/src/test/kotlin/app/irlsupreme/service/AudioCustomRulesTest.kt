package app.irlsupreme.service

import app.irlsupreme.engine.Scene
import app.irlsupreme.engine.audio.ImportFailure
import app.irlsupreme.twitch.TwitchAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Custom alert sounds and card music: defaults that keep today's sound, the rules, and the log's words. */
class AudioCustomRulesTest {
    @Test fun `by default every alert keeps its built-in chime at its usual level`() {
        val d = AlertSoundSetting()
        assertEquals(AlertSoundChoice.BUILT_IN, d.choice)
        assertEquals(0, d.gainDb)
        assertFalse(d.hasFile)
        assertEquals(AlertSoundChoice.BUILT_IN, AlertSoundRules.effective(d))
        // The built-in chime isn't logged, as before.
        for (k in AlertKind.entries) assertNull(AlertSoundRules.logLine(AlertSoundChoice.BUILT_IN, k))
    }

    @Test fun `each kind's chime is the one its alerts have always had`() {
        val samples = mapOf(
            AlertKind.FOLLOW to TwitchAlert.Follow("1", "a"),
            AlertKind.SUB to TwitchAlert.Sub("1", "a", "1000", prime = false, months = null),
            AlertKind.GIFT to TwitchAlert.GiftSubs("1", "a", 5, "1000"),
            AlertKind.BITS to TwitchAlert.Cheer("1", "a", 100),
            AlertKind.RAID to TwitchAlert.Raid("1", "a", 20),
            AlertKind.TIP to TwitchAlert.Tip("1", "a", "$5"),
        )
        assertEquals(AlertKind.entries.toSet(), samples.keys)
        for ((k, a) in samples) assertEquals(k.name, AlertText.card(a, AlertLook()).style, AlertSoundRules.chimeFor(k))
    }

    @Test fun `your sound with no file falls back to the chime, volume stays in range`() {
        assertEquals(AlertSoundChoice.BUILT_IN, AlertSoundRules.effective(AlertSoundSetting(AlertSoundChoice.CUSTOM, hasFile = false)))
        assertEquals(AlertSoundChoice.CUSTOM, AlertSoundRules.effective(AlertSoundSetting(AlertSoundChoice.CUSTOM, hasFile = true)))
        assertEquals(AlertSoundChoice.NONE, AlertSoundRules.effective(AlertSoundSetting(AlertSoundChoice.NONE)))
        assertEquals(-24, AlertSoundRules.clampGain(-40))
        assertEquals(6, AlertSoundRules.clampGain(12))
        assertEquals(-3, AlertSoundRules.clampGain(-3))
        assertEquals("+3 dB", AlertSoundRules.gainText(3))
        assertEquals("−12 dB", AlertSoundRules.gainText(-12))
        assertEquals("0 dB", AlertSoundRules.gainText(0))
    }

    @Test fun `alert sound log lines name the kind, never a file`() {
        assertEquals("alert sound: custom (follow)", AlertSoundRules.logLine(AlertSoundChoice.CUSTOM, AlertKind.FOLLOW))
        assertEquals("alert sound: none (raid)", AlertSoundRules.logLine(AlertSoundChoice.NONE, AlertKind.RAID))
    }

    @Test fun `by default no card has music, and its settings are a quiet level with the mic on`() {
        val d = CardMusicSetting()
        assertFalse(d.hasFile)
        assertEquals(-12, d.volumeDb)
        assertTrue(d.micOn)
        val dir = File("/data/music")
        assertNull(CardMusicRules.track(d, dir))
        assertNull(CardMusicRules.track(null, dir))
    }

    @Test fun `a card's music plays at its loudness gain plus your volume`() {
        val s = CardMusicSetting(fileName = "brb-1.music", volumeDb = -10, micOn = false, startFrame = 480, endFrame = 96_000, gainDb = -4.5f)
        val t = CardMusicRules.track(s, File("/data/music"))!!
        assertEquals("/data/music/brb-1.music", t.path)
        assertEquals(-14.5f, t.gainDb, 1e-6f)
        assertTrue(t.micOff)
        assertEquals(480L, t.startFrame)
        assertEquals(96_000L, t.endFrame)
        // Volume is held to -30..0 dB.
        assertEquals(-30, CardMusicRules.clampVolume(-50))
        assertEquals(0, CardMusicRules.clampVolume(6))
        assertEquals(-4.5f, CardMusicRules.track(s.copy(volumeDb = 6), File("/x"))!!.gainDb, 1e-6f)
    }

    @Test fun `every chosen scene can have music, Live and unknown names never`() {
        for (scene in Scene.entries) {
            if (scene == Scene.LIVE) assertNull(CardMusicRules.sceneFor(scene.name)) else assertEquals(scene, CardMusicRules.sceneFor(scene.name))
        }
        assertNotNull(CardMusicRules.sceneFor("BRB"))
        assertNull(CardMusicRules.sceneFor("NOT_A_SCENE"))
        assertNull(CardMusicRules.sceneFor(""))
        assertNull(CardMusicRules.sceneFor("brb"))
    }

    @Test fun `card music log lines name the scene, never a file`() {
        assertEquals("card music on (BRB)", CardMusicRules.logOn(Scene.BRB))
        assertEquals("card music on (STARTING_SOON)", CardMusicRules.logOn(Scene.STARTING_SOON))
        assertEquals("card music off", CardMusicRules.LOG_OFF)
        // Stored copies are named for the card, not for the file that was picked.
        assertEquals("brb-1700000000000.music", CardMusicRules.newFileName("BRB", 1_700_000_000_000L))
    }

    @Test fun `forgetting a scene's music takes all its settings and files, and only its own`() {
        val keys = listOf(
            "music_CUSTOM_1_file", "music_CUSTOM_1_volume", "music_CUSTOM_1_mic", "music_CUSTOM_1_start",
            "music_CUSTOM_1_end", "music_CUSTOM_1_gain", "music_CUSTOM_1_ms",
            "music_CUSTOM_2_mic", "music_BRB_file", "music_BRB_mic", "something_else",
        )
        assertEquals(keys.take(7), CardMusicRules.keysOf("CUSTOM_1", keys))
        assertEquals(listOf("music_CUSTOM_2_mic"), CardMusicRules.keysOf("CUSTOM_2", keys))
        assertEquals(emptyList<String>(), CardMusicRules.keysOf("CUSTOM_3", keys))
        // Every file a pick ever made for the scene, and nothing of another's.
        val name = CardMusicRules.newFileName("CUSTOM_1", 1_700_000_000_000L)
        assertTrue(CardMusicRules.ownsFile("CUSTOM_1", name))
        assertTrue(CardMusicRules.ownsFile("CUSTOM_1", "custom_1-1600000000000.music"))
        assertFalse(CardMusicRules.ownsFile("CUSTOM_2", name))
        assertFalse(CardMusicRules.ownsFile("CUSTOM_1", "custom_1.tmp"))
        assertFalse(CardMusicRules.ownsFile("BRB", name))
    }

    @Test fun `a card turns the mic off only with music that asks for it`() {
        val all = mapOf(
            "BRB" to CardMusicSetting(fileName = "brb-1.music", micOn = false),
            "ENDING" to CardMusicSetting(fileName = "ending-1.music", micOn = true),
            "PRIVACY" to CardMusicSetting(fileName = null, micOn = false),
            "LIVE" to CardMusicSetting(fileName = "live-1.music", micOn = false),
        )
        assertTrue(CardMusicRules.micOff(Scene.BRB, all))
        assertFalse(CardMusicRules.micOff(Scene.ENDING, all))
        assertFalse(CardMusicRules.micOff(Scene.PRIVACY, all)) // no music: the mic setting is only kept for next time
        assertFalse(CardMusicRules.micOff(Scene.LIVE, all))
        assertFalse(CardMusicRules.micOff(Scene.CUSTOM_1, all))
    }

    @Test fun `failures are said plainly`() {
        for (f in ImportFailure.entries) assertTrue(AudioPick.failureText(f).isNotBlank())
        assertEquals("Music can be up to 15 minutes.", AudioPick.failureText(ImportFailure.TOO_LONG))
        assertEquals("That file is too big. Up to 60 MB.", AudioPick.copyFailureText(AudioPick.Copy.TOO_BIG))
        assertNull(AudioPick.copyFailureText(AudioPick.Copy.OK))
        assertEquals(
            "Use music you have the rights to. Twitch can mute your VOD for copyrighted music.",
            CardMusicRules.COPYRIGHT_NOTE,
        )
    }

    @Test fun `music length reads as minutes and seconds`() {
        assertEquals("3:05", CardMusicRules.lengthText(185_000))
        assertEquals("0:01", CardMusicRules.lengthText(1_000))
        assertEquals("15:00", CardMusicRules.lengthText(900_000))
    }
}
