package app.irlsupreme.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsTextTest {
    private fun speak(raw: String, blocked: List<String> = emptyList()) = TtsText.prepare(raw, blocked) as TtsPrepared.Speak

    private fun dropped(raw: String?, blocked: List<String> = emptyList()): TtsDrop? =
        (TtsText.prepare(raw, blocked) as? TtsPrepared.Drop)?.why

    @Test fun `there are no voice words, everything is said as written in the diva voice`() {
        assertEquals(TtsPrepared.Speak("deep: hello chat"), TtsText.prepare("deep: hello chat"))
        assertEquals(TtsPrepared.Speak("note: buy milk"), TtsText.prepare("note: buy milk"))
        assertTrue(TtsVoice.PITCH > 1f && TtsVoice.BASES.first() == "en-us-x-tpf-local")
    }

    @Test fun `links, runs and spaces are tidied, and it stops at 150 characters on a word`() {
        assertEquals("go to a link now", speak("go to https://x.example/abc now").text)
        assertEquals("nooo way", speak("noooooooooo   way").text)
        val long = speak(List(60) { "word" }.joinToString(" ")).text
        assertTrue(long.length <= TtsText.MAX)
        assertTrue(long.endsWith("word"))
        assertEquals("a b", speak("a\u0000b").text)
    }

    @Test fun `nothing to say is dropped`() {
        assertEquals(TtsDrop.EMPTY, dropped(null))
        assertEquals(TtsDrop.EMPTY, dropped("   "))
        assertEquals(TtsDrop.EMPTY, dropped("!!! ???"))
        assertEquals(TtsDrop.EMPTY, dropped(":"))
    }

    @Test fun `the built-in words are blocked through the usual dodges`() {
        for (s in listOf(
            "you are a f4gg0t", "FAGGOT", "n i g g e r", "n.i.g.g.e.r lol", "niiiiigger", "kys", "just kill yourself",
            "what a retard", "R3TARD",
        )) assertEquals(s, TtsDrop.BLOCKED, dropped(s))
    }

    @Test fun `ordinary words that contain a blocked one are left alone`() {
        for (s in listOf(
            "this is so spicy", "go skill yourself up", "the class assassin", "I'm in Niger next week", "a retardant spray",
            "kyshawn says hi", "can you see the coon tail", "it's a pakistani restaurant",
        )) assertTrue(s, TtsText.prepare(s) is TtsPrepared.Speak)
    }

    @Test fun `the streamer's own words and Twitch's wildcards block too`() {
        val mine = listOf("pineapple pizza", "spoiler*", "*fan")
        assertEquals(TtsDrop.BLOCKED, dropped("I love Pineapple  Pizza", mine))
        assertEquals(TtsDrop.BLOCKED, dropped("SPOILERS ahead", mine))
        assertEquals(TtsDrop.BLOCKED, dropped("big superfan here", mine))
        assertTrue(TtsText.prepare("pineapple on pizza", mine) is TtsPrepared.Speak)
        assertTrue(TtsText.prepare("fans everywhere", mine) is TtsPrepared.Speak)
        // An empty or stray entry blocks nothing.
        assertTrue(TtsText.prepare("hello", listOf("", "*", " ")) is TtsPrepared.Speak)
    }

    @Test fun `a blocked word after a colon is still caught`() {
        assertEquals(TtsDrop.BLOCKED, dropped("robot: kys"))
    }

    @Test fun `words are folded for matching`() {
        assertEquals(listOf("nigger"), TtsText.words("N1GG3R"))
        assertEquals(listOf("hello", "abc", "world"), TtsText.words("hello a-b-c world"))
        assertFalse(TtsText.isBlocked("hello world"))
    }

    @Test fun `the Overlays row says what sends it`() {
        assertEquals("Off", ttsValue(TtsPrefs(), alertKinds = 3))
        assertEquals("Points", ttsValue(TtsPrefs(on = true)))
        assertEquals("Points · Alerts", ttsValue(TtsPrefs(on = true), alertKinds = 2))
        assertEquals("Alerts", ttsValue(TtsPrefs(on = true, pointsOn = false), alertKinds = 1))
        assertEquals("On, nothing sends it", ttsValue(TtsPrefs(on = true, pointsOn = false)))
        assertEquals(listOf("a", "b c", "d"), TtsPrefs(ownWords = "a, b c\n d,,").ownList)
    }

    @Test fun `the reward fits Twitch's limits and only changes with what Twitch is told`() {
        assertTrue(TtsReward.TITLE.length <= 45)
        assertTrue(TtsReward.PROMPT.length <= 200)
        val p = TtsPrefs(on = true)
        // Saving the reward's id, the volume or the wait doesn't count as a change to the reward.
        assertEquals(TtsReward.wanted(p), TtsReward.wanted(p.copy(rewardId = "x", gainDb = 3, waitS = 10)))
        assertTrue(TtsReward.wanted(p) != TtsReward.wanted(p.copy(cost = 1000)))
    }

    @Test fun `the streamer hears it in earbuds, or from the speaker only on a Test stream`() {
        assertEquals(TtsHeard.EARBUDS, TtsHeard.of(headphones = true, earbudsOn = true, test = false))
        assertEquals(TtsHeard.NOT_HERE, TtsHeard.of(headphones = true, earbudsOn = false, test = true))
        assertEquals(TtsHeard.SPEAKER, TtsHeard.of(headphones = false, earbudsOn = true, test = true))
        assertEquals(TtsHeard.SPEAKER, TtsHeard.of(headphones = false, earbudsOn = false, test = true))
        // Never from the speaker on a real stream: the mic would send it to viewers twice.
        assertEquals(TtsHeard.NOT_HERE, TtsHeard.of(headphones = false, earbudsOn = true, test = false))
    }

    @Test fun `the backlog keeps the newest, refunds the oldest and ignores repeats`() {
        val dropped = ArrayList<String>()
        val b = TtsBacklog(2) { dropped.add(it.id) }
        fun r(id: String) = TtsRequest(id, TtsSource.POINTS, "u", "t")
        b.post(r("1")); b.post(r("2")); b.post(r("2")); b.post(r("3"))
        assertEquals(listOf("1"), dropped)
        assertEquals(2, b.size())
        assertEquals("2", b.next()?.id)
        assertEquals(listOf("3"), b.clear().map { it.id })
        assertEquals(null, b.next())
    }
}
