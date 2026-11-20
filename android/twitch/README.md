# :twitch

Twitch sign-in, stream key, ingest URL, chat, alerts and mod chat commands for Fullbars.
The user taps **Continue with Twitch** and never sees or pastes a stream key.

The core logic is plain Kotlin on OkHttp, kotlinx.serialization and coroutines. The only Android-specific piece is
`BuildConfig.TWITCH_CLIENT_ID`. Everything runs in JVM unit tests against MockWebServer.

## One-time setup: register a Twitch application

1. Sign in at <https://dev.twitch.tv/console>. Twitch requires two-factor authentication on the account.
2. **Applications → Register Your Application**:
   - **Name**: anything unique, e.g. `Fullbars for yourchannel`. Users see this name on their Twitch *Connections* page.
   - **OAuth Redirect URLs**: the device code flow never redirects. The form still asks for a URL, so enter
     `http://localhost`.
   - **Category**: *Broadcasting Suite*. Any category works technically.
   - **Client Type**: **Public**. That means no client secret is shipped in the APK. Public clients can only get
     tokens through the device code flow, and they refresh without a secret.
3. Open the app's **Manage** page and copy the **Client ID**. Do not create a client secret; this app never uses one.
4. Add the Client ID to `android/local.properties`. That file is not committed:

   ```properties
   twitch.clientId=abcdefghijklmnopqrstuvwxyz0123
   ```

5. Rebuild. The value becomes `BuildConfig.TWITCH_CLIENT_ID`, and `TwitchConfig.fromBuildConfig()` reads it. If it is
   empty, every network call throws `TwitchException.MissingClientId` before sending anything.

## API surface (package `app.irlsupreme.twitch`)

```kotlin
// Wiring
TwitchConfig(clientId, scopes = DEFAULT_SCOPES, oauthBaseUrl, helixBaseUrl, ingestListUrl, eventSubUrl)  // URLs injectable
TwitchConfig.fromBuildConfig()
TwitchClient(config, tokenStore, http = TwitchHttp.defaultClient())   // facade: .auth .session .helix .ingest .chat
  suspend fun prepareBroadcast(bandwidthTest = false): BroadcastSetup  // user + channel + ingest + PublishUrl

// Auth (Device Code Grant Flow, Public client)
class TwitchAuth(config, http, clock)
  suspend fun startDeviceFlow(scopes = config.scopes): DeviceCode     // .userCode, .verificationUri, .expiresAtEpochMs
  suspend fun pollForToken(code: DeviceCode): TwitchToken             // handles pending / slow_down / expired / denied
  suspend fun refresh(token: TwitchToken): TwitchToken                // no client secret
  suspend fun validate(token: TwitchToken): TokenValidation
  suspend fun revoke(token: TwitchToken)
interface TokenStore { suspend fun load(): TwitchToken?; suspend fun save(token); suspend fun clear() }  // app: encrypted
class TwitchSession(auth, store) : AccessTokenProvider
  suspend fun completeSignIn(code): TwitchToken  // poll, then validate (adds userId/login), then save
  suspend fun current(); suspend fun refreshAfterUnauthorized(stale); suspend fun validate(); suspend fun signOut()
suspend fun <T> AccessTokenProvider.withToken(block: suspend (TwitchToken) -> T): T   // on 401: refresh once, retry

// Helix
class HelixClient(config, http)
  suspend fun getCurrentUser(token): TwitchUser
  suspend fun getStreamKey(token, broadcasterId): StreamKey           // toString() redacted
  suspend fun getChannelInfo(token, broadcasterId): ChannelInfo       // title, category, language, tags
  suspend fun updateChannel(token, broadcasterId, title?, categoryId?)
  suspend fun sendChatMessage(token, broadcasterId, senderId, message, replyParentMessageId?): SendChatResult
  suspend fun createEventSubSubscription(token, type, version, condition, sessionId): String?

// Ingest
fun buildPublishUrl(streamKey, ingest = IngestServer.GLOBAL, bandwidthTest = false): PublishUrl  // .url .serverUrl .streamName
class TwitchIngest(config, http) { suspend fun listIngests(); suspend fun pickIngest(): IngestServer }

// Chat (EventSub WebSocket)
class TwitchChatClient(config, helix, tokens, http, options = ChatOptions())
  fun messages(broadcasterId, readerUserId = broadcasterId): Flow<ChatMessage>
  val state: StateFlow<ChatConnectionState>
  suspend fun sendChatMessage(broadcasterId, senderId, message, replyParentMessageId?): SendChatResult

// Mod commands (pure)
ChatCommandParser.parse(message: ChatMessage, policy = CommandPolicy()): CommandResult
  // Accepted(ChatCommand.Brb|Live|Privacy|End|Quality(preset)|RestartCam) | NotAllowed | Invalid(hint) | NotACommand
```

