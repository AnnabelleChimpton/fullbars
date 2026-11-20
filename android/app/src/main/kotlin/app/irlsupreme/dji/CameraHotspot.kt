package app.irlsupreme.dji

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiSsid
import android.util.SparseIntArray
import app.irlsupreme.service.SecretStore
import java.security.SecureRandom
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * A private Wi-Fi network for the camera (Android's local-only hotspot). The app hands its name and password to the
 * camera over Bluetooth, and the user never sees either.
 *
 * On Android 16+ the app asks for 5 GHz: left to itself Android puts this hotspot on 2.4 GHz channel 1, too slow
 * for the Pocket's video (OnePlus 12: 5 GHz gave channel 40, 80 MHz, Wi-Fi 6). It also asks for a fixed name and
 * password of its own (kept in [SecretStore]) so the camera and helper phones can rejoin the same network; Android
 * 16.0 accepts that request but ignores it (a new "AndroidShare_NNNN" each time), so [fixedName] reports what Android
 * actually did. Older Android, or a phone that refuses 5 GHz: Android picks everything.
 *
 * The camera only needs to reach the phone (rtmp://<this hotspot's address>), never the internet; the phone keeps
 * streaming out over mobile data (and Wi-Fi too, if it supports being a hotspot and a Wi-Fi client at once).
 *
 * Android only starts one while the app is in the foreground (WifiServiceImpl checks), so it's started when the user
 * taps Go live and kept for the whole stream; Android tears it down if the app process dies.
 */
class CameraHotspot(private val context: Context) {
    /** [security]: SoftApConfiguration's security type (helper phones need it to join). */
    class Info(val ssid: String, val password: String, val address: String, val staApConcurrent: Boolean, val security: Int)

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private val main = Handler(Looper.getMainLooper())

    /** For the log and the Inspector: "5 GHz, fixed name", "5 GHz" or "Android's choice". */
    var mode = ""
        private set

    /**
     * [onLost]: a hotspot that was up went away by itself (seen on the OnePlus 12 when its own Wi-Fi was switched back
     * on mid-stream). Not called after [stop].
     */
    @SuppressLint("MissingPermission") // NEARBY_WIFI_DEVICES is requested before the stream starts
    fun start(onReady: (Info) -> Unit, onFailed: (String) -> Unit, onLost: (String) -> Unit = {}) {
        stopping = false
        // Best first: 5 GHz with a fixed name, then 5 GHz alone, then whatever Android picks.
        val tries = buildList {
            if (Build.VERSION.SDK_INT >= 36) {
                runCatching { fixedConfig(withName = true) }.getOrNull()?.let { add("5 GHz, fixed name" to it) }
                runCatching { fixedConfig(withName = false) }.getOrNull()?.let { add("5 GHz" to it) }
            }
            add("Android's choice" to null)
        }.distinctBy { it.first }
        fun next(i: Int) {
            // Stopped while an earlier 5 GHz try was being refused: don't ask Android for another hotspot nobody
            // will close (it would block every later start in this process as "already running").
            if (stopping) return
            val (name, cfg) = tries[i]
            mode = name
            if (i == tries.lastIndex) launch(cfg, onReady, onFailed, onLost)
            else launch(cfg, onReady, { reason -> Log.w(TAG, "camera hotspot: $name refused ($reason)"); next(i + 1) }, onLost)
        }
        next(0)
    }

    /**
     * 5 GHz, plus a fixed WPA2 name and password where Android has the setters (public from API 36.1; called by
     * reflection so the app still builds against API 36). WPA2 because cameras (the Pocket 3 included) may not speak
     * WPA3. The Pocket 3 does 5.15–5.25 and 5.725–5.85 GHz; Android avoids the radar channels in between for hotspots.
     */
    @androidx.annotation.RequiresApi(36)
    private fun fixedConfig(withName: Boolean): SoftApConfiguration? {
        val b = SoftApConfiguration.Builder()
            // Band 5 GHz, channel 0: Android picks the clearest 5 GHz channel.
            .setChannels(SparseIntArray().apply { put(SoftApConfiguration.BAND_5GHZ, 0) })
        if (!withName) return b.build()
        val setSsid = runCatching { b.javaClass.getMethod("setWifiSsid", WifiSsid::class.java) }.getOrNull()
        val setPass = runCatching { b.javaClass.getMethod("setPassphrase", String::class.java, Int::class.javaPrimitiveType) }.getOrNull()
        if (setSsid != null && setPass != null) {
            val secrets = SecretStore(context)
            val ssid = secrets.get(KEY_SSID) ?: ("Fullbars-" + random(4, "ABCDEFGHJKLMNPQRSTUVWXYZ23456789")).also { secrets.put(KEY_SSID, it) }
            val pass = secrets.get(KEY_PASS) ?: random(20, "abcdefghijkmnpqrstuvwxyz23456789").also { secrets.put(KEY_PASS, it) }
            setSsid.invoke(b, WifiSsid.fromBytes(ssid.toByteArray(Charsets.UTF_8)))
            setPass.invoke(b, pass, SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
            requestedSsid = ssid
            return b.build()
        }
        return null
    }

    /** Whether Android used our fixed name and password, so the network is the same from one stream to the next. */
    var fixedName = false
        private set
    private var requestedSsid: String? = null

    @SuppressLint("MissingPermission", "NewApi")
    private fun launch(cfg: SoftApConfiguration?, onReady: (Info) -> Unit, onFailed: (String) -> Unit, onLost: (String) -> Unit) {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
        val before = ipv4ByInterface()
        val concurrent = runCatching { wm.isStaApConcurrencySupported }.getOrDefault(false)
        try {
            val callback = object : WifiManager.LocalOnlyHotspotCallback() {
                private var up = false

                private fun lost(why: String) {
                    up = false
                    reservation = null
                    if (stopping) return
                    Log.w(TAG, "camera hotspot lost: $why")
                    onLost(why)
                }

                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    // The stream ended while Android was still starting this: stop() had no reservation to close then,
                    // so close it here, or it outlives the stream and every later go-live fails "already running".
                    if (stopping) { runCatching { r.close() }; return }
                    up = true
                    reservation = r
                    val got = r.softApConfiguration
                    @Suppress("DEPRECATION")
                    val ssid = (if (Build.VERSION.SDK_INT >= 33) got.wifiSsid?.toString()?.trim('"') else got.ssid) ?: ""
                    val pass = got.passphrase ?: ""
                    fixedName = cfg != null && ssid == requestedSsid
                    if (cfg != null && !fixedName) mode = "5 GHz"
                    // The hotspot's interface gets its address a moment after onStarted: look for the new one.
                    fun find(tries: Int) {
                        if (stopping) return // stop() already closed the reservation
                        // Last try: accept the soft-AP interface's address even if it was there before (Android can
                        // keep the interface up between two quick starts, so nothing looks new).
                        val addr = newAddress(before) ?: if (tries == 0) newAddress(emptyMap()) else null
                        when {
                            addr != null -> onReady(Info(ssid, pass, addr, concurrent, got.securityType))
                            tries > 0 -> main.postDelayed({ find(tries - 1) }, 250)
                            else -> {
                                Log.w(TAG, "camera hotspot: no address; interfaces ${ipv4ByInterface().keys}, Wi-Fi clients ${wifiClientInterfaces()}")
                                // Let it go: holding a hotspot we can't use makes every retry fail as "already running".
                                up = false
                                runCatching { r.close() }
                                reservation = null
                                onFailed("The phone's hotspot started but has no address")
                            }
                        }
                    }
                    Log.i(TAG, "camera hotspot up: $mode, \"$ssid\", security ${got.securityType} (STA+AP concurrent: $concurrent)")
                    find(20)
                }

                override fun onStopped() {
                    if (up) lost("stopped") else reservation = null
                }

                // Worded for anyone using it (the camera, helper phones): the Pocket and the live screen show these.
                override fun onFailed(reason: Int) = if (up) lost("failed ($reason)") else onFailed(
                    when (reason) {
                        ERROR_TETHERING_DISALLOWED -> BLOCKED
                        ERROR_INCOMPATIBLE_MODE -> "Turn off the phone's own hotspot first"
                        ERROR_NO_CHANNEL -> "No free Wi-Fi channel for the phone's hotspot"
                        else -> "Couldn't start the phone's hotspot ($reason)"
                    },
                )
            }
            if (cfg != null) wm.startLocalOnlyHotspotWithConfiguration(cfg, { main.post(it) }, callback)
            else wm.startLocalOnlyHotspot(callback, main)
        } catch (e: SecurityException) {
            if (cfg != null) { onFailed("security: ${e.message}"); return }
            onFailed("Fullbars needs the Nearby devices permission for its hotspot")
        } catch (e: IllegalArgumentException) {
            onFailed("Couldn't start the phone's hotspot (${e.message})")
        } catch (e: IllegalStateException) {
            // Android says this app already has a hotspot request: release ours so the next try can start afresh.
            runCatching { reservation?.close() }
            reservation = null
            onFailed("The phone's hotspot is already running")
        }
    }

    private var stopping = false

    fun stop() {
        stopping = true
        runCatching { reservation?.close() }
        reservation = null
    }

    private fun ipv4ByInterface(): Map<String, Set<String>> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.associate { nif ->
            nif.name to nif.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.toSet()
        }
    }.getOrDefault(emptyMap())

    /** The interfaces this phone uses as a Wi-Fi client right now (never the hotspot's own). */
    @Suppress("DEPRECATION") // allNetworks: also Wi-Fi without internet
    private fun wifiClientInterfaces(): Set<String> = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.allNetworks.filter { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@filter false
            // Android 16 lists the local-only hotspot itself as a Wi-Fi network too, marked LOCAL_NETWORK (seen on the
            // OnePlus 12: wlan2). That's ours, not a client: skipping it here is what lets us find its address.
            val localOnly = android.os.Build.VERSION.SDK_INT >= 35 && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !localOnly
        }.mapNotNull { cm.getLinkProperties(it)?.interfaceName }.toSet()
    }.getOrDefault(emptySet())

    /** The hotspot's gateway address on the phone: see [pickAddress]. */
    private fun newAddress(before: Map<String, Set<String>>): String? {
        val (name, addr) = pickAddress(before, ipv4ByInterface(), wifiClientInterfaces()) ?: return null
        Log.i(TAG, "camera hotspot on $name")
        return addr
    }

    companion object {
        private const val TAG = "Engine"
        private const val KEY_SSID = "cameraHotspotSsid"
        private const val KEY_PASS = "cameraHotspotPass"
        private const val BLOCKED = "Hotspots are blocked on this phone"
        private val rng = SecureRandom()
        private fun random(n: Int, alphabet: String) = String(CharArray(n) { alphabet[rng.nextInt(alphabet.length)] })

        /** A failure that trying again won't fix (the phone's policy forbids hotspots). */
        fun isPermanent(failure: String) = failure == BLOCKED

        private val SOFT_AP_NAMES = listOf("wlan", "swlan", "ap", "softap")
        private val PRIVATE = listOf("192.168.", "10.", "172.")

        /**
         * A private IPv4 address that wasn't there before the hotspot started, on a soft-AP interface (wlan*, swlan*,
         * ap*, softap*) that isn't one of the phone's Wi-Fi client interfaces ([wifiClients]). Mobile data comes up in
         * the same moment when the stream starts, and carriers hand out 10.x addresses: rmnet and friends are skipped by
         * name. 192.168.x first, since that's what Android gives its hotspots.
         */
        internal fun pickAddress(
            before: Map<String, Set<String>>,
            now: Map<String, Set<String>>,
            wifiClients: Set<String>,
        ): Pair<String, String>? = now.asSequence()
            .filter { (name, _) -> name !in wifiClients && SOFT_AP_NAMES.any { name.startsWith(it) } }
            .flatMap { (name, addrs) -> (addrs - before[name].orEmpty()).map { name to it } }
            .filter { (_, a) -> PRIVATE.any { a.startsWith(it) } }
            .sortedBy { (_, a) -> if (a.startsWith("192.168.")) 0 else 1 }
            .firstOrNull()
    }
}
