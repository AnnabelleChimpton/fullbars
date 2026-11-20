package app.irlsupreme.engine.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import app.irlsupreme.engine.HelperHosting
import srtlakt.moblink.Moblink
import srtlakt.moblink.MoblinkStreamer
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Lets helper phones join this stream (Moblink, so Moblin's relays work too): runs the Moblink server for the length
 * of a bonded stream, advertises it over mDNS (`_moblink._tcp`, TXT name=…), and turns each helper's tunnel into a
 * bonding link through [links]. Helpers are told to send to the BRB server at [host]:[port].
 *
 * Link failures arrive from the bonding sender's thread at any time, including after [stop]: [post] drops them once the
 * worker is shut down rather than letting a RejectedExecutionException end the app.
 */
class HelperHost(
    context: Context,
    private val hosting: HelperHosting,
    private val links: LinkManager,
    private val host: String,
    private val port: Int,
) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "engine-helpers") }
    private var streamer: MoblinkStreamer? = null
    private var registration: NsdManager.RegistrationListener? = null

    val helpers: List<MoblinkStreamer.Helper> get() = streamer?.helpers.orEmpty()

    private fun post(block: () -> Unit) {
        if (worker.isShutdown) return
        try { worker.execute(block) } catch (_: RejectedExecutionException) {} // stopped meanwhile
    }

    fun start() = post {
        val s = try {
            MoblinkStreamer(
                hosting.password,
                listenPort = hosting.port,
                onTunnel = { h, endpoint -> links.addHelper(h.id, h.name, endpoint, h.localAddress) },
                onTunnelGone = { h -> links.removeHelper(h.id) },
                // Heat and battery, every few seconds: a helper that runs hot or low carries less before it fails.
                onStatus = { h -> links.helperStatus(h.id, h.thermal, h.batteryPercentage) },
                log = { Log.i(TAG, it) },
            )
        } catch (e: java.io.IOException) {
            Log.w(TAG, "helpers: can't listen on ${hosting.port}: $e")
            return@post
        }
        streamer = s
        // Moblin sends an address; resolving here also avoids a helper resolving through a captive DNS.
        val address = runCatching { InetAddress.getByName(host).hostAddress }.getOrNull() ?: host
        s.setDestination(address, port)
        advertise(s.port)
        Log.i(TAG, "helpers: waiting on port ${s.port} as \"${hosting.displayName}\"")
    }

    /** A helper's link failed: ask it for a fresh tunnel (its phone may have switched cell or Wi-Fi). */
    fun linkFailed(linkId: Int) {
        val id = links.helperFor(linkId) ?: return
        post { streamer?.restartTunnel(id) }
    }

    private fun advertise(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = hosting.serviceId
            serviceType = Moblink.SERVICE_TYPE
            setPort(port)
            setAttribute("name", hosting.displayName)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { Log.i(TAG, "helpers: advertised as ${i.serviceName}") }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { Log.w(TAG, "helpers: mDNS registration failed ($code)") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            registration = l
        } catch (e: RuntimeException) {
            Log.w(TAG, "helpers: can't advertise: $e") // helpers can still use the address shown in the app
        }
    }

    /**
     * Stops advertising and drops every helper. Waits (up to 1 s) for that to finish, so the helpers' last
     * onTunnelGone calls reach [links] before the bonded output shuts it down.
     */
    fun stop() {
        post {
            registration?.let { runCatching { nsd.unregisterService(it) } }
            registration = null
            streamer?.stop()
            streamer = null
        }
        worker.shutdown()
        runCatching { worker.awaitTermination(1, TimeUnit.SECONDS) }
    }

    private companion object { const val TAG = "Engine" }
}
