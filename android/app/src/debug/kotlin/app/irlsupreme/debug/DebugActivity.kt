package app.irlsupreme.debug

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import app.irlsupreme.dji.CameraHotspot
import app.irlsupreme.engine.Scene
import app.irlsupreme.engine.StreamEngine
import app.irlsupreme.engine.video.BrowserSource
import app.irlsupreme.service.AlertQueue
import app.irlsupreme.service.BrbPairingStore
import app.irlsupreme.service.ExternalCamera
import app.irlsupreme.service.Helpers
import app.irlsupreme.service.HotspotBeacon
import app.irlsupreme.service.HotspotShare
import app.irlsupreme.service.SecretStore
import app.irlsupreme.service.StreamService
import app.irlsupreme.twitch.TwitchAlert
import app.irlsupreme.ui.MainActivity
import app.irlsupreme.ui.Quality
import app.irlsupreme.ui.ScreenCapture
import app.irlsupreme.ui.StreamOrientation

/**
 * Debug builds only (src/debug): drives the app from adb for automated and hands-on tests. It applies the extras, then
 * brings up MainActivity and closes. It lives here, not in MainActivity, so a release build has no debug hooks at all,
 * and the manifest guards it with the DUMP permission, which adb's shell holds and ordinary apps can't get.
 *
 *   adb shell am start -n app.irlsupreme/.debug.DebugActivity --es debug_url rtmp://127.0.0.1:1935/live/test
 *   adb shell am start -n app.irlsupreme/.debug.DebugActivity --es debug_bond 1   (stream to the paired BRB server)
 *   ... --es debug_quality HD720   (with debug_url/debug_bond; default HD1080)
 *   ... --es debug_orientation portrait   (with debug_url/debug_bond: a portrait stream; default: the Settings choice)
 *   ... --ez debug_preview true   (with debug_url/debug_bond: start in preview; nothing is sent until debug_golive)
 *   adb shell am start -n app.irlsupreme/.debug.DebugActivity --es debug_golive camera   (or STARTING_SOON: end the preview)
 *   adb shell am start -n app.irlsupreme/.debug.DebugActivity --ez debug_stop true
 *   adb shell am start -n app.irlsupreme/.debug.DebugActivity --es debug_scene BRB   (or LIVE, STARTING_SOON, ...)
 *   adb shell am start -n app.irlsupreme/.debug.DebugActivity --es debug_mic off   (or on)
 *   adb shell am start -n app.irlsupreme/.debug.DebugActivity --ez debug_screenshots true   (setup screens capturable)
 *   adb shell am start -n app.irlsupreme/.debug.DebugActivity --es debug_captions on|off   (live captions; mid-stream too)
 *   ... --es debug_caption_clip speech.pcm   (while streaming with captions: feed a 16 kHz clip to them instead of the voice)
 *   adb shell am start -n app.irlsupreme/.debug.DebugActivity --es debug_camera external   (or phone, pocket)
 *   ... --ez debug_as_test true   (with debug_url/debug_bond: count it as a Test stream, as Home's TEST would)
 *   debug_camera, debug_url and debug_bond change the user's camera and TEST/LIVE choice; debug_stop (or
 *   --ez debug_restore true) puts back what they were before the first debug change.
 *   adb shell am start -n app.irlsupreme/.debug.DebugActivity --ei debug_pocket_kbps 6000
 *   adb shell am start -n app.irlsupreme/.debug.DebugActivity --es debug_browser test   (or crash, off)
 *   adb shell am start -n app.irlsupreme/.debug.DebugActivity --es debug_alert raid   (follow|sub|resub|gift|raid|cheer)
 *   adb shell am start -n app.irlsupreme/.debug.DebugActivity --es debug_weak on   (or off, auto)
 *   adb shell am start -n app.irlsupreme/.debug.DebugActivity --es debug_hotspot on   (or off: the camera hotspot alone)
 *   adb shell am start -n app.irlsupreme/.debug.DebugActivity --es debug_helpers PASSWORD   (or on, off: accept helper phones)
 *
 * Starting a stream goes through an activity because camera/microphone foreground services must start from the
 * foreground; the rest also works with the screen off through [DebugControlReceiver].
 */
