package app.irlsupreme.twitch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** A chat badge, e.g. setId="moderator", id="1". */
data class ChatBadge(val setId: String, val id: String, val info: String?)

/** A piece of a chat message: plain text, or a Twitch emote (drawn from its id; [Emote.name] is its text). */
sealed interface ChatFragment {
    val text: String

    data class Text(override val text: String) : ChatFragment
    data class Emote(override val text: String, val id: String) : ChatFragment
}

/**
 * Twitch's fragments as [ChatFragment]s: emotes kept, everything else (mentions, cheermotes) as text. An emote id is
 * only trusted when it looks like one, since it ends up in an image URL and a file name.
 */
internal fun chatFragments(dto: List<FragmentDto>?): List<ChatFragment> = dto.orEmpty().map { f ->
    val id = f.emote?.id
    if (f.type == "emote" && id != null && EMOTE_ID.matches(id)) ChatFragment.Emote(f.text, id) else ChatFragment.Text(f.text)
}

private val EMOTE_ID = Regex("[A-Za-z0-9_]{1,64}")

/**
 * A cheer's own words: the message without its cheermotes ("Cheer100"), which Twitch marks as fragments of their own.
 * Without fragments, words shaped like a cheermote (letters then a number) are dropped. Null when nothing is left.
 */
internal fun cheerWords(dto: ChatTextDto): String? {
    val f = dto.fragments
    val text = if (!f.isNullOrEmpty()) f.filter { it.type != "cheermote" }.joinToString("") { it.text }
    else dto.text.split(' ').filterNot { CHEERMOTE.matches(it) }.joinToString(" ")
    return text.replace(Regex("\\s+"), " ").trim().ifEmpty { null }
}

private val CHEERMOTE = Regex("[A-Za-z]+[0-9]+")

/** One `channel.chat.message` event (https://dev.twitch.tv/docs/eventsub/reference/chat#channel-chat-message). */
data class ChatMessage(
    val messageId: String,
    val broadcasterUserId: String,
    val chatterUserId: String,
    val chatterLogin: String,
    val chatterDisplayName: String,
    /** Hex name color like "#FF0000", or null when the user never set one. */
    val color: String?,
    val text: String,
    val badges: List<ChatBadge>,
    /** text, channel_points_highlighted, channel_points_sub_only, user_intro, power_ups_*. */
    val messageType: String,
    val replyParentMessageId: String?,
    /** Non-null when the message came from another channel in a Shared Chat session. */
    val sourceBroadcasterUserId: String?,
    /** Bits cheered with this message, if any. */
    val bits: Int? = null,
    /** With [bits]: the message's own words, without the cheermotes; null when there are none. */
    val cheerMessage: String? = null,
    /** [text] in pieces, with Twitch emotes marked, so they can be drawn as images. Empty: just [text]. */
    val fragments: List<ChatFragment> = emptyList(),
    /**
     * Non-null for a Twitch notice shown in chat rather than something typed, in words that follow the name, e.g.
     * "watched 12 streams in a row" for a shared watch streak. [text] is then the viewer's own message, often empty.
     */
    val notice: String? = null,
) {
    val isBroadcaster: Boolean get() = chatterUserId == broadcasterUserId || badges.any { it.setId == "broadcaster" }
    val isModerator: Boolean get() = badges.any { it.setId == "moderator" }
    val isVip: Boolean get() = badges.any { it.setId == "vip" }
    val isSubscriber: Boolean get() = badges.any { it.setId == "subscriber" || it.setId == "founder" }

    /** True when the message was sent in a different channel and relayed by Shared Chat. */
    val isFromSharedChat: Boolean get() = sourceBroadcasterUserId != null && sourceBroadcasterUserId != broadcasterUserId
}

/**
 * Something worth an on-stream alert. From `channel.chat.notification` (subs, resubs, gift subs, raids: all with
 * the chat scope Fullbars already has), cheers in `channel.chat.message`, and `channel.follow` v2 (needs
 * moderator:read:followers). [id] is Twitch's message id, for de-duplication.
 */
sealed interface TwitchAlert {
    val id: String
    /** Who did it ("Anonymous" for anonymous gifts and cheers). */
    val user: String

