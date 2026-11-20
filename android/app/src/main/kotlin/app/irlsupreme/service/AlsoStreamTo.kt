package app.irlsupreme.service

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Where else a stream can go, next to Twitch. Each sends the same video through the BRB server. */
enum class ExtraKind(val title: String, val fixedServer: String?) {
    KICK("Kick", null),
    YOUTUBE("YouTube", "rtmps://a.rtmps.youtube.com/live2"),
    OTHER("Other server", null),
}

/** One destination as the streamer set it. The key is a secret: it lives in [SecretStore] only. */
data class ExtraDestination(val on: Boolean = false, val server: String = "", val key: String = "")

/**
 * Settings > Also stream to: Kick, YouTube or any RTMP(S) server, sent by the BRB server alongside Twitch so the phone
 * uploads once. Stored encrypted (the stream keys are secrets), read by the go-live path through [toSend].
 */
object AlsoStreamTo {
    private const val SECRET = "also_stream_to"
    private var flow: MutableStateFlow<Map<ExtraKind, ExtraDestination>>? = null

    @Synchronized
    fun state(ctx: Context): StateFlow<Map<ExtraKind, ExtraDestination>> =
        (flow ?: MutableStateFlow(load(ctx)).also { flow = it }).asStateFlow()

    fun set(ctx: Context, kind: ExtraKind, d: ExtraDestination) {
        state(ctx)
        val f = flow ?: return
        val next = f.value + (kind to d.copy(server = d.server.take(MAX_URL), key = d.key.take(MAX_KEY)))
        f.value = next
        SecretStore(ctx).put(SECRET, encode(next))
    }

    /** What the go-live path sends: the destinations that are on and complete, as (name, URL with key). */
    fun toSend(ctx: Context): List<Pair<String, String>> = AlsoStreamRules.toSend(state(ctx).value)

    private fun load(ctx: Context): Map<ExtraKind, ExtraDestination> =
        SecretStore(ctx).get(SECRET)?.let { decode(it) } ?: emptyMap()

    internal fun encode(m: Map<ExtraKind, ExtraDestination>): String = JSONObject().apply {
        m.forEach { (k, d) -> put(k.name, JSONObject().put("on", d.on).put("server", d.server).put("key", d.key)) }
    }.toString()

    internal fun decode(s: String): Map<ExtraKind, ExtraDestination> = runCatching {
        val o = JSONObject(s)
        ExtraKind.entries.mapNotNull { k ->
            o.optJSONObject(k.name)?.let { d -> k to ExtraDestination(d.optBoolean("on"), d.optString("server"), d.optString("key")) }
        }.toMap()
    }.getOrDefault(emptyMap())

    private const val MAX_URL = 300
    private const val MAX_KEY = 200
}

/** The pure rules behind Also stream to, for tests. */
object AlsoStreamRules {
    /** The full ingest URL with the key, or null if [d] isn't complete and usable. */
    fun url(kind: ExtraKind, d: ExtraDestination): String? {
        if (problem(kind, d) != null) return null
        return serverOf(kind, d) + "/" + d.key.trim()
    }

    /** What's missing or wrong, in the words the page shows; null when it's ready to go. */
    fun problem(kind: ExtraKind, d: ExtraDestination): String? {
        val server = serverOf(kind, d)
        val key = d.key.trim()
        return when {
            server.isEmpty() -> "Add the stream URL."
            !validServer(server) -> "Use the rtmp:// or rtmps:// address ${kind.title} gives you."
            key.isEmpty() -> "Add the stream key."
            key.any { it.isWhitespace() || it == '/' || it == '?' } -> "That stream key has a space or a slash in it. Copy it again."
            else -> null
        }
    }

    fun toSend(m: Map<ExtraKind, ExtraDestination>): List<Pair<String, String>> =
        ExtraKind.entries.mapNotNull { k -> m[k]?.takeIf { it.on }?.let { d -> url(k, d)?.let { k.title to it } } }

    /** The names that are on, for the Settings row ("Kick, YouTube"), whether or not they're complete. */
    fun onNames(m: Map<ExtraKind, ExtraDestination>): List<String> = ExtraKind.entries.filter { m[it]?.on == true }.map { it.title }

    /**
     * The server part: the fixed one (YouTube), or what was typed with the trailing slash gone. Kick's ingest is an
     * Amazon IVS endpoint, which wants /app; Kick's dashboard sometimes shows the address without it.
     */
    internal fun serverOf(kind: ExtraKind, d: ExtraDestination): String {
        kind.fixedServer?.let { return it }
        var s = d.server.trim().trimEnd('/')
        if (s.isEmpty()) return ""
        val u = runCatching { java.net.URI(s) }.getOrNull()
        val host = u?.host.orEmpty()
        if (kind == ExtraKind.KICK && host.endsWith(".live-video.net") && u?.path.isNullOrEmpty()) s += "/app"
        return s
    }

    private fun validServer(s: String): Boolean {
        val u = runCatching { java.net.URI(s) }.getOrNull() ?: return false
        return (u.scheme == "rtmp" || u.scheme == "rtmps") && !u.host.isNullOrEmpty() && s.none { it.isWhitespace() }
    }
}