class DebugActivity : ComponentActivity() {
    private var engine: StreamEngine? = null
    private var bound = false
    private var engineWork: ((StreamEngine) -> Unit)? = null
    private var permissionWork: (() -> Unit)? = null
    private val missingPermissions = linkedSetOf<String>()
    private var restartMain = false

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) permissionWork?.invoke()
        else Log.w(TAG, "debug: permissions refused, skipped")
        permissionWork = null
        finishWhenDone()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val e = (binder as StreamService.LocalBinder).engine
            engine = e
            engineWork?.invoke(e)
            engineWork = null
            finishWhenDone()
        }
        override fun onServiceDisconnected(name: ComponentName) { engine = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreation (rotation) mid-permission-request: the extras were already applied once.
        if (savedInstanceState != null) return
        apply(intent)
        if (missingPermissions.isNotEmpty()) permissions.launch(missingPermissions.toTypedArray())
        finishWhenDone()
    }

    private fun apply(intent: Intent) {
        Log.i(TAG, "debug intent: ${intent.extras?.keySet()?.sorted()}")
        if (intent.getBooleanExtra("debug_screenshots", false)) ScreenCapture.allowed = true
        intent.getIntExtra("debug_pocket_kbps", 0).takeIf { it > 0 }?.let { ExternalCamera.setPocketKbps(this, it) }
        intent.getStringExtra("debug_camera")?.let { // "external" | "phone" | "pocket"
            saveOnce("camera_kind")
            if (it == "pocket") ExternalCamera.setKind(this, ExternalCamera.Kind.POCKET) else ExternalCamera.setEnabled(this, it == "external")
            ExternalCamera.path(this)
        }
        intent.getStringExtra("debug_url")?.let { url ->
            notATwitchTest(intent.getBooleanExtra("debug_as_test", false))
            val (w, h, bps) = streamSize(intent)
            withPermissions(listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
                StreamService.start(this, url, w, h, bps, preview = intent.getBooleanExtra("debug_preview", false))
            }
        }
        intent.getStringExtra("debug_bond")?.let { // any value: stream to the paired BRB server (as set up in the app)
            notATwitchTest(intent.getBooleanExtra("debug_as_test", false))
            val (w, h, bps) = streamSize(intent)
            val p = BrbPairingStore(SecretStore(this)).load()
            if (p == null) Log.w(TAG, "debug_bond: no paired BRB server")
            else withPermissions(listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
                StreamService.startBonded(this, p.host, p.port, "live/${p.publishId}", p.srtPassphrase, w, h, bps, preview = intent.getBooleanExtra("debug_preview", false))
            }
        }
        if (intent.getBooleanExtra("debug_stop", false)) { StreamService.stop(this); restoreSaved() }
        if (intent.getBooleanExtra("debug_restore", false)) restoreSaved()
        intent.getStringExtra("debug_golive")?.let { s -> // end the preview: "camera", or a scene such as STARTING_SOON
            StreamService.goLive(this, runCatching { Scene.valueOf(s) }.getOrNull())
        }
        intent.getStringExtra("debug_scene")?.let { s ->
            runCatching { Scene.valueOf(s) }.getOrNull()?.let { scene -> withEngine { it.setScene(scene) } }
        }
        intent.getStringExtra("debug_captions")?.let { v -> app.irlsupreme.service.CaptionSettings.setOn(applicationContext, v == "on") }
        intent.getStringExtra("debug_caption_clip")?.let { f -> // a 16 kHz mono PCM file in files/: fed to captions instead of the voice
            val bytes = java.io.File(filesDir, f).readBytes()
            withEngine { it.debugCaptionClip(bytes) }
        }
        intent.getStringExtra("debug_mic")?.let { v -> withEngine { it.setMicMuted(v == "off") } }
        intent.getStringExtra("debug_browser")?.let { v -> // "test": a built-in animated test page; "crash": kill its renderer; "off"
            withEngine {
                if (v == "crash") it.debugCrashBrowserOverlay()
                else it.setBrowserOverlay(if (v == "test") BrowserSource.Content.Html(DEBUG_OVERLAY_HTML) else null)
            }
        }
        intent.getStringExtra("debug_tts")?.let { text -> // a pretend viewer TTS message ("deep: hello chat")
            app.irlsupreme.service.TtsQueue.post(
                app.irlsupreme.service.TtsRequest("debug-tts-${System.nanoTime()}", app.irlsupreme.service.TtsSource.TEST, "Tester", text),
            )
        }
        if (intent.getBooleanExtra("debug_tts_voices", false)) { // the engine's voices, to files/tts-voices.txt
            val appCtx = applicationContext
            Thread {
                val speaker = app.irlsupreme.service.TtsSpeaker(appCtx)
                val text: String = kotlinx.coroutines.runBlocking { speaker.describeVoices() }
                java.io.File(appCtx.filesDir, "tts-voices.txt").writeText(text)
                speaker.shutdown()
            }.start()
        }
        intent.getBooleanExtra("debug_tts_skip", false).takeIf { it }?.let { app.irlsupreme.service.TtsQueue.skip() }
        intent.getStringExtra("debug_alert")?.let { kind -> // a pretend alert
            val id = "debug-${System.nanoTime()}"
            when (kind) {
                "follow" -> TwitchAlert.Follow(id, "NewFan")
                "sub" -> TwitchAlert.Sub(id, "KitIRL", "1000", prime = false, months = null)
                "resub" -> TwitchAlert.Sub(id, "KitIRL", "2000", prime = false, months = 14)
                "gift" -> TwitchAlert.GiftSubs(id, "Anonymous", 5, "1000")
                "raid" -> TwitchAlert.Raid(id, "BigStreamer", 230)
                "cheer" -> TwitchAlert.Cheer(id, "KitIRL", 100)
                else -> null
            }?.let { AlertQueue.post(it) }
        }
        intent.getStringExtra("debug_weak")?.let { v ->
            withEngine { it.forceWeakSignal(when (v) { "on" -> true; "off" -> false; else -> null }) }
        }
        intent.getStringExtra("debug_helpers")?.let { pw -> // accept helpers with this password ("on": the current one; "off": don't)
            Helpers.setHostingOn(this, pw != "off")
            if (pw != "off" && pw != "on") SecretStore(this).put("helper_password", pw)
            // Settings screens read their values once: start MainActivity afresh so they show the new ones.
            restartMain = true
        }
        intent.getStringExtra("debug_hotspot")?.let { v -> // the private hotspot alone, shared over Bluetooth if helpers are on
            DebugHotspot.stop()
            if (v != "on") return@let
            val needed = buildList {
                add(Manifest.permission.BLUETOOTH_ADVERTISE); add(Manifest.permission.BLUETOOTH_CONNECT)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            withPermissions(needed) { DebugHotspot.start(applicationContext) }
        }
    }

    /** Debug streams are never a Twitch test: the live screen and recap shouldn't say so. */
    /**
     * A debug stream isn't a Twitch Test unless [asTest] (it counts as one for the session log and for things that act
     * differently in a Test, like viewer TTS on the speaker). The user's own TEST/LIVE choice is saved first and put back
     * by [restoreSaved]: on 2026-11-22 debug streams left Home on LIVE, a public stream waiting to happen.
     */
    private fun notATwitchTest(asTest: Boolean = false) {
        saveOnce("live_test")
        getSharedPreferences("settings", MODE_PRIVATE).edit { putBoolean("live_test", asTest) }
    }

    /** Keeps the user's [key] (as it was before the first debug change) under a debug key, once, for [restoreSaved]. */
    private fun saveOnce(key: String) {
        val p = getSharedPreferences("settings", MODE_PRIVATE)
        val saved = "debug_saved_$key"
        if (p.contains(saved)) return
        p.edit {
            when (val v = p.all[key]) {
                is Boolean -> putString(saved, "b:$v")
                is String -> putString(saved, "s:$v")
                else -> putString(saved, "none")
            }
        }
    }

    /** Puts back every setting [saveOnce] kept. */
    private fun restoreSaved() {
        val p = getSharedPreferences("settings", MODE_PRIVATE)
        p.edit {
            for ((k, v) in p.all) {
                if (!k.startsWith("debug_saved_") || v !is String) continue
                val key = k.removePrefix("debug_saved_")
                when {
                    v.startsWith("b:") -> putBoolean(key, v.removePrefix("b:").toBoolean())
                    v.startsWith("s:") -> putString(key, v.removePrefix("s:"))
                    else -> remove(key)
                }
                remove(k)
            }
        }
        Log.i(TAG, "debug: restored the user's settings")
    }

    /** `--es debug_quality HD720` etc. (default 1080p) in `--es debug_orientation portrait|landscape` (default: Settings). */
    private fun streamSize(intent: Intent): Triple<Int, Int, Int> {
        val q = Quality.entries.firstOrNull { it.name == intent.getStringExtra("debug_quality") } ?: Quality.HD1080
        val name = intent.getStringExtra("debug_orientation")?.uppercase()
            ?: getSharedPreferences("settings", MODE_PRIVATE).getString("orientation", null)
        val (w, h) = (StreamOrientation.entries.firstOrNull { it.name == name } ?: StreamOrientation.LANDSCAPE).size(q)
        return Triple(w, h, q.bitrate)
    }

    /** Work that needs permissions not yet granted waits for one combined request, launched after [apply]. */
    private fun withPermissions(needed: List<String>, work: () -> Unit) {
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { work(); return }
        missingPermissions += missing
        val earlier = permissionWork
        permissionWork = { earlier?.invoke(); work() }
    }

    /** Engine commands wait for the stream service to bind (a moment), as MainActivity does. */
    private fun withEngine(work: (StreamEngine) -> Unit) {
        engine?.let { work(it); return }
        val earlier = engineWork
        engineWork = { e -> earlier?.invoke(e); work(e) }
        if (!bound) bound = bindService(Intent(this, StreamService::class.java), connection, BIND_AUTO_CREATE)
        if (!bound) { Log.w(TAG, "debug: couldn't bind the stream service, engine commands skipped"); engineWork = null }
    }

    private fun finishWhenDone() {
        if (permissionWork != null || engineWork != null || isFinishing) return
        val main = Intent(this, MainActivity::class.java)
        // MainActivity is singleTask: this brings the running one forward, or with restartMain replaces it.
        if (restartMain) main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(main)
        finish()
    }

    override fun onDestroy() {
        if (bound) unbindService(connection)
        bound = false
        super.onDestroy()
    }

    private companion object {
        const val TAG = "EngineDebug"
    }
}

