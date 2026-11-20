package app.irlsupreme.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.irlsupreme.dji.CameraHotspot

/**
 * The phone's private hotspot for one stream, shared by whoever needs it: the camera (Pocket 3) and helper phones.
 * Started on first [use], stopped with the stream. If Android takes it down mid-stream (seen when the phone's own
 * Wi-Fi came back on), it's started again, usually with a new name and password on Android 16.0: every user hears
 * about each new one through onReady. A hotspot that won't start is tried again every [RETRY_MS] (the user may turn
 * off their own hotspot, or Android may have refused while the app was briefly in the background), unless the phone
 * forbids hotspots: users hear each new reason through onFailed, then onReady once it works. Main thread only (the
 * hotspot's callbacks arrive there).
 */
class PhoneHotspot(context: Context) {
    private class User(val onReady: (CameraHotspot.Info) -> Unit, val onFailed: (String) -> Unit, val onLost: () -> Unit)

    private val hotspot = CameraHotspot(context)
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var stopped = false
    private var restarting = false
    private var failure: String? = null
    private val users = mutableListOf<User>()

    /** The hotspot that's up right now, if any. */
    var current: CameraHotspot.Info? = null
        private set

    val mode: String get() = hotspot.mode

    fun use(onReady: (CameraHotspot.Info) -> Unit, onFailed: (String) -> Unit, onLost: () -> Unit = {}) {
        users += User(onReady, onFailed, onLost)
        current?.let(onReady) ?: failure?.let(onFailed)
        if (!started) { started = true; launch() }
    }

    private fun launch() {
        hotspot.start(
            onReady = { i ->
                if (stopped) return@start
                current = i; failure = null; restarting = false
                users.toList().forEach { it.onReady(i) }
            },
            onFailed = { why ->
                if (stopped) return@start
                val permanent = CameraHotspot.isPermanent(why)
                if (!permanent) {
                    Log.w(TAG, "camera hotspot: ${if (restarting) "restart" else "start"} failed ($why); trying again in ${RETRY_MS / 1000} s")
                    main.postDelayed(::launch, RETRY_MS)
                }
                // A failed restart keeps the users' last news (the hotspot just went away); a new reason is news.
                if (restarting && !permanent) return@start
                if (why == failure) return@start
                failure = why
                users.toList().forEach { it.onFailed(why) }
            },
            onLost = {
                if (stopped) return@start
                current = null
                restarting = true
                users.toList().forEach { it.onLost() }
                main.postDelayed(::launch, RESTART_MS)
            },
        )
    }

    fun stop() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        users.clear()
        current = null
        hotspot.stop()
    }

    companion object {
        private const val TAG = "Engine"
        private const val RESTART_MS = 1_000L
        const val RETRY_MS = 10_000L
        const val RETRY_SECONDS = (RETRY_MS / 1000).toInt()
    }
}

/**
 * The stream's private hotspot while helper phones are accepted, for the live screen: Moblin helpers can't take the
 * Bluetooth hand-off, so their users type this name and password in by hand. Null otherwise.
 */
object HelperHotspotStatus {
    val state = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, String>?>(null)
    /** Why the helper hotspot isn't running this stream, in words for the streamer; null when fine or not wanted. */
    val problem = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}