### Errors

Every failure is a `TwitchException`:

- `Unauthorized`: HTTP 401. Call `refresh`, or let `withToken` refresh and retry for you.
- `SignInRequired`: refresh failed. Run the device flow again. `TwitchSession` has already cleared the store.
- `DeviceCodeExpired`, `AccessDenied`.
- `Forbidden`: HTTP 403. For Get Stream Key, its `serverMessage` names a setup step the user still has to do; show it.
- `RateLimited`, `Api`, `Network`, `Protocol`.
- `SubscriptionRevoked`: chat permission was revoked.
- `MissingClientId`.

### Typical flow

```kotlin
val twitch = TwitchClient(TwitchConfig.fromBuildConfig(), encryptedStore)
val code = twitch.auth.startDeviceFlow()
// UI: "Go to twitch.tv/activate and enter ${code.userCode}", or open code.verificationUri (it already carries the code)
twitch.session.completeSignIn(code)
val setup = twitch.prepareBroadcast(bandwidthTest = isTestStream)
engine.publish(setup.publishUrl.url)   // or serverUrl + streamName for clients that take them separately
twitch.chat.messages(setup.user.id).collect { msg ->
    when (val r = ChatCommandParser.parse(msg, policy)) { is CommandResult.Accepted -> ...; else -> Unit }
}
```

Twitch requires apps to validate tokens at startup and then hourly while in use. Call `session.validate()` on that
schedule.

## Security notes

- There is no HTTP logging interceptor, and none should be added. Requests carry bearer tokens, and responses carry
  stream keys.
- `TwitchToken`, `StreamKey`, `PublishUrl` and `DeviceCode` all redact secrets in `toString()`.
- `buildPublishUrl` rejects stream keys containing anything other than `[A-Za-z0-9_-]`.
- Messages relayed by Shared Chat from other channels are never accepted as commands.
- The EventSub socket never sends frames and has `pingInterval(0)`. Twitch closes the socket with code 4001 on any
  inbound traffic other than pongs.

## What was checked against Twitch's docs (November 2026)

