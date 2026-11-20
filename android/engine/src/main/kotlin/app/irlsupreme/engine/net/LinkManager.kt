package app.irlsupreme.engine.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.telephony.TelephonyManager
import android.util.Log
import srtlakt.LinkHint
import srtlakt.LinkPriority
import srtlakt.SrtlaSender
import srtlakt.moblink.HelperLoad
import srtlakt.moblink.Moblink
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A bonding link as the UI names it ("SIM · Wi-Fi · Sam's phone"). */
data class LinkLabel(val linkId: Int, val kind: String, val name: String)

/**
 * Turns Android networks into SRTLA links (the mechanism was first proven on a OnePlus 12):
 *  - requestNetwork(CELLULAR) keeps mobile data up even while Wi-Fi is connected (Android tears it down otherwise);
 *  - every available internet-capable Wi-Fi / cellular / Ethernet network gets its own DatagramChannel bound with
 *    Network.bindSocket, so each link really leaves through its own interface;
 *  - networks appearing/disappearing add/remove links live; lost networks fail fast instead of timing out.
 *  - a network that can't resolve the BRB server yet (it just came up, a captive portal, DNS still settling) is
 *    retried after 1, 2 and 4 s, then every 10 s, and at once when Android validates it. Lookups run on their own
 *    small pool, so a slow resolver never holds up other networks or helpers; once one validated network has
 *    resolved the server, later networks start with that address at once.
 *  - each link tells the bonder what kind of network it is (validated, captive portal...) so the group registers
 *    over the most promising one first;
 *  - when a network's address goes away (a new DHCP lease on the same Wi-Fi), its link gets a fresh socket and
 *    registers again at once, instead of sending from a dead address until the 4 s timeout.
 * Callbacks stay registered for the whole stream.
 *
 * [cellularAsSecondary]: while a validated, unmetered Wi-Fi or Ethernet network is up, mobile data only carries what
 * that network can't (plus a small warm-up share). Saves data; costs a moment of spill-over when Wi-Fi falters.
 * Off by default (every link equal, as always); never makes a link BACKUP.
 *
 * All work runs on one worker thread. Helper threads and Android's network callbacks can still fire while the stream
 * is ending, after [stop]: everything goes through [post], which drops work once the worker is shut down instead of
 * throwing (an uncaught RejectedExecutionException there used to end the app at almost every stream end with a helper).
 */
