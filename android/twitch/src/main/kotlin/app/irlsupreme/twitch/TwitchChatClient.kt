package app.irlsupreme.twitch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

sealed interface ChatConnectionState {
    data object Idle : ChatConnectionState
    data object Connecting : ChatConnectionState
    data object Connected : ChatConnectionState
    data class Reconnecting(val attempt: Int, val lastError: String?) : ChatConnectionState
    data class Stopped(val reason: String) : ChatConnectionState
}

data class ChatOptions(
    /** Sent as `keepalive_timeout_seconds` (Twitch accepts 10..600). */
    val keepaliveTimeoutSeconds: Int = 30,
    /** Extra slack before a silent socket is declared dead. */
    val keepaliveGraceMs: Long = 5_000,
    /** How long to wait for session_welcome after connecting. */
    val welcomeTimeoutMs: Long = 10_000,
    /** Twitch sends session_reconnect ~30 s before closing the old socket. */
    val reconnectHandoverTimeoutMs: Long = 30_000,
    val initialBackoffMs: Long = 1_000,
    val maxBackoffMs: Long = 60_000,
)

/**
 * Twitch chat over EventSub WebSocket (https://dev.twitch.tv/docs/eventsub/handling-websocket-events/).
 *
 * [messages] connects, waits for `session_welcome`, subscribes to `channel.chat.message` v1 with the session id,
 * then emits chat messages. It honours `session_keepalive` (declares the socket dead after the keepalive window),
 * migrates on `session_reconnect` (opens the new URL, keeps the old socket until the new welcome, does not
 * resubscribe), ends with [TwitchException.SubscriptionRevoked] on `revocation`, and otherwise reconnects with
 * exponential backoff. Notifications are de-duplicated by message id. The client never sends frames (Twitch
 * closes with 4001 if it does); OkHttp answers server pings automatically.
 */
