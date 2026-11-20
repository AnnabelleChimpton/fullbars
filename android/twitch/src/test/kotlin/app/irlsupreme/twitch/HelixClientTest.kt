package app.irlsupreme.twitch

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class HelixClientTest {
    private val server = MockWebServer()
    private lateinit var helix: HelixClient

    @Before fun setUp() {
        server.start()
        helix = HelixClient(server.config(), testHttp())
    }

    @After fun tearDown() = server.close()

    @Test fun getCurrentUserSendsClientIdAndBearer() = runTest {
        server.enqueue(json(200, """{"data":[{"id":"1001","login":"streamer","display_name":"Streamer","type":"","broadcaster_type":"affiliate","profile_image_url":"https://x/y.png"}]}"""))
        val user = helix.getCurrentUser(token())
        assertEquals(TwitchUser("1001", "streamer", "Streamer", "https://x/y.png", "affiliate"), user)
        val req = server.take()
        assertEquals("/helix/users", req.url.encodedPath)
        assertEquals(CLIENT_ID, req.headers["Client-Id"])
        assertEquals("Bearer access-1", req.headers["Authorization"])
    }

    @Test fun getStreamKey() = runTest {
        server.enqueue(json(200, """{"data":[{"stream_key":"live_44322889_a34ub37c8ajv98a0"}]}"""))
        val key = helix.getStreamKey(token(), "1001")
        assertEquals("live_44322889_a34ub37c8ajv98a0", key.value)
        assertFalse(key.toString().contains("live_"))
        val req = server.take()
        assertEquals("/helix/streams/key", req.url.encodedPath)
        assertEquals("1001", req.url.queryParameter("broadcaster_id"))
    }

    @Test fun unauthorizedMapsToTypedError() = runTest {
        server.enqueue(json(401, """{"error":"Unauthorized","status":401,"message":"Invalid OAuth token"}"""))
        try {
            helix.getStreamKey(token(), "1001")
            fail("expected Unauthorized")
        } catch (e: TwitchException.Unauthorized) {
            assertEquals("Invalid OAuth token", e.serverMessage)
        }
    }

    @Test fun forbiddenCarriesUserFacingMessage() = runTest {
        server.enqueue(json(403, """{"error":"Forbidden","status":403,"message":"Enable two-factor authentication to stream"}"""))
        try {
            helix.getStreamKey(token(), "1001")
            fail("expected Forbidden")
        } catch (e: TwitchException.Forbidden) {
            assertEquals("Enable two-factor authentication to stream", e.serverMessage)
        }
    }

    @Test fun rateLimitedReadsResetHeader() = runTest {
        server.enqueue(MockResponse.Builder().code(429).addHeader("Ratelimit-Reset", "1700000000").body("{}").build())
        try {
            helix.getCurrentUser(token())
            fail("expected RateLimited")
        } catch (e: TwitchException.RateLimited) {
            assertEquals(1_700_000_000L, e.resetAtEpochSeconds)
        }
    }

    @Test fun withTokenRefreshesOnceAfter401() = runTest {
        server.enqueue(json(401, """{"status":401,"message":"Invalid OAuth token"}"""))
        server.enqueue(json(200, """{"data":[{"stream_key":"live_1_abc"}]}"""))
        val tokens = FakeTokens()
        val key = tokens.withToken { helix.getStreamKey(it, "1001") }
        assertEquals("live_1_abc", key.value)
        assertEquals(1, tokens.refreshes)
        assertEquals("Bearer access-1", server.take().headers["Authorization"])
        assertEquals("Bearer access-2", server.take().headers["Authorization"])
    }

    @Test fun getChannelInfo() = runTest {
        server.enqueue(json(200, """{"data":[{"broadcaster_id":"1001","broadcaster_login":"streamer","broadcaster_name":"Streamer","broadcaster_language":"en","game_id":"509658","game_name":"Just Chatting","title":"IRL walk","delay":0,"tags":["IRL"],"content_classification_labels":[],"is_branded_content":false}]}"""))
        val info = helix.getChannelInfo(token(), "1001")
        assertEquals("IRL walk", info.title)
        assertEquals("Just Chatting", info.categoryName)
        assertEquals(listOf("IRL"), info.tags)
    }

    @Test fun updateChannelTitleUsesPatch() = runTest {
        server.enqueue(MockResponse.Builder().code(204).build())
        helix.updateChannel(token(), "1001", title = "New title")
        val req = server.take()
        assertEquals("PATCH", req.method)
        assertEquals("1001", req.url.queryParameter("broadcaster_id"))
        val body = Json.parseToJsonElement(req.bodyText()).jsonObject
        assertEquals("New title", body["title"]!!.jsonPrimitive.content)
        assertNull(body["game_id"])
    }

    @Test fun searchCategories() = runTest {
        server.enqueue(json(200, """{"data":[{"id":"509658","name":"Just Chatting","box_art_url":"https://x/52x72.jpg"},{"id":"509672","name":"Travel & Outdoors","box_art_url":"https://x/y.jpg"}],"pagination":{}}"""))
        val found = helix.searchCategories(token(), "  chat ")
        assertEquals(listOf(TwitchCategory("509658", "Just Chatting"), TwitchCategory("509672", "Travel & Outdoors")), found)
        val req = server.take()
        assertEquals("/helix/search/categories", req.url.encodedPath)
        assertEquals("chat", req.url.queryParameter("query"))
        assertEquals("8", req.url.queryParameter("first"))
    }

    @Test fun blankCategorySearchSendsNothing() = runTest {
        assertEquals(emptyList<TwitchCategory>(), helix.searchCategories(token(), "   "))
        assertEquals(0, server.requestCount)
    }

    @Test fun channelInfoUsesTheSavedUserIdAndUpdateReadsBack() = runTest {
        val channel = """{"data":[{"broadcaster_id":"1001","broadcaster_login":"streamer","broadcaster_name":"Streamer","game_id":"509672","game_name":"Travel & Outdoors","title":"Night market","tags":[]}]}"""
        val twitch = TwitchClient(server.config(), InMemoryTokenStore(token()), testHttp())
        server.enqueue(json(200, channel))
        assertEquals("Night market", twitch.channelInfo().title)
        assertEquals("/helix/channels", server.take().url.encodedPath) // no Get Users: the token knows its user

        server.enqueue(MockResponse.Builder().code(204).build())
        server.enqueue(json(200, channel))
        val after = twitch.updateChannelInfo(title = "Night market", categoryId = "509672")
        assertEquals("Travel & Outdoors", after.categoryName)
        val patch = server.take()
        assertEquals("PATCH", patch.method)
        assertEquals("509672", Json.parseToJsonElement(patch.bodyText()).jsonObject["game_id"]!!.jsonPrimitive.content)
        assertEquals("GET", server.take().method)
    }

    @Test(expected = IllegalArgumentException::class)
    fun updateChannelRejectsTooLongTitle() = runTest {
        helix.updateChannel(token(), "1001", title = "x".repeat(141))
    }

    @Test fun sendChatMessage() = runTest {
        server.enqueue(json(200, """{"data":[{"message_id":"abc-123-def","is_sent":true,"drop_reason":null}]}"""))
        val r = helix.sendChatMessage(token(), "1001", "1001", "BRB scene on")
        assertEquals(SendChatResult("abc-123-def", true, null, null), r)
        val body = Json.parseToJsonElement(server.take().bodyText()).jsonObject
        assertEquals("1001", body["sender_id"]!!.jsonPrimitive.content)
        assertEquals("BRB scene on", body["message"]!!.jsonPrimitive.content)
    }

    @Test fun sendChatMessageDropped() = runTest {
        server.enqueue(json(200, """{"data":[{"message_id":"","is_sent":false,"drop_reason":{"code":"msg_duplicate","message":"duplicate"}}]}"""))
        val r = helix.sendChatMessage(token(), "1001", "1001", "hi")
        assertFalse(r.isSent)
        assertEquals("msg_duplicate", r.dropReasonCode)
    }

    @Test fun eventSubConflictIsNotAnError() = runTest {
        server.enqueue(json(409, """{"error":"Conflict","status":409,"message":"subscription already exists"}"""))
        assertNull(helix.createEventSubSubscription(token(), "channel.chat.message", "1", mapOf("broadcaster_user_id" to "1"), "s"))
    }
}