class LinkManager(
    private val context: Context,
    private val sender: SrtlaSender,
    private val host: String,
    private val port: Int,
    private val cellularAsSecondary: Boolean = false,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val worker = ScheduledThreadPoolExecutor(1) { Thread(it, "engine-links") }.apply {
        executeExistingDelayedTasksAfterShutdownPolicy = false // pending DNS retries die with the stream
    }
    private val dns = Executors.newFixedThreadPool(DNS_THREADS) { Thread(it, "engine-dns").apply { isDaemon = true } }
    private val links = ConcurrentHashMap<Network, LinkLabel>()
    private val helperLinks = ConcurrentHashMap<String, LinkLabel>() // by helper id
    private val callbacks = mutableListOf<ConnectivityManager.NetworkCallback>()

    /** Networks whose server lookup failed, waiting for a retry (worker thread only). */
    private class Pending(val transport: Int, var attempt: Int, var retry: ScheduledFuture<*>?)
    private val pending = HashMap<Network, Pending>()

    /** This phone's own links' details (worker thread only). */
    private class Own(
        val linkId: Int,
        val name: String,
        val transport: Int,
        val server: InetAddress,
        var hint: LinkHint,
        var priority: LinkPriority,
        var addresses: Set<InetAddress>,
        var lastRelinkMs: Long = Long.MIN_VALUE,
        var relink: ScheduledFuture<*>? = null,
    )
    private val own = HashMap<Network, Own>()
    /** Networks Android says are up, by transport (worker thread only): a lookup that finishes after a loss is dropped. */
    private val available = HashMap<Network, Int>()
    private val resolving = HashSet<Network>()
    /** The server's address as last resolved on a validated network (worker thread only). */
    private var cachedServer: InetAddress? = null
    /** Each helper's heat/battery history, kept across its tunnels (worker thread only). */
    private val helperLoads = HashMap<String, HelperLoad>()

    val labels: Collection<LinkLabel> get() = links.values + helperLinks.values

    /** How many of this phone's own networks are links (helpers not counted). */
    val ownLinkCount: Int get() = links.size

    /** The helper (by id) behind a link, if it is a helper link. */
    fun helperFor(linkId: Int): String? = helperLinks.entries.firstOrNull { it.value.linkId == linkId }?.key

    private fun post(block: () -> Unit) {
        if (worker.isShutdown) return
        try { worker.execute(block) } catch (_: RejectedExecutionException) {} // stopped meanwhile
    }

    private fun postDelayed(ms: Long, block: () -> Unit): ScheduledFuture<*>? {
        if (worker.isShutdown) return null
        return try { worker.schedule(block, ms, TimeUnit.MILLISECONDS) } catch (_: RejectedExecutionException) { null }
    }

    /**
     * A helper phone's tunnel ([endpoint] on the local network) as one more link. [localAddress] is this phone's end
     * of the helper's control connection: the link leaves through the same network. That's a Wi-Fi Network when the
     * helper is on a travel router or the same Wi-Fi (bound, or it would follow the default network to mobile data);
     * on this phone's own hotspot there is no Network object and the hotspot's routes apply to every socket.
     */
    fun addHelper(helperId: String, name: String, endpoint: InetSocketAddress, localAddress: InetAddress) = post {
        removeHelperNow(helperId)
        val via = networkWithAddress(localAddress)
        val load = helperLoads[helperId]
        val id = sender.addLink(name, endpoint, helperPriority(load), LinkHint.HELPER) {
            DatagramChannel.open().also { ch -> via?.bindSocket(ch.socket()) }
        }
        if (load != null && load.capScale < 1.0) sender.setLinkCapScale(id, load.capScale)
        helperLinks[helperId] = LinkLabel(id, "helper", name)
        Log.i(TAG, "link up: helper $name via ${via?.let { cm.getLinkProperties(it)?.interfaceName } ?: "hotspot"}")
    }

    fun removeHelper(helperId: String) = post { removeHelperNow(helperId) }

    /**
     * A helper's latest status report: one running hot or low on battery carries less (see [HelperLoad]) before it
     * fails. Safe from any thread.
     */
    fun helperStatus(helperId: String, thermal: Moblink.Thermal?, batteryPercent: Int?) = post {
        val load = helperLoads.getOrPut(helperId) { HelperLoad() }
        if (!load.update(thermal, batteryPercent)) return@post
        val l = helperLinks[helperId] ?: return@post
        sender.setLinkPriority(l.linkId, helperPriority(load))
        sender.setLinkCapScale(l.linkId, load.capScale)
        Log.i(TAG, "helper ${l.name}: ${if (load.demoted) "running hot or low, now carries only overflow" else "back to a full share"}" +
            if (load.capScale < 1.0) " (reduced cap)" else "")
    }

    private fun helperPriority(load: HelperLoad?) = if (load?.demoted == true) LinkPriority.SECONDARY else LinkPriority.PRIMARY

    private fun removeHelperNow(helperId: String) {
        val l = helperLinks.remove(helperId) ?: return
        sender.removeLink(l.linkId)
        Log.i(TAG, "link gone: helper ${l.name}")
    }

    @Suppress("DEPRECATION") // allNetworks: every network, including ones without internet
    private fun networkWithAddress(addr: InetAddress): Network? = cm.allNetworks.firstOrNull { n ->
        cm.getLinkProperties(n)?.linkAddresses?.any { it.address == addr } == true
    }

    fun start() {
        fun watch(transport: Int, request: Boolean) {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { post { available[network] = transport; add(network, transport) } }
                override fun onLost(network: Network) { post { available.remove(network); remove(network) } }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    val captive = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
                    post {
                        // Validated: DNS works now, if it didn't when the network came up.
                        if (validated) retryNow(network)
                        capabilitiesChanged(network, transport, validated, captive)
                    }
                }
                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                    val addresses = lp.linkAddresses.map { it.address }.toSet()
                    post { addressesChanged(network, addresses) }
                }
            }
            val req = NetworkRequest.Builder()
                .addTransportType(transport)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            try {
                if (request) cm.requestNetwork(req, cb) else cm.registerNetworkCallback(req, cb)
                callbacks += cb
            } catch (e: SecurityException) {
                // Never take the stream down over a link we can't watch; bond over whatever else is available.
                Log.e(TAG, "can't watch transport $transport: $e")
            }
        }
        watch(NetworkCapabilities.TRANSPORT_CELLULAR, request = true) // keep mobile data up alongside Wi-Fi
        watch(NetworkCapabilities.TRANSPORT_WIFI, request = false)
        watch(NetworkCapabilities.TRANSPORT_ETHERNET, request = false)
    }

    private fun add(network: Network, transport: Int) {
        if (links.containsKey(network) || network in resolving) return
        val cached = cachedServer
        if (cached != null) {
            addResolved(network, transport, cached)
            resolveAsync(network) { r -> r.getOrNull()?.let { remember(network, it) } } // keep the cache fresh
            return
        }
        resolving += network
        resolveAsync(network) { r ->
            resolving -= network
            if (available[network] == null || links.containsKey(network)) return@resolveAsync // lost meanwhile
            r.fold(
                onSuccess = { addr -> remember(network, addr); addResolved(network, transport, addr) },
                onFailure = { retryLater(network, transport, it) },
            )
        }
    }

    /** Looks the server up through [network] on the DNS pool and hands the result to [then] on the worker. */
    private fun resolveAsync(network: Network, then: (Result<InetAddress>) -> Unit) {
        if (dns.isShutdown) return
        try {
            dns.execute {
                val r = runCatching {
                    val all = network.getAllByName(host)
                    all.firstOrNull { it is Inet4Address } ?: all.first()
                }
                post { then(r) }
            }
        } catch (_: RejectedExecutionException) {} // stopping
    }

    /** Caches [addr] only from a validated network: a captive portal's DNS answers with the portal's own address. */
    private fun remember(network: Network, addr: InetAddress) {
        val validated = runCatching { cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }.getOrNull() == true
        if (validated) cachedServer = addr
    }

    private fun addResolved(network: Network, transport: Int, addr: InetAddress) {
        pending.remove(network)?.retry?.cancel(false)
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
        val hint = regHint(
            transport,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            captive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
        )
        val priority = defaultPriority(transport, strongUnmeteredUp(), cellularAsSecondary)
        val (kind, name) = describe(network, transport)
        val id = sender.addLink(name, InetSocketAddress(addr, port), priority, hint) {
            DatagramChannel.open().also { ch -> network.bindSocket(ch.socket()) }
        }
        links[network] = LinkLabel(id, kind, name)
        val lp = runCatching { cm.getLinkProperties(network) }.getOrNull()
        own[network] = Own(id, name, transport, addr, hint, priority, lp?.linkAddresses?.map { it.address }?.toSet().orEmpty())
        updatePriorities()
        Log.i(TAG, "link up: $name (${lp?.interfaceName}, $hint, $priority)")
    }

    private fun capabilitiesChanged(network: Network, transport: Int, validated: Boolean, captive: Boolean) {
        val o = own[network] ?: return
        val hint = regHint(transport, validated, captive)
        if (hint != o.hint) {
            o.hint = hint
            sender.setLinkHint(o.linkId, hint)
        }
        updatePriorities()
    }

    /** Whether a validated, unmetered Wi-Fi or Ethernet link is up (worker thread). */
    private fun strongUnmeteredUp(): Boolean = own.any { (n, o) ->
        (o.transport == NetworkCapabilities.TRANSPORT_WIFI || o.transport == NetworkCapabilities.TRANSPORT_ETHERNET) &&
            runCatching { cm.getNetworkCapabilities(n) }.getOrNull()?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            } == true
    }

    private fun updatePriorities() {
        if (!cellularAsSecondary) return
        val strong = strongUnmeteredUp()
        for (o in own.values) {
            val p = defaultPriority(o.transport, strong, true)
            if (p == o.priority) continue
            o.priority = p
            sender.setLinkPriority(o.linkId, p)
            Log.i(TAG, "link ${o.name}: $p")
        }
    }

    /**
     * The network's addresses changed. Most updates (DNS servers, routes, an added IPv6 address) don't matter; one that
     * removes an address the link may be sending from does: its socket stays tied to that address.
     */
    private fun addressesChanged(network: Network, addresses: Set<InetAddress>) {
        val o = own[network] ?: return
        val removed = removedAddress(o.addresses, addresses, ipv6 = o.server is Inet6Address)
        o.addresses = addresses
        if (!removed || o.relink != null) return
        val now = System.nanoTime() / 1_000_000
        val delay = relinkDelayMs(now, o.lastRelinkMs)
        val go = {
            o.relink = null
            if (own[network] === o) {
                o.lastRelinkMs = System.nanoTime() / 1_000_000
                Log.i(TAG, "link ${o.name}: its address changed, re-registering on a new socket")
                sender.relink(o.linkId)
            }
        }
        if (delay == 0L) go() else o.relink = postDelayed(delay, go)
    }

    private fun retryLater(network: Network, transport: Int, error: Throwable) {
        val p = pending.getOrPut(network) { Pending(transport, 0, null) }
        val delay = retryDelayMs(p.attempt++)
        Log.w(TAG, "link: can't resolve BRB server on $transport ($error); trying again in ${delay / 1000} s")
        p.retry?.cancel(false)
        p.retry = postDelayed(delay) { if (pending[network] === p) add(network, transport) }
    }

    private fun retryNow(network: Network) {
        val p = pending[network] ?: return
        p.retry?.cancel(false)
        p.retry = null
        add(network, p.transport)
    }

    private fun remove(network: Network) {
        pending.remove(network)?.retry?.cancel(false)
        own.remove(network)?.relink?.cancel(false)
        val l = links.remove(network) ?: return
        sender.removeLink(l.linkId)
        updatePriorities()
        Log.i(TAG, "link gone: ${l.name}")
    }

    private fun describe(network: Network, transport: Int): Pair<String, String> = when (transport) {
        NetworkCapabilities.TRANSPORT_CELLULAR -> {
            val carrier = runCatching {
                context.getSystemService(TelephonyManager::class.java).networkOperatorName
            }.getOrNull()?.takeIf { it.isNotBlank() }
            "cellular" to (carrier ?: "Mobile data")
        }
        NetworkCapabilities.TRANSPORT_WIFI -> "wifi" to "Wi-Fi"
        NetworkCapabilities.TRANSPORT_ETHERNET -> "ethernet" to "Ethernet"
        else -> "other" to "Network ${network.networkHandle}"
    }

    fun stop() {
        callbacks.forEach { runCatching { cm.unregisterNetworkCallback(it) } }
        callbacks.clear()
        post {
            pending.values.forEach { it.retry?.cancel(false) }
            pending.clear()
            own.values.forEach { it.relink?.cancel(false) }
            own.clear()
            (links.values + helperLinks.values).forEach { sender.removeLink(it.linkId) }
            links.clear()
            helperLinks.clear()
            helperLoads.clear()
        }
        worker.shutdown()
        dns.shutdownNow() // a lookup still running finishes on its own; its result is dropped
    }

    internal companion object {
        const val TAG = "Engine"
        private const val DNS_THREADS = 2
        /** At most one socket replacement per network per this long: address changes come in bursts of callbacks. */
        const val RELINK_MIN_INTERVAL_MS = 1_000L

        /** Server lookup retries for a new network: 1, 2, 4 s, then every 10 s for as long as it stays up. */
        fun retryDelayMs(attempt: Int): Long = when (attempt) {
            0 -> 1_000L
            1 -> 2_000L
            2 -> 4_000L
            else -> 10_000L
        }

        /** What the bonder should know about a network before it carries anything (see [LinkHint]). */
        fun regHint(transport: Int, validated: Boolean, captive: Boolean): LinkHint = when (transport) {
            NetworkCapabilities.TRANSPORT_ETHERNET -> LinkHint.ETHERNET
            NetworkCapabilities.TRANSPORT_CELLULAR -> LinkHint.CELLULAR
            NetworkCapabilities.TRANSPORT_WIFI -> when {
                captive -> LinkHint.CAPTIVE_PORTAL
                validated -> LinkHint.WIFI_VALIDATED
                else -> LinkHint.WIFI_UNVALIDATED
            }
            else -> LinkHint.UNKNOWN
        }

        /**
         * A link's priority class: mobile data is SECONDARY while a validated, unmetered Wi-Fi or Ethernet network is
         * up, when the streamer opted in ([enabled]); everything else PRIMARY. Never BACKUP by default, so losing the
         * Wi-Fi still fails over within the scheduler's spill-over, not after a timeout.
         */
        fun defaultPriority(transport: Int, strongUnmeteredUp: Boolean, enabled: Boolean): LinkPriority =
            if (enabled && strongUnmeteredUp && transport == NetworkCapabilities.TRANSPORT_CELLULAR) LinkPriority.SECONDARY
            else LinkPriority.PRIMARY

        /**
         * Whether an address the link may be sending from is gone: one of the server's family (IPv4 for an IPv4
         * server), not link-local, that was there before and isn't now.
         */
        fun removedAddress(before: Collection<InetAddress>, after: Collection<InetAddress>, ipv6: Boolean): Boolean {
            fun usable(a: InetAddress) = (if (ipv6) a is Inet6Address else a is Inet4Address) && !a.isLinkLocalAddress && !a.isLoopbackAddress
            val now = after.filter(::usable).toSet()
            return before.any { usable(it) && it !in now }
        }

        /** How long to hold a socket replacement so there's at most one per [RELINK_MIN_INTERVAL_MS]; 0 = now. */
        fun relinkDelayMs(nowMs: Long, lastRelinkMs: Long): Long =
            if (lastRelinkMs == Long.MIN_VALUE) 0 else maxOf(0L, lastRelinkMs + RELINK_MIN_INTERVAL_MS - nowMs)
    }
}