class TwitchChatClient(
    private val config: TwitchConfig,
    private val helix: HelixClient,
    private val tokens: AccessTokenProvider,
    http: OkHttpClient = TwitchHttp.defaultClient(),
    private val options: ChatOptions = ChatOptions(),
) {
    private val wsHttp: OkHttpClient = http.newBuilder()
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // liveness comes from EventSub keepalives
        .build()

    private val _alerts = kotlinx.coroutines.flow.MutableSharedFlow<TwitchAlert>(extraBufferCapacity = 64)
    /** Alerts (subs, gifts, raids, cheers, follows) seen while [messages] is being collected. */
    val alerts: kotlinx.coroutines.flow.SharedFlow<TwitchAlert> = _alerts

    private val _alertSubscriptions = MutableStateFlow(AlertSubscriptions())
    /**
     * What happened to the alert subscriptions on the current chat session: for the session log, and so the app can
     * say "follow alerts are off" instead of failing silently.
     */
    val alertSubscriptions: StateFlow<AlertSubscriptions> = _alertSubscriptions.asStateFlow()

    private val _state = MutableStateFlow<ChatConnectionState>(ChatConnectionState.Idle)
    val state: StateFlow<ChatConnectionState> = _state.asStateFlow()

    /**
     * Chat for [broadcasterId], read as [readerUserId] (the signed-in user; for your own channel both are your id).
     * Cold: connects on collect, disconnects on cancel. Collect it from one place.
     * Terminates with [TwitchException.SignInRequired], [TwitchException.SubscriptionRevoked],
     * [TwitchException.Forbidden] (missing user:read:chat) or [TwitchException.MissingClientId]; everything else retries.
     */
    fun messages(broadcasterId: String, readerUserId: String = broadcasterId): Flow<ChatMessage> = channelFlow {
        config.requireClientId()
        runLoop(broadcasterId, readerUserId)
    }

    /** POST /helix/chat/messages as [senderId] (the signed-in user), refreshing the token on 401. */
    suspend fun sendChatMessage(
        broadcasterId: String,
        senderId: String,
        message: String,
        replyParentMessageId: String? = null,
    ): SendChatResult = tokens.withToken { helix.sendChatMessage(it, broadcasterId, senderId, message, replyParentMessageId) }

    private suspend fun ProducerScope<ChatMessage>.runLoop(broadcasterId: String, readerUserId: String) {
        var attempt = 0
        var lastError: String? = null
        try {
            while (true) {
                _state.value = if (attempt == 0) ChatConnectionState.Connecting else ChatConnectionState.Reconnecting(attempt, lastError)
                try {
                    runConnection(broadcasterId, readerUserId) { attempt = 0 }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TwitchException) {
                    if (e.isFatal()) {
                        _state.value = ChatConnectionState.Stopped(e.message ?: e.javaClass.simpleName)
                        throw e
                    }
                    lastError = e.message
                } catch (e: IOException) {
                    lastError = e.message
                }
                attempt++
                delay(backoffMs(attempt))
            }
        } finally {
            if (_state.value !is ChatConnectionState.Stopped) _state.value = ChatConnectionState.Idle
        }
    }

    private fun TwitchException.isFatal(): Boolean = when (this) {
        is TwitchException.SignInRequired,
        is TwitchException.SubscriptionRevoked,
        is TwitchException.Forbidden,
        is TwitchException.Unauthorized, // still 401 after a refresh
        is TwitchException.MissingClientId -> true
        else -> false
    }

    internal fun backoffMs(attempt: Int): Long {
        val exp = options.initialBackoffMs * (1L shl (attempt - 1).coerceIn(0, 16))
        val capped = exp.coerceAtMost(options.maxBackoffMs)
        return (capped * Random.nextDouble(0.8, 1.2)).toLong().coerceAtLeast(1)
    }

    private suspend fun ProducerScope<ChatMessage>.runConnection(
        broadcasterId: String,
        readerUserId: String,
        onConnected: () -> Unit,
    ) {
        val dedupe = RecentIds(512)
        var conn = open(initialUrl())
        try {
            val welcome = awaitWelcome(conn)
            var keepaliveMs = welcome.keepaliveMs()
            tokens.withToken {
                helix.createEventSubSubscription(
                    it,
                    type = CHAT_MESSAGE_TYPE,
                    version = "1",
                    condition = mapOf("broadcaster_user_id" to broadcasterId, "user_id" to readerUserId),
                    sessionId = welcome.sessionId,
                )
            }
            subscribeAlerts(broadcasterId, readerUserId, welcome.sessionId)
            _state.value = ChatConnectionState.Connected
            onConnected()
            while (true) {
                val msg = parseOrNull(conn.receiveText(keepaliveMs + options.keepaliveGraceMs, "keepalive timeout")) ?: continue
                if (msg is EventSubMessage.Reconnect) {
                    val (next, newWelcome) = handover(conn, msg.reconnectUrl, dedupe)
                    conn = next
                    keepaliveMs = newWelcome.keepaliveMs()
                } else {
                    handle(msg, dedupe)
                }
            }
        } finally {
            conn.close()
        }
    }

    /**
     * Alert subscriptions on the same session. Best effort: chat works without them. Follows only when the token
     * has moderator:read:followers (sign-ins from older app versions don't). Each is tried a few times (a busy Twitch or a flaky
     * connection shouldn't cost a whole stream of alerts), except when Twitch says no outright (401/403), and the result
     * is published in [alertSubscriptions].
     */
    private suspend fun subscribeAlerts(broadcasterId: String, readerUserId: String, sessionId: String) {
        _alertSubscriptions.value = AlertSubscriptions(SubscriptionStatus.Pending, SubscriptionStatus.Pending)
        val notifications = subscribeWithRetry {
            helix.createEventSubSubscription(
                it, type = CHAT_NOTIFICATION_TYPE, version = "1",
                condition = mapOf("broadcaster_user_id" to broadcasterId, "user_id" to readerUserId), sessionId = sessionId,
            )
        }
        _alertSubscriptions.value = _alertSubscriptions.value.copy(notifications = notifications)
        val canFollow = runCatching { tokens.withToken { it.hasScopes(listOf(TwitchConfig.SCOPE_READ_FOLLOWERS)) } }.getOrDefault(false)
        val follows = if (!canFollow) SubscriptionStatus.NeedsSignIn else subscribeWithRetry {
            helix.createEventSubSubscription(
                it, type = FOLLOW_TYPE, version = "2",
                condition = mapOf("broadcaster_user_id" to broadcasterId, "moderator_user_id" to readerUserId), sessionId = sessionId,
            )
        }
        _alertSubscriptions.value = _alertSubscriptions.value.copy(follows = follows)
    }

    private suspend fun subscribeWithRetry(create: suspend (TwitchToken) -> Unit): SubscriptionStatus {
        var last: Throwable? = null
        for (wait in SUBSCRIBE_RETRY_MS) {
            if (wait > 0) kotlinx.coroutines.delay(wait)
            val r = runCatching { tokens.withToken { create(it) } }
            if (r.isSuccess) return SubscriptionStatus.Active
            val e = r.exceptionOrNull()
            if (e is kotlinx.coroutines.CancellationException) throw e
            last = e
            if (!retryable(e)) break
        }
        return SubscriptionStatus.Failed(last?.message ?: "unknown error")
    }

    /** Opens [url], keeps draining [old] until the new socket's welcome arrives, then closes [old]. */
    private suspend fun ProducerScope<ChatMessage>.handover(
        old: WsConnection,
        url: String,
        dedupe: RecentIds,
    ): Pair<WsConnection, EventSubMessage.Welcome> {
        val next = open(url)
        var oldOpen = true
        val welcome = try {
            withTimeoutOrNull(options.reconnectHandoverTimeoutMs) {
                var found: EventSubMessage.Welcome? = null
                while (found == null) {
                    select {
                        next.events.onReceiveCatching { result ->
                            when (val e = result.getOrNull()) {
                                is WsEvent.Text -> when (val m = parseOrNull(e.text)) {
                                    is EventSubMessage.Welcome -> found = m
                                    null, is EventSubMessage.Reconnect -> Unit
                                    else -> handle(m, dedupe)
                                }
                                else -> throw ConnectionLost("reconnect socket closed before welcome")
                            }
                        }
                        if (oldOpen) old.events.onReceiveCatching { result ->
                            when (val e = result.getOrNull()) {
                                is WsEvent.Text -> when (val m = parseOrNull(e.text)) {
                                    null, is EventSubMessage.Reconnect, is EventSubMessage.Welcome -> Unit
                                    else -> handle(m, dedupe)
                                }
                                else -> oldOpen = false
                            }
                        }
                    }
                }
                found
            }
        } catch (e: Throwable) {
            next.close()
            throw e
        }
        if (welcome == null) {
            next.close()
            throw ConnectionLost("reconnect handover timed out")
        }
        old.close()
        return next to welcome
    }

    private suspend fun ProducerScope<ChatMessage>.handle(msg: EventSubMessage, dedupe: RecentIds) {
        when (msg) {
            is EventSubMessage.Notification -> {
                if (!dedupe.add(msg.messageId)) return
                try {
                    when (msg.subscriptionType) {
                        CHAT_MESSAGE_TYPE -> {
                            val chat = EventSubParser.parseChatMessage(msg.event)
                            chat.bits?.let { _alerts.tryEmit(TwitchAlert.Cheer(chat.messageId, chat.chatterDisplayName, it)) }
                            send(chat)
                        }
                        CHAT_NOTIFICATION_TYPE -> EventSubParser.parseChatNotification(msg.messageId, msg.event)?.let { _alerts.tryEmit(it) }
                        FOLLOW_TYPE -> _alerts.tryEmit(EventSubParser.parseFollow(msg.messageId, msg.event))
                    }
                } catch (_: TwitchException.Protocol) {
                    return
                }
            }
            // Losing an alert subscription isn't worth ending chat for.
            is EventSubMessage.Revocation -> if (msg.subscriptionType == CHAT_MESSAGE_TYPE) throw TwitchException.SubscriptionRevoked(msg.status)
            is EventSubMessage.Keepalive, is EventSubMessage.Welcome, is EventSubMessage.Unknown, is EventSubMessage.Reconnect -> Unit
        }
    }

    private suspend fun awaitWelcome(conn: WsConnection): EventSubMessage.Welcome {
        val text = conn.receiveText(options.welcomeTimeoutMs, "no session_welcome")
        return EventSubParser.parse(text) as? EventSubMessage.Welcome
            ?: throw TwitchException.Protocol("first EventSub frame was not session_welcome")
    }

    private fun EventSubMessage.Welcome.keepaliveMs(): Long =
        (keepaliveTimeoutSeconds ?: options.keepaliveTimeoutSeconds).coerceAtLeast(1) * 1000L

    private fun parseOrNull(text: String): EventSubMessage? = try {
        EventSubParser.parse(text)
    } catch (_: TwitchException.Protocol) {
        null
    }

    private fun initialUrl(): String {
        val sep = if (config.eventSubUrl.contains('?')) '&' else '?'
        return "${config.eventSubUrl}${sep}keepalive_timeout_seconds=${options.keepaliveTimeoutSeconds.coerceIn(10, 600)}"
    }

    private fun open(url: String): WsConnection {
        val events = Channel<WsEvent>(Channel.UNLIMITED)
        val socket = wsHttp.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                events.trySend(WsEvent.Text(text))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                events.trySend(WsEvent.Closed(code, reason))
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                events.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                events.trySend(WsEvent.Failed(t))
                events.close()
            }
        })
        return WsConnection(socket, events)
    }

    internal companion object {
        const val CHAT_MESSAGE_TYPE = "channel.chat.message"
        const val CHAT_NOTIFICATION_TYPE = "channel.chat.notification"
        const val FOLLOW_TYPE = "channel.follow"
        /** Waits before each try: at once, then twice more spread over ~20 s. */
        internal val SUBSCRIBE_RETRY_MS = longArrayOf(0, 3_000, 15_000)

        /** Twitch saying no outright (bad token, missing permission) won't change on a retry; anything else might. */
        internal fun retryable(e: Throwable?): Boolean = when (e) {
            is TwitchException.Unauthorized, is TwitchException.Forbidden, is TwitchException.SignInRequired -> false
            is TwitchException.Api -> e.status >= 500 || e.status == 429
            else -> true
        }
    }
}

