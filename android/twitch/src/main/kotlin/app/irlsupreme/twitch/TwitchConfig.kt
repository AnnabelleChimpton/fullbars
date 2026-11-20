package app.irlsupreme.twitch

/**
 * Static configuration for the Twitch integration.
 *
 * All base URLs are injectable so tests can point them at a MockWebServer.
 * [clientId] comes from `twitch.clientId` in android/local.properties (see [fromBuildConfig]); when it is blank
 * every network call fails fast with [TwitchException.MissingClientId]. Release builds refuse to build without it
 * (twitch/build.gradle.kts); a debug build without it hides sign-in and streams with a stream key ([isConfigured]).
 */
data class TwitchConfig(
    val clientId: String,
    val scopes: List<String> = DEFAULT_SCOPES,
    /** Base of the OAuth endpoints (`device`, `token`, `validate`, `revoke`). Must end with `/`. */
    val oauthBaseUrl: String = "https://id.twitch.tv/oauth2/",
    /** Base of the Helix API. Must end with `/`. */
    val helixBaseUrl: String = "https://api.twitch.tv/helix/",
    val ingestListUrl: String = "https://ingest.twitch.tv/ingests",
    val eventSubUrl: String = "wss://eventsub.wss.twitch.tv/ws",
) {
    val isConfigured: Boolean get() = clientId.isNotBlank()

    internal fun requireClientId(): String {
        if (clientId.isBlank()) throw TwitchException.MissingClientId()
        return clientId.trim()
    }

    override fun toString(): String = "TwitchConfig(clientIdSet=$isConfigured, scopes=$scopes)"

    companion object {
        /** Read an authorized user's stream key (Get Stream Key). */
        const val SCOPE_READ_STREAM_KEY = "channel:read:stream_key"
        /** Update title / category (Modify Channel Information): the stream info sheet before going live. */
        const val SCOPE_MANAGE_BROADCAST = "channel:manage:broadcast"
        /** Receive chat via EventSub `channel.chat.message`. */
        const val SCOPE_READ_CHAT = "user:read:chat"
        /** Send chat via POST /helix/chat/messages. */
        const val SCOPE_WRITE_CHAT = "user:write:chat"
        /** Follow alerts via EventSub `channel.follow` v2. Tokens from older app versions lack it: sign in again. */
        const val SCOPE_READ_FOLLOWERS = "moderator:read:followers"

        val DEFAULT_SCOPES: List<String> = listOf(
            SCOPE_READ_STREAM_KEY,
            SCOPE_MANAGE_BROADCAST,
            SCOPE_READ_CHAT,
            SCOPE_WRITE_CHAT,
            SCOPE_READ_FOLLOWERS,
        )

        /** Config using the client ID baked in at build time from local.properties `twitch.clientId`. */
        fun fromBuildConfig(): TwitchConfig = TwitchConfig(clientId = BuildConfig.TWITCH_CLIENT_ID)
    }
}
