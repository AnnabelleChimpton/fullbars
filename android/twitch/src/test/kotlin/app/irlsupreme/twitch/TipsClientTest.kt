package app.irlsupreme.twitch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class TipsClientTest {
    // ---- StreamElements (Astro) ----

    private val seTip = """{"id":"01HTIP","ts":"2026-11-18T10:00:00Z","type":"message","topic":"channel.tips","room":"r1",
        "data":{"donation":{"user":{"username":"Styler","email":"x@example.com"},"message":"","amount":4.2,"currency":"USD"},"provider":"paypal"}}"""

    @Test fun `streamelements messages`() {
        assertEquals(TipParsers.SeMessage.Welcome, TipParsers.streamElements("""{"id":"1","ts":"t","type":"welcome","data":{"client_id":"c"}}"""))
        assertEquals(
            TipParsers.SeMessage.Tip(TwitchAlert.Tip("se-01HTIP", "Styler", "$4.20")),
            TipParsers.streamElements(seTip),
        )
        assertEquals(
            TipParsers.SeMessage.Reconnect("tok"),
            TipParsers.streamElements("""{"id":"2","ts":"t","type":"reconnect","data":{"message":"draining","reconnect_token":"tok"}}"""),
        )
        assertEquals(
            TipParsers.SeMessage.Error("err_unauthorized"),
            TipParsers.streamElements("""{"id":"3","ts":"t","type":"response","nonce":"n","error":"err_unauthorized","data":{"message":"bad"}}"""),
        )
        assertEquals(TipParsers.SeMessage.Other, TipParsers.streamElements("""{"type":"response","nonce":"n","data":{"message":"successfully subscribed to topic"}}"""))
        assertEquals(TipParsers.SeMessage.Other, TipParsers.streamElements("not json"))
        val sub = TipParsers.seSubscribe("jwt-x")
        assertEquals("subscribe", sub["type"]!!.jsonPrimitive.content)
        val d = sub["data"]!!.jsonObject
        assertEquals(listOf("channel.tips", "jwt-x", "jwt"), listOf("topic", "token", "token_type").map { d[it]!!.jsonPrimitive.content })
    }

    // ---- Streamlabs (socket.io v2) ----

    @Test fun `streamlabs frames`() {
        assertEquals(TipParsers.SlFrame.Open(25_000), TipParsers.streamlabs("""0{"sid":"abc","upgrades":[],"pingInterval":25000,"pingTimeout":5000}"""))
        assertEquals(TipParsers.SlFrame.Pong, TipParsers.streamlabs("3"))
        assertEquals(
            TipParsers.SlFrame.Tips(listOf(TwitchAlert.Tip("sl-96164121", "Jeff", "$5.00"))),
            TipParsers.streamlabs(
                """42["event",{"type":"donation","message":[{"id":96164121,"name":"Jeff","amount":"5.0000000000","formatted_amount":"$5.00","message":"hi","currency":"USD","from":"Jeff","_id":"96164121"}],"event_id":"e1"}]""",
            ),
        )
        assertEquals(TipParsers.SlFrame.Other, TipParsers.streamlabs("""42["event",{"type":"follow","for":"twitch_account","message":[{"name":"x"}]}]"""))
        assertEquals(TipParsers.SlFrame.Unauthorized, TipParsers.streamlabs("""44"Invalid token""""))
        assertEquals(TipParsers.SlFrame.Other, TipParsers.streamlabs("40"))
    }

    @Test fun money() {
        assertEquals("$4.20", TipParsers.money(4.2, "USD"))
        assertEquals("€5.00", TipParsers.money(5.0, "EUR"))
        assertEquals("$1.00", TipParsers.money(1.0, null))
        assertEquals("3.50 ZZQ", TipParsers.money(3.5, "ZZQ"))
    }

    @Test fun `backoff starts over after a healthy connection and escalates on fast failures`() {
        assertEquals(1, TipsClient.nextAttempt(0, 0))
        assertEquals(4, TipsClient.nextAttempt(3, 500))
        assertEquals(8, TipsClient.nextAttempt(7, TipsClient.HEALTHY_MS - 1))
        assertEquals(1, TipsClient.nextAttempt(7, TipsClient.HEALTHY_MS))
        assertEquals(1, TipsClient.nextAttempt(7, 3_600_000))
    }

    // ---- The StreamElements flow against a fake Astro ----

    private val server = MockWebServer()
    private val received = LinkedBlockingQueue<String>()

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"id":"w","ts":"t","type":"welcome","data":{"client_id":"c1"}}""")
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.add(text)
                    webSocket.send("""{"id":"r","ts":"t","type":"response","nonce":"fullbars-tips","data":{"message":"successfully subscribed to topic","topic":"channel.tips","room":"r1"}}""")
                    webSocket.send(seTip)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = Unit
            }).build()
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    @Test fun `subscribes with the jwt after the welcome, then delivers tips`() = runBlocking(Dispatchers.Default) {
        val url = server.url("/").toString().replaceFirst("http://", "ws://")
        val client = TipsClient(streamElementsUrl = url)
        val tip = withTimeout(10_000) { client.streamElements("jwt-123").first() }
        assertEquals(TwitchAlert.Tip("se-01HTIP", "Styler", "$4.20"), tip)
        val sub = TwitchJson.parseToJsonElement(received.poll(1, TimeUnit.SECONDS)!!).jsonObject
        assertEquals("jwt-123", sub["data"]!!.jsonObject["token"]!!.jsonPrimitive.content)
    }
}
