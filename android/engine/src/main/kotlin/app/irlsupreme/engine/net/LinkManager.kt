package app.irlsupreme.engine.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.telephony.TelephonyManager
import android.util.Log
import srtlakt.SrtlaSender
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.util.concurrent.ConcurrentHashMap
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
 *    retried after 1, 2 and 4 s, then every 10 s, and at once when Android validates it.
 * Callbacks stay registered for the whole stream.
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
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val worker = ScheduledThreadPoolExecutor(1) { Thread(it, "engine-links") }.apply {
        executeExistingDelayedTasksAfterShutdownPolicy = false // pending DNS retries die with the stream
    }
    private val links = ConcurrentHashMap<Network, LinkLabel>()
    private val helperLinks = ConcurrentHashMap<String, LinkLabel>() // by helper id
    private val callbacks = mutableListOf<ConnectivityManager.NetworkCallback>()

    /** Networks whose server lookup failed, waiting for a retry (worker thread only). */
    private class Pending(val transport: Int, var attempt: Int, var retry: ScheduledFuture<*>?)
    private val pending = HashMap<Network, Pending>()

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
        val id = sender.addLink(name, endpoint) {
            DatagramChannel.open().also { ch -> via?.bindSocket(ch.socket()) }
        }
        helperLinks[helperId] = LinkLabel(id, "helper", name)
        Log.i(TAG, "link up: helper $name via ${via?.let { cm.getLinkProperties(it)?.interfaceName } ?: "hotspot"}")
    }

    fun removeHelper(helperId: String) = post { removeHelperNow(helperId) }

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
                override fun onAvailable(network: Network) { post { add(network, transport) } }
                override fun onLost(network: Network) { post { remove(network) } }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    // Validated: DNS works now, if it didn't when the network came up.
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) post { retryNow(network) }
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
        if (links.containsKey(network)) return
        val addr = runCatching {
            val all = network.getAllByName(host)
            all.firstOrNull { it is Inet4Address } ?: all.first()
        }.getOrElse {
            retryLater(network, transport, it)
            return
        }
        pending.remove(network)?.retry?.cancel(false)
        val (kind, name) = describe(network, transport)
        val id = sender.addLink(name, InetSocketAddress(addr, port)) {
            DatagramChannel.open().also { ch -> network.bindSocket(ch.socket()) }
        }
        links[network] = LinkLabel(id, kind, name)
        Log.i(TAG, "link up: $name (${(cm.getLinkProperties(network) as LinkProperties?)?.interfaceName})")
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
        val l = links.remove(network) ?: return
        sender.removeLink(l.linkId)
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
            (links.values + helperLinks.values).forEach { sender.removeLink(it.linkId) }
            links.clear()
            helperLinks.clear()
        }
        worker.shutdown()
    }

    internal companion object {
        const val TAG = "Engine"

        /** Server lookup retries for a new network: 1, 2, 4 s, then every 10 s for as long as it stays up. */
        fun retryDelayMs(attempt: Int): Long = when (attempt) {
            0 -> 1_000L
            1 -> 2_000L
            2 -> 4_000L
            else -> 10_000L
        }
    }
}