    data class Follow(override val id: String, override val user: String) : TwitchAlert
    /**
     * [tier]: "1000", "2000", "3000" (or Prime); [months]: cumulative, when Twitch says (resubs). [message]: what the
     * viewer wrote with it, when they wrote something.
     */
    data class Sub(
        override val id: String,
        override val user: String,
        val tier: String,
        val prime: Boolean,
        val months: Int?,
        val message: String? = null,
    ) : TwitchAlert
    /** One person gifting [count] subs (a batch shows once, not once per recipient). */
    data class GiftSubs(override val id: String, override val user: String, val count: Int, val tier: String) : TwitchAlert
    data class Raid(override val id: String, override val user: String, val viewers: Int) : TwitchAlert
    /** [message]: the words cheered with, without the Cheer100-style tokens; null when there are none. */
    data class Cheer(override val id: String, override val user: String, val bits: Int, val message: String? = null) : TwitchAlert
    /** A tip through StreamElements or Streamlabs ([TipsClient]); [amount] is ready to show ("$4.20"). */
    data class Tip(override val id: String, override val user: String, val amount: String) : TwitchAlert
}

/**
 * A viewer redeeming Fullbars' channel points reward (`channel.channel_points_custom_reward_redemption.add`).
 * [id] is the redemption's id, for [TwitchClient.updateRedemptionStatus]; [user] is the display name; [text] is what
 * the viewer typed, trimmed (empty when the reward asks for nothing).
 */
data class Redemption(
    val id: String,
    val rewardId: String,
    val user: String,
    val userLogin: String,
    val text: String,
)

/** Parsed EventSub WebSocket frame (https://dev.twitch.tv/docs/eventsub/handling-websocket-events/). */
internal sealed interface EventSubMessage {
    val messageId: String

    data class Welcome(override val messageId: String, val sessionId: String, val keepaliveTimeoutSeconds: Int?) : EventSubMessage
    data class Keepalive(override val messageId: String) : EventSubMessage
    data class Reconnect(override val messageId: String, val reconnectUrl: String) : EventSubMessage
    data class Notification(override val messageId: String, val subscriptionType: String, val event: JsonObject) : EventSubMessage
    data class Revocation(override val messageId: String, val subscriptionType: String, val status: String) : EventSubMessage
    data class Unknown(override val messageId: String, val type: String) : EventSubMessage
}

internal object EventSubParser {
    fun parse(text: String): EventSubMessage {
        val frame = decodeOrThrow("EventSub frame") { TwitchJson.decodeFromString(FrameDto.serializer(), text) }
        val id = frame.metadata.messageId
        val payload = frame.payload
        return when (frame.metadata.messageType) {
            "session_welcome" -> {
                val session = payload.session ?: throw TwitchException.Protocol("welcome without session")
                EventSubMessage.Welcome(id, session.id, session.keepaliveTimeoutSeconds)
            }
            "session_keepalive" -> EventSubMessage.Keepalive(id)
            "session_reconnect" -> {
                val url = payload.session?.reconnectUrl ?: throw TwitchException.Protocol("reconnect without reconnect_url")
                EventSubMessage.Reconnect(id, url)
            }
            "notification" -> EventSubMessage.Notification(
                id,
                frame.metadata.subscriptionType ?: payload.subscription?.type.orEmpty(),
                payload.event ?: JsonObject(emptyMap()),
            )
            "revocation" -> EventSubMessage.Revocation(
                id,
                frame.metadata.subscriptionType ?: payload.subscription?.type.orEmpty(),
                payload.subscription?.status ?: "unknown",
            )
            else -> EventSubMessage.Unknown(id, frame.metadata.messageType)
        }
    }

    fun parseChatMessage(event: JsonObject): ChatMessage {
        val e = decodeOrThrow("channel.chat.message event") { TwitchJson.decodeFromJsonElement(ChatEventDto.serializer(), event) }
        return ChatMessage(
            messageId = e.messageId,
            broadcasterUserId = e.broadcasterUserId,
            chatterUserId = e.chatterUserId,
            chatterLogin = e.chatterUserLogin,
            chatterDisplayName = e.chatterUserName,
            color = e.color?.takeIf { it.isNotBlank() },
            text = e.message.text,
            badges = e.badges.orEmpty().map { ChatBadge(it.setId, it.id, it.info?.takeIf { i -> i.isNotBlank() }) },
            messageType = e.messageType ?: "text",
            replyParentMessageId = e.reply?.parentMessageId,
            sourceBroadcasterUserId = e.sourceBroadcasterUserId,
            bits = e.cheer?.bits?.takeIf { it > 0 },
            cheerMessage = if ((e.cheer?.bits ?: 0) > 0) cheerWords(e.message) else null,
            fragments = chatFragments(e.message.fragments).takeIf { f -> f.any { it is ChatFragment.Emote } }.orEmpty(),
        )
    }

