package app.irlsupreme.twitch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Payloads shaped like the examples in Twitch's EventSub reference (channel.chat.notification, channel.follow v2). */
class TwitchAlertParserTest {
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private fun notification(noticeType: String, body: String, anonymous: Boolean = false) = obj(
        """{"broadcaster_user_id":"1","broadcaster_user_name":"Me","broadcaster_user_login":"me",
        "chatter_user_id":"2","chatter_user_name":"KitIRL","chatter_user_login":"kitirl","chatter_is_anonymous":$anonymous,
        "color":"#FF0000","badges":[],"system_message":"…","message_id":"m1","message":{"text":"","fragments":[]},
        "notice_type":"$noticeType",$body}""",
    )

    @Test fun `sub and resub`() {
        assertEquals(
            TwitchAlert.Sub("x", "KitIRL", "1000", prime = false, months = null),
            EventSubParser.parseChatNotification("x", notification("sub", """"sub":{"sub_tier":"1000","is_prime":false,"duration_months":1}""")),
        )
        assertEquals(
            TwitchAlert.Sub("x", "KitIRL", "2000", prime = false, months = 14),
            EventSubParser.parseChatNotification(
                "x",
                notification("resub", """"resub":{"cumulative_months":14,"duration_months":1,"streak_months":null,"sub_tier":"2000","is_prime":false,"is_gift":false}"""),
            ),
        )
        assertEquals(true, (EventSubParser.parseChatNotification("x", notification("sub", """"sub":{"sub_tier":"1000","is_prime":true}""")) as TwitchAlert.Sub).prime)
    }

    @Test fun `a gift batch is one alert, its individual gifts none`() {
        assertEquals(
            TwitchAlert.GiftSubs("x", "Anonymous", 5, "1000"),
            EventSubParser.parseChatNotification(
                "x",
                notification("community_sub_gift", """"community_sub_gift":{"id":"g1","total":5,"sub_tier":"1000","cumulative_total":null}""", anonymous = true),
            ),
        )
        assertNull(
            EventSubParser.parseChatNotification(
                "y",
                notification("sub_gift", """"sub_gift":{"duration_months":1,"recipient_user_name":"Moe","sub_tier":"1000","community_gift_id":"g1"}"""),
            ),
        )
        assertEquals(
            TwitchAlert.GiftSubs("z", "KitIRL", 1, "1000"),
            EventSubParser.parseChatNotification(
                "z",
                notification("sub_gift", """"sub_gift":{"duration_months":1,"recipient_user_name":"Moe","sub_tier":"1000","community_gift_id":null}"""),
            ),
        )
    }

    @Test fun `raid, and kinds that aren't alerts`() {
        assertEquals(
            TwitchAlert.Raid("x", "BigStreamer", 230),
            EventSubParser.parseChatNotification("x", notification("raid", """"raid":{"user_id":"9","user_name":"BigStreamer","user_login":"bigstreamer","viewer_count":230}""")),
        )
        assertNull(EventSubParser.parseChatNotification("x", notification("announcement", """"announcement":{"color":"BLUE"}""")))
    }

    @Test fun `cheers come with chat messages, follows on their own`() {
        val chat = EventSubParser.parseChatMessage(
            obj(
                """{"broadcaster_user_id":"1","chatter_user_id":"2","chatter_user_login":"kitirl","chatter_user_name":"KitIRL",
                "message_id":"c1","message":{"text":"Cheer100 nice"},"color":"","badges":[],"message_type":"text","cheer":{"bits":100}}""",
            ),
        )
        assertEquals(100, chat.bits)
        assertEquals(
            TwitchAlert.Follow("f", "NewFan"),
            EventSubParser.parseFollow("f", obj("""{"user_id":"5","user_login":"newfan","user_name":"NewFan","broadcaster_user_id":"1","followed_at":"2026-11-18T10:00:00Z"}""")),
        )
    }
}
