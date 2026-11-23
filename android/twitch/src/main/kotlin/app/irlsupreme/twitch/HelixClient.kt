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

    /**
     * `GET /helix/channel_points/custom_rewards?broadcaster_id=` (scope channel:manage:redemptions). With
     * [onlyManageable], only the rewards this app's Client ID created: the only ones it may update or refund.
     * A 403 ([TwitchException.Forbidden]) means the channel has no channel points (not a partner or affiliate).
     */
    suspend fun getCustomRewards(token: TwitchToken, broadcasterId: String, onlyManageable: Boolean = true): List<CustomReward> {
        val result = get(
            token, "channel_points/custom_rewards",
            "broadcaster_id" to broadcasterId, "only_manageable_rewards" to onlyManageable.toString(),
        )
        val body = decode("custom rewards") { TwitchJson.decodeFromString(DataList.serializer(CustomRewardDto.serializer()), result.body) }
        return body.data.map { it.toReward() }
    }

    /**
     * `POST /helix/channel_points/custom_rewards?broadcaster_id=` (scope channel:manage:redemptions). Redemptions
     * wait in the request queue (unfulfilled), so they can be refunded. [globalCooldownSec] 0 turns the cooldown off.
     * Twitch's create call takes no paused flag, so [isPaused] is a second request (a PATCH) right after.
     * Twitch answers 400 when the channel already has a reward with this [title], 403 without channel points.
     */
    suspend fun createCustomReward(
        token: TwitchToken,
        broadcasterId: String,
        title: String,
        cost: Int,
        prompt: String? = null,
        userInputRequired: Boolean = true,
        globalCooldownSec: Int = 0,
        isPaused: Boolean = false,
    ): CustomReward {
        require(title.isNotBlank() && title.length <= MAX_REWARD_TITLE_LENGTH) { "Reward title must be 1..$MAX_REWARD_TITLE_LENGTH characters" }
        require(cost >= 1) { "Reward cost must be at least 1" }
        requireCooldown(globalCooldownSec)
        if (prompt != null) requirePrompt(prompt)
        val json = buildJsonObject {
            put("title", title)
            put("cost", cost)
            if (prompt != null) put("prompt", prompt)
            put("is_enabled", true)
            put("is_user_input_required", userInputRequired)
            put("is_global_cooldown_enabled", globalCooldownSec > 0)
            if (globalCooldownSec > 0) put("global_cooldown_seconds", globalCooldownSec)
            put("should_redemptions_skip_request_queue", false)
        }
        val request = authed(token, url("channel_points/custom_rewards", "broadcaster_id" to broadcasterId))
            .post(json.toString().toRequestBody(JSON))
            .build()
        val created = rewardFrom(http.execute(request), "create custom reward")
        return if (isPaused) updateCustomReward(token, broadcasterId, created.id, isPaused = true) else created
    }

    /**
     * `PATCH /helix/channel_points/custom_rewards?broadcaster_id=&id=` (scope channel:manage:redemptions). Pass only
     * what changes; [globalCooldownSec] 0 turns the cooldown off. Returns the reward as Twitch now has it. Only works
     * on rewards this app created; a 404 means the reward is gone (deleted on Twitch).
     */
    suspend fun updateCustomReward(
        token: TwitchToken,
        broadcasterId: String,
        rewardId: String,
        isPaused: Boolean? = null,
        cost: Int? = null,
        globalCooldownSec: Int? = null,
        prompt: String? = null,
        isEnabled: Boolean? = null,
    ): CustomReward {
        require(isPaused != null || cost != null || globalCooldownSec != null || prompt != null || isEnabled != null) { "Nothing to update" }
        if (cost != null) require(cost >= 1) { "Reward cost must be at least 1" }
        if (globalCooldownSec != null) requireCooldown(globalCooldownSec)
        if (prompt != null) requirePrompt(prompt)
        val json = buildJsonObject {
            if (isPaused != null) put("is_paused", isPaused)
            if (cost != null) put("cost", cost)
            if (globalCooldownSec != null) {
                put("is_global_cooldown_enabled", globalCooldownSec > 0)
                if (globalCooldownSec > 0) put("global_cooldown_seconds", globalCooldownSec)
            }
            if (prompt != null) put("prompt", prompt)
            if (isEnabled != null) put("is_enabled", isEnabled)
        }
        val request = authed(token, url("channel_points/custom_rewards", "broadcaster_id" to broadcasterId, "id" to rewardId))
            .patch(json.toString().toRequestBody(JSON))
            .build()
        return rewardFrom(http.execute(request), "update custom reward")
    }

    /**
     * `PATCH /helix/channel_points/custom_rewards/redemptions?id=&broadcaster_id=&reward_id=` (scope
     * channel:manage:redemptions): [RedemptionStatus.FULFILLED] keeps the viewer's points, [RedemptionStatus.CANCELED]
     * refunds them. Only an unfulfilled redemption of a reward this app created can change; otherwise Twitch answers 404.
     */
    suspend fun updateRedemptionStatus(
        token: TwitchToken,
        broadcasterId: String,
        rewardId: String,
        redemptionId: String,
        status: RedemptionStatus,
    ) {
        val json = buildJsonObject { put("status", status.name) }
        val request = authed(
            token,
            url("channel_points/custom_rewards/redemptions", "id" to redemptionId, "broadcaster_id" to broadcasterId, "reward_id" to rewardId),
        ).patch(json.toString().toRequestBody(JSON)).build()
        val result = http.execute(request)
        if (!result.isSuccessful) result.throwHelixError()
    }

    /**
     * `GET /helix/moderation/blocked_terms?broadcaster_id=&moderator_id=` (scope moderator:read:blocked_terms), every
     * page. [moderatorId] is the token's user (the broadcaster, for your own channel). Returns the terms' text.
     */
    suspend fun getBlockedTerms(token: TwitchToken, broadcasterId: String, moderatorId: String): List<String> {
        val terms = mutableListOf<String>()
        var cursor: String? = null
        repeat(MAX_BLOCKED_TERM_PAGES) {
            val query = buildList {
                add("broadcaster_id" to broadcasterId)
                add("moderator_id" to moderatorId)
                add("first" to "100")
                cursor?.let { add("after" to it) }
            }
            val result = get(token, "moderation/blocked_terms", *query.toTypedArray())
            val body = decode("blocked terms") { TwitchJson.decodeFromString(BlockedTermsDto.serializer(), result.body) }
            body.data.mapNotNullTo(terms) { it.text.trim().ifEmpty { null } }
            val next = body.pagination?.cursor?.takeIf { it.isNotEmpty() }
            if (next == null || next == cursor) return terms
            cursor = next
        }
        return terms
    }

    private fun rewardFrom(result: HttpResult, what: String): CustomReward {
        if (!result.isSuccessful) result.throwHelixError()
        val body = decode(what) { TwitchJson.decodeFromString(DataList.serializer(CustomRewardDto.serializer()), result.body) }
        return body.data.firstOrNull()?.toReward() ?: throw TwitchException.Protocol("$what response was empty")
    }

    private fun requireCooldown(seconds: Int) =
        require(seconds in 0..MAX_REWARD_COOLDOWN_SEC) { "Global cooldown must be 0..$MAX_REWARD_COOLDOWN_SEC seconds" }

    private fun requirePrompt(prompt: String) =
        require(prompt.length <= MAX_REWARD_PROMPT_LENGTH) { "Reward prompt must be at most $MAX_REWARD_PROMPT_LENGTH characters" }

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
        const val MAX_REWARD_TITLE_LENGTH = 45
        const val MAX_REWARD_PROMPT_LENGTH = 200
        /** Seven days, Twitch's longest global cooldown. */
        const val MAX_REWARD_COOLDOWN_SEC = 604_800
        /** 100 terms a page: plenty for any channel, and a stop if Twitch ever kept handing back cursors. */
        internal const val MAX_BLOCKED_TERM_PAGES = 50
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