    /**
     * A `channel.chat.notification` as an alert, or null for the kinds that aren't one (announcements, upgrades, ...)
     * and for the individual gifts of a community gift batch (the batch itself is the alert).
     */
    fun parseChatNotification(messageId: String, event: JsonObject): TwitchAlert? {
        val e = decodeOrThrow("channel.chat.notification event") { TwitchJson.decodeFromJsonElement(ChatNotificationDto.serializer(), event) }
        val who = if (e.chatterIsAnonymous == true) "Anonymous" else e.chatterUserName ?: "Someone"
        val said = e.message?.text?.trim()?.ifEmpty { null }
        return when (e.noticeType) {
            "sub" -> e.sub?.let { TwitchAlert.Sub(messageId, who, it.subTier ?: "1000", it.isPrime == true, null, said) }
            "resub" -> e.resub?.let { TwitchAlert.Sub(messageId, who, it.subTier ?: "1000", it.isPrime == true, it.cumulativeMonths, said) }
            "community_sub_gift" -> e.communitySubGift?.let { TwitchAlert.GiftSubs(messageId, who, it.total ?: 1, it.subTier ?: "1000") }
            "sub_gift" -> e.subGift?.takeIf { it.communityGiftId.isNullOrEmpty() }?.let { TwitchAlert.GiftSubs(messageId, who, 1, it.subTier ?: "1000") }
            "raid" -> e.raid?.let { TwitchAlert.Raid(messageId, it.userName ?: who, it.viewerCount ?: 0) }
            else -> null
        }
    }

    /**
     * A `channel.chat.notification` that Twitch shows in chat as a line of its own and Fullbars has no alert for, as a
     * chat message with [ChatMessage.notice] set: so far a viewer sharing their watch streak. Null for everything else.
     */
    fun parseChatNotice(messageId: String, event: JsonObject): ChatMessage? {
        val e = decodeOrThrow("channel.chat.notification event") { TwitchJson.decodeFromJsonElement(ChatNotificationDto.serializer(), event) }
        val notice = when (e.noticeType) {
            "watch_streak" -> e.watchStreak?.streakCount?.takeIf { it > 0 }?.let { "watched $it streams in a row" } ?: "shared a watch streak"
            else -> return null
        }
        val login = e.chatterUserLogin ?: return null
        val text = e.message?.text.orEmpty()
        return ChatMessage(
            messageId = e.messageId ?: messageId,
            broadcasterUserId = e.broadcasterUserId.orEmpty(),
            chatterUserId = e.chatterUserId.orEmpty(),
            chatterLogin = login,
            chatterDisplayName = e.chatterUserName ?: login,
            color = e.color?.takeIf { it.isNotBlank() },
            text = text,
            badges = e.badges.orEmpty().map { ChatBadge(it.setId, it.id, it.info?.takeIf { i -> i.isNotBlank() }) },
            messageType = e.noticeType,
            replyParentMessageId = null,
            sourceBroadcasterUserId = e.sourceBroadcasterUserId,
            fragments = chatFragments(e.message?.fragments).takeIf { f -> f.any { it is ChatFragment.Emote } }.orEmpty(),
            notice = notice,
        )
    }

    fun parseRedemption(event: JsonObject): Redemption {
        val e = decodeOrThrow("channel points redemption event") { TwitchJson.decodeFromJsonElement(RedemptionDto.serializer(), event) }
        val rewardId = e.reward?.id ?: throw TwitchException.Protocol("redemption without reward")
        return Redemption(
            id = e.id,
            rewardId = rewardId,
            user = e.userName ?: e.userLogin,
            userLogin = e.userLogin,
            text = e.userInput.orEmpty().trim(),
        )
    }

    fun parseFollow(messageId: String, event: JsonObject): TwitchAlert.Follow {
        val e = decodeOrThrow("channel.follow event") { TwitchJson.decodeFromJsonElement(FollowDto.serializer(), event) }
        return TwitchAlert.Follow(messageId, e.userName)
    }
}

@Serializable
internal data class FrameDto(val metadata: MetadataDto, val payload: PayloadDto = PayloadDto())

@Serializable
internal data class MetadataDto(
    @SerialName("message_id") val messageId: String,
    @SerialName("message_type") val messageType: String,
    @SerialName("subscription_type") val subscriptionType: String? = null,
)

