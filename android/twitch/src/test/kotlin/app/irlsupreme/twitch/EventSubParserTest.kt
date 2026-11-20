package app.irlsupreme.twitch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventSubParserTest {
    @Test fun welcome() {
        val m = EventSubParser.parse(TwitchChatClientTest.welcome("abc", 10)) as EventSubMessage.Welcome
        assertEquals("abc", m.sessionId)
        assertEquals(10, m.keepaliveTimeoutSeconds)
    }

    @Test fun keepalive() {
        assertTrue(EventSubParser.parse(TwitchChatClientTest.keepalive("k")) is EventSubMessage.Keepalive)
    }

    @Test fun reconnect() {
        val m = EventSubParser.parse(TwitchChatClientTest.reconnect("r", "wss://eventsub.wss.twitch.tv?x=1")) as EventSubMessage.Reconnect
        assertEquals("wss://eventsub.wss.twitch.tv?x=1", m.reconnectUrl)
    }

    @Test fun revocation() {
        val m = EventSubParser.parse(TwitchChatClientTest.revocation("v", "user_removed")) as EventSubMessage.Revocation
        assertEquals("user_removed", m.status)
        assertEquals("channel.chat.message", m.subscriptionType)
    }

    @Test fun chatNotification() {
        val n = EventSubParser.parse(TwitchChatClientTest.chatNotification("n", "hi there")) as EventSubMessage.Notification
        assertEquals("channel.chat.message", n.subscriptionType)
        val chat = EventSubParser.parseChatMessage(n.event)
        assertEquals("cc106a89-1814-919d-454c-f4f2f970aae7", chat.messageId)
        assertEquals("hi there", chat.text)
        assertEquals("mod_person", chat.chatterLogin)
        assertEquals(listOf(ChatBadge("moderator", "1", null), ChatBadge("subscriber", "12", "16")), chat.badges)
        assertTrue(chat.isModerator)
        assertTrue(chat.isSubscriber)
        assertNull(chat.replyParentMessageId)
        assertEquals(false, chat.isFromSharedChat)
    }

    @Test fun emptyColorBecomesNull() {
        val text = TwitchChatClientTest.chatNotification("n", "x").replace("#9ACD32", "")
        val n = EventSubParser.parse(text) as EventSubMessage.Notification
        assertNull(EventSubParser.parseChatMessage(n.event).color)
    }

    @Test fun unknownTypeIsTolerated() {
        val m = EventSubParser.parse("""{"metadata":{"message_id":"u","message_type":"something_new"},"payload":{}}""")
        assertEquals(EventSubMessage.Unknown("u", "something_new"), m)
    }
}
