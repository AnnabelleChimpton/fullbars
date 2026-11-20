package app.irlsupreme.service

import android.net.Uri
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import srtlakt.protocol.Packets
import srtlakt.protocol.Srtla
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * A paired BRB server, from its pairing QR / link (server: `docker exec -it fullbars-brb brb pair`):
 *
 *     fullbars://pair?v=1&h=HOST&p=PORT&k=TOKEN&f=SPKI_SHA256&n=NAME
 *
 * [token] authenticates the app to the server's control API; [pin] is the SHA-256 of the server's TLS public key, the
 * only key the app will talk to (the server's certificate is self-signed: no domain or CA needed, and nobody in
 * between can read the stream key the app hands over). Stored in the Keystore-encrypted [SecretStore].
 */
data class BrbPairing(val host: String, val port: Int, val token: String, val pin: String, val name: String?) {
    /** What this app puts in its SRT stream ids ("live/ID", "slate/ID"); the server accepts only those once paired. */
    val publishId: String by lazy {
        val sum = MessageDigest.getInstance("SHA-256").digest("fullbars-publish:$token".toByteArray())
        sum.take(8).joinToString("") { "%02x".format(it) }
    }

    /** Encrypts this app's SRT streams to the server (AES; the server requires it once paired). Never shown or logged. */
    val srtPassphrase: String by lazy {
        val sum = MessageDigest.getInstance("SHA-256").digest("fullbars-srt:$token".toByteArray())
        Base64.encodeToString(sum, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    val label: String get() = name?.takeIf { it.isNotBlank() } ?: host

    fun toJson(): String = JSONObject().put("h", host).put("p", port).put("k", token).put("f", pin).put("n", name ?: "").toString()

    companion object {
        fun parse(link: String): BrbPairing? = runCatching {
            val u = Uri.parse(link.trim())
            if (u.scheme != "fullbars" || u.host != "pair" || u.getQueryParameter("v") != "1") return null
            val host = u.getQueryParameter("h")?.takeIf { it.isNotBlank() } ?: return null
            val token = u.getQueryParameter("k")?.takeIf { it.length >= 40 } ?: return null
            val pin = u.getQueryParameter("f")?.takeIf { it.length >= 40 } ?: return null
            val port = u.getQueryParameter("p")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 5000
            BrbPairing(host, port, token, pin, u.getQueryParameter("n"))
        }.getOrNull()

        fun fromJson(s: String): BrbPairing? = runCatching {
            val o = JSONObject(s)
            BrbPairing(o.getString("h"), o.getInt("p"), o.getString("k"), o.getString("f"), o.optString("n").ifBlank { null })
        }.getOrNull()
    }
}

class BrbPairingStore(private val secrets: SecretStore) {
    fun load(): BrbPairing? = secrets.get(KEY)?.let { BrbPairing.fromJson(it) }
    fun save(p: BrbPairing) = secrets.put(KEY, p.toJson())
    fun clear() = secrets.put(KEY, null)

    private companion object { const val KEY = "brb_pairing" }
}

/** What the server reports; never includes the stream key. */
data class BrbStatus(
    val state: String, // waiting | live | slate | unknown
    val outputKind: String, // twitch | rtmp | srt | record
    val outputHost: String?,
    val outputTest: Boolean,
    val phoneSlate: Boolean,
    /** "Also stream to" destinations the server holds, with the last error of any that's failing. */
    val extras: List<BrbExtra> = emptyList(),
) {
    val outputLabel: String get() = when (outputKind) {
        "twitch" -> if (outputTest) "Twitch (test stream)" else "Twitch"
        "record" -> "recording on the server"
        "obs" -> "your home OBS"
        else -> outputHost ?: outputKind
    }
}

/** One "Also stream to" destination as the server reports it: never its key. */
data class BrbExtra(val name: String, val host: String?, val error: String?)

/** A refusal from the server: [code] is its HTTP status (0 when there was no answer). */
class BrbException(message: String, val code: Int = 0) : IOException(message)

/** The server's control API (TLS on TCP, same port as bonding's UDP), talking only to the pinned key. */
class BrbClient(private val pairing: BrbPairing) {
    private val http: OkHttpClient = run {
        val tm = PinnedTrustManager(pairing.pin)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, tm)
            .hostnameVerifier { _, _ -> true } // identity is the pinned key, checked in PinnedTrustManager
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }
    private val base = "https://${if (':' in pairing.host) "[${pairing.host}]" else pairing.host}:${pairing.port}"

    /** Blocking; call off the main thread. */
    fun status(): BrbStatus {
        val o = call(Request.Builder().url("$base/v1/status").get())
        val out = o.optJSONObject("output") ?: JSONObject()
        return BrbStatus(
            state = o.optString("state", "unknown"),
            outputKind = out.optString("kind", "record"),
            outputHost = out.optString("host").ifBlank { null },
            outputTest = out.optBoolean("test"),
            phoneSlate = o.optString("slate") == "phone",
            extras = o.optJSONArray("extras")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    a.optJSONObject(i)?.let { e ->
                        BrbExtra(e.optString("name"), e.optString("host").ifBlank { null }, e.optString("error").ifBlank { null })
                    }
                }
            } ?: emptyList(),
        )
    }

    /** Where the server sends the stream from the next go-live on (an RTMP(S)/SRT URL with the key). */
    fun setOutput(url: String) {
        val body = JSONObject().put("url", url).toString().toRequestBody("application/json".toMediaType())
        call(Request.Builder().url("$base/v1/output").put(body))
    }

    /**
     * The "Also stream to" destinations ([name] to RTMP(S) URL with its key), sent next to the main output from the next
     * go-live on; an empty list clears them. False when the server is older than this feature (it has no such route),
     * so the caller can say so; it only matters when there's something to send. Blocking.
     */
    fun setExtraOutputs(outputs: List<Pair<String, String>>): Boolean {
        val arr = org.json.JSONArray()
        outputs.forEach { (name, url) -> arr.put(JSONObject().put("name", name).put("url", url)) }
        val body = JSONObject().put("outputs", arr).toString().toRequestBody("application/json".toMediaType())
        return try {
            call(Request.Builder().url("$base/v1/extra-outputs").put(body))
            true
        } catch (e: BrbException) {
            if (e.code == 404 || e.code == 405) false else throw e
        }
    }

    /** "My OBS": the server serves the stream for home OBS to pull (settings: `brb obs` on the server). */
    fun setOutputObs() {
        val body = JSONObject().put("mode", "obs").toString().toRequestBody("application/json".toMediaType())
        call(Request.Builder().url("$base/v1/output").put(body))
    }

    /** The mod remote's link, created on the server the first time. Blocking. */
    fun remoteLink(): String = base + call(Request.Builder().url("$base/v1/remote").get()).getString("path")

    /** A new mod remote link; old ones stop working. Blocking. */
    fun resetRemote(): String =
        base + call(Request.Builder().url("$base/v1/remote/reset").post(ByteArray(0).toRequestBody())).getString("path")

    /** Tells the server what viewers see and collects the mods' picks since the last poll (scene names). Blocking. */
    fun pollRemote(live: Boolean, scene: String, micOn: Boolean): List<String> {
        val body = JSONObject().put("live", live).put("scene", scene).put("mic", micOn).toString().toRequestBody("application/json".toMediaType())
        val cmds = call(Request.Builder().url("$base/v1/remote/poll").post(body)).optJSONArray("commands") ?: return emptyList()
        return (0 until cmds.length()).mapNotNull { cmds.optJSONObject(it)?.optString("scene")?.takeIf { s -> s.isNotEmpty() } }
    }

    /**
     * Tells the server the streamer is ending on purpose (`POST /v1/end`), so it stops the Twitch output when the next
     * session closes instead of holding the Signal lost screen for a reconnect. Without this the server can't tell a
     * deliberate end from a phone-side reconnect closing its old SRT session cleanly; on the first real stream it
     * guessed wrong twice and cut viewers off mid-stream.
     *
     * Blocks for at most [timeoutMs] whatever the network does (a DNS lookup ignores OkHttp's call timeout, so the call
     * runs on its own thread and is abandoned when time is up): ending a stream must not wait on a dead link. Throws an
     * IOException ([BrbException] for a refusal or no answer in time) when the server didn't confirm.
     */
    fun end(timeoutMs: Long = END_TIMEOUT_MS) {
        val quick = http.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        val task = FutureTask { call(Request.Builder().url("$base/v1/end").post(ByteArray(0).toRequestBody()), quick) }
        Thread(task, "brb-end").apply { isDaemon = true }.start()
        try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            task.cancel(true)
            throw BrbException("The BRB server didn't answer within $timeoutMs ms")
        } catch (e: ExecutionException) {
            throw (e.cause as? IOException) ?: IOException(e.cause)
        }
    }

    private fun call(b: Request.Builder, client: OkHttpClient = http): JSONObject {
        val req = b.header("Authorization", "Bearer ${pairing.token}").build()
        try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body.string()
                when {
                    resp.code == 401 -> throw BrbException("This BRB server doesn't know this phone any more. Pair again.")
                    !resp.isSuccessful -> throw BrbException(
                        runCatching { JSONObject(text).getString("error") }.getOrNull() ?: "BRB server error ${resp.code}",
                        resp.code,
                    )
                }
                return if (text.isBlank()) JSONObject() else JSONObject(text)
            }
        } catch (e: PinMismatch) {
            throw BrbException("That isn't your BRB server (its security key changed). Pair again if you rebuilt it.")
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            if (e.cause is PinMismatch) throw BrbException("That isn't your BRB server (its security key changed). Pair again if you rebuilt it.")
            throw e
        }
    }

    companion object {
        /** How long ending a stream waits for the server's OK before going ahead without it. */
        const val END_TIMEOUT_MS = 1_500L
    }
}