@Serializable
internal data class PayloadDto(
    val session: SessionDto? = null,
    val subscription: EventSubSubscriptionDto? = null,
    val event: JsonObject? = null,
)

@Serializable
internal data class SessionDto(
    val id: String,
    val status: String? = null,
    @SerialName("keepalive_timeout_seconds") val keepaliveTimeoutSeconds: Int? = null,
    @SerialName("reconnect_url") val reconnectUrl: String? = null,
)

@Serializable
internal data class EventSubSubscriptionDto(val id: String? = null, val type: String? = null, val status: String? = null)

@Serializable
internal data class ChatEventDto(
    @SerialName("broadcaster_user_id") val broadcasterUserId: String,
    @SerialName("chatter_user_id") val chatterUserId: String,
    @SerialName("chatter_user_login") val chatterUserLogin: String,
    @SerialName("chatter_user_name") val chatterUserName: String,
    @SerialName("message_id") val messageId: String,
    val message: ChatTextDto,
    val color: String? = null,
    val badges: List<BadgeDto>? = null,
    @SerialName("message_type") val messageType: String? = null,
    val reply: ReplyDto? = null,
    @SerialName("source_broadcaster_user_id") val sourceBroadcasterUserId: String? = null,
    val cheer: CheerDto? = null,
)

@Serializable
internal data class ChatTextDto(val text: String, val fragments: List<FragmentDto>? = null)

@Serializable
internal data class FragmentDto(val type: String = "text", val text: String = "", val emote: EmoteDto? = null)

@Serializable
internal data class EmoteDto(val id: String? = null)

@Serializable
internal data class CheerDto(val bits: Int = 0)

@Serializable
internal data class ChatNotificationDto(
    @SerialName("notice_type") val noticeType: String,
    @SerialName("broadcaster_user_id") val broadcasterUserId: String? = null,
    @SerialName("chatter_user_id") val chatterUserId: String? = null,
    @SerialName("chatter_user_login") val chatterUserLogin: String? = null,
    @SerialName("chatter_user_name") val chatterUserName: String? = null,
    @SerialName("chatter_is_anonymous") val chatterIsAnonymous: Boolean? = null,
    @SerialName("message_id") val messageId: String? = null,
    val color: String? = null,
    val badges: List<BadgeDto>? = null,
    val message: ChatTextDto? = null,
    @SerialName("source_broadcaster_user_id") val sourceBroadcasterUserId: String? = null,
    @SerialName("watch_streak") val watchStreak: WatchStreakDto? = null,
    val sub: SubDto? = null,
    val resub: ResubDto? = null,
    @SerialName("sub_gift") val subGift: SubGiftDto? = null,
    @SerialName("community_sub_gift") val communitySubGift: CommunitySubGiftDto? = null,
    val raid: RaidDto? = null,
)

@Serializable
internal data class SubDto(@SerialName("sub_tier") val subTier: String? = null, @SerialName("is_prime") val isPrime: Boolean? = null)

@Serializable
internal data class ResubDto(
    @SerialName("sub_tier") val subTier: String? = null,
    @SerialName("is_prime") val isPrime: Boolean? = null,
    @SerialName("cumulative_months") val cumulativeMonths: Int? = null,
)

@Serializable
internal data class SubGiftDto(@SerialName("sub_tier") val subTier: String? = null, @SerialName("community_gift_id") val communityGiftId: String? = null)

@Serializable
internal data class CommunitySubGiftDto(val total: Int? = null, @SerialName("sub_tier") val subTier: String? = null)

@Serializable
internal data class RaidDto(@SerialName("user_name") val userName: String? = null, @SerialName("viewer_count") val viewerCount: Int? = null)

@Serializable
internal data class WatchStreakDto(
    @SerialName("streak_count") val streakCount: Int? = null,
    @SerialName("channel_points_awarded") val channelPointsAwarded: Int? = null,
)

@Serializable
internal data class RedemptionDto(
    val id: String,
    @SerialName("user_login") val userLogin: String,
    @SerialName("user_name") val userName: String? = null,
    @SerialName("user_input") val userInput: String? = null,
    val status: String? = null,
    val reward: RedemptionRewardDto? = null,
)

@Serializable
internal data class RedemptionRewardDto(val id: String)

@Serializable
internal data class FollowDto(@SerialName("user_name") val userName: String)

@Serializable
internal data class BadgeDto(@SerialName("set_id") val setId: String, val id: String, val info: String? = null)

@Serializable
internal data class ReplyDto(@SerialName("parent_message_id") val parentMessageId: String? = null)
