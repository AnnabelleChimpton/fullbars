package app.irlsupreme.twitch

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Supplies a usable user access token; implemented by [TwitchSession] and by fakes in tests. */
interface AccessTokenProvider {
    /** A token that is not about to expire. Throws [TwitchException.SignInRequired] if there is none. */
    suspend fun current(): TwitchToken

    /** Called after a 401 with the token that failed; returns a refreshed token or throws [TwitchException.SignInRequired]. */
    suspend fun refreshAfterUnauthorized(stale: TwitchToken): TwitchToken
}

/** Runs [block] with a fresh token; on [TwitchException.Unauthorized] refreshes once and retries. */
suspend fun <T> AccessTokenProvider.withToken(block: suspend (TwitchToken) -> T): T {
    val token = current()
    return try {
        block(token)
    } catch (e: TwitchException.Unauthorized) {
        block(refreshAfterUnauthorized(token))
    }
}

/**
 * Owns the signed-in user's token: persists it via [TokenStore], refreshes it before expiry and after a 401
 * (single-flight, as Twitch recommends refreshing from one place), and clears it when refresh fails.
 */
class TwitchSession(
    private val auth: TwitchAuth,
    private val store: TokenStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : AccessTokenProvider {
    private val mutex = Mutex()

    suspend fun isSignedIn(): Boolean = store.load() != null

    /**
     * The saved token as it is, with no network and no refresh. Lets the app show the account straight away at launch,
     * and keep showing it when a refresh fails for lack of signal: going live calls [current] and checks again.
     */
    suspend fun stored(): TwitchToken? = store.load()

    /** The saved account's login, or null when nobody is signed in. No network. */
    suspend fun storedLogin(): String? = store.load()?.login

    /**
     * Completes sign-in for a device code from [TwitchAuth.startDeviceFlow]: polls until approved, validates the
     * token to learn the user id/login, and saves it.
     */
    suspend fun completeSignIn(code: DeviceCode): TwitchToken {
        val token = auth.pollForToken(code)
        val validation = auth.validate(token)
        val withUser = token.copy(userId = validation.userId, login = validation.login)
        mutex.withLock { store.save(withUser) }
        return withUser
    }

    override suspend fun current(): TwitchToken = mutex.withLock {
        val token = store.load() ?: throw TwitchException.SignInRequired("not signed in")
        if (token.expiresWithin(clock())) refreshLocked(token) else token
    }

    override suspend fun refreshAfterUnauthorized(stale: TwitchToken): TwitchToken = mutex.withLock {
        val stored = store.load() ?: throw TwitchException.SignInRequired("not signed in")
        // Another caller already refreshed while we were waiting for the lock.
        if (stored.accessToken != stale.accessToken) stored else refreshLocked(stored)
    }

    /**
     * Validates the stored token (Twitch requires this at startup and hourly). Refreshes on 401.
     * Returns null when not signed in.
     */
    suspend fun validate(): TokenValidation? {
        val token = store.load() ?: return null
        return try {
            auth.validate(token)
        } catch (e: TwitchException.Unauthorized) {
            auth.validate(refreshAfterUnauthorized(token))
        }
    }

    /** Revokes the token (best effort) and forgets it. */
    suspend fun signOut() {
        val token = mutex.withLock { store.load().also { store.clear() } } ?: return
        try {
            auth.revoke(token)
        } catch (_: TwitchException) {
            // Already forgotten locally; a failed revoke must not block sign-out.
        }
    }

    private suspend fun refreshLocked(token: TwitchToken): TwitchToken = try {
        auth.refresh(token).also { store.save(it) }
    } catch (e: TwitchException.SignInRequired) {
        store.clear()
        throw e
    }
}
