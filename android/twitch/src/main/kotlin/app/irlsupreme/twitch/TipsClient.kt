package app.irlsupreme.twitch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Tips for on-stream alerts, from the two services streamers use for them. Only tips: Twitch's own events come
 * from Twitch ([TwitchChatClient.alerts]), so taking them from these too would show each twice.
 *
 *  - **StreamElements** ([streamElements]): the Astro gateway (`wss://astro.streamelements.com`), topic `channel.tips`,
 *    authorised with the channel's JWT from the StreamElements dashboard. The server pings and OkHttp answers; OkHttp
 *    also pings every 30 s so a dead socket is noticed.
 *  - **Streamlabs** ([streamlabs]): the Socket API (socket.io v2 over WebSocket) with the account's Socket API token;
 *    `donation` events. The client pings every `pingInterval`.
 *
 * Both flows are cold, reconnect with backoff, and end with [TwitchException.Unauthorized] when the token is refused.
 */
class TipsClient(
    http: OkHttpClient = TwitchHttp.defaultClient(),
    private val streamElementsUrl: String = "wss://astro.streamelements.com/",
    private val streamlabsUrl: String = "wss://sockets.streamlabs.com/socket.io/",
) {
    private val wsHttp: OkHttpClient = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    /**
     * StreamElements only: OkHttp sends its own WebSocket pings and fails the socket when a pong is missed, so a dead
     * link is noticed within about a minute. Server pings are answered inside OkHttp and never reach `onMessage`, so
     * before this a tip-less channel hit the text-read timeout every 2 minutes and dropped the socket (tips sent while it
     * was down were lost). Streamlabs keeps [wsHttp]: its socket.io client pings in text already.
     */
    private val seHttp: OkHttpClient = wsHttp.newBuilder().pingInterval(SE_PING_S, TimeUnit.SECONDS).build()

    fun streamElements(jwt: String): Flow<TwitchAlert.Tip> = channelFlow {
        var reconnectToken: String? = null
        retrying {
            val url = streamElementsUrl + (reconnectToken?.let { "?reconnect_token=$it" } ?: "")
            val conn = open(url, seHttp)
            try {
                var subscribed = reconnectToken != null // a reconnect restores subscriptions
                reconnectToken = null
                while (true) {
                    when (val m = TipParsers.streamElements(conn.receiveText(READ_TIMEOUT_MS, "StreamElements went quiet"))) {
                        is TipParsers.SeMessage.Welcome -> if (!subscribed) {
                            conn.send(TipParsers.seSubscribe(jwt).toString())
                            subscribed = true
                        }
                        is TipParsers.SeMessage.Error -> if (m.code == "err_unauthorized") throw TwitchException.Unauthorized("StreamElements refused the token") else throw IOException(m.code)
                        is TipParsers.SeMessage.Tip -> send(m.tip)
                        is TipParsers.SeMessage.Reconnect -> { reconnectToken = m.token; return@retrying }
                        TipParsers.SeMessage.Other -> Unit
                    }
                }
            } finally {
                conn.close()
            }
        }
    }

    fun streamlabs(socketToken: String): Flow<TwitchAlert.Tip> = channelFlow {
        retrying {
            val conn = open("$streamlabsUrl?token=$socketToken&EIO=3&transport=websocket")
            try {
                var pingMs = 25_000L
                var lastPong = System.currentTimeMillis()
                while (true) {
                    val text = try {
                        conn.receiveText(pingMs, "ping")
                    } catch (e: ConnectionLost) {
                        if (e.message != "ping") throw e
                        if (System.currentTimeMillis() - lastPong > pingMs * 2 + 10_000) throw ConnectionLost("Streamlabs stopped answering")
                        conn.send("2") // socket.io v2: the client pings
                        continue
                    }
                    when (val f = TipParsers.streamlabs(text)) {
                        is TipParsers.SlFrame.Open -> pingMs = f.pingIntervalMs
                        TipParsers.SlFrame.Pong -> lastPong = System.currentTimeMillis()
                        is TipParsers.SlFrame.Tips -> f.tips.forEach { send(it) }
                        TipParsers.SlFrame.Unauthorized -> throw TwitchException.Unauthorized("Streamlabs refused the token")
                        TipParsers.SlFrame.Other -> Unit
                    }
                }
            } finally {
                conn.close()
            }
        }
    }

    /** Runs [block] until it fails for good, reconnecting with backoff (1 s .. 60 s) after anything but a refusal. */
    private suspend fun ProducerScope<TwitchAlert.Tip>.retrying(block: suspend () -> Unit) {
        var attempt = 0
        while (true) {
            val startedAt = System.nanoTime()
            try {
                block()
                attempt = 0 // a planned reconnect
                continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: TwitchException.Unauthorized) {
                throw e
            } catch (_: IOException) {
            } catch (_: TwitchException) {
            }
            attempt = nextAttempt(attempt, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
            val backoff = (1_000L shl (attempt - 1).coerceIn(0, 6)).coerceAtMost(60_000)
            delay((backoff * Random.nextDouble(0.8, 1.2)).toLong())
        }
    }

    private class Conn(private val socket: WebSocket, val inner: WsConnection) {
        fun send(text: String) { socket.send(text) }
        suspend fun receiveText(timeoutMs: Long, reason: String) = inner.receiveText(timeoutMs, reason)
        fun close() = inner.close()
    }

    private fun open(url: String, client: OkHttpClient = wsHttp): Conn {
        val events = Channel<WsEvent>(Channel.UNLIMITED)
        val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { events.trySend(WsEvent.Text(text)) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                events.trySend(WsEvent.Closed(code, reason))
                webSocket.close(1000, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { events.close() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                events.trySend(WsEvent.Failed(t))
                events.close()
            }
        })
        return Conn(socket, WsConnection(socket, events))
    }

    internal companion object {
        /**
         * A backstop only: liveness on the StreamElements socket comes from [SE_PING_S]. A quiet channel sends no text
         * for long stretches, so a short timeout here tore down healthy sockets (it was 2 minutes).
         */
        const val READ_TIMEOUT_MS = 30 * 60_000L
        const val SE_PING_S = 30L

        /** A connection that stayed up this long was healthy: its drop starts the backoff over. */
        const val HEALTHY_MS = 30_000L

        /**
         * The backoff attempt after a connection that was up for [upMs] ended in an error. Without the reset a long
         * stream with a few coverage drops climbed to the 60 s cap for good, and every later drop missed a minute of
         * tips. Connections that keep failing fast still escalate.
         */
        fun nextAttempt(attempt: Int, upMs: Long): Int = if (upMs >= HEALTHY_MS) 1 else attempt + 1
    }
}

/** The two services' messages. Pure, for tests. */
internal object TipParsers {
    sealed interface SeMessage {
        data object Welcome : SeMessage
        data class Error(val code: String) : SeMessage
        data class Tip(val tip: TwitchAlert.Tip) : SeMessage
        data class Reconnect(val token: String) : SeMessage
        data object Other : SeMessage
    }

    fun seSubscribe(jwt: String): JsonObject = buildJsonObject {
        put("type", "subscribe")
        put("nonce", "fullbars-tips")
        put("data", buildJsonObject {
            put("topic", "channel.tips")
            put("token", jwt)
            put("token_type", "jwt")
        })
    }

    fun streamElements(text: String): SeMessage {
        val o = runCatching { TwitchJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return SeMessage.Other
        return when (o.str("type")) {
            "welcome" -> SeMessage.Welcome
            "reconnect" -> o.obj("data")?.str("reconnect_token")?.let { SeMessage.Reconnect(it) } ?: SeMessage.Other
            "response" -> o.str("error")?.let { SeMessage.Error(it) } ?: SeMessage.Other
            "message" -> {
                if (o.str("topic") != "channel.tips") return SeMessage.Other
                val d = o.obj("data")?.obj("donation") ?: return SeMessage.Other
                val amount = d["amount"]?.jsonPrimitive?.doubleOrNull ?: return SeMessage.Other
                val user = d.obj("user")?.str("username")?.takeIf { it.isNotBlank() } ?: "Someone"
                SeMessage.Tip(TwitchAlert.Tip("se-" + (o.str("id") ?: text.hashCode().toString()), user, money(amount, d.str("currency"))))
            }
            else -> SeMessage.Other
        }
    }

    sealed interface SlFrame {
        data class Open(val pingIntervalMs: Long) : SlFrame
        data object Pong : SlFrame
        data class Tips(val tips: List<TwitchAlert.Tip>) : SlFrame
        data object Unauthorized : SlFrame
        data object Other : SlFrame
    }

    /** socket.io v2 over Engine.IO 3: "0{open}", "3" pong, "42[event, payload]", "44..." error. */
    fun streamlabs(text: String): SlFrame = when {
        text.startsWith("0{") -> runCatching {
            SlFrame.Open(TwitchJson.parseToJsonElement(text.substring(1)).jsonObject["pingInterval"]!!.jsonPrimitive.content.toLong())
        }.getOrDefault(SlFrame.Other)
        text == "3" -> SlFrame.Pong
        text.startsWith("44") -> SlFrame.Unauthorized
        text.startsWith("42") -> streamlabsEvent(text.substring(2))
        else -> SlFrame.Other
    }

    private fun streamlabsEvent(json: String): SlFrame {
        val arr = runCatching { TwitchJson.parseToJsonElement(json).jsonArray }.getOrNull() ?: return SlFrame.Other
        if ((arr.getOrNull(0) as? JsonPrimitive)?.contentOrNull != "event") return SlFrame.Other
        val e = arr.getOrNull(1) as? JsonObject ?: return SlFrame.Other
        if (e.str("type") != "donation") return SlFrame.Other
        val list = (e["message"] as? JsonArray).orEmpty().mapNotNull { m ->
            val d = m as? JsonObject ?: return@mapNotNull null
            val shown = d.str("formatted_amount")?.takeIf { it.isNotBlank() }
                ?: d["amount"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.let { money(it, d.str("currency")) }
                ?: return@mapNotNull null
            val id = d.str("_id") ?: d["id"]?.jsonPrimitive?.contentOrNull ?: (shown + d.str("name")).hashCode().toString()
            TwitchAlert.Tip("sl-$id", d.str("name")?.takeIf { it.isNotBlank() } ?: "Someone", shown)
        }
        return if (list.isEmpty()) SlFrame.Other else SlFrame.Tips(list)
    }

    /** "$4.20", "€5.00"; an unknown currency code as "4.20 XYZ". */
    fun money(amount: Double, currency: String?): String = runCatching {
        NumberFormat.getCurrencyInstance(Locale.US).apply { this.currency = Currency.getInstance(currency ?: "USD") }.format(amount)
    }.getOrElse { "%.2f %s".format(Locale.US, amount, currency.orEmpty()).trim() }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
}
