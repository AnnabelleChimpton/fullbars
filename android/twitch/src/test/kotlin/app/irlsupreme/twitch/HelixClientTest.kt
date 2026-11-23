package app.irlsupreme.twitch

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private val rewardJson = """{"broadcaster_name":"Streamer","broadcaster_login":"streamer","broadcaster_id":"1001","id":"rw-1","image":null,"background_color":"#9147FF","is_enabled":true,"cost":500,"title":"Make me talk","prompt":"Type what I should say","is_user_input_required":true,"max_per_stream_setting":{"is_enabled":false,"max_per_stream":0},"max_per_user_per_stream_setting":{"is_enabled":false,"max_per_user_per_stream":0},"global_cooldown_setting":{"is_enabled":true,"global_cooldown_seconds":60},"is_paused":PAUSED,"is_in_stock":true,"default_image":{"url_1x":"https://x/1.png"},"should_redemptions_skip_request_queue":false,"redemptions_redeemed_current_stream":null,"cooldown_expires_at":null}"""

    private fun reward(paused: Boolean) = """{"data":[${rewardJson.replace("PAUSED", paused.toString())}]}"""

    @Test fun getCustomRewardsOnlyManageable() = runTest {
        server.enqueue(json(200, reward(paused = true)))
        val rewards = helix.getCustomRewards(token(), "1001")
        assertEquals(listOf(CustomReward("rw-1", "Make me talk", 500, isPaused = true, isEnabled = true, globalCooldownSec = 60,
            prompt = "Type what I should say", isUserInputRequired = true)), rewards)
        val req = server.take()
        assertEquals("/helix/channel_points/custom_rewards", req.url.encodedPath)
        assertEquals("1001", req.url.queryParameter("broadcaster_id"))
        assertEquals("true", req.url.queryParameter("only_manageable_rewards"))
    }

    @Test fun createCustomRewardThenPausesIt() = runTest {
        server.enqueue(json(200, reward(paused = false)))
        server.enqueue(json(200, reward(paused = true)))
        val r = helix.createCustomReward(token(), "1001", "Make me talk", 500, "Type what I should say", globalCooldownSec = 60, isPaused = true)
        assertTrue(r.isPaused)
        val create = server.take()
        assertEquals("POST", create.method)
        assertEquals("/helix/channel_points/custom_rewards", create.url.encodedPath)
        assertEquals("1001", create.url.queryParameter("broadcaster_id"))
        val body = Json.parseToJsonElement(create.bodyText()).jsonObject
        assertEquals("Make me talk", body["title"]!!.jsonPrimitive.content)
        assertEquals(500, body["cost"]!!.jsonPrimitive.int)
        assertEquals(true, body["is_user_input_required"]!!.jsonPrimitive.boolean)
        assertEquals(true, body["is_global_cooldown_enabled"]!!.jsonPrimitive.boolean)
        assertEquals(60, body["global_cooldown_seconds"]!!.jsonPrimitive.int)
        assertEquals(false, body["should_redemptions_skip_request_queue"]!!.jsonPrimitive.boolean)
        assertNull("create takes no paused flag", body["is_paused"])
        val pause = server.take()
        assertEquals("PATCH", pause.method)
        assertEquals("rw-1", pause.url.queryParameter("id"))
        assertEquals("""{"is_paused":true}""", pause.bodyText())
    }

    @Test fun createCustomRewardWithoutPauseIsOneRequest() = runTest {
        server.enqueue(json(200, reward(paused = false)))
        helix.createCustomReward(token(), "1001", "Make me talk", 500)
        val body = Json.parseToJsonElement(server.take().bodyText()).jsonObject
        assertEquals(false, body["is_global_cooldown_enabled"]!!.jsonPrimitive.boolean)
        assertNull(body["global_cooldown_seconds"])
        assertNull(body["prompt"])
        assertEquals(1, server.requestCount)
    }

    @Test fun createCustomRewardDuplicateTitleIsAnApiError() = runTest {
        server.enqueue(json(400, """{"error":"Bad Request","status":400,"message":"CREATE_CUSTOM_REWARD_DUPLICATE_REWARD"}"""))
        try {
            helix.createCustomReward(token(), "1001", "Make me talk", 500)
            fail("expected Api")
        } catch (e: TwitchException.Api) {
            assertEquals(400, e.status)
        }
    }

    @Test fun createCustomRewardWithoutChannelPointsIsForbidden() = runTest {
        server.enqueue(json(403, """{"error":"Forbidden","status":403,"message":"The broadcaster must have partner or affiliate status."}"""))
        try {
            helix.createCustomReward(token(), "1001", "Make me talk", 500)
            fail("expected Forbidden")
        } catch (_: TwitchException.Forbidden) {
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun createCustomRewardRejectsLongTitle() = runTest {
        helix.createCustomReward(token(), "1001", "x".repeat(46), 500)
    }

    @Test fun updateCustomRewardSendsOnlyWhatChanges() = runTest {
        server.enqueue(json(200, reward(paused = false)))
        val r = helix.updateCustomReward(token(), "1001", "rw-1", isPaused = false, globalCooldownSec = 0)
        assertFalse(r.isPaused)
        val req = server.take()
        assertEquals("PATCH", req.method)
        assertEquals("/helix/channel_points/custom_rewards", req.url.encodedPath)
        assertEquals("1001", req.url.queryParameter("broadcaster_id"))
        assertEquals("rw-1", req.url.queryParameter("id"))
        val body = Json.parseToJsonElement(req.bodyText()).jsonObject
        assertEquals(setOf("is_paused", "is_global_cooldown_enabled"), body.keys)
        assertEquals(false, body["is_global_cooldown_enabled"]!!.jsonPrimitive.boolean)
    }

    @Test fun refundRedemption() = runTest {
        server.enqueue(json(200, """{"data":[{"broadcaster_name":"Streamer","broadcaster_login":"streamer","broadcaster_id":"1001","id":"red-1","user_id":"9001","user_name":"Cooler_User","user_login":"cooler_user","user_input":"hi","status":"CANCELED","redeemed_at":"2026-11-22T17:16:03Z","reward":{"id":"rw-1","title":"Make me talk","prompt":"","cost":500}}]}"""))
        helix.updateRedemptionStatus(token(), "1001", "rw-1", "red-1", RedemptionStatus.CANCELED)
        val req = server.take()
        assertEquals("PATCH", req.method)
        assertEquals("/helix/channel_points/custom_rewards/redemptions", req.url.encodedPath)
        assertEquals("red-1", req.url.queryParameter("id"))
        assertEquals("1001", req.url.queryParameter("broadcaster_id"))
        assertEquals("rw-1", req.url.queryParameter("reward_id"))
        assertEquals("""{"status":"CANCELED"}""", req.bodyText())
    }

    @Test fun refundOfAHandledRedemptionIsNotFound() = runTest {
        server.enqueue(json(404, """{"error":"Not Found","status":404,"message":"no redemptions found"}"""))
        try {
            helix.updateRedemptionStatus(token(), "1001", "rw-1", "red-1", RedemptionStatus.FULFILLED)
            fail("expected Api")
        } catch (e: TwitchException.Api) {
            assertEquals(404, e.status)
        }
    }

    @Test fun blockedTermsFollowEveryPage() = runTest {
        server.enqueue(json(200, """{"data":[{"broadcaster_id":"1001","moderator_id":"1001","id":"t1","text":"badword","created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z","expires_at":null},{"broadcaster_id":"1001","moderator_id":"1001","id":"t2","text":" other term ","created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z","expires_at":null}],"pagination":{"cursor":"page-2"}}"""))
        server.enqueue(json(200, """{"data":[{"broadcaster_id":"1001","moderator_id":"1001","id":"t3","text":"third","created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z","expires_at":null}],"pagination":{}}"""))
        assertEquals(listOf("badword", "other term", "third"), helix.getBlockedTerms(token(), "1001", "1001"))
        val first = server.take()
        assertEquals("/helix/moderation/blocked_terms", first.url.encodedPath)
        assertEquals("1001", first.url.queryParameter("broadcaster_id"))
        assertEquals("1001", first.url.queryParameter("moderator_id"))
        assertEquals("100", first.url.queryParameter("first"))
        assertNull(first.url.queryParameter("after"))
        assertEquals("page-2", server.take().url.queryParameter("after"))
        assertEquals(2, server.requestCount)
    }

    @Test fun blockedTermsStopOnARepeatedCursor() = runTest {
        repeat(3) { server.enqueue(json(200, """{"data":[{"id":"t","text":"x"}],"pagination":{"cursor":"same"}}""")) }
        assertEquals(listOf("x", "x"), helix.getBlockedTerms(token(), "1001", "1001"))
        assertEquals(2, server.requestCount)
    }

    @Test fun twitchClientRewardCallsUseTheSignedInChannel() = runTest {
        val twitch = TwitchClient(server.config(), InMemoryTokenStore(token()), testHttp())
        server.enqueue(json(200, """{"data":[]}"""))
        assertEquals(emptyList<String>(), twitch.blockedTerms())
        val req = server.take()
        assertEquals("1001", req.url.queryParameter("broadcaster_id"))
        assertEquals("1001", req.url.queryParameter("moderator_id"))

        server.enqueue(json(200, reward(paused = false)))
        twitch.updateCustomReward("rw-1", isPaused = false)
        assertEquals("1001", server.take().url.queryParameter("broadcaster_id"))
    }
}
