package app.irlsupreme.service

import android.content.Context
import app.irlsupreme.engine.CameraInput
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * The "External camera" setting: an RTMP camera (a DJI Pocket 3 started by Camera link, or any camera with a custom
 * RTMP output such as DJI Mimo's) streams to an RTMP server inside the app at rtmp://<phone>:1935/live/<path>. The
 * path is random per install, so other devices on a shared Wi-Fi can't push into the stream.
 */
object ExternalCamera {
    const val PORT = 1935

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    enum class Kind { PHONE, POCKET, RTMP }

    /**
     * Labs: the DJI Pocket integration is unofficial (reverse-engineered; DJI may change it in firmware), so it's
     * opt-in. On for anyone who has already chosen the Pocket.
     */
    fun labsDji(ctx: Context): Boolean = prefs(ctx).getBoolean("labs_dji", prefs(ctx).getString("camera_kind", null) == Kind.POCKET.name)
    fun setLabsDji(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("labs_dji", on).apply()
        if (!on && kind(ctx) == Kind.POCKET) setKind(ctx, Kind.PHONE)
    }

    fun kind(ctx: Context): Kind = runCatching { Kind.valueOf(prefs(ctx).getString("camera_kind", null) ?: "") }.getOrElse {
        if (prefs(ctx).getBoolean("camera_external", false)) Kind.RTMP else Kind.PHONE
    }
    fun setKind(ctx: Context, k: Kind) = prefs(ctx).edit().putString("camera_kind", k.name).apply()
    fun enabled(ctx: Context): Boolean = kind(ctx) != Kind.PHONE
    fun setEnabled(ctx: Context, on: Boolean) = setKind(ctx, if (on) Kind.RTMP else Kind.PHONE)

    /** The chosen DJI camera (Bluetooth address + name). */
    fun pocket(ctx: Context): Pair<String, String>? =
        prefs(ctx).getString("pocket_address", null)?.let { it to (prefs(ctx).getString("pocket_name", null) ?: "DJI camera") }
    fun setPocket(ctx: Context, address: String, name: String) =
        prefs(ctx).edit().putString("pocket_address", address).putString("pocket_name", name).apply()

    /**
     * How the Pocket reaches the phone. The automatic private hotspot needs no setup but Android won't let an app
     * pick its band, and on a OnePlus 12 it landed on 2.4 GHz channel 1 (20 MHz, Wi-Fi 4): the Pocket fell seconds
     * behind even at 3.5 Mbps. The phone's own hotspot ran on 5 GHz Wi-Fi 6.
     */
    enum class CameraNet { PHONE_HOTSPOT, WIFI, PRIVATE }

    fun cameraNet(ctx: Context): CameraNet =
        runCatching { CameraNet.valueOf(prefs(ctx).getString("camera_net", null) ?: "") }.getOrDefault(CameraNet.PHONE_HOTSPOT)
    fun setCameraNet(ctx: Context, n: CameraNet) = prefs(ctx).edit().putString("camera_net", n.name).apply()

    /** The network the camera joins for [CameraNet.PHONE_HOTSPOT] / [CameraNet.WIFI]: name and password (encrypted). */
    fun cameraWifi(ctx: Context): Pair<String, String>? {
        val secrets = SecretStore(ctx)
        val ssid = secrets.get("camera_wifi_ssid") ?: return null
        return ssid to (secrets.get("camera_wifi_password") ?: "")
    }
    fun setCameraWifi(ctx: Context, ssid: String, password: String) {
        SecretStore(ctx).put("camera_wifi_ssid", ssid.trim())
        SecretStore(ctx).put("camera_wifi_password", password)
    }

    /**
     * The phone's address the camera should stream to: on the phone's own hotspot, the hotspot interface's address;
     * on a shared Wi-Fi, the phone's Wi-Fi address. Null if that network isn't up.
     */
    fun phoneAddressFor(ctx: Context, net: CameraNet): String? = runCatching {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val staIfaces = cm.allNetworks.mapNotNull { n ->
            val caps = cm.getNetworkCapabilities(n)
            if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true) cm.getLinkProperties(n)?.interfaceName else null
        }.toSet()
        val ifaces = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        val candidates = when (net) {
            CameraNet.WIFI -> ifaces.filter { it.name in staIfaces }
            else -> ifaces.filter { it.name !in staIfaces && (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("swlan")) }
        }
        candidates.flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    /**
     * What the Pocket sends to the phone (kbps); the phone re-encodes it anyway. 3.5 Mbps: on 5 GHz home Wi-Fi a
     * Pocket 3 at 6 Mbps fell up to 1.5 s behind in a repeating sawtooth (its uplink tops out below that), while
     * 3.5 Mbps arrived at a steady 30 fps within 0.2 s.
     */
    fun pocketKbps(ctx: Context): Int = prefs(ctx).getInt("pocket_kbps", DEFAULT_POCKET_KBPS)
    const val DEFAULT_POCKET_KBPS = 3500
    fun setPocketKbps(ctx: Context, kbps: Int) = prefs(ctx).edit().putInt("pocket_kbps", kbps).apply()

    /** The app identity a DJI camera remembers its pairing approval by: one per install, never rotated. */
    fun djiIdentity(ctx: Context): String = prefs(ctx).getString("dji_identity", null) ?: run {
        val id = java.util.UUID.randomUUID().toString().take(32)
        prefs(ctx).edit().putString("dji_identity", id).apply()
        id
    }

    /** "live/<random>", created once. */
    fun path(ctx: Context): String = prefs(ctx).getString("camera_path", null) ?: run {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        val r = SecureRandom()
        val p = "live/" + (1..10).map { alphabet[r.nextInt(alphabet.length)] }.joinToString("")
        prefs(ctx).edit().putString("camera_path", p).apply()
        p
    }

    fun input(ctx: Context): CameraInput = if (enabled(ctx)) CameraInput.Rtmp(PORT, path(ctx)) else CameraInput.Phone

    /** Addresses a camera on the phone's Wi-Fi or hotspot can reach it at (Wi-Fi first). */
    fun urls(ctx: Context): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("swlan") || it.name.startsWith("eth")) }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress } }
            .map { "rtmp://$it:$PORT/${path(ctx)}" }
    }.getOrDefault(emptyList())
}

/** The Pocket's start-up progress, for the live screen (null when no DJI camera is in use). */
object PocketStatus {
    val state = kotlinx.coroutines.flow.MutableStateFlow<app.irlsupreme.dji.PocketState?>(null)
    fun set(s: app.irlsupreme.dji.PocketState?) { state.value = s }
}
