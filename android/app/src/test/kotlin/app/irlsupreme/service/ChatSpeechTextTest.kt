package app.irlsupreme.service

import app.irlsupreme.twitch.ChatBadge
import app.irlsupreme.twitch.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSpeechTextTest {
    private fun msg(text: String, vararg badges: String, chatter: String = "2", source: String? = null) = ChatMessage(
        "m", "1", chatter, "sam", "Sam", null, text, badges.map { ChatBadge(it, "1", null) }, "text", null, source,
    )

    @Test fun `who gets read`() {
        val plain = msg("hi")
        assertFalse(ChatSpeechText.shouldRead(plain, ReadAloud.TRUSTED, false))
        assertTrue(ChatSpeechText.shouldRead(plain, ReadAloud.EVERYONE, false))
        for (b in listOf("moderator", "vip", "subscriber", "founder")) assertTrue(b, ChatSpeechText.shouldRead(msg("hi", b), ReadAloud.TRUSTED, false))
        assertFalse("off", ChatSpeechText.shouldRead(msg("hi", "moderator"), ReadAloud.OFF, false))
        assertFalse("mod commands", ChatSpeechText.shouldRead(msg("!brb", "moderator"), ReadAloud.EVERYONE, true))
        assertFalse("bot commands", ChatSpeechText.shouldRead(msg("!uptime"), ReadAloud.EVERYONE, false))
        assertFalse("your own messages", ChatSpeechText.shouldRead(msg("hi", chatter = "1"), ReadAloud.EVERYONE, false))
        assertFalse("shared chat", ChatSpeechText.shouldRead(msg("hi", source = "99"), ReadAloud.EVERYONE, false))
    }

    @Test fun `what gets said`() {
        assertEquals("Sam says this view is unreal", ChatSpeechText.utterance(msg("this view is unreal")))
        assertEquals("Sam says look at a link now", ChatSpeechText.utterance(msg("look at https://example.com/x?y=1 now")))
        assertEquals("Sam says a link", ChatSpeechText.utterance(msg("www.example.com")))
        assertEquals("Sam says nooo!!!", ChatSpeechText.utterance(msg("nooooooooo!!!!!!!!!!")))
        assertNull(ChatSpeechText.utterance(msg("   ")))
        val long = ChatSpeechText.utterance(msg(("word ".repeat(60)).trim()))!!
        assertTrue(long.length <= "Sam says ".length + ChatSpeechText.MAX + 1)
        assertTrue(long.endsWith("…"))
    }
}