/**
 * A channel points reward. [globalCooldownSec] is 0 when the cooldown is off. [isPaused]: viewers see it but can't
 * redeem it; [isEnabled] false hides it.
 */
data class CustomReward(
    val id: String,
    val title: String,
    val cost: Int,
    val isPaused: Boolean,
    val isEnabled: Boolean,
    val globalCooldownSec: Int,
    val prompt: String = "",
    val isUserInputRequired: Boolean = false,
)

/** What to do with a redemption waiting in the reward's queue: keep the points, or refund them. */
enum class RedemptionStatus { FULFILLED, CANCELED }

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
internal data class CustomRewardDto(
    val id: String,
    val title: String = "",
    val cost: Int = 0,
    val prompt: String? = null,
    @SerialName("is_paused") val isPaused: Boolean = false,
    @SerialName("is_enabled") val isEnabled: Boolean = true,
    @SerialName("is_user_input_required") val isUserInputRequired: Boolean = false,
    @SerialName("global_cooldown_setting") val globalCooldown: GlobalCooldownDto? = null,
) {
    fun toReward() = CustomReward(
        id = id,
        title = title,
        cost = cost,
        isPaused = isPaused,
        isEnabled = isEnabled,
        globalCooldownSec = globalCooldown?.takeIf { it.isEnabled }?.seconds ?: 0,
        prompt = prompt.orEmpty(),
        isUserInputRequired = isUserInputRequired,
    )
}

@Serializable
internal data class GlobalCooldownDto(
    @SerialName("is_enabled") val isEnabled: Boolean = false,
    @SerialName("global_cooldown_seconds") val seconds: Int = 0,
)

@Serializable
internal data class BlockedTermsDto(val data: List<BlockedTermDto> = emptyList(), val pagination: PaginationDto? = null)

@Serializable
internal data class BlockedTermDto(val text: String = "")

@Serializable
internal data class PaginationDto(val cursor: String? = null)

@Serializable
internal data class SubscriptionDto(val id: String, val status: String? = null)
