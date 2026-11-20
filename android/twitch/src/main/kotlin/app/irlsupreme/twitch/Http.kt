package app.irlsupreme.twitch

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object TwitchHttp {
    /**
     * A client suitable for every class in this module. No logging interceptor on purpose: requests carry
     * bearer tokens and responses carry stream keys. `pingInterval` stays 0 because EventSub closes the
     * socket (4001) on any client-initiated frame; OkHttp still answers server pings with pongs.
     */
    fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
}

internal val TwitchJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

internal class HttpResult(val code: Int, val body: String, val headers: Headers) {
    val isSuccessful: Boolean get() = code in 200..299
}

@Serializable
internal data class TwitchErrorBody(val status: Int? = null, val message: String? = null, val error: String? = null)

internal fun HttpResult.errorBody(): TwitchErrorBody? =
    runCatching { TwitchJson.decodeFromString(TwitchErrorBody.serializer(), body) }.getOrNull()

/** Executes the call asynchronously, reads the whole body off the caller's thread, and always closes the response. */
internal suspend fun OkHttpClient.execute(request: Request): HttpResult = suspendCancellableCoroutine { cont ->
    val call = newCall(request)
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(TwitchException.Network(e))
        }

        override fun onResponse(call: Call, response: Response) {
            val result = try {
                response.use { HttpResult(it.code, it.body.string(), it.headers) }
            } catch (e: IOException) {
                cont.resumeWithException(TwitchException.Network(e))
                return
            }
            cont.resume(result)
        }
    })
}

/** Maps a failed Helix response to a typed exception. 401 → [TwitchException.Unauthorized]. */
internal fun HttpResult.throwHelixError(): Nothing {
    val message = errorBody()?.message
    throw when (code) {
        401 -> TwitchException.Unauthorized(message)
        403 -> TwitchException.Forbidden(message)
        429 -> TwitchException.RateLimited(headers["Ratelimit-Reset"]?.toLongOrNull())
        else -> TwitchException.Api(code, message)
    }
}

internal fun String.resolveUrl(path: String): HttpUrl = toHttpUrl().resolve(path)
    ?: throw IllegalArgumentException("Cannot resolve '$path' against base URL")

internal inline fun <T> decodeOrThrow(what: String, block: () -> T): T = try {
    block()
} catch (e: kotlinx.serialization.SerializationException) {
    throw TwitchException.Protocol("could not parse $what", e)
} catch (e: IllegalArgumentException) {
    throw TwitchException.Protocol("could not parse $what", e)
}