| Topic | Verified | Source |
|---|---|---|
| Device flow: `POST /oauth2/device` (`client_id`, `scopes`) returns `device_code`, `user_code`, `verification_uri`, `expires_in` (1800), `interval` (5). Poll `POST /oauth2/token` with `grant_type=urn:ietf:params:oauth:grant-type:device_code`. Errors are `{"status":400,"message":"authorization_pending"}` and `"invalid device code"`. Public clients don't need a secret and can only use the device flow. Access tokens last about 4 h. | yes | [Getting OAuth tokens: device code flow](https://dev.twitch.tv/docs/authentication/getting-tokens-oauth/#device-code-grant-flow) |
| `slow_down` / `expired_token` | **not documented by Twitch.** Handled per RFC 8628 (`slow_down` adds 5 s), plus a local `expires_in` deadline | [RFC 8628 §3.5](https://www.rfc-editor.org/rfc/rfc8628#section-3.5) |
| Refresh: `grant_type=refresh_token`, `client_secret` not required for Public clients. Public-client refresh tokens expire **30 days after they are generated** (the device-flow page says 30 days of inactivity) and are single-use, so always store the new one. A 400 or 401 means sign in again. | yes | [Refreshing access tokens](https://dev.twitch.tv/docs/authentication/refresh-tokens/) |
| Validate: `GET /oauth2/validate` with an `OAuth` (or `Bearer`) header. Returns `client_id`, `login`, `user_id`, `scopes`, `expires_in`. A 401 returns `invalid access token`. | yes | [Validating tokens](https://dev.twitch.tv/docs/authentication/validate-tokens/) |
| Revoke: `POST /oauth2/revoke` with `client_id` and `token` | yes | [Revoking tokens](https://dev.twitch.tv/docs/authentication/revoke-tokens/) |
| Scope names `channel:read:stream_key`, `user:read:chat`, `user:write:chat`, `channel:manage:broadcast` | yes | [Scopes](https://dev.twitch.tv/docs/authentication/scopes/) |
| Get Users with no params returns the token's own user | yes | [Users reference](https://dev.twitch.tv/docs/api/reference/users) |
| Get Stream Key: `GET /helix/streams/key?broadcaster_id=` (must be the token's user). 401 for token or scope problems. 403 when the user must finish setup. | yes | [Streams reference](https://dev.twitch.tv/docs/api/reference/streams) |
| Get/Modify Channel Information. PATCH needs `channel:manage:broadcast`, returns 204, and limits the title to 140 characters. | yes | [Channels reference](https://dev.twitch.tv/docs/api/reference/channels) |
| Send Chat Message: `POST /helix/chat/messages` with `user:write:chat`, at most 500 characters. Returns `is_sent` and `drop_reason`. | yes | [Chat reference](https://dev.twitch.tv/docs/api/reference/chat) |
| Create EventSub Subscription: WebSocket transport takes `session_id`, needs a user token, returns 202. 409 means a duplicate. | yes | [EventSub API reference](https://dev.twitch.tv/docs/api/reference/eventsub) |
| EventSub WebSocket: `wss://eventsub.wss.twitch.tv/ws` with `keepalive_timeout_seconds` (10–600). Subscribe within 10 s of the welcome. Covers keepalive, reconnect (keep the old socket until the new welcome; subscriptions carry over), revocation and close codes 4000–4007. | yes | [Handling WebSocket events](https://dev.twitch.tv/docs/eventsub/handling-websocket-events/) |
| `channel.chat.message` v1: condition `broadcaster_user_id` + `user_id`, scope `user:read:chat`. Event fields: chatter, message, color, badges, reply, `source_*`. | yes | [EventSub chat reference](https://dev.twitch.tv/docs/eventsub/reference/chat) |
| Ingests: `GET https://ingest.twitch.tv/ingests` (no auth). Each entry has `url_template` and `url_template_secure` (`rtmps://…contribute.live-video.net/app/{stream_key}`). The "Default" entry is `ingest.global-contribute.live-video.net`. | yes (live endpoint checked) | [Ingest reference](https://dev.twitch.tv/docs/video-broadcast/reference/ingest) |
| Publish URL `rtmp://<ingest>/app/<stream-key>[?bandwidthtest=true]`. The bandwidth test "disables live viewing". | yes | [Video broadcast overview](https://dev.twitch.tv/docs/video-broadcast/) |
| RTMPS on port 443 | **assumed.** The docs give `rtmps://` templates without a port, and 443 is the RTMPS default, so it is made explicit | |
| OAuth redirect URL / client type on the registration form | **partly assumed.** The registration page doesn't mention client types or the device flow; Public client behavior comes from the device-flow page | [Registering your app](https://dev.twitch.tv/docs/authentication/register-app/) |
