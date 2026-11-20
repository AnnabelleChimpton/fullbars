package app.irlsupreme.service

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.irlsupreme.engine.HelperHosting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import srtlakt.moblink.Moblink
import java.security.SecureRandom
import java.util.UUID

/**
 * Helper phones: spare phones lend their mobile data to the streaming phone's bonding, over Moblink (so Moblin's
 * helpers and streamers work with ours too).
 *
 *  - **Streaming phone** ("host"): accepts helpers while a combined stream runs, if [hostingOn]. Helpers prove they
 *    know [hostPassword], which travels in a QR code: `fullbars://helper?p=PASSWORD&n=NAME`.
 *  - **Helper phone**: [HelperService] finds the streaming phone on the shared Wi-Fi or hotspot and forwards.
 */
object Helpers {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // ---- This phone as the streaming phone ----

    fun hostingOn(ctx: Context) = prefs(ctx).getBoolean("helpers_on", false)
    fun setHostingOn(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("helpers_on", on).apply()

    /** Start the phone's private hotspot for helpers while live, and hand them its details over Bluetooth. */
    fun makeHotspot(ctx: Context) = prefs(ctx).getBoolean("helpers_hotspot", true)
    fun setMakeHotspot(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("helpers_hotspot", on).apply()

    /** Short enough to type into Moblin by hand; random, so nobody else on a café Wi-Fi can join. */
    fun hostPassword(ctx: Context): String {
        val s = SecretStore(ctx)
        return s.get("helper_password") ?: random(12).also { s.put("helper_password", it) }
    }

    fun newHostPassword(ctx: Context) = SecretStore(ctx).put("helper_password", random(12))

    fun deviceName(ctx: Context): String =
        runCatching { Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let(Moblink::cleanName) ?: Build.MODEL

    /** What the engine needs to accept helpers on this stream, or null when that's off. */
    fun hosting(ctx: Context): HelperHosting? {
        if (!hostingOn(ctx)) return null
        val p = prefs(ctx)
        val serviceId = p.getString("helper_service_id", null)
            ?: UUID.randomUUID().toString().uppercase().also { p.edit().putString("helper_service_id", it).apply() }
        return HelperHosting(hostPassword(ctx), deviceName(ctx), serviceId)
    }

    fun pairingLink(ctx: Context): String =
        "fullbars://helper?p=${Uri.encode(hostPassword(ctx))}&n=${Uri.encode(deviceName(ctx))}"

    // ---- This phone as a helper ----

    /** The streaming phone this helper serves: its name (to pick it out on the network) and password. */
    data class Host(val name: String, val password: String, val manualAddress: String? = null)

    fun parseLink(link: String): Host? {
        val u = runCatching { Uri.parse(link.trim()) }.getOrNull() ?: return null
        if (u.scheme != "fullbars" || u.host != "helper") return null
        val p = u.getQueryParameter("p")?.takeIf { it.isNotEmpty() } ?: return null
        return Host(u.getQueryParameter("n").orEmpty(), p)
    }

    fun host(ctx: Context): Host? {
        val s = SecretStore(ctx)
        val pass = s.get("helping_password") ?: return null
        val p = prefs(ctx)
        return Host(p.getString("helping_name", "") ?: "", pass, p.getString("helping_address", null))
    }

    fun saveHost(ctx: Context, h: Host) {
        SecretStore(ctx).put("helping_password", h.password)
        prefs(ctx).edit().putString("helping_name", h.name).putString("helping_address", h.manualAddress).apply()
    }

    /** Stable, so a streaming phone recognises this helper when it reconnects. */
    fun relayId(ctx: Context): String {
        val p = prefs(ctx)
        return p.getString("relay_id", null)
            ?: UUID.randomUUID().toString().uppercase().also { p.edit().putString("relay_id", it).apply() }
    }

    private val rng = SecureRandom()
    private fun random(n: Int): String {
        val a = "abcdefghijkmnpqrstuvwxyz23456789"
        return String(CharArray(n) { a[rng.nextInt(a.length)] })
    }
}

/** What the helper phone shows. */
sealed interface HelperState {
    data object Off : HelperState
    data object NoWifi : HelperState
    /** Wi-Fi is switched off: the helper needs it on (not connected) to join the streaming phone's hotspot. */
    data object WifiOff : HelperState
    /** Ready to join the streaming phone's hotspot by itself (over Bluetooth) once that phone goes live. */
    data class WaitingToGoLive(val hostName: String) : HelperState
    /** Asked Android to join the streaming phone's hotspot (Android may ask the user to allow it). */
    data class JoiningHotspot(val hostName: String) : HelperState
    data object NoMobileData : HelperState
    data class Searching(val hostName: String) : HelperState
    data class Connecting(val hostName: String) : HelperState
    data object WrongPassword : HelperState
    /** Identified; [forwarding] once the streaming phone asked for a tunnel (it's live with Combine connections). */
    data class Helping(val hostName: String, val forwarding: Boolean, val upKbps: Long, val totalMb: Long) : HelperState
}

object HelperStatus {
    val state: StateFlow<HelperState> get() = mutable
    internal val mutable = MutableStateFlow<HelperState>(HelperState.Off)
}
