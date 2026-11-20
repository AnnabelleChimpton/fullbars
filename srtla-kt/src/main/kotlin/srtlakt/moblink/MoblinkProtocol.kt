// SPDX-License-Identifier: Apache-2.0
package srtlakt.moblink

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Moblink: how a spare phone (the relay, our "helper") lends its mobile data to the streaming phone's SRTLA bonding.
 * Interoperable with Moblin (eerimoq/moblin, MIT) and moblink-rust (MIT); this is a clean re-implementation of the
 * wire protocol, with no code copied from either.
 *
 *  - The streamer runs a WebSocket server (default port [DEFAULT_PORT]) and advertises `_moblink._tcp` over mDNS
 *    with a TXT record `name`. Relays connect to `ws://ip:port`.
 *  - Text frames hold externally tagged JSON: `{"hello":{...}}`. Keepalive is WebSocket ping/pong every 10 s.
 *  - Streamer: hello(challenge, salt) → relay: identify(id, name, auth) → streamer: identified(result) → requests.
 *  - `startTunnel{address,port}`: the relay opens a UDP port facing the streamer and forwards datagrams unchanged to
 *    address:port over its own mobile data; replies flow back to whoever last sent to that port.
 */
object Moblink {
    const val DEFAULT_PORT = 7777
    const val SERVICE_TYPE = "_moblink._tcp"
    const val API_VERSION = "0.1"
    const val PING_INTERVAL_MS = 10_000L
    const val RECONNECT_DELAY_MS = 5_000L
    const val TUNNEL_IDLE_TIMEOUT_MS = 30_000L
    const val MAX_NAME = 30

    enum class Thermal(val wire: String) { WHITE("white"), YELLOW("yellow"), RED("red") }

    /** `base64(sha256(base64(sha256(password + salt)) + challenge))`, as Moblin and moblink-rust compute it. */
    fun authentication(password: String, salt: String, challenge: String): String {
        val b64 = Base64.getEncoder()
        val h1 = sha256("$password$salt")
        return b64.encodeToString(sha256(b64.encodeToString(h1) + challenge))
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    private val rng = SecureRandom()
    private const val ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    fun randomString(n: Int = 64) = String(CharArray(n) { ALNUM[rng.nextInt(ALNUM.length)] })

    /** What a streamer shows for a relay's name: first line, trimmed, at most [MAX_NAME] characters. */
    fun cleanName(name: String) = name.substringBefore('\n').trim().take(MAX_NAME)

    // ---- Messages. Unknown fields are ignored; unknown message types decode to null. ----

    sealed interface ToRelay
    data class Hello(val apiVersion: String, val challenge: String, val salt: String) : ToRelay
    data class Identified(val result: String) : ToRelay
    data class Request(val id: Int, val data: RequestData) : ToRelay

    sealed interface RequestData
    data class StartTunnel(val address: String, val port: Int) : RequestData
    data object Status : RequestData
    data class OtherRequest(val name: String) : RequestData

    sealed interface ToStreamer
    data class Identify(val id: String, val name: String, val authentication: String) : ToStreamer
    data class Response(val id: Int, val result: String, val data: ResponseData?) : ToStreamer

    sealed interface ResponseData
    data class TunnelStarted(val port: Int) : ResponseData
    data class StatusReport(val batteryPercentage: Int?, val thermal: Thermal?) : ResponseData

    const val OK = "ok"
    const val WRONG_PASSWORD = "wrongPassword"
    const val UNKNOWN_REQUEST = "unknownRequest"
    const val NOT_IDENTIFIED = "notIdentified"
    const val ALREADY_IDENTIFIED = "alreadyIdentified"

    private val json = Json { ignoreUnknownKeys = true }
    private val empty = JsonObject(emptyMap())

    fun encode(m: ToRelay): String = when (m) {
        is Hello -> buildJsonObject {
            putJsonObject("hello") {
                put("apiVersion", m.apiVersion)
                putJsonObject("authentication") { put("challenge", m.challenge); put("salt", m.salt) }
            }
        }
        is Identified -> buildJsonObject { putJsonObject("identified") { put("result", tag(m.result)) } }
        is Request -> buildJsonObject {
            putJsonObject("request") {
                put("id", m.id)
                put("data", when (val d = m.data) {
                    is StartTunnel -> buildJsonObject { putJsonObject("startTunnel") { put("address", d.address); put("port", d.port) } }
                    Status -> tag("status")
                    is OtherRequest -> tag(d.name)
                })
            }
        }
    }.toString()

    fun encode(m: ToStreamer): String = when (m) {
        is Identify -> buildJsonObject {
            putJsonObject("identify") { put("id", m.id); put("name", m.name); put("authentication", m.authentication) }
        }
        is Response -> buildJsonObject {
            putJsonObject("response") {
                put("id", m.id)
                put("result", tag(m.result))
                when (val d = m.data) {
                    is TunnelStarted -> putJsonObject("data") { putJsonObject("startTunnel") { put("port", d.port) } }
                    is StatusReport -> putJsonObject("data") {
                        putJsonObject("status") {
                            d.batteryPercentage?.let { put("batteryPercentage", it) }
                            d.thermal?.let { put("thermalState", it.wire) }
                        }
                    }
                    null -> {}
                }
            }
        }
    }.toString()

    fun decodeToRelay(text: String): ToRelay? = runCatching {
        val (type, body) = single(json.parseToJsonElement(text)) ?: return null
        when (type) {
            "hello" -> {
                val auth = body.obj("authentication")
                Hello(body.str("apiVersion") ?: "", auth.str("challenge")!!, auth.str("salt")!!)
            }
            "identified" -> Identified(single(body.getValue("result"))!!.first)
            "request" -> {
                val (name, d) = single(body.getValue("data"))!!
                Request(body.getValue("id").jsonPrimitive.int, when (name) {
                    "startTunnel" -> StartTunnel(d.str("address")!!, d.getValue("port").jsonPrimitive.int)
                    "status" -> Status
                    else -> OtherRequest(name)
                })
            }
            else -> null
        }
    }.getOrNull()

    fun decodeToStreamer(text: String): ToStreamer? = runCatching {
        val (type, body) = single(json.parseToJsonElement(text)) ?: return null
        when (type) {
            "identify" -> Identify(body.str("id")!!, body.str("name") ?: "", body.str("authentication")!!)
            "response" -> {
                val data = body["data"]?.let { single(it) }?.let { (name, d) ->
                    when (name) {
                        "startTunnel" -> TunnelStarted(d.getValue("port").jsonPrimitive.int)
                        "status" -> StatusReport(
                            d["batteryPercentage"]?.jsonPrimitive?.intOrNull,
                            d.str("thermalState")?.let { t -> Thermal.entries.firstOrNull { it.wire == t } },
                        )
                        else -> null
                    }
                }
                Response(body.getValue("id").jsonPrimitive.int, single(body.getValue("result"))!!.first, data)
            }
            else -> null
        }
    }.getOrNull()

    private fun tag(name: String) = buildJsonObject { put(name, empty) }

    /** An externally tagged value `{"name": {...}}` as (name, body). */
    private fun single(e: JsonElement): Pair<String, JsonObject>? {
        val o = e.jsonObject
        if (o.size != 1) return null
        val (k, v) = o.entries.first()
        return k to ((v as? JsonObject) ?: empty)
    }

    private fun JsonObject.obj(k: String) = getValue(k).jsonObject
    private fun JsonObject.str(k: String): String? = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
}
