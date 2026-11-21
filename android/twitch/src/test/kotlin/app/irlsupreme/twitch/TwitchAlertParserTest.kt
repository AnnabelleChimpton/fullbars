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

    @Test fun `a shared watch streak is a chat notice, not an alert`() {
        val streak = notification("watch_streak", """"watch_streak":{"streak_count":5,"channel_points_awarded":350}""")
        assertNull(EventSubParser.parseChatNotification("x", streak))
        val m = EventSubParser.parseChatNotice("x", streak)!!
        assertEquals("watched 5 streams in a row", m.notice)
        assertEquals("KitIRL", m.chatterDisplayName)
        assertEquals("kitirl", m.chatterLogin)
        assertEquals("#FF0000", m.color)
        assertEquals("m1", m.messageId)
        assertEquals("", m.text)
        // Without a count it still shows; other notices aren't chat lines.
        assertEquals("shared a watch streak", EventSubParser.parseChatNotice("x", notification("watch_streak", """"watch_streak":null"""))!!.notice)
        assertNull(EventSubParser.parseChatNotice("x", notification("sub", """"sub":{"sub_tier":"1000","is_prime":false}""")))
        assertNull(EventSubParser.parseChatNotice("x", notification("announcement", """"announcement":{"color":"BLUE"}""")))
    }

    @Test fun `a mod's words on their watch streak are never a command`() {
        val shared = EventSubParser.parseChatNotice(
            "x",
            obj(
                """{"broadcaster_user_id":"1","chatter_user_id":"2","chatter_user_name":"KitIRL","chatter_user_login":"kitirl",
                "badges":[{"set_id":"moderator","id":"1","info":""}],"message_id":"m2","message":{"text":"!end now","fragments":[]},
                "notice_type":"watch_streak","watch_streak":{"streak_count":3,"channel_points_awarded":0}}""",
            ),
        )!!
        assertEquals(CommandResult.NotACommand, ChatCommandParser.parse(shared))
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

    @Test fun `a resub carries what the viewer wrote, a blank one nothing`() {
        val withWords = obj(
            """{"chatter_user_id":"2","chatter_user_name":"KitIRL","chatter_user_login":"kitirl","chatter_is_anonymous":false,
            "message_id":"m1","message":{"text":"  two years already  ","fragments":[]},"notice_type":"resub",
            "resub":{"cumulative_months":24,"sub_tier":"1000","is_prime":false}}""",
        )
        assertEquals("two years already", (EventSubParser.parseChatNotification("x", withWords) as TwitchAlert.Sub).message)
        assertNull((EventSubParser.parseChatNotification("x", notification("sub", """"sub":{"sub_tier":"1000"}""")) as TwitchAlert.Sub).message)
    }

    @Test fun `a cheer's words leave out the cheermotes`() {
        fun cheer(message: String) = EventSubParser.parseChatMessage(
            obj(
                """{"broadcaster_user_id":"1","chatter_user_id":"2","chatter_user_login":"kitirl","chatter_user_name":"KitIRL",
                "message_id":"c1","message":$message,"badges":[],"cheer":{"bits":200}}""",
            ),
        ).cheerMessage
        assertEquals(
            "nice view",
            cheer(
                """{"text":"Cheer100 nice view Cheer100","fragments":[{"type":"cheermote","text":"Cheer100"},
                {"type":"text","text":" nice view "},{"type":"cheermote","text":"Cheer100"}]}""",
            ),
        )
        assertEquals("go go", cheer("""{"text":"Cheer100 go   go"}"""))
        assertNull(cheer("""{"text":"Cheer100 Corgo100"}"""))
        // A plain chat line has no cheer words.
        val plain = EventSubParser.parseChatMessage(
            obj("""{"broadcaster_user_id":"1","chatter_user_id":"2","chatter_user_login":"k","chatter_user_name":"K","message_id":"c2","message":{"text":"hi"}}"""),
        )
        assertNull(plain.cheerMessage)
    }
}
