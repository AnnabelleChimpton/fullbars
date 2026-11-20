package app.irlsupreme.twitch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatEmotesTest {
    private fun withFragments(text: String, fragments: String): ChatMessage {
        val json = TwitchChatClientTest.chatNotification("n", "x")
            .replace(""""message":{"text":"x","fragments":[{"type":"text","text":"x","cheermote":null,"emote":null,"mention":null}]}""",
                """"message":{"text":"$text","fragments":$fragments}""")
        val n = EventSubParser.parse(json) as EventSubMessage.Notification
        return EventSubParser.parseChatMessage(n.event)
    }

    @Test fun `emotes come through with their ids, the rest as text`() {
        val m = withFragments(
            "hi Kappa @kit PogChamp",
            """[{"type":"text","text":"hi "},{"type":"emote","text":"Kappa","emote":{"id":"25","emote_set_id":"0","owner_id":"0","format":["static"]}},
               {"type":"text","text":" "},{"type":"mention","text":"@kit","mention":{"user_id":"9","user_login":"kit","user_name":"kit"}},
               {"type":"text","text":" "},{"type":"emote","text":"PogChamp","emote":{"id":"emotesv2_dcd06b30a5c24f6eb871e8f5edbd44f7","format":["static","animated"]}}]""",
        )
        assertEquals("hi Kappa @kit PogChamp", m.text)
        assertEquals(
            listOf(
                ChatFragment.Text("hi "), ChatFragment.Emote("Kappa", "25"), ChatFragment.Text(" "), ChatFragment.Text("@kit"),
                ChatFragment.Text(" "), ChatFragment.Emote("PogChamp", "emotesv2_dcd06b30a5c24f6eb871e8f5edbd44f7"),
            ),
            m.fragments,
        )
        assertEquals(m.text, m.fragments.joinToString("") { it.text })
    }

    @Test fun `messages without emotes carry no pieces`() {
        assertTrue(withFragments("just words", """[{"type":"text","text":"just words"}]""").fragments.isEmpty())
    }

    @Test fun `an emote id that isn't shaped like one stays text`() {
        val m = withFragments("x", """[{"type":"emote","text":"Bad","emote":{"id":"../../etc"}},{"type":"emote","text":"Ok","emote":{"id":"1"}}]""")
        assertEquals(listOf(ChatFragment.Text("Bad"), ChatFragment.Emote("Ok", "1")), m.fragments)
    }

    @Test fun `only errors that can change are retried`() {
        assertFalse(TwitchChatClient.retryable(TwitchException.Forbidden("missing scope")))
        assertFalse(TwitchChatClient.retryable(TwitchException.Unauthorized("bad token")))
        assertFalse(TwitchChatClient.retryable(TwitchException.Api(400, "bad request")))
        assertTrue(TwitchChatClient.retryable(TwitchException.Api(503, "busy")))
        assertTrue(TwitchChatClient.retryable(TwitchException.Api(429, "slow down")))
        assertTrue(TwitchChatClient.retryable(java.io.IOException("timeout")))
    }
}
