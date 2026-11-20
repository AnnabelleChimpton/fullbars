package app.irlsupreme.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import app.irlsupreme.R
import app.irlsupreme.ui.MainActivity
import srtlakt.moblink.Moblink
import srtlakt.moblink.MoblinkRelay
import java.net.Inet4Address
import java.nio.channels.DatagramChannel

/**
 * Helper mode: this phone lends its mobile data to a streaming phone on the same Wi-Fi (its hotspot, a travel router).
 * Joins the streaming phone's private hotspot by itself when that phone shares it over Bluetooth ([HotspotFinder]:
 * Android asks the user to allow each new hotspot once). Finds the streaming phone over mDNS (`_moblink._tcp`, matching the name from the pairing code), else tries the Wi-Fi's gateway (the
 * streaming phone, when this phone is on its hotspot), then forwards its SRTLA traffic over this phone's mobile data.
 * Runs with the screen off in a pocket: a connectedDevice foreground service.
 *
 * It mostly waits (hours, in a bag, for the streaming phone to go live), so waiting is cheap: the wake and Wi-Fi locks
 * and the 1 s tick exist only while a relay is talking to a streaming phone, and mobile data is only held up alongside
 * a Wi-Fi a streaming phone could be on. After [IDLE_LIMIT_MS] without forwarding anything it stops itself and leaves
 * a notification to resume.
 */
