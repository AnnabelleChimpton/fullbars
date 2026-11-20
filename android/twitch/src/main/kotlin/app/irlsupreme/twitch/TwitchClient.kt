package app.irlsupreme.twitch

import okhttp3.OkHttpClient

/** Everything needed to go live, fetched after sign-in. [publishUrl] embeds the stream key (redacted in toString). */
data class BroadcastSetup(
    val user: TwitchUser,
    val channel: ChannelInfo,
    val ingest: IngestServer,
    val publishUrl: PublishUrl,
)

/**
 * Convenience facade wiring the pieces together with one OkHttpClient.
 *
 * ```
 * val twitch = TwitchClient(TwitchConfig.fromBuildConfig(), encryptedTokenStore)
 * val code = twitch.auth.startDeviceFlow()          // show code.userCode / open code.verificationUri
 * twitch.session.completeSignIn(code)               // suspends until approved
 * val setup = twitch.prepareBroadcast(bandwidthTest = true)
 * engine.publish(setup.publishUrl.url)
 * twitch.chat.messages(setup.user.id).collect { ... }
 * ```
 */
class TwitchClient(
    val config: TwitchConfig,
    tokenStore: TokenStore,
    http: OkHttpClient = TwitchHttp.defaultClient(),
    clock: () -> Long = System::currentTimeMillis,
    chatOptions: ChatOptions = ChatOptions(),
) {
    val auth = TwitchAuth(config, http, clock)
    val session = TwitchSession(auth, tokenStore, clock)
    val helix = HelixClient(config, http)
    val ingest = TwitchIngest(config, http)
    val chat = TwitchChatClient(config, helix, session, http, chatOptions)

    /** Fetches user, channel info and stream key, picks an ingest and builds the RTMPS URL. */
    suspend fun prepareBroadcast(bandwidthTest: Boolean = false): BroadcastSetup {
        val (user, channel, key) = session.withToken { token ->
            val user = helix.getCurrentUser(token)
            Triple(user, helix.getChannelInfo(token, user.id), helix.getStreamKey(token, user.id))
        }
        val server = ingest.pickIngest()
        return BroadcastSetup(user, channel, server, buildPublishUrl(key, server, bandwidthTest))
    }

    /**
     * The signed-in channel's title and category, so the streamer sees what viewers will see before going public.
     * Uses the user id saved at sign-in when there is one (one request instead of two).
     */
    suspend fun channelInfo(): ChannelInfo = session.withToken { token ->
        helix.getChannelInfo(token, token.userId ?: helix.getCurrentUser(token).id)
    }

    /**
     * Changes the signed-in channel's title and/or category (scope channel:manage:broadcast, requested at sign-in).
     * Pass only what changes. Returns the channel as Twitch now reports it.
     */
    suspend fun updateChannelInfo(title: String? = null, categoryId: String? = null): ChannelInfo = session.withToken { token ->
        val id = token.userId ?: helix.getCurrentUser(token).id
        if (title != null || categoryId != null) helix.updateChannel(token, id, title, categoryId)
        helix.getChannelInfo(token, id)
    }

    /** Categories matching [query], for the stream info sheet. */
    suspend fun searchCategories(query: String): List<TwitchCategory> = session.withToken { token ->
        helix.searchCategories(token, query)
    }
}
