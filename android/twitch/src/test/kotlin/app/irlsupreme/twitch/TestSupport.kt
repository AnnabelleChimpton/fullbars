package app.irlsupreme.twitch

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal const val CLIENT_ID = "test-client-id"

internal fun json(code: Int, body: String): MockResponse =
    MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

internal fun MockWebServer.config(clientId: String = CLIENT_ID) = TwitchConfig(
    clientId = clientId,
    oauthBaseUrl = url("/oauth2/").toString(),
    helixBaseUrl = url("/helix/").toString(),
    ingestListUrl = url("/ingests").toString(),
    eventSubUrl = url("/ws").toString().replaceFirst("http://", "ws://"),
)

internal fun MockWebServer.take(): RecordedRequest =
    takeRequest(5, TimeUnit.SECONDS) ?: error("expected a request")

internal fun RecordedRequest.bodyText(): String = body?.utf8().orEmpty()

/** Decodes an x-www-form-urlencoded body. */
internal fun RecordedRequest.form(): Map<String, String> = bodyText().split('&').filter { it.isNotEmpty() }.associate {
    val (k, v) = it.split('=', limit = 2)
    java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
}

internal fun testHttp(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.SECONDS)
    .build()

internal fun token(access: String = "access-1", refresh: String? = "refresh-1", expiresAt: Long = Long.MAX_VALUE / 2) =
    TwitchToken(access, refresh, TwitchConfig.DEFAULT_SCOPES, expiresAt, userId = "1001", login = "streamer")

/** Token provider that hands out a fixed token and counts refreshes. */
internal class FakeTokens(var token: TwitchToken = token(), private val refreshed: TwitchToken = token("access-2")) : AccessTokenProvider {
    var refreshes = 0
    override suspend fun current(): TwitchToken = token
    override suspend fun refreshAfterUnauthorized(stale: TwitchToken): TwitchToken {
        refreshes++
        token = refreshed
        return refreshed
    }
}
