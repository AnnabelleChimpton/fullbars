package app.irlsupreme.twitch

import java.io.IOException

/**
 * Every failure surfaced by this module. Messages never contain tokens, device codes or stream keys;
 * [serverMessage]s are Twitch's own human-readable error text.
 *
 * [message] is for logs. Show people [userMessage] instead: it is short, plain and says what to do.
 */
sealed class TwitchException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /**
     * The build has no Twitch client ID. Developers: add `twitch.clientId=<your client id>` to android/local.properties
     * and rebuild (release builds refuse to build without it; debug builds fall back to stream-key-only).
     */
    class MissingClientId : TwitchException("Twitch sign-in isn't available in this build. Use your stream key instead.")

    /** HTTP 401 from Helix or /validate: the access token expired or was revoked. Refresh, then retry. */
    class Unauthorized(val serverMessage: String?) :
        TwitchException("Twitch access token expired or revoked" + serverMessage.suffix())

    /** The refresh token is invalid/expired (public-client refresh tokens last 30 days) or there is no token: run the device flow again. */
    class SignInRequired(reason: String) : TwitchException("Twitch sign-in required: $reason")

    /** The device code expired (or was already used) before the user approved it: call startDeviceFlow() again. */
    class DeviceCodeExpired : TwitchException("Twitch activation code expired. Start sign-in again.")

    /** The user declined the authorization request. */
    class AccessDenied : TwitchException("Twitch authorization was declined.")

    /** HTTP 403. For Get Stream Key, [serverMessage] tells the user what setup step is missing; show it to them. */
    class Forbidden(val serverMessage: String?) : TwitchException("Twitch refused the request" + serverMessage.suffix())

    /** HTTP 429. [resetAtEpochSeconds] comes from the `Ratelimit-Reset` header when present. */
    class RateLimited(val resetAtEpochSeconds: Long?) : TwitchException("Twitch rate limit exceeded")

    /** Any other non-success HTTP status. */
    class Api(val status: Int, val serverMessage: String?) :
        TwitchException("Twitch API error $status" + serverMessage.suffix())

    /** Transport failure (no network, DNS, TLS, timeout). Usually worth retrying. */
    class Network(cause: IOException) : TwitchException("Network error talking to Twitch: ${cause.javaClass.simpleName}", cause)

    /** Twitch answered with something this client could not understand. */
    class Protocol(detail: String, cause: Throwable? = null) : TwitchException("Unexpected Twitch response: $detail", cause)

    /** EventSub revoked the chat subscription (`authorization_revoked`, `user_removed` or `version_removed`). */
    class SubscriptionRevoked(val reason: String) : TwitchException("Twitch chat subscription revoked: $reason")
}

/**
 * What to tell the streamer, in plain words, with the next step. Developer detail (exception class names, HTTP codes)
 * stays in [TwitchException.message] for logs: "UnknownHostException" helps nobody holding a phone on one bar.
 */
fun TwitchException.userMessage(): String = when (this) {
    is TwitchException.Network -> "Couldn't reach Twitch. Check your connection and try again."
    is TwitchException.RateLimited -> "Twitch is busy. Try again in a minute."
    is TwitchException.Api, is TwitchException.Protocol -> "Twitch had a problem. Try again in a moment."
    is TwitchException.Unauthorized, is TwitchException.SignInRequired, is TwitchException.SubscriptionRevoked ->
        "Please sign in to Twitch again."
    // Get Stream Key's 403 says which account step is missing (e.g. a verified email): that is the useful part.
    is TwitchException.Forbidden -> serverMessage?.takeIf { it.isNotBlank() } ?: "Twitch said no. Check your account on twitch.tv."
    is TwitchException.DeviceCodeExpired -> "The sign-in code expired. Tap Continue with Twitch to get a new one."
    is TwitchException.AccessDenied -> "Sign-in was cancelled on Twitch."
    is TwitchException.MissingClientId -> message ?: "Twitch sign-in isn't available in this build. Use your stream key instead."
}

/**
 * True for failures that say nothing about whether the stored sign-in is still good: no signal, Twitch busy or down,
 * or a garbled answer (a captive portal's login page, say). The app keeps the account signed in for those.
 */
val TwitchException.isTransient: Boolean
    get() = this is TwitchException.Network || this is TwitchException.RateLimited || this is TwitchException.Protocol ||
        (this is TwitchException.Api && status >= 500)

private fun String?.suffix(): String = if (isNullOrBlank()) "" else ": $this"