class HelperService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var cm: ConnectivityManager
    private lateinit var nsd: NsdManager
    /** Any Wi-Fi this phone is on by itself (a travel router, the streaming phone's hotspot joined by hand). */
    private var ambientWifi: Network? = null
    /** The streaming phone's private hotspot, joined for this app alone via [joinHotspot]. Preferred. */
    private var hotspotNet: Network? = null
    private val wifi: Network? get() = hotspotNet ?: ambientWifi
    private var cellular: Network? = null
    private var activeWifi: Network? = null
    private var activeCellular: Network? = null
    private var finder: HotspotFinder? = null
    /** Bumped for each new [HotspotFinder] being built, so a slow build for an old code is dropped. */
    private var finderGeneration = 0
    private var hotspotRequest: ConnectivityManager.NetworkCallback? = null
    private var hotspotSsid: String? = null
    /** After Android couldn't join (or the user said no), don't ask about the same hotspot again until then. */
    private var askAgainAtMs = 0L
    /** Hotspot details that arrived while Wi-Fi was switched off: joined as soon as it's on. */
    private var pendingHotspot: HotspotShare.Credentials? = null
    private lateinit var wm: WifiManager
    private var relayIdentified = false
    /** Whether onStartCommand has set everything up (and onDestroy has something to undo). */
    private var running = false
    private val wifiSwitch = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (isInitialStickyBroadcast) return // the current state, replayed on registering: nothing changed
            if (relay != null && relayState is MoblinkRelay.State.Connected) return
            when (intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1)) {
                WifiManager.WIFI_STATE_ENABLED -> {
                    Log.i(TAG, "helper: Wi-Fi switched on")
                    askAgainAtMs = 0
                    finder?.readSoon()
                    val c = pendingHotspot
                    pendingHotspot = null
                    if (c != null) joinHotspot(c) else if (wifi == null) publish(idleState())
                }
                WifiManager.WIFI_STATE_DISABLED -> if (wifi == null) publish(idleState())
            }
        }
    }
    /** Bluetooth back on: the finder can listen again (it only started once before, and said "waiting" regardless). */
    private val bluetoothSwitch = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> {
                    Log.i(TAG, "helper: Bluetooth switched on")
                    finder?.readSoon()
                    finder?.start()
                }
                BluetoothAdapter.STATE_OFF -> {
                    Log.i(TAG, "helper: Bluetooth switched off")
                    finder?.onAdapterOff()
                }
                else -> return
            }
            if (wifi == null && hotspotRequest == null) publish(idleState())
        }
    }
    private val callbacks = mutableListOf<ConnectivityManager.NetworkCallback>()
    /** Our request that keeps mobile data up alongside Wi-Fi; only while there is a Wi-Fi to help on. */
    private var cellularRequest: ConnectivityManager.NetworkCallback? = null
    private var cellularRequestedAtMs = 0L
    private var discovery: NsdManager.DiscoveryListener? = null
    private var relay: MoblinkRelay? = null
    private var relayAddress: String? = null
    private var relayFromMdns = false
    /** The mDNS service the relay was found through, to notice when the streaming phone stops advertising. */
    private var relayServiceName: String? = null
    /** Failed connection attempts in a row for the current relay (it reconnects every 5 s by itself). */
    private var relayFailures = 0
    private var relayState: MoblinkRelay.State = MoblinkRelay.State.Connecting
    private var lastUp = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var host: Helpers.Host
    /** The last time this phone forwarded the stream (or was started, or given a new code), for the idle stop. */
    private var lastUsefulMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val h = Helpers.host(this) ?: run { stopSelf(); return START_NOT_STICKY }
        if (running) {
            // "Scan the code again" while helping: a different streaming phone or password means starting over.
            if (h != host) switchHost(h)
            return START_STICKY
        }
        running = true
        host = h
        lastUsefulMs = System.currentTimeMillis()
        getSystemService(NotificationManager::class.java).cancel(IDLE_NOTIFICATION_ID)
        startForeground(NOTIFICATION_ID, notification("Looking for ${h.name.ifEmpty { "the streaming phone" }}"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        cm = getSystemService(ConnectivityManager::class.java)
        nsd = getSystemService(NsdManager::class.java)
        wm = getSystemService(WifiManager::class.java)
        // System broadcasts only (protected): no export flag needed below Android 13.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(wifiSwitch, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION), RECEIVER_NOT_EXPORTED)
            registerReceiver(bluetoothSwitch, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(wifiSwitch, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION))
            registerReceiver(bluetoothSwitch, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        }
        watchNetworks()
        main.postDelayed(idleCheck, IDLE_CHECK_MS)
        buildFinder(h)
        return START_STICKY
    }

    /** The Bluetooth finder for [h]'s code. PBKDF2 takes a moment: off the main thread. */
    private fun buildFinder(h: Helpers.Host) {
        val generation = ++finderGeneration
        Thread {
            val keys = HotspotShare.Keys(h.password)
            main.post {
                if (!running || generation != finderGeneration) return@post // stopped, or a newer code meanwhile
                finder = HotspotFinder(this, keys, wanted = ::wantHotspot, onFound = ::joinHotspot).also { it.start() }
                if (wifi == null && hotspotRequest == null) publish(idleState())
            }
        }.start()
    }

    /** A new code: forget the old streaming phone (its hotspot, its password) and look for the new one. */
    private fun switchHost(h: Helpers.Host) {
        Log.i(TAG, "helper: new code; starting over")
        host = h
        lastUsefulMs = System.currentTimeMillis()
        stopRelay()
        stopDiscovery()
        main.removeCallbacks(tryGateway)
        main.removeCallbacks(rediscover)
        finder?.stop(); finder = null
        dropHotspot()
        pendingHotspot = null
        declinedSsid = null
        askAgainAtMs = 0
        activeWifi = null
        activeCellular = null
        publish(idleState())
        buildFinder(h)
        if (wifi != null) restart("new code") // a shared Wi-Fi or travel router: the new phone may be right here
    }

    private fun watchNetworks() {
        fun watch(req: NetworkRequest, onUp: (Network) -> Unit, onDown: (Network) -> Unit) {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) { main.post { onUp(n) } }
                override fun onLost(n: Network) { main.post { onDown(n) } }
            }
            cm.registerNetworkCallback(req, cb)
            callbacks += cb
        }
        // Mobile data: watched only. [holdCellular] asks Android to keep it up alongside Wi-Fi when it's needed.
        watch(CELLULAR,
            { cellular = it; refresh("mobile data up") }, { if (cellular == it) { cellular = null; refresh("mobile data lost") } })
        // Any Wi-Fi, with or without internet (a phone's hotspot often has none).
        watch(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            { ambientWifi = it; refresh("Wi-Fi up") }, { if (ambientWifi == it) { ambientWifi = null; refresh("Wi-Fi lost") } })
    }

    /**
     * Android drops mobile data while Wi-Fi is connected unless an app asks for it. Asked for while there's a Wi-Fi a
     * streaming phone could be on; let go otherwise, so a waiting helper doesn't keep its mobile radio up for hours.
     */
    private fun holdCellular(want: Boolean) {
        val held = cellularRequest
        if (want && held == null) {
            val cb = object : ConnectivityManager.NetworkCallback() {} // the watch in [watchNetworks] reports it
            try {
                cm.requestNetwork(CELLULAR, cb)
                cellularRequest = cb
                cellularRequestedAtMs = System.currentTimeMillis()
            } catch (e: RuntimeException) {
                Log.w(TAG, "helper: can't keep mobile data up: $e")
            }
        } else if (!want && held != null) {
            runCatching { cm.unregisterNetworkCallback(held) }
            cellularRequest = null
        }
    }

    /** Restart only when the networks the relay would use changed (the hotspot shows up in both callbacks). */
    private fun refresh(why: String) {
        if (wifi == activeWifi && cellular == activeCellular) return
        restart(why)
    }

    /**
     * Read the hotspot details while not helping already and not on the hotspot. Also while Android is still trying to
     * join one after [STALE_REQUEST_MS]: the streaming phone may have restarted its hotspot under a new name.
     */
    private fun wantHotspot() = relayState !is MoblinkRelay.State.Connected && hotspotNet == null &&
        (hotspotRequest == null || System.currentTimeMillis() - requestedAtMs > STALE_REQUEST_MS)
    private var requestedAtMs = 0L
    private var declinedSsid: String? = null

    /** Ask Android for the streaming phone's hotspot, for this app alone (mobile data stays the phone's default). */
    private fun joinHotspot(c: HotspotShare.Credentials) {
        if (hotspotRequest != null && c.ssid == hotspotSsid) return
        // The same hotspot Android just couldn't join (or the user declined): not again for a while. A new one: now.
        if (c.ssid == declinedSsid && System.currentTimeMillis() < askAgainAtMs) return
        dropHotspot()
        if (!wm.isWifiEnabled) { // Android rejects the request outright; wait for the switch instead
            Log.i(TAG, "helper: Wi-Fi is switched off; joining when it's on")
            pendingHotspot = c
            if (wifi == null) publish(HelperState.WifiOff)
            return
        }
        val spec = try {
            WifiNetworkSpecifier.Builder().setSsid(c.ssid).apply {
                if (c.security == HotspotShare.SECURITY_WPA3) setWpa3Passphrase(c.passphrase) else setWpa2Passphrase(c.passphrase)
            }.build()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "helper: can't join that hotspot: ${e.message}"); return
        }
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec).build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { main.post { if (hotspotRequest === this) { hotspotNet = n; refresh("joined the hotspot") } } }
            override fun onLost(n: Network) {
                main.post {
                    if (hotspotRequest !== this) return@post
                    // The stream ended (or the phones moved apart): let go, so the next stream's hotspot is asked for fresh.
                    dropHotspot()
                    finder?.readSoon()
                    refresh("left the hotspot")
                }
            }
            override fun onUnavailable() {
                main.post {
                    if (hotspotRequest !== this) return@post
                    hotspotRequest = null
                    if (!wm.isWifiEnabled) {
                        Log.i(TAG, "helper: Wi-Fi went off before joining; joining when it's on")
                        pendingHotspot = c
                    } else {
                        Log.i(TAG, "helper: couldn't join the hotspot (declined, or out of range)")
                        declinedSsid = c.ssid
                        askAgainAtMs = System.currentTimeMillis() + ASK_AGAIN_MS
                    }
                    if (wifi == null) publish(idleState())
                }
            }
        }
        hotspotRequest = cb
        hotspotSsid = c.ssid
        requestedAtMs = System.currentTimeMillis()
        publish(HelperState.JoiningHotspot(host.name))
        try {
            cm.requestNetwork(req, cb)
        } catch (e: RuntimeException) {
            Log.w(TAG, "helper: can't ask to join the hotspot: $e")
            hotspotRequest = null
            if (wifi == null) publish(idleState())
        }
    }

    private fun dropHotspot() {
        hotspotRequest?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        hotspotRequest = null
        hotspotNet = null
    }

    /** (Re)start looking for the streaming phone with the current networks. */
    private fun restart(why: String) {
        Log.i(TAG, "helper: $why")
        stopRelay()
        stopDiscovery()
        main.removeCallbacks(tryGateway)
        main.removeCallbacks(rediscover)
        main.removeCallbacks(noMobileData)
        val w = wifi
        activeWifi = w
        activeCellular = cellular
        holdCellular(w != null)
        when {
            w == null && hotspotRequest != null -> publish(HelperState.JoiningHotspot(host.name))
            w == null -> publish(idleState())
            cellular == null -> {
                // Android takes a moment to bring mobile data up alongside a new Wi-Fi: don't cry "no mobile data" yet.
                val wait = cellularRequestedAtMs + CELLULAR_GRACE_MS - System.currentTimeMillis()
                if (wait > 0) { publish(HelperState.Searching(host.name)); main.postDelayed(noMobileData, wait) }
                else publish(HelperState.NoMobileData)
            }
            else -> {
                publish(HelperState.Searching(host.name))
                host.manualAddress?.let { startRelay(it, fromMdns = false); return }
                discover(w)
                main.postDelayed(tryGateway, GATEWAY_FALLBACK_MS)
            }
        }
    }

    private val noMobileData = Runnable { if (wifi != null && cellular == null) publish(HelperState.NoMobileData) }

    private val tryGateway = Runnable {
        if (relay != null) return@Runnable
        val lp = wifi?.let { cm.getLinkProperties(it) } ?: return@Runnable
        val gw = lp.dhcpServerAddress?.takeIf { it is Inet4Address }
            ?: lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway
        gw?.hostAddress?.let { Log.i(TAG, "helper: no mDNS answer yet, trying the gateway $it"); startRelay(it, fromMdns = false) }
    }

    /** Search again from scratch: re-reports a streaming phone mDNS still remembers, after we gave up on it. */
    private val rediscover = Runnable {
        val w = wifi ?: return@Runnable
        if (relay != null || cellular == null) return@Runnable
        stopDiscovery()
        discover(w)
    }

    private fun discover(w: Network) {
        val l = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { main.post { resolve(info) } }
            override fun onServiceLost(info: NsdServiceInfo) { main.post { serviceLost(info.serviceName) } }
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { Log.w(TAG, "helper: mDNS failed ($code)") }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
        }
        try {
            when {
                android.os.Build.VERSION.SDK_INT >= 33 ->
                    nsd.discoverServices(Moblink.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, w, { main.post(it) }, l)
                else -> nsd.discoverServices(Moblink.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
            }
            discovery = l
        } catch (e: RuntimeException) {
            Log.w(TAG, "helper: can't search: $e")
        }
    }

    /**
     * The streaming phone stopped advertising (its stream ended): stop knocking on its closed port. While still
     * connected the relay's own read timeout decides; mDNS finds the phone again when it next goes live.
     */
    private fun serviceLost(name: String) {
        if (relay == null || !relayFromMdns || name != relayServiceName) return
        if (relayState is MoblinkRelay.State.Connected) return
        Log.i(TAG, "helper: \"$name\" stopped advertising; waiting for it")
        stopRelay()
        publish(idleState())
    }

    @Suppress("DEPRECATION") // resolveService: fine for the one or two services on a hotspot
    private fun resolve(info: NsdServiceInfo) {
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, code: Int) { Log.i(TAG, "helper: resolve failed ($code)") }
            override fun onServiceResolved(i: NsdServiceInfo) {
                val name = i.attributes["name"]?.toString(Charsets.UTF_8) ?: return
                @Suppress("DEPRECATION")
                val all = if (android.os.ext.SdkExtensions.getExtensionVersion(android.os.Build.VERSION_CODES.TIRAMISU) >= 7) i.hostAddresses
                else listOfNotNull(i.host)
                val addr = (all.firstOrNull { it is Inet4Address } ?: all.firstOrNull())?.hostAddress ?: return
                main.post {
                    Log.i(TAG, "helper: found \"$name\" at $addr")
                    // The paired phone by name; any Moblink streamer if the code carried no name.
                    if (host.name.isNotEmpty() && name != host.name) return@post
                    val identified = relayState is MoblinkRelay.State.Connected
                    if (relay == null || (!relayFromMdns && !identified && addr != relayAddress)) {
                        startRelay(addr, fromMdns = true, port = i.port, serviceName = i.serviceName)
                    }
                }
            }
        })
    }

    private fun startRelay(address: String, fromMdns: Boolean, port: Int = Moblink.DEFAULT_PORT, serviceName: String? = null) {
        stopRelay()
        val w = wifi ?: return
        val c = cellular ?: return
        relayAddress = address
        relayFromMdns = fromMdns
        relayServiceName = serviceName
        relayFailures = 0
        publish(HelperState.Connecting(host.name))
        relayIdentified = false
        acquireLocks()
        var self: MoblinkRelay? = null
        val r = MoblinkRelay(
            address, port, Helpers.relayId(this), Helpers.deviceName(this), host.password,
            connectSocket = { h, p -> w.socketFactory.createSocket().apply { connect(java.net.InetSocketAddress(h, p), 10_000) } },
            streamerChannel = { DatagramChannel.open().also { w.bindSocket(it.socket()) } },
            destinationChannel = { DatagramChannel.open().also { c.bindSocket(it.socket()) } },
            resolve = { name -> c.getAllByName(name).let { all -> all.firstOrNull { it is Inet4Address } ?: all.first() } },
            status = ::status,
            onState = { s -> main.post { if (relay === self) onRelayState(s) } }, // not a stopped relay's last word
            log = { Log.i(TAG, it) },
        )
        self = r
        relay = r
        r.start()
        main.removeCallbacks(tick)
        main.postDelayed(tick, 1_000)
    }

    /** What to show while there's no streaming phone to help. */
    private fun idleState(): HelperState = when {
        !wm.isWifiEnabled -> HelperState.WifiOff
        finder?.usable == true -> HelperState.WaitingToGoLive(host.name)
        wifi == null -> HelperState.NoWifi
        else -> HelperState.Searching(host.name)
    }

    private fun onRelayState(s: MoblinkRelay.State) {
        relayState = s
        if (s is MoblinkRelay.State.WrongPassword) {
            // Retrying can't fix the code; a rescan (or a network change) starts over. Meanwhile hold nothing.
            stopRelay()
            publish(HelperState.WrongPassword)
            return
        }
        if (s is MoblinkRelay.State.Connected) { relayIdentified = true; relayFailures = 0 }
        if (s is MoblinkRelay.State.Waiting && host.manualAddress == null) {
            relayFailures++
            // A guessed gateway that refuses is just a router (e.g. home Wi-Fi after the stream ended): stop knocking
            // every few seconds, wait (mDNS and Bluetooth keep looking) and try it again now and then.
            val giveUp = relayGivesUp(hotspotNet != null, relayFromMdns, relayIdentified, relayFailures, MAX_RELAY_FAILURES)
            if (giveUp) {
                Log.i(TAG, "helper: nothing answers at $relayAddress; waiting")
                val fromMdns = relayFromMdns
                stopRelay()
                publish(idleState())
                main.postDelayed(if (fromMdns) rediscover else tryGateway, GATEWAY_RETRY_MS)
                return
            }
        }
        publishRelay()
    }

    private fun publishRelay() {
        val r = relay ?: return
        when (val s = relayState) {
            MoblinkRelay.State.WrongPassword -> publish(HelperState.WrongPassword)
            is MoblinkRelay.State.Connected -> publish(HelperState.Helping(host.name, s.tunnelTo != null, upKbps, r.bytesUp.get() / 1_000_000))
            else -> publish(HelperState.Connecting(host.name))
        }
    }

    private var upKbps = 0L
    /** Byte counts for the helper's screen, once a second while a relay exists. */
    private val tick = object : Runnable {
        override fun run() {
            val r = relay ?: return
            val up = r.bytesUp.get()
            upKbps = (up - lastUp) * 8 / 1000
            lastUp = up
            if (relayState is MoblinkRelay.State.Connected) publishRelay()
            main.postDelayed(this, 1_000)
        }
    }

    /** Stops the helper after [IDLE_LIMIT_MS] without forwarding: nobody needs it, and it shouldn't run for days. */
    private val idleCheck = object : Runnable {
        override fun run() {
            // Wall-clock time: Handler delays pause while the phone sleeps, a pocketed idle helper's normal state.
            if (System.currentTimeMillis() - lastUsefulMs > IDLE_LIMIT_MS) { stopForIdle(); return }
            main.postDelayed(this, IDLE_CHECK_MS)
        }
    }

    private fun stopForIdle() {
        Log.i(TAG, "helper: nothing to forward for ${IDLE_LIMIT_MS / 3_600_000} h; stopping")
        val nm = getSystemService(NotificationManager::class.java)
        val resume = PendingIntent.getForegroundService(this, 3, Intent(this, HelperService::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(IDLE_NOTIFICATION_ID, Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_fullbars)
            .setContentTitle("Fullbars helper")
            .setContentText("Helper stopped after ${IDLE_LIMIT_MS / 3_600_000} h idle: tap to resume")
            .setContentIntent(resume)
            .setAutoCancel(true)
            .build())
        stopSelf()
    }

    private fun status(): Moblink.StatusReport {
        val battery = runCatching { getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }
            .getOrNull()?.takeIf { it in 0..100 }
        val thermal = when (getSystemService(PowerManager::class.java).currentThermalStatus) {
            in PowerManager.THERMAL_STATUS_SEVERE..Int.MAX_VALUE -> Moblink.Thermal.RED
            PowerManager.THERMAL_STATUS_MODERATE -> Moblink.Thermal.YELLOW
            else -> Moblink.Thermal.WHITE
        }
        return Moblink.StatusReport(battery, thermal)
    }

    private var lastPublished: HelperState? = null
    private fun publish(s: HelperState) {
        HelperStatus.mutable.value = s
        if (s is HelperState.Helping && s.forwarding) lastUsefulMs = System.currentTimeMillis()
        val nm = getSystemService(NotificationManager::class.java)
        // The notification only changes with the situation, not with every byte count.
        val text = describe(s)
        if (lastPublished?.let(::describe) != text) nm.notify(NOTIFICATION_ID, notification(text))
        // Android 16 asks before joining each new hotspot, and the ongoing notification is silent: buzz for that tap.
        if (s is HelperState.JoiningHotspot) {
            if (lastPublished !is HelperState.JoiningHotspot) main.postDelayed(tapAlert, TAP_ALERT_DELAY_MS)
        } else {
            main.removeCallbacks(tapAlert)
            nm.cancel(TAP_NOTIFICATION_ID)
        }
        lastPublished = s
    }

    /** Posted only if joining is still pending after a moment, so a hotspot Android joins by itself never buzzes. */
    private val tapAlert = Runnable {
        if (HelperStatus.mutable.value !is HelperState.JoiningHotspot) return@Runnable
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(TAP_CHANNEL, "Helper needs a tap", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(TAP_NOTIFICATION_ID, Notification.Builder(this, TAP_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_fullbars)
            .setContentTitle("Tap Connect to keep helping")
            .setContentText("Android asks before joining ${host.name.ifEmpty { "the streaming phone" }}'s hotspot")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build())
    }

    private fun describe(s: HelperState): String = when (s) {
        HelperState.Off -> "Off"
        HelperState.NoWifi -> "Join the streaming phone's hotspot or Wi‑Fi"
        HelperState.WifiOff -> "Turn on Wi‑Fi so this phone can join ${host.name.ifEmpty { "the streaming phone" }}'s hotspot"
        is HelperState.WaitingToGoLive -> "Waiting for ${s.hostName.ifEmpty { "the streaming phone" }} to go live"
        is HelperState.JoiningHotspot -> "Joining ${s.hostName.ifEmpty { "the streaming phone" }}'s hotspot: tap Connect if asked"
        HelperState.NoMobileData -> "This phone needs its own mobile data"
        is HelperState.Searching -> "Looking for ${s.hostName.ifEmpty { "the streaming phone" }}"
        is HelperState.Connecting -> "Connecting to ${s.hostName.ifEmpty { "the streaming phone" }}"
        HelperState.WrongPassword -> "Wrong code: scan the streaming phone's code again"
        is HelperState.Helping -> if (s.forwarding) "Helping ${s.hostName.ifEmpty { "the stream" }}" else "Ready: ${s.hostName.ifEmpty { "the streaming phone" }} isn't live yet"
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Helper phone", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, HelperService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_fullbars)
            .setContentTitle("Fullbars helper")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop helping", stop).build())
            .build()
    }

    /** Held while a relay talks to a streaming phone: forwarding with the screen off needs the CPU and fast Wi-Fi. */
    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fullbars:helper")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "fullbars:helper").apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }; wakeLock = null
        wifiLock?.let { runCatching { if (it.isHeld) it.release() } }; wifiLock = null
    }

    private fun stopRelay() {
        relay?.let { r -> Thread { r.stop() }.start() }
        relay = null
        relayAddress = null
        relayServiceName = null
        relayState = MoblinkRelay.State.Connecting
        lastUp = 0
        main.removeCallbacks(tick)
        releaseLocks()
    }

    private fun stopDiscovery() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (running) {
            running = false
            stopRelay()
            stopDiscovery()
            finder?.stop(); finder = null
            dropHotspot()
            holdCellular(false)
            runCatching { unregisterReceiver(wifiSwitch) }
            runCatching { unregisterReceiver(bluetoothSwitch) }
            callbacks.forEach { runCatching { cm.unregisterNetworkCallback(it) } }
            callbacks.clear()
            getSystemService(NotificationManager::class.java).cancel(TAP_NOTIFICATION_ID)
        }
        releaseLocks()
        HelperStatus.mutable.value = HelperState.Off
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Engine"
        private const val CHANNEL = "helper"
        private const val TAP_CHANNEL = "helper_tap"
        private const val NOTIFICATION_ID = 7
        private const val TAP_NOTIFICATION_ID = 8
        private const val IDLE_NOTIFICATION_ID = 9
        private const val ACTION_STOP = "stop"
        private const val GATEWAY_FALLBACK_MS = 4_000L
        private const val ASK_AGAIN_MS = 120_000L
        private const val GATEWAY_RETRY_MS = 30_000L
        private const val STALE_REQUEST_MS = 20_000L
        private const val CELLULAR_GRACE_MS = 5_000L
        private const val TAP_ALERT_DELAY_MS = 3_000L
        /** A relay that fails this many times in a row (5 s apart) is given up on until the phone shows up again. */
        private const val MAX_RELAY_FAILURES = 3
        private const val IDLE_LIMIT_MS = 2 * 3_600_000L
        private const val IDLE_CHECK_MS = 60_000L
        private val CELLULAR: NetworkRequest = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()

        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, HelperService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, HelperService::class.java)) }
    }
}

/**
 * Whether a helper relay that just failed should be stopped and retried 30 s later. Never while on the streaming
 * phone's private hotspot: that phone is the only thing there to reach, and the retry is a Handler delay that pauses
 * while a pocketed helper sleeps (its wake lock goes with the relay), so it could stay off for most of the stream.
 * There the relay keeps its own 5 s reconnects with its locks held. Top level so a JVM test can call it without
 * loading HelperService (its companion builds Android objects).
 */
internal fun relayGivesUp(onHotspot: Boolean, fromMdns: Boolean, identified: Boolean, failures: Int, maxFailures: Int): Boolean =
    !onHotspot && ((!fromMdns && !identified) || failures >= maxFailures)
