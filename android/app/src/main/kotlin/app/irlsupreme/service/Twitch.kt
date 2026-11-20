package app.irlsupreme.service

import android.content.Context
import app.irlsupreme.twitch.ChatMessage
import app.irlsupreme.twitch.TokenStore
import app.irlsupreme.twitch.TwitchClient
import app.irlsupreme.twitch.TwitchConfig
import app.irlsupreme.twitch.TwitchToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Twitch tokens in the Keystore-encrypted [SecretStore]; never logged. */
class SecretTokenStore(private val secrets: SecretStore) : TokenStore {
    override suspend fun load(): TwitchToken? =
        secrets.get(KEY)?.let { runCatching { TwitchToken.fromJson(it) }.getOrNull() }

    override suspend fun save(token: TwitchToken) = secrets.put(KEY, token.toJson())
    override suspend fun clear() = secrets.put(KEY, null)

    private companion object { const val KEY = "twitch_token" }
}

/** One TwitchClient per process (shares its HTTP connection pool between the UI and the stream service). */
object Twitch {
    @Volatile private var client: TwitchClient? = null

    fun get(context: Context): TwitchClient = client ?: synchronized(this) {
        client ?: TwitchClient(TwitchConfig.fromBuildConfig(), SecretTokenStore(SecretStore(context.applicationContext)))
            .also { client = it }
    }
}

/** A chat line for the live screen ticker and Chat sheet. [atMs]: when it arrived, so the ticker can let old lines go. */
data class ChatLine(
    val id: String,
    val name: String,
    val color: String?,
    val text: String,
    val isCommand: Boolean,
    val atMs: Long = System.currentTimeMillis(),
    /** [text] in pieces with Twitch emotes marked (empty: no emotes, just [text]). */
    val fragments: List<app.irlsupreme.twitch.ChatFragment> = emptyList(),
)

/** Recent chat, published by [StreamService] while live and read by the live screen. */
object ChatFeed {
    /**
     * Lines kept for the live screen's Chat sheet, where you scroll back to read what you missed while walking. The
     * ticker only shows the last few; a few hundred short lines are a few tens of KB.
     */
    const val KEEP = 200

    private val _lines = MutableStateFlow<List<ChatLine>>(emptyList())
    val lines: StateFlow<List<ChatLine>> = _lines.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    /** Short chat connection status ("Chat connected", "Chat reconnecting…"), null when not using Twitch. */
    val status: StateFlow<String?> = _status.asStateFlow()

    fun add(m: ChatMessage, isCommand: Boolean) {
        m.fragments.forEach { if (it is app.irlsupreme.twitch.ChatFragment.Emote) EmoteCache.request(it.id) }
        _lines.value = (_lines.value + ChatLine(m.messageId, m.chatterDisplayName, m.color, m.text, isCommand, fragments = m.fragments)).takeLast(KEEP)
    }

    fun setStatus(s: String?) { _status.value = s }

    fun clear() {
        _lines.value = emptyList()
        _status.value = null
    }
}
