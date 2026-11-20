package app.irlsupreme.twitch

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Fake EventSub server: every WebSocket upgrade sends session_welcome; Helix subscription POSTs are recorded. */
class TwitchChatClientTest {
    private val server = MockWebServer()
    private val sockets = LinkedBlockingQueue<WebSocket>()
    private val closedByClient = LinkedBlockingQueue<Int>()
    private val subscriptions = LinkedBlockingQueue<RecordedRequest>()
    private val wsPaths = LinkedBlockingQueue<String>()
    @Volatile private var keepaliveSeconds = 30
    @Volatile private var sessionCounter = 0

    private inner class FakeEventSub(private val sessionId: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(welcome(sessionId, keepaliveSeconds))
            sockets.add(webSocket)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closedByClient.add(code)
            webSocket.close(code, null)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            error("EventSub clients must never send messages (4001)")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = Unit
    }

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/ws", "/ws-reconnect" -> {
                    wsPaths.add(request.url.toString())
                    // A reconnect URL keeps the same session (subscriptions carry over); a fresh connect gets a new one.
                    val session = if (request.url.encodedPath == "/ws") "session-${++sessionCounter}" else "session-$sessionCounter"
                    MockResponse.Builder().webSocketUpgrade(FakeEventSub(session)).build()
                }
                "/helix/eventsub/subscriptions" -> {
                    subscriptions.add(request)
                    json(202, """{"data":[{"id":"sub-1","status":"enabled","type":"channel.chat.message","version":"1","cost":0}],"total":1,"total_cost":0,"max_total_cost":10}""")
                }
                else -> json(404, "{}")
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    private fun client(tokens: AccessTokenProvider = FakeTokens()) = TwitchChatClient(
        server.config(),
        HelixClient(server.config(), testHttp()),
        tokens,
        testHttp(),
        ChatOptions(keepaliveGraceMs = 300, initialBackoffMs = 50, maxBackoffMs = 200, welcomeTimeoutMs = 2_000),
    )

    private fun <T> LinkedBlockingQueue<T>.next(): T = poll(5, TimeUnit.SECONDS) ?: error("timed out waiting")

    @Test fun welcomeSubscribeNotifyKeepaliveReconnect() = runBlocking(Dispatchers.Default) {
        withTimeout(20_000) {
            val chat = client()
            val received = Channel<ChatMessage>(Channel.UNLIMITED)
            val job = launch { chat.messages("1001").collect { received.send(it) } }

            // welcome → subscribe with that session id
            assertTrue(wsPaths.next().contains("keepalive_timeout_seconds=30"))
            val sub = subscriptions.next()
            assertEquals("Bearer access-1", sub.headers["Authorization"])
            assertEquals(CLIENT_ID, sub.headers["Client-Id"])
            val body = Json.parseToJsonElement(sub.bodyText()).jsonObject
            assertEquals("channel.chat.message", body["type"]!!.jsonPrimitive.content)
            assertEquals("1", body["version"]!!.jsonPrimitive.content)
            val condition = body["condition"]!!.jsonObject
            assertEquals("1001", condition["broadcaster_user_id"]!!.jsonPrimitive.content)
            assertEquals("1001", condition["user_id"]!!.jsonPrimitive.content)
            val transport = body["transport"]!!.jsonObject
            assertEquals("websocket", transport["method"]!!.jsonPrimitive.content)
            assertEquals("session-1", transport["session_id"]!!.jsonPrimitive.content)
            // Then the alert subscriptions on the same session (follows: the token has moderator:read:followers).
            val notif = Json.parseToJsonElement(subscriptions.next().bodyText()).jsonObject
            assertEquals("channel.chat.notification", notif["type"]!!.jsonPrimitive.content)
            assertEquals("1001", notif["condition"]!!.jsonObject["user_id"]!!.jsonPrimitive.content)
            val follow = Json.parseToJsonElement(subscriptions.next().bodyText()).jsonObject
            assertEquals("channel.follow", follow["type"]!!.jsonPrimitive.content)
            assertEquals("2", follow["version"]!!.jsonPrimitive.content)
            assertEquals("1001", follow["condition"]!!.jsonObject["moderator_user_id"]!!.jsonPrimitive.content)

            val ws1 = sockets.next()
            ws1.send(chatNotification("n-1", "hello chat"))
            val first = received.receive()
            assertEquals("hello chat", first.text)
            assertEquals("Mod_Person", first.chatterDisplayName)
            assertEquals("#9ACD32", first.color)
            assertTrue(first.isModerator)
            assertFalse(first.isBroadcaster)
            assertEquals(ChatConnectionState.Connected, chat.state.value)

            // duplicate id is dropped, keepalive is a no-op, next message flows
            ws1.send(chatNotification("n-1", "hello chat"))
            ws1.send(keepalive("k-1"))
            ws1.send(chatNotification("n-2", "second"))
            assertEquals("second", received.receive().text)

            // A viewer shares their watch streak: it comes as a chat notification and shows in chat as a notice.
            ws1.send(watchStreakNotification("n-ws", 12, "lets go"))
            val streak = received.receive()
            assertEquals("watched 12 streams in a row", streak.notice)
            assertEquals("lets go", streak.text)
            assertEquals("Kit_IRL", streak.chatterDisplayName)

            // session_reconnect → new socket, old one closed after new welcome, no resubscribe
            val reconnectUrl = server.url("/ws-reconnect").toString().replaceFirst("http://", "ws://") + "?token=opaque"
            ws1.send(reconnect("r-1", reconnectUrl))
            assertEquals(reconnectUrl.replaceFirst("ws://", "http://"), wsPaths.next())
            val ws2 = sockets.next()
            assertEquals(1000, closedByClient.next())
            ws2.send(chatNotification("n-3", "after reconnect"))
            assertEquals("after reconnect", received.receive().text)
            assertNull("subscriptions carry over on reconnect", subscriptions.poll(300, TimeUnit.MILLISECONDS))

            // Server drops the socket → backoff, fresh connect, resubscribe with the new session
            ws2.close(4000, "internal error")
            assertTrue(wsPaths.next().contains("/ws?"))
            val resub = Json.parseToJsonElement(subscriptions.next().bodyText()).jsonObject
            assertEquals("session-2", resub["transport"]!!.jsonObject["session_id"]!!.jsonPrimitive.content)
            val ws3 = sockets.next()
            ws3.send(chatNotification("n-4", "back again"))
            assertEquals("back again", received.receive().text)

            job.cancel()
        }
    }

    @Test fun missingKeepaliveTriggersReconnect() = runBlocking(Dispatchers.Default) {
        withTimeout(20_000) {
            keepaliveSeconds = 1 // welcome advertises 1 s; nothing else is sent
            val job = launch { client().messages("1001").collect { } }
            wsPaths.next()
            subscriptions.next()
            // After 1 s + grace with no frames the client declares the socket dead and reconnects.
            assertTrue(wsPaths.next().contains("/ws?"))
            job.cancel()
        }
    }

    @Test fun revocationEndsTheFlow() = runBlocking(Dispatchers.Default) {
        withTimeout(20_000) {
            val chat = client()
            val failure = CompletableDeferred<Throwable?>()
            val job = launch {
                try {
                    chat.messages("1001").collect { }
                    failure.complete(null)
                } catch (e: Throwable) {
                    failure.complete(e)
                }
            }
            subscriptions.next()
            sockets.next().send(revocation("v-1", "authorization_revoked"))
            val e = failure.await()
            assertTrue(e is TwitchException.SubscriptionRevoked)
            assertEquals("authorization_revoked", (e as TwitchException.SubscriptionRevoked).reason)
            assertTrue(chat.state.value is ChatConnectionState.Stopped)
            job.join()
        }
    }

    @Test fun subscribeRefreshesTokenOn401() = runBlocking(Dispatchers.Default) {
        withTimeout(20_000) {
            val first = CompletableDeferred<Unit>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                    "/ws" -> MockResponse.Builder().webSocketUpgrade(FakeEventSub("session-x")).build()
                    else -> {
                        subscriptions.add(request)
                        if (first.complete(Unit)) json(401, """{"status":401,"message":"Invalid OAuth token"}""")
                        else json(202, """{"data":[{"id":"sub","status":"enabled"}]}""")
                    }
                }
            }
            val tokens = FakeTokens()
            val chat = client(tokens)
            val job = launch { chat.messages("1001").collect { } }
            assertEquals("Bearer access-1", subscriptions.next().headers["Authorization"])
            assertEquals("Bearer access-2", subscriptions.next().headers["Authorization"])
            sockets.next()
            assertEquals(1, tokens.refreshes)
            job.cancel()
        }
    }

    @Test fun backoffGrowsAndCaps() {
        val chat = TwitchChatClient(TwitchConfig(CLIENT_ID), HelixClient(TwitchConfig(CLIENT_ID)), FakeTokens(),
            options = ChatOptions(initialBackoffMs = 1_000, maxBackoffMs = 60_000))
        assertTrue(chat.backoffMs(1) in 800..1_200)
        assertTrue(chat.backoffMs(3) in 3_200..4_800)
        assertTrue(chat.backoffMs(30) in 48_000..72_000)
    }

    companion object {
        fun welcome(sessionId: String, keepalive: Int) = """
            {"metadata":{"message_id":"w-$sessionId-${System.nanoTime()}","message_type":"session_welcome","message_timestamp":"2023-07-19T14:56:51.634234626Z"},
             "payload":{"session":{"id":"$sessionId","status":"connected","connected_at":"2023-07-19T14:56:51.616329898Z","keepalive_timeout_seconds":$keepalive,"reconnect_url":null}}}
        """.trimIndent()

        fun keepalive(id: String) = """
            {"metadata":{"message_id":"$id","message_type":"session_keepalive","message_timestamp":"2023-07-19T10:11:12.634234626Z"},"payload":{}}
        """.trimIndent()

        fun reconnect(id: String, url: String) = """
            {"metadata":{"message_id":"$id","message_type":"session_reconnect","message_timestamp":"2022-11-18T09:10:11.634234626Z"},
             "payload":{"session":{"id":"session-1","status":"reconnecting","keepalive_timeout_seconds":null,"reconnect_url":"$url","connected_at":"2022-11-16T10:11:12.634234626Z"}}}
        """.trimIndent()

        fun revocation(id: String, status: String) = """
            {"metadata":{"message_id":"$id","message_type":"revocation","message_timestamp":"2022-11-16T10:11:12.464757833Z","subscription_type":"channel.chat.message","subscription_version":"1"},
             "payload":{"subscription":{"id":"sub-1","status":"$status","type":"channel.chat.message","version":"1","cost":0,"condition":{"broadcaster_user_id":"1001","user_id":"1001"},"transport":{"method":"websocket","session_id":"session-1"},"created_at":"2022-11-16T10:11:12.464757833Z"}}}
        """.trimIndent()

        /** A channel.chat.notification with notice_type watch_streak, shaped like the EventSub reference's events. */
        fun watchStreakNotification(id: String, count: Int, text: String) = """
            {"metadata":{"message_id":"$id","message_type":"notification","message_timestamp":"2026-11-19T22:11:12.464757833Z","subscription_type":"channel.chat.notification","subscription_version":"1"},
             "payload":{
               "subscription":{"id":"sub-2","status":"enabled","type":"channel.chat.notification","version":"1","condition":{"broadcaster_user_id":"1001","user_id":"1001"},"transport":{"method":"websocket","session_id":"session-1"},"created_at":"2026-11-19T22:11:12.464757833Z","cost":0},
               "event":{
                 "broadcaster_user_id":"1001","broadcaster_user_login":"streamer","broadcaster_user_name":"Streamer",
                 "chatter_user_id":"3003","chatter_user_login":"kit_irl","chatter_user_name":"Kit_IRL","chatter_is_anonymous":false,
                 "color":"#FF4500","badges":[],"system_message":"Kit_IRL watched $count consecutive streams and sparked a watch streak!",
                 "message_id":"ws-$id","message":{"text":"$text","fragments":[{"type":"text","text":"$text","cheermote":null,"emote":null,"mention":null}]},
                 "notice_type":"watch_streak","watch_streak":{"streak_count":$count,"channel_points_awarded":450},
                 "sub":null,"resub":null,"sub_gift":null,"community_sub_gift":null,"raid":null,"announcement":null,
                 "source_broadcaster_user_id":null,"source_broadcaster_user_login":null,"source_broadcaster_user_name":null,"source_message_id":null,"source_badges":null
               }}}
        """.trimIndent()

        /** Shape follows the channel.chat.message example in the EventSub reference. */
        fun chatNotification(id: String, text: String) = """
            {"metadata":{"message_id":"$id","message_type":"notification","message_timestamp":"2023-11-16T10:11:12.464757833Z","subscription_type":"channel.chat.message","subscription_version":"1"},
             "payload":{
               "subscription":{"id":"sub-1","status":"enabled","type":"channel.chat.message","version":"1","condition":{"broadcaster_user_id":"1001","user_id":"1001"},"transport":{"method":"websocket","session_id":"session-1"},"created_at":"2023-11-16T10:11:12.464757833Z","cost":0},
               "event":{
                 "broadcaster_user_id":"1001","broadcaster_user_login":"streamer","broadcaster_user_name":"Streamer",
                 "chatter_user_id":"2002","chatter_user_login":"mod_person","chatter_user_name":"Mod_Person",
                 "message_id":"cc106a89-1814-919d-454c-f4f2f970aae7",
                 "message":{"text":"$text","fragments":[{"type":"text","text":"$text","cheermote":null,"emote":null,"mention":null}]},
                 "color":"#9ACD32",
                 "badges":[{"set_id":"moderator","id":"1","info":""},{"set_id":"subscriber","id":"12","info":"16"}],
                 "message_type":"text","cheer":null,"reply":null,"channel_points_custom_reward_id":null,
                 "source_broadcaster_user_id":null,"source_broadcaster_user_login":null,"source_broadcaster_user_name":null,"source_message_id":null,"source_badges":null
               }}}
        """.trimIndent()
    }
}
