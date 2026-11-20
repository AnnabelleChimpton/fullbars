package app.irlsupreme.twitch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * One Twitch ingest endpoint from `GET https://ingest.twitch.tv/ingests`
 * (https://dev.twitch.tv/docs/video-broadcast/reference/ingest). Templates contain `{stream_key}`.
 */
data class IngestServer(
    val id: Int,
    val name: String,
    val urlTemplate: String,
    /** The `rtmps://` template (`url_template_secure`). */
    val urlTemplateSecure: String?,
    val priority: Int,
) {
    companion object {
        /** Twitch's global ingest ("Default", _id 0 in the ingest list), which routes to a nearby PoP. */
        val GLOBAL = IngestServer(
            id = 0,
            name = "Default",
            urlTemplate = "rtmp://ingest.global-contribute.live-video.net/app/{stream_key}",
            urlTemplateSecure = "rtmps://ingest.global-contribute.live-video.net/app/{stream_key}",
            priority = 0,
        )
    }
}

/**
 * A ready-to-publish RTMPS target. It embeds the stream key, so `toString()` is redacted.
 * [url] is the full URL; [serverUrl] + [streamName] is the same target split for RTMP clients that take them separately.
 */
class PublishUrl internal constructor(
    val url: String,
    val serverUrl: String,
    val streamName: String,
    val bandwidthTest: Boolean,
) {
    override fun toString(): String = "PublishUrl(server=$serverUrl, streamName=<redacted>, bandwidthTest=$bandwidthTest)"
    override fun equals(other: Any?): Boolean = other is PublishUrl && other.url == url
    override fun hashCode(): Int = url.hashCode()
}

private val STREAM_KEY_CHARS = Regex("^[A-Za-z0-9_-]+$")
private val TEMPLATE = Regex("^(rtmps?)://([^/]+)/(.+)$")
private const val KEY_PLACEHOLDER = "{stream_key}"
private const val RTMPS_PORT = 443

/**
 * Builds `rtmps://<host>:443/app/<stream_key>[?bandwidthtest=true]` from an ingest's secure template.
 *
 * `?bandwidthtest=true` is Twitch's documented bandwidth-test mode: the stream reaches ingest (visible in
 * Twitch Inspector) but live viewing is disabled (https://dev.twitch.tv/docs/video-broadcast/).
 * When the server lists no secure template, the plain template is upgraded to rtmps.
 */
fun buildPublishUrl(streamKey: StreamKey, ingest: IngestServer = IngestServer.GLOBAL, bandwidthTest: Boolean = false): PublishUrl {
    require(STREAM_KEY_CHARS.matches(streamKey.value)) { "Stream key has unexpected characters" }
    val template = ingest.urlTemplateSecure?.takeIf { it.isNotBlank() } ?: ingest.urlTemplate
    val match = TEMPLATE.matchEntire(template.trim()) ?: throw IllegalArgumentException("Unsupported ingest template for '${ingest.name}'")
    val (_, authority, path) = match.destructured
    require(path.endsWith(KEY_PLACEHOLDER)) { "Ingest template for '${ingest.name}' has no $KEY_PLACEHOLDER" }
    val hostPort = if (authority.contains(':')) authority else "$authority:$RTMPS_PORT"
    val app = path.removeSuffix(KEY_PLACEHOLDER).trimEnd('/')
    val streamName = streamKey.value + if (bandwidthTest) "?bandwidthtest=true" else ""
    val serverUrl = "rtmps://$hostPort/$app"
    return PublishUrl(url = "$serverUrl/$streamName", serverUrl = serverUrl, streamName = streamName, bandwidthTest = bandwidthTest)
}

/** Fetches Twitch's ingest list (no auth needed) and picks one. */
class TwitchIngest(
    private val config: TwitchConfig,
    private val http: OkHttpClient = TwitchHttp.defaultClient(),
) {
    suspend fun listIngests(): List<IngestServer> {
        val result = http.execute(Request.Builder().url(config.ingestListUrl).get().build())
        if (!result.isSuccessful) throw TwitchException.Api(result.code, null)
        val body = decodeOrThrow("ingest list") { TwitchJson.decodeFromString(IngestListDto.serializer(), result.body) }
        return body.ingests.map {
            IngestServer(it.id, it.name, it.urlTemplate, it.urlTemplateSecure, it.priority)
        }
    }

    /**
     * The global "Default" ingest from the list (falls back to the lowest-priority entry, then to
     * [IngestServer.GLOBAL] if the list cannot be fetched). Never throws for network errors.
     */
    suspend fun pickIngest(): IngestServer {
        val list = try {
            listIngests()
        } catch (_: TwitchException) {
            return IngestServer.GLOBAL
        }
        return list.firstOrNull { it.name.equals("Default", ignoreCase = true) }
            ?: list.minByOrNull { it.priority }
            ?: IngestServer.GLOBAL
    }
}

@Serializable
internal data class IngestListDto(val ingests: List<IngestDto> = emptyList())

@Serializable
internal data class IngestDto(
    @SerialName("_id") val id: Int,
    val name: String,
    @SerialName("url_template") val urlTemplate: String,
    @SerialName("url_template_secure") val urlTemplateSecure: String? = null,
    val priority: Int = 0,
)