internal sealed interface WsEvent {
    data class Text(val text: String) : WsEvent
    data class Closed(val code: Int, val reason: String) : WsEvent
    data class Failed(val error: Throwable) : WsEvent
}

internal class ConnectionLost(message: String, cause: Throwable? = null) : IOException(message, cause)

internal class WsConnection(private val socket: WebSocket, val events: ReceiveChannel<WsEvent>) {
    fun close() {
        if (!socket.close(1000, null)) socket.cancel()
    }

    suspend fun receiveText(timeoutMs: Long, timeoutReason: String): String {
        val result = withTimeoutOrNull(timeoutMs) { events.receiveCatching() } ?: throw ConnectionLost(timeoutReason)
        return when (val e = result.getOrNull()) {
            is WsEvent.Text -> e.text
            is WsEvent.Closed -> throw ConnectionLost("EventSub closed the socket (${e.code})")
            is WsEvent.Failed -> throw ConnectionLost("EventSub socket failed", e.error)
            null -> throw ConnectionLost("EventSub socket closed")
        }
    }
}

/** Bounded set of recently seen EventSub message ids. */
internal class RecentIds(private val capacity: Int) {
    private val ids = LinkedHashSet<String>()
    fun add(id: String): Boolean {
        if (!ids.add(id)) return false
        if (ids.size > capacity) ids.remove(ids.first())
        return true
    }
}

/** One alert subscription's fate on the current chat session. */
sealed interface SubscriptionStatus {
    data object Pending : SubscriptionStatus
    data object Active : SubscriptionStatus
    /** The sign-in lacks the permission (follows need moderator:read:followers): sign in to Twitch again. */
    data object NeedsSignIn : SubscriptionStatus
    data class Failed(val reason: String) : SubscriptionStatus
}

/** Subs/gifts/raids (chat notifications) and follows. */
data class AlertSubscriptions(
    val notifications: SubscriptionStatus = SubscriptionStatus.Pending,
    val follows: SubscriptionStatus = SubscriptionStatus.Pending,
)