private class PinMismatch : CertificateException("server key doesn't match the pairing")

/** Trusts exactly one public key: the one whose SHA-256 (base64url) came in the pairing QR. */
private class PinnedTrustManager(private val pin: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw PinMismatch()
        val sum = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
        val got = Base64.encodeToString(sum, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        if (!MessageDigest.isEqual(got.toByteArray(), pin.toByteArray())) throw PinMismatch()
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException()
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Whether the server's SRTLA receiver answers on UDP [port]: sends a REG1 (a new bonding group with a random id, as the
 * stream would) and waits for any SRTLA registration reply, trying twice. Pairing and the control API only prove TCP,
 * but the stream itself is UDP, and a firewall that forgot the UDP rule (Oracle Cloud needs two) otherwise passes
 * every check and then hangs at Connecting. The probe group has no connections, so the receiver drops it on its own.
 * Blocking; call off the main thread.
 */
fun srtlaReachable(host: String, port: Int, timeoutMs: Int = 2_000): Boolean = runCatching {
    val addr = InetSocketAddress(InetAddress.getByName(host), port)
    val id = ByteArray(Srtla.ID_LEN).also { SecureRandom().nextBytes(it) }
    val reg1 = Packets.reg1(id)
    DatagramSocket().use { socket ->
        socket.soTimeout = timeoutMs
        val buf = ByteArray(1500)
        repeat(2) {
            socket.send(DatagramPacket(reg1, reg1.size, addr))
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            // Skip stray datagrams until the deadline: only an SRTLA registration reply counts.
            while (System.nanoTime() < deadline) {
                val reply = DatagramPacket(buf, buf.size)
                try { socket.receive(reply) } catch (_: SocketTimeoutException) { break }
                if (isSrtlaReply(reply.data, reply.length)) return@runCatching true
            }
        }
        false
    }
}.getOrDefault(false)

/**
 * Any SRTLA registration answer proves the UDP port is open: REG2 (accepted), or REG_ERR / REG_NGP / REG_NAK, which a
 * receiver sends when it is full or confused but still reachable.
 */
internal fun isSrtlaReply(buf: ByteArray, len: Int): Boolean {
    val type = Packets.type(buf, len) ?: return false
    return type == Srtla.TYPE_REG2 || type == Srtla.TYPE_REG_ERR ||
        type == Srtla.TYPE_REG_NGP || type == Srtla.TYPE_REG_NAK
}

/** A pairing link that arrived from outside (a tapped fullbars:// link) and waits for the user's OK on the setup screen. */
object PendingPairing {
    val link = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}
