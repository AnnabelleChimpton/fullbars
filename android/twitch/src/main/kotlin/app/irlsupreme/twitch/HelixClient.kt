package app.irlsupreme.twitch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Thin Helix client. Every call sends `Client-Id` + `Authorization: Bearer`, and maps 401 to
 * [TwitchException.Unauthorized] (wrap calls in [AccessTokenProvider.withToken] to refresh and retry automatically).
 * Reference: https://dev.twitch.tv/docs/api/reference/
 */
class HelixClient(
    private val config: TwitchConfig,
    private val http: OkHttpClient = TwitchHttp.defaultClient(),
) {
    /** `GET /helix/users` with no parameters returns the user who owns the token. */
    suspend fun getCurrentUser(token: TwitchToken): TwitchUser {
        val result = get(token, "users")
        val body = decode("users") { TwitchJson.decodeFromString(DataList.serializer(UserDto.serializer()), result.body) }
        val user = body.data.firstOrNull() ?: throw TwitchException.Protocol("users response was empty")
        return TwitchUser(user.id, user.login, user.displayName, user.profileImageUrl, user.broadcasterType)
    }

    /**
     * `GET /helix/streams/key?broadcaster_id=` (scope channel:read:stream_key). broadcasterId must be the token's user.
     * A 403 ([TwitchException.Forbidden]) means the user must finish account setup before streaming; show its message.
     */
    suspend fun getStreamKey(token: TwitchToken, broadcasterId: String): StreamKey {
        val result = get(token, "streams/key", "broadcaster_id" to broadcasterId)
        val body = decode("stream key") { TwitchJson.decodeFromString(DataList.serializer(StreamKeyDto.serializer()), result.body) }
        val key = body.data.firstOrNull()?.streamKey ?: throw TwitchException.Protocol("stream key response was empty")
        return StreamKey(key)
    }

    /** `GET /helix/channels?broadcaster_id=` — title, category and language. */
    suspend fun getChannelInfo(token: TwitchToken, broadcasterId: String): ChannelInfo {
        val result = get(token, "channels", "broadcaster_id" to broadcasterId)
        val body = decode("channel") { TwitchJson.decodeFromString(DataList.serializer(ChannelDto.serializer()), result.body) }
        val c = body.data.firstOrNull() ?: throw TwitchException.Protocol("channel response was empty")
        return ChannelInfo(
            broadcasterId = c.broadcasterId,
            broadcasterLogin = c.broadcasterLogin,
            broadcasterName = c.broadcasterName,
            language = c.broadcasterLanguage,
            categoryId = c.gameId,
            categoryName = c.gameName,
            title = c.title,
            tags = c.tags.orEmpty(),
        )
    }

    /**
     * `GET /helix/search/categories?query=&first=` — categories (games) whose name matches [query], best match first.
     * Needs no scope. Blank queries return nothing without a request.
     */
    suspend fun searchCategories(token: TwitchToken, query: String, first: Int = 8): List<TwitchCategory> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val result = get(token, "search/categories", "query" to q, "first" to first.coerceIn(1, 100).toString())
        val body = decode("categories") { TwitchJson.decodeFromString(DataList.serializer(CategoryDto.serializer()), result.body) }
        return body.data.map { TwitchCategory(it.id, it.name) }
    }

    /**
     * `PATCH /helix/channels?broadcaster_id=` (scope channel:manage:broadcast). Pass only what changes.
     * Title must be non-empty and at most 140 characters; categoryId "" or "0" unsets the category.
     */
    suspend fun updateChannel(token: TwitchToken, broadcasterId: String, title: String? = null, categoryId: String? = null) {
        require(title != null || categoryId != null) { "Nothing to update" }
        if (title != null) require(title.isNotBlank() && title.length <= MAX_TITLE_LENGTH) { "Title must be 1..$MAX_TITLE_LENGTH characters" }
        val json = buildJsonObject {
            if (title != null) put("title", title)
            if (categoryId != null) put("game_id", categoryId)
        }
        val request = authed(token, url("channels", "broadcaster_id" to broadcasterId))
            .patch(json.toString().toRequestBody(JSON))
            .build()
        val result = http.execute(request)
        if (!result.isSuccessful) result.throwHelixError()
    }

    /** `POST /helix/chat/messages` (scope user:write:chat). senderId must be the token's user. Max 500 characters. */
    suspend fun sendChatMessage(
        token: TwitchToken,
        broadcasterId: String,
        senderId: String,
        message: String,
        replyParentMessageId: String? = null,
    ): SendChatResult {
        require(message.isNotBlank() && message.length <= MAX_CHAT_LENGTH) { "Chat message must be 1..$MAX_CHAT_LENGTH characters" }
        val json = buildJsonObject {
            put("broadcaster_id", broadcasterId)
            put("sender_id", senderId)
            put("message", message)
            if (replyParentMessageId != null) put("reply_parent_message_id", replyParentMessageId)
        }
        val request = authed(token, url("chat/messages")).post(json.toString().toRequestBody(JSON)).build()
        val result = http.execute(request)
        if (!result.isSuccessful) result.throwHelixError()
        val body = decode("send chat") { TwitchJson.decodeFromString(DataList.serializer(SendChatDto.serializer()), result.body) }
        val r = body.data.firstOrNull() ?: throw TwitchException.Protocol("send chat response was empty")
        return SendChatResult(r.messageId, r.isSent, r.dropReason?.code, r.dropReason?.message)
    }

    /**
     * `POST /helix/eventsub/subscriptions` with WebSocket transport. Returns the subscription id, or null when Twitch
     * answers 409 (an identical subscription already exists, which is fine).
     */
    suspend fun createEventSubSubscription(
        token: TwitchToken,
        type: String,
        version: String,
        condition: Map<String, String>,
        sessionId: String,
    ): String? {
        val json = buildJsonObject {
            put("type", type)
            put("version", version)
            put("condition", JsonObject(condition.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
            put("transport", buildJsonObject {
                put("method", "websocket")
                put("session_id", sessionId)
            })
        }
        val request = authed(token, url("eventsub/subscriptions")).post(json.toString().toRequestBody(JSON)).build()
        val result = http.execute(request)
        if (result.code == 409) return null
        if (!result.isSuccessful) result.throwHelixError()
        val body = decode("eventsub subscription") {
            TwitchJson.decodeFromString(DataList.serializer(SubscriptionDto.serializer()), result.body)
        }
        return body.data.firstOrNull()?.id
    }

    private suspend fun get(token: TwitchToken, path: String, vararg query: Pair<String, String>): HttpResult {
        val result = http.execute(authed(token, url(path, *query)).get().build())
        if (!result.isSuccessful) result.throwHelixError()
        return result
    }

    private fun url(path: String, vararg query: Pair<String, String>) =
        config.helixBaseUrl.resolveUrl(path).newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()

    private fun authed(token: TwitchToken, url: okhttp3.HttpUrl): Request.Builder = Request.Builder()
        .url(url)
        .header("Client-Id", config.requireClientId())
        .header("Authorization", "Bearer ${token.accessToken}")

    private inline fun <T> decode(what: String, block: () -> T): T = decodeOrThrow(what, block)

    companion object {
        const val MAX_TITLE_LENGTH = 140
        const val MAX_CHAT_LENGTH = 500
        private val JSON = "application/json".toMediaType()
    }
}

data class TwitchUser(
    val id: String,
    val login: String,
    val displayName: String,
    val profileImageUrl: String?,
    val broadcasterType: String?,
)

data class ChannelInfo(
    val broadcasterId: String,
    val broadcasterLogin: String,
    val broadcasterName: String,
    val language: String?,
    val categoryId: String?,
    val categoryName: String?,
    val title: String,
    val tags: List<String>,
)

/** A Twitch category (usually a game, or "Just Chatting", "IRL", "Travel & Outdoors"). */
data class TwitchCategory(val id: String, val name: String)

data class SendChatResult(
    val messageId: String?,
    val isSent: Boolean,
    /** e.g. msg_duplicate, user_timed_out, slow_mode, automod_held. */
    val dropReasonCode: String?,
    val dropReasonMessage: String?,
)

/** A stream key. `toString()` is redacted so it can never leak into logs or crash reports. */
class StreamKey(val value: String) {
    override fun toString(): String = "StreamKey(<redacted>)"
    override fun equals(other: Any?): Boolean = other is StreamKey && other.value == value
    override fun hashCode(): Int = value.hashCode()
}

@Serializable
internal data class DataList<T>(val data: List<T> = emptyList())

@Serializable
internal data class UserDto(
    val id: String,
    val login: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("profile_image_url") val profileImageUrl: String? = null,
    @SerialName("broadcaster_type") val broadcasterType: String? = null,
)

@Serializable
internal data class StreamKeyDto(@SerialName("stream_key") val streamKey: String)

@Serializable
internal data class ChannelDto(
    @SerialName("broadcaster_id") val broadcasterId: String,
    @SerialName("broadcaster_login") val broadcasterLogin: String,
    @SerialName("broadcaster_name") val broadcasterName: String,
    @SerialName("broadcaster_language") val broadcasterLanguage: String? = null,
    @SerialName("game_id") val gameId: String? = null,
    @SerialName("game_name") val gameName: String? = null,
    val title: String = "",
    val tags: List<String>? = null,
)

@Serializable
internal data class CategoryDto(val id: String, val name: String)

@Serializable
internal data class SendChatDto(
    @SerialName("message_id") val messageId: String? = null,
    @SerialName("is_sent") val isSent: Boolean = false,
    @SerialName("drop_reason") val dropReason: DropReasonDto? = null,
)

@Serializable
internal data class DropReasonDto(val code: String? = null, val message: String? = null)

@Serializable
internal data class SubscriptionDto(val id: String, val status: String? = null)
