package app.irlsupreme.twitch

import kotlinx.serialization.Serializable

/**
 * A user access token plus its refresh token. `toString()` is redacted; persist it with [toJson] through a
 * [TokenStore] backed by encrypted storage.
 *
 * Per Twitch docs: access tokens from the device flow last about 4 hours; refresh tokens issued to a Public
 * client expire 30 days after they are issued and are single-use (each refresh returns a new one).
 */
@Serializable
data class TwitchToken(
    val accessToken: String,
    val refreshToken: String?,
    val scopes: List<String>,
    /** Wall-clock expiry of [accessToken] (epoch ms), computed from `expires_in` when the token was issued. */
    val expiresAtEpochMs: Long,
    /** Filled in after the first /validate; lets callers skip a Get Users round trip. */
    val userId: String? = null,
    val login: String? = null,
) {
    fun expiresWithin(nowEpochMs: Long, marginMs: Long = 5 * 60_000L): Boolean = nowEpochMs + marginMs >= expiresAtEpochMs

    fun hasScopes(required: Collection<String>): Boolean = scopes.containsAll(required)

    fun toJson(): String = TwitchJson.encodeToString(serializer(), this)

    override fun toString(): String =
        "TwitchToken(accessToken=<redacted>, refreshToken=${if (refreshToken == null) "null" else "<redacted>"}, " +
            "scopes=$scopes, expiresAtEpochMs=$expiresAtEpochMs, userId=$userId, login=$login)"

    companion object {
        fun fromJson(json: String): TwitchToken = TwitchJson.decodeFromString(serializer(), json)
    }
}

/**
 * Persistence for the signed-in user's token. The app implements this with encrypted storage
 * (e.g. Android Keystore-backed); [InMemoryTokenStore] is for tests.
 */
interface TokenStore {
    suspend fun load(): TwitchToken?
    suspend fun save(token: TwitchToken)
    suspend fun clear()
}

class InMemoryTokenStore(initial: TwitchToken? = null) : TokenStore {
    @Volatile private var token: TwitchToken? = initial
    override suspend fun load(): TwitchToken? = token
    override suspend fun save(token: TwitchToken) { this.token = token }
    override suspend fun clear() { token = null }
}

/** Result of `GET /oauth2/validate`. */
data class TokenValidation(
    val clientId: String,
    val login: String?,
    val userId: String?,
    val scopes: List<String>,
    val expiresInSeconds: Long,
)

/**
 * Output of [TwitchAuth.startDeviceFlow]. Show [userCode] and [verificationUri] (or open the URI, which already
 * carries the code) to the user, then call [TwitchAuth.pollForToken].
 */
class DeviceCode(
    internal val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresAtEpochMs: Long,
    val intervalSeconds: Int,
    val scopes: List<String>,
) {
    override fun toString(): String =
        "DeviceCode(userCode=$userCode, verificationUri=$verificationUri, expiresAtEpochMs=$expiresAtEpochMs, intervalSeconds=$intervalSeconds)"

    /**
     * For keeping a sign-in in progress across the app being closed while the streamer approves in the browser (phones
     * like OnePlus close background apps quickly). Holds the device code, which can fetch a token once approved: store
     * it only somewhere encrypted, and delete it when the sign-in ends.
     */
    fun encode(): String = TwitchJson.encodeToString(StoredDeviceCode.serializer(), StoredDeviceCode(deviceCode, userCode, verificationUri, expiresAtEpochMs, intervalSeconds, scopes))

    companion object {
        /** The [DeviceCode] in [s] ([encode]), or null if it can't be read. Expired codes are returned as they are. */
        fun decode(s: String): DeviceCode? = runCatching {
            val d = TwitchJson.decodeFromString(StoredDeviceCode.serializer(), s)
            DeviceCode(d.deviceCode, d.userCode, d.verificationUri, d.expiresAtEpochMs, d.intervalSeconds, d.scopes)
        }.getOrNull()
    }
}

@Serializable
internal data class StoredDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresAtEpochMs: Long,
    val intervalSeconds: Int,
    val scopes: List<String>,
)