/**
 * The debug hotspot outlives the DebugActivity that started it (that closes at once), so it is held for the process:
 * a later `debug_hotspot off` always reaches it, and Android tears the hotspot down if the process dies. Both hold
 * the application context only, so nothing leaks.
 */
@SuppressLint("StaticFieldLeak")
private object DebugHotspot {
    private var hotspot: CameraHotspot? = null
    private var beacon: HotspotBeacon? = null
    private val main = Handler(Looper.getMainLooper())

    fun start(ctx: Context) {
        stop()
        val hs = CameraHotspot(ctx)
        hotspot = hs
        hs.start(
            onReady = { info ->
                Log.i("Engine", "debug hotspot ready: ${hs.mode}, ${info.address}")
                val h = Helpers.hosting(ctx) ?: return@start
                // Deriving the share keys is slow (a password hash): off the main thread.
                Thread {
                    val keys = HotspotShare.Keys(h.password)
                    main.post {
                        if (hotspot !== hs) return@post
                        beacon = HotspotBeacon(ctx, keys, HotspotShare.Credentials(info.ssid, info.password, info.security))
                            .takeIf { it.start() }
                    }
                }.start()
            },
            onFailed = { Log.w("Engine", "debug hotspot failed: $it") },
        )
    }

    fun stop() {
        beacon?.stop(); beacon = null
        hotspot?.stop(); hotspot = null
    }
}

/** Web overlay test page: transparent, with a moving half-transparent badge and a ticking clock (CSS and JS timers). */
private const val DEBUG_OVERLAY_HTML = """<!doctype html><html><head><style>
html,body{margin:0;background:transparent;overflow:hidden;width:100vw;height:100vh;font-family:sans-serif}
#b{position:absolute;bottom:6vh;left:4vw;padding:2vh 3vw;border-radius:2vh;background:rgba(220,40,120,.6);color:#fff;
font-size:5vh;font-weight:bold;animation:s 3s ease-in-out infinite alternate}
#c{position:absolute;top:4vh;right:4vw;color:#fff;font-size:4vh;text-shadow:0 0 1vh #000}
@keyframes s{from{transform:translateX(0)}to{transform:translateX(40vw)}}
</style></head><body><div id="b">Browser overlay test</div><div id="c"></div>
<script>setInterval(function(){document.getElementById('c').textContent=new Date().toLocaleTimeString()+' '+innerWidth+'x'+innerHeight},250)</script>
</body></html>"""
