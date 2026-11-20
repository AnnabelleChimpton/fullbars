package app.irlsupreme.dji

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.irlsupreme.service.ExternalCamera
import app.irlsupreme.service.PocketStatus

/**
 * Keeps the DJI Pocket streaming for as long as the phone is live, including after the camera is power-cycled
 * mid-stream. Joins the camera network (the stream's private hotspot, if that's the choice), owns the Bluetooth
 * link, and:
 *  - retries with backoff (2, 4, 8, then every 15 s) whenever the link fails or drops: a power-cycled Pocket drops
 *    Bluetooth, attempts fail while it boots, then it reconnects and re-pairs silently;
 *  - restarts the camera's stream when the link says "streaming" but no frames have reached the phone for
 *    [STALL_RESTART_MS] (e.g. its Wi-Fi dropped while Bluetooth stayed up).
 * Meanwhile the engine shows the phone camera and mic.
 */
class PocketSession(
    private val context: Context,
    /** How long since the external camera's last frame reached the compositor (null: none yet this stream). */
    private val cameraIdleMs: () -> Long?,
    /** Whether the camera is publishing to the phone's RTMP server right now. */
    private val cameraConnected: () -> Boolean,
    /** The phone's private hotspot (owned by the stream; helper phones may share it), when that's the camera network. */
    private val hotspot: app.irlsupreme.service.PhoneHotspot?,
) {
    private var disconnectedSinceMs = 0L
    private val main = Handler(Looper.getMainLooper())
    private var link: PocketLink? = null
    private var hotspotInfo: CameraHotspot.Info? = null
    @Volatile private var wanted = false
    private var retries = 0
    private var streamingSinceMs = 0L

    fun start() {
        wanted = true
        retries = 0
        if (ExternalCamera.cameraNet(context) == ExternalCamera.CameraNet.PRIVATE && hotspot != null) {
            // Step 1 is Fullbars' own hotspot starting; it used to read "Connecting to the camera" while no camera was
            // being contacted yet.
            set(PocketState.StartingHotspot)
            hotspot.use(onReady = { info ->
                Log.i(TAG, "camera hotspot ready (STA+AP ${info.staApConcurrent})")
                hotspotInfo = info
                // A restarted hotspot has a new name and password: the camera needs telling.
                link?.let { l -> Log.i(TAG, "Pocket: the hotspot changed; reconnecting"); Thread { l.stop() }.start() }
                link = null
                attempt()
            }, onFailed = { why ->
                // The phone's hotspot keeps retrying by itself (the user may turn off their own hotspot meanwhile);
                // only a phone that forbids hotspots is a dead end.
                set(
                    if (CameraHotspot.isPermanent(why)) PocketState.Failed(why, PocketStep.HOTSPOT)
                    else PocketState.Reconnecting(why, app.irlsupreme.service.PhoneHotspot.RETRY_SECONDS, PocketStep.HOTSPOT),
                )
            }, onLost = { hotspotInfo = null })
        } else {
            attempt()
        }
        main.postDelayed(watchdog, WATCHDOG_MS)
    }

    fun stop() {
        wanted = false
        main.removeCallbacksAndMessages(null)
        link?.let { l -> Thread { l.stop() }.start() } // sends the camera's stop frame; may block briefly
        link = null
        hotspotInfo = null // the stream stops the hotspot itself
        PocketStatus.set(null)
    }

    private fun set(s: PocketState) = PocketStatus.set(s)

    /** A mod's !restartcam: drop the camera's link and start it again now (its stream restarts with it). */
    fun restart() {
        if (!wanted) return
        Log.i(TAG, "Pocket: restarting on request")
        link?.let { l -> Thread { l.stop() }.start() }
        link = null
        streamingSinceMs = 0
        disconnectedSinceMs = 0
        retries = 0
        attempt()
    }

    private fun attempt() {
        if (!wanted || link != null) return // a retry already reconnected
        val (btAddress, _) = ExternalCamera.pocket(context) ?: return set(PocketState.Failed("Find your Pocket 3 in Settings › Camera first"))
        val scanner = DjiScanner(context)
        if (!scanner.bluetoothOn) return retryLater("Turn on Bluetooth", PocketStep.BLUETOOTH)
        val device = scanner.device(btAddress) ?: return set(PocketState.Failed("Find your Pocket 3 again in Settings › Camera"))
        val net = ExternalCamera.cameraNet(context)
        val (ssid, password, address) = when (net) {
            ExternalCamera.CameraNet.PRIVATE -> hotspotInfo?.let { Triple(it.ssid, it.password, it.address) } ?: return
            else -> {
                val (s, p) = ExternalCamera.cameraWifi(context) ?: return set(PocketState.Failed("Add the hotspot or Wi‑Fi in Settings › Camera", PocketStep.HOTSPOT))
                val a = ExternalCamera.phoneAddressFor(context, net) ?: return retryLater(
                    if (net == ExternalCamera.CameraNet.PHONE_HOTSPOT) "Turn on your hotspot" else "Connect this phone to $s",
                    PocketStep.HOTSPOT,
                )
                Triple(s, p, a)
            }
        }
        val url = "rtmp://$address:${ExternalCamera.PORT}/${ExternalCamera.path(context)}"
        Log.i(TAG, "Pocket: connecting (attempt ${retries + 1}, ${net.name.lowercase()}, phone at $address)")
        link = PocketLink(context, device, ExternalCamera.djiIdentity(context)) { s -> main.post { onLinkState(s) } }
            .also { it.start(PocketLink.Settings(ssid, password, url, ExternalCamera.pocketKbps(context), hotspot = net != ExternalCamera.CameraNet.WIFI)) }
    }

    private fun onLinkState(s: PocketState) {
        if (!wanted) return
        when (s) {
            is PocketState.Streaming -> {
                if (streamingSinceMs == 0L) streamingSinceMs = System.currentTimeMillis()
                retries = 0
                set(s)
            }
            is PocketState.Failed -> { link = null; streamingSinceMs = 0; retryLater(s.reason, s.at) }
            PocketState.Stopped -> Unit // our own stop
            else -> set(s)
        }
    }

    /** [at]: the step that failed, so the state (and the live screen) can say where it stopped. */
    private fun retryLater(reason: String, at: PocketStep?) {
        if (!wanted) return
        val delay = when (retries++) { 0 -> 2_000L; 1 -> 4_000L; 2 -> 8_000L; else -> 15_000L }
        Log.i(TAG, "Pocket: $reason; retrying in ${delay / 1000} s")
        set(PocketState.Reconnecting(reason, (delay / 1000).toInt(), at))
        main.postDelayed({ attempt() }, delay)
    }

    /** The link can say "streaming" while no picture arrives (the camera's Wi-Fi dropped): restart it. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!wanted) return
            val l = link
            val idle = cameraIdleMs()
            val now = System.currentTimeMillis()
            val streamingFor = if (streamingSinceMs == 0L) 0 else now - streamingSinceMs
            // A camera that ended its stream (a Pocket switched off says goodbye cleanly but keeps Bluetooth a while)
            // is restarted after [ENDED_RESTART_MS]; one that just went quiet, after [STALL_RESTART_MS].
            val connected = cameraConnected()
            if (connected || idle == null) disconnectedSinceMs = 0 else if (disconnectedSinceMs == 0L) disconnectedSinceMs = now
            val ended = disconnectedSinceMs != 0L && now - disconnectedSinceMs > ENDED_RESTART_MS
            // The startup grace is only for "no first frame yet"; an ended or stalled stream is acted on regardless.
            val noFirstFrame = idle == null && streamingFor > STARTUP_GRACE_MS
            val stalled = idle != null && idle > STALL_RESTART_MS
            if (l != null && l.streaming && (ended || stalled || noFirstFrame)) {
                Log.i(TAG, "Pocket: " + (if (ended) "its stream ended" else "no frames for ${idle ?: streamingFor} ms") + "; restarting it")
                disconnectedSinceMs = 0
                link = null
                streamingSinceMs = 0
                Thread { l.stop() }.start()
                retryLater("The Pocket 3 stopped sending", PocketStep.PICTURE)
            }
            main.postDelayed(this, WATCHDOG_MS)
        }
    }

    private companion object {
        const val TAG = "Engine"
        const val WATCHDOG_MS = 1_000L
        const val ENDED_RESTART_MS = 3_000L
        const val STALL_RESTART_MS = 12_000L
        const val STARTUP_GRACE_MS = 20_000L
    }
}
