package app.irlsupreme.twitch

import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * OAuth for a Public client (no client secret) using Twitch's Device Code Grant Flow.
 * https://dev.twitch.tv/docs/authentication/getting-tokens-oauth/#device-code-grant-flow
 *
 * [clock] returns wall-clock epoch ms; inject a virtual clock in tests.
 */
class TwitchAuth(
    private val config: TwitchConfig,
    private val http: OkHttpClient = TwitchHttp.defaultClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Step 1: `POST /oauth2/device`. */
    suspend fun startDeviceFlow(scopes: List<String> = config.scopes): DeviceCode {
        val clientId = config.requireClientId()
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("scopes", scopes.joinToString(" "))
            .build()
        val result = http.execute(Request.Builder().url(config.oauthBaseUrl.resolveUrl("device")).post(form).build())
        if (!result.isSuccessful) throw oauthError(result)
        val body = decodeOrThrow("device code response") {
            TwitchJson.decodeFromString(DeviceCodeResponse.serializer(), result.body)
        }
        return DeviceCode(
            deviceCode = body.deviceCode,
            userCode = body.userCode,
            verificationUri = body.verificationUri,
            expiresAtEpochMs = clock() + body.expiresIn * 1000L,
            intervalSeconds = body.interval.coerceAtLeast(1),
            scopes = scopes,
        )
    }

    /**
     * Step 3: poll `POST /oauth2/token` (grant_type=urn:ietf:params:oauth:grant-type:device_code) every
     * `interval` seconds until the user approves.
     *
     * - `authorization_pending` → keep polling.
     * - `slow_down` (RFC 8628 §3.5; not documented by Twitch but handled) or 429 → interval += 5 s.
     * - `expired_token` / `invalid device code` / local expiry → [TwitchException.DeviceCodeExpired].
     * - `access_denied` → [TwitchException.AccessDenied].
     * - A dropped request ([TwitchException.Network]) → keep polling: the user is busy approving in the browser, often
     *   on the same weak signal, and one blip must not throw their sign-in away. Gives up after
     *   [MAX_NETWORK_FAILURES] failures in a row.
     *
     * Cancel the coroutine to abort.
     */
    suspend fun pollForToken(code: DeviceCode): TwitchToken {
        val clientId = config.requireClientId()
        var intervalMs = code.intervalSeconds * 1000L
        var networkFailures = 0
        while (true) {
            delay(intervalMs)
            if (clock() >= code.expiresAtEpochMs) throw TwitchException.DeviceCodeExpired()
            val form = FormBody.Builder()
                .add("client_id", clientId)
                .add("scopes", code.scopes.joinToString(" "))
                .add("device_code", code.deviceCode)
                .add("grant_type", DEVICE_CODE_GRANT)
                .build()
            val result = try {
                http.execute(Request.Builder().url(config.oauthBaseUrl.resolveUrl("token")).post(form).build())
            } catch (e: TwitchException.Network) {
                if (++networkFailures >= MAX_NETWORK_FAILURES) throw e
                continue
            }
            networkFailures = 0
            if (result.isSuccessful) return parseToken(result, previous = null)

            val err = result.errorBody()
            val reason = (err?.message ?: err?.error ?: "").lowercase()
            when {
                "authorization_pending" in reason -> Unit
                "slow_down" in reason || result.code == 429 -> intervalMs += SLOW_DOWN_STEP_MS
                "expired" in reason || "invalid device code" in reason -> throw TwitchException.DeviceCodeExpired()
                "access_denied" in reason || "denied" in reason -> throw TwitchException.AccessDenied()
                else -> throw oauthError(result)
            }
        }
    }

    /**
     * `POST /oauth2/token` with grant_type=refresh_token and no client secret (allowed for Public clients).
     * Throws [TwitchException.SignInRequired] when the refresh token is invalid, reused or expired.
     * https://dev.twitch.tv/docs/authentication/refresh-tokens/
     */
    suspend fun refresh(token: TwitchToken): TwitchToken {
        val clientId = config.requireClientId()
        val refreshToken = token.refreshToken ?: throw TwitchException.SignInRequired("no refresh token")
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()
        val result = http.execute(Request.Builder().url(config.oauthBaseUrl.resolveUrl("token")).post(form).build())
        if (result.isSuccessful) return parseToken(result, previous = token)
        if (result.code == 400 || result.code == 401) {
            throw TwitchException.SignInRequired(result.errorBody()?.message ?: "refresh rejected (${result.code})")
        }
        throw oauthError(result)
    }

    /**
     * `GET /oauth2/validate`. Twitch requires apps to validate tokens on startup and hourly while in use.
     * Throws [TwitchException.Unauthorized] on 401.
     * https://dev.twitch.tv/docs/authentication/validate-tokens/
     */
    suspend fun validate(token: TwitchToken): TokenValidation {
        config.requireClientId()
        val request = Request.Builder()
            .url(config.oauthBaseUrl.resolveUrl("validate"))
            .header("Authorization", "OAuth ${token.accessToken}")
            .get()
            .build()
        val result = http.execute(request)
        if (result.code == 401) throw TwitchException.Unauthorized(result.errorBody()?.message)
        if (!result.isSuccessful) throw oauthError(result)
        val body = decodeOrThrow("validate response") {
            TwitchJson.decodeFromString(ValidateResponse.serializer(), result.body)
        }
        return TokenValidation(body.clientId, body.login, body.userId, body.scopes.orEmpty(), body.expiresIn)
    }

    /** `POST /oauth2/revoke` (sign out). 400 "Invalid token" is treated as already revoked. */
    suspend fun revoke(token: TwitchToken) {
        val clientId = config.requireClientId()
        val form = FormBody.Builder().add("client_id", clientId).add("token", token.accessToken).build()
        val result = http.execute(Request.Builder().url(config.oauthBaseUrl.resolveUrl("revoke")).post(form).build())
        if (!result.isSuccessful && result.code != 400) throw oauthError(result)
    }

    private fun parseToken(result: HttpResult, previous: TwitchToken?): TwitchToken {
        val body = decodeOrThrow("token response") { TwitchJson.decodeFromString(TokenResponse.serializer(), result.body) }
        return TwitchToken(
            accessToken = body.accessToken,
            refreshToken = body.refreshToken ?: previous?.refreshToken,
            scopes = body.scope.toScopeList().ifEmpty { previous?.scopes.orEmpty() },
            expiresAtEpochMs = clock() + body.expiresIn * 1000L,
            userId = previous?.userId,
            login = previous?.login,
        )
    }

    private fun oauthError(result: HttpResult): TwitchException {
        val msg = result.errorBody()?.message
        return when (result.code) {
            401 -> TwitchException.Unauthorized(msg)
            403 -> TwitchException.Forbidden(msg)
            429 -> TwitchException.RateLimited(result.headers["Ratelimit-Reset"]?.toLongOrNull())
            else -> TwitchException.Api(result.code, msg)
        }
    }

    internal companion object {
        const val DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        const val SLOW_DOWN_STEP_MS = 5_000L
        /** About 25 s of dropped polls at Twitch's 5 s interval before sign-in reports a network problem. */
        const val MAX_NETWORK_FAILURES = 5
    }
}

private fun JsonElement?.toScopeList(): List<String> = when (this) {
    is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    is JsonPrimitive -> contentOrNull?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
    else -> emptyList()
}

@Serializable
internal data class DeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("expires_in") val expiresIn: Long = 1800,
    val interval: Int = 5,
)

@Serializable
internal data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 0,
    val scope: JsonElement? = null,
    @SerialName("token_type") val tokenType: String? = null,
)

@Serializable
internal data class ValidateResponse(
    @SerialName("client_id") val clientId: String,
    val login: String? = null,
    @SerialName("user_id") val userId: String? = null,
    val scopes: List<String>? = null,
    @SerialName("expires_in") val expiresIn: Long = 0,
)
