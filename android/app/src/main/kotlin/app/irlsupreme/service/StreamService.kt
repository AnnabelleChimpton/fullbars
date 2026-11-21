package app.irlsupreme.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import app.irlsupreme.engine.CameraInput
import app.irlsupreme.engine.OutputTarget
import app.irlsupreme.engine.Scene
import app.irlsupreme.engine.SessionSummary
import app.irlsupreme.engine.StreamConfig
import app.irlsupreme.engine.video.AlertGraphic
import app.irlsupreme.engine.video.BrandType
import app.irlsupreme.engine.video.Slates
import app.irlsupreme.twitch.ChatCommand
import app.irlsupreme.twitch.ChatCommandParser
import app.irlsupreme.twitch.ChatConnectionState
import app.irlsupreme.twitch.SubscriptionStatus
import app.irlsupreme.twitch.CommandResult
import app.irlsupreme.twitch.TwitchException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import app.irlsupreme.engine.EngineState
import app.irlsupreme.engine.StreamEngine
import app.irlsupreme.ui.MainActivity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that owns the [StreamEngine] for the whole stream, so the stream outlives the UI, which is only
 * a thin client. Holds a partial wake lock and two Wi-Fi locks while live ([WifiLocks]): low latency, which only acts
 * with the screen on and the app in front, and high performance, which keeps Wi-Fi out of power save with the screen
 * off on Android 12 and 13. Started from the visible activity, as Android 14+ requires for camera/microphone
 * foreground services. While live it also tells the engine the battery level and temperature every 10 s and passes on
 * Android's thermal status changes the moment they happen.
 *
 * Starting and stopping the engine take seconds (render setup, waiting for an RTMP camera, the SRT goodbye grace,
 * thread joins), so they run on [engineExec], one at a time and in order; the main thread only flips [live] and
 * starts or stops the side features (hotspot, Pocket, chat, overlays, alerts). Side features never take the stream
 * down: a coroutine that fails is logged by [scope]'s handler, and chat that stops for good just says so.
 */
class StreamService : Service() {
    inner class LocalBinder : Binder() {
        val engine: StreamEngine get() = this@StreamService.engine
    }

    lateinit var engine: StreamEngine
        private set
    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLocks: WifiLocks? = null
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.e(TAG, "a side feature failed; the stream goes on", e) },
    )
    private var twitchJob: Job? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** engine.start and engine.stop, off the main thread and in the order asked. */
    private val engineExec = Executors.newSingleThreadExecutor { Thread(it, "stream-engine") }

    // Main thread only.
    /** A stream is wanted: from Go live until End (or a failed start). */
    private var live = false
    /** An engine stop is under way; the service goes once it's done. */
    private var ending = false
    /** Bumped on every start and stop, so work posted back for an older one is dropped. */
    private var generation = 0
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        // Alerts, chat and the scene cards are drawn in the bundled faces; load them before anything draws.
        BrandType.load(applicationContext)
        EmoteCache.init(applicationContext)
        engine = StreamEngine(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val preview = intent.getBooleanExtra(EXTRA_PREVIEW, false)
                goForeground(preview) // within moments of startForegroundService, whatever happens next
                if (live) { Log.i(TAG, "already live; ignoring a second Go live"); return START_NOT_STICKY }
                val bondHost = intent.getStringExtra(EXTRA_BOND_HOST)
                val url = intent.getStringExtra(EXTRA_URL)
                if (bondHost == null && url == null) return stopAndForget(EndReason.USER) // not live: just goes
                acquireLocks()
                val startMs = SessionStore(this).markLive()
                live = true
                ending = false
                val gen = ++generation
                alertsShown.set(0)
                val log = SessionLog(SessionLog.dir(filesDir), startMs, onError = { Log.w(TAG, "session log stopped: $it") })
                    .also { sessionLog = it }
                val broadcasterId = intent.getStringExtra(EXTRA_BROADCASTER_ID)
                // Before the engine starts: it reports its frame-rate fallback while starting.
                startEvents(log)
                engineExec.execute {
                    // The scene cards as the streamer set them, read fresh: the stream and the slates sent to the BRB
                    // server draw them from here, even when no activity ran first.
                    CardStore.apply(this)
                    AlertStyles.apply(this)
                    // Built here: the helper password and camera settings are Keystore reads.
                    val target = if (bondHost != null) {
                        StreamRules.bonded(
                            bondHost, intent.getIntExtra(EXTRA_BOND_PORT, 5000), intent.getStringExtra(EXTRA_BOND_STREAM_ID) ?: "live",
                            intent.getStringExtra(EXTRA_BOND_PASSPHRASE), StreamSettings.delay(this), Helpers.hosting(this),
                        )
                    } else {
                        OutputTarget.Rtmp(url!!)
                    }
                    val choices = StreamChoices(
                        width = intent.getIntExtra(EXTRA_WIDTH, 1920),
                        height = intent.getIntExtra(EXTRA_HEIGHT, 1080),
                        bitrate = intent.getIntExtra(EXTRA_BITRATE, 6_000_000),
                        fps = intent.getIntExtra(EXTRA_FPS, 30),
                        camera = ExternalCamera.input(this),
                        autoWeakSignal = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("weak_signal_screen", true),
                        mic = MicSettings.current(this),
                        audio = StreamSettings.audio(this),
                        avOffsetMs = StreamSettings.avOffsetMs(this),
                        autoLipSync = StreamSettings.autoLipSync(this),
                        channelLogin = channelLogin(),
                    )
                    // 60 fps only for the phone camera, where its camera and encoder carry it (the engine checks again).
                    val fps = StreamRules.fps(choices.fps, choices.camera is CameraInput.Phone) {
                        app.irlsupreme.engine.video.CameraCapabilities.supports(this, CameraPrefs.startFacing(this), choices.width, choices.height, 60)
                    }
                    val config = StreamRules.config(target, choices, fps).copy(startInPreview = preview)
                    SessionLogText.startLines(sessionStart(config, choices.fps)).forEach(log::essential)
                    // The battery as the stream starts, for the recap's battery per hour and the heat ladder's floor.
                    readBattery()
                    // Open the side the streamer starts on, so the engine's own 60 fps check reads that camera (and
                    // the camera isn't reopened a moment after going live).
                    if (choices.camera is CameraInput.Phone) engine.setCameraFacing(CameraPrefs.startFacing(this))
                    val started = try {
                        engine.start(config)
                    } catch (t: Throwable) {
                        Log.e(TAG, "engine start failed", t)
                        log.essential("engine start threw ${t.javaClass.simpleName}: ${t.message}")
                        runCatching { engine.stop() } // whatever got built
                        false
                    }
                    if (started) {
                        startedTarget = config.target
                        log.line(if (preview) "engine started, in preview: nothing is sent until Go live" else "engine started, connecting")
                    } else {
                        log.essential("start failed: ${engine.state.value.lastError ?: "no reason given"}")
                    }
                    main.post {
                        if (gen != generation || destroyed) return@post // ended while starting; that stop is queued behind us
                        if (started) {
                            startSideFeatures(config, broadcasterId)
                            startLogging(log)
                            startDeviceWatch(log)
                        } else {
                            failedStart()
                        }
                    }
                }
            }
            ACTION_STOP -> return stopAndForget(EndReason.USER)
            ACTION_SCENE -> intent.getStringExtra(EXTRA_SCENE)
                ?.let { runCatching { Scene.valueOf(it) }.getOrNull() }
                ?.let { engine.setScene(it) }
            ACTION_MIC -> engine.setMicMuted(intent.getBooleanExtra(EXTRA_MUTED, false))
            ACTION_GO_LIVE -> {
                val scene = intent.getStringExtra(EXTRA_SCENE)?.let { runCatching { Scene.valueOf(it) }.getOrNull() }
                val gen = generation
                engineExec.execute {
                    val went = engine.goLive(scene)
                    main.post {
                        if (gen != generation || destroyed) return@post
                        if (went) {
                            sessionLog?.essential("go live${scene?.let { " on ${it.name}" } ?: ""}")
                            goForeground(preview = false)
                        }
                    }
                }
            }
        }
        // Not sticky: Android 14+ forbids restarting camera/mic foreground services from the background anyway.
        return START_NOT_STICKY
    }

    /**
     * The signed-in Twitch login, for the scene cards' footer, or empty without a sign-in. A Keystore read
     * (blocking), so it's called on [engineExec] like the rest of the config; a failed read just leaves the footer blank.
     */
    private fun channelLogin(): String =
        runCatching { runBlocking { Twitch.get(this@StreamService).session.storedLogin() } }.getOrNull().orEmpty()

    /** The engine is up: everything around it. Main thread. */
    private fun startSideFeatures(config: StreamConfig, broadcasterId: String?) {
        val target = config.target
        stopHotspot()
        startHotspot(target)
        startOverlays(config.width, config.height)
        startAlerts(config.width, config.height)
        startTips()
        if (target is OutputTarget.Bonded) startRemote()
        if (ExternalCamera.kind(this) == ExternalCamera.Kind.POCKET) startPocket()
        broadcasterId?.let { startTwitch(it) }
    }

    /**
     * The engine couldn't build its pipeline (its reason is in its state): not live after all. The recap says it
     * didn't start, and why, instead of showing the previous stream's.
     */
    private fun failedStart() {
        val why = engine.state.value.lastError
        Log.w(TAG, "stream didn't start: $why")
        val now = System.currentTimeMillis()
        SessionStore(this).markEnded(SessionSummary(now, now, 0, 0, 0, 0, 0, 0, why))
        stopAndForget(EndReason.FAILED_START, markEnded = false)
    }

    /**
     * What this stream was set up as, for the session log: only settings, never the stream key, URL or passwords.
     * Engine thread (the settings are disk reads).
     */
    private fun sessionStart(config: StreamConfig, requestedFps: Int): SessionStart {
        val kind = ExternalCamera.kind(this)
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).let { "${it.versionName} (${it.longVersionCode})" }
        }.getOrDefault("version unknown")
        return SessionStart(
            config = config,
            // Written by Go live just before it starts this service (the live screen's Test label reads it too).
            test = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("live_test", false),
            cameraKind = when (kind) {
                ExternalCamera.Kind.PHONE -> "phone"
                ExternalCamera.Kind.POCKET -> "DJI Pocket 3 over RTMP"
                ExternalCamera.Kind.RTMP -> "RTMP camera"
            },
            pocket = if (kind == ExternalCamera.Kind.POCKET) {
                "camera network ${ExternalCamera.cameraNet(this).name.lowercase()}, ${ExternalCamera.pocketKbps(this)} kb/s"
            } else null,
            startFacing = CameraPrefs.startFacing(this).name.lowercase(),
            mic = MicSettings.current(this),
            app = version,
            device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}",
            requestedFps = requestedFps,
        )
    }

    @Volatile private var sessionLog: SessionLog? = null
    private var logJob: Job? = null
    private val alertsShown = AtomicInteger()
    /** Engine thread: where the running engine streams to (null when it isn't running). */
    @Volatile private var startedTarget: OutputTarget? = null

    /**
     * While live, keeps [log] up to date: every change of health, scene, links, camera and audio as it happens (the
     * engine publishes twice a second, so a short drop between snapshots still shows), the mic and Pocket as they
     * change, and a full snapshot every [SNAPSHOT_EVERY_MS]. Main thread; runs until [stopSideFeatures].
     */
    private fun startLogging(log: SessionLog) {
        logJob?.cancel()
        val mic = MicWindow()
        val startedAt = System.currentTimeMillis()
        logJob = scope.launch {
            launch { engine.micLevel.collect { mic.add(it) } }
            // Music under the card on air, preview included.
            launch { CardMusic.follow(this@StreamService, engine) { log.line(it) } }
            launch {
                var prev: EngineState? = null
                engine.state.collect { st ->
                    SessionLogText.changes(prev, st).forEach(log::line)
                    prev = st
                }
            }
            launch {
                // Settings › Microphone and the live mic sheet write the settings; the stream follows them here, so the
                // page, the sheet and the stream never disagree.
                MicSettings.state(this@StreamService).drop(1).collect {
                    log.line("mic setting now ${micValue(it)}, input ${it.input.name.lowercase()}, low cut ${it.lowCut.name.lowercase()}, " +
                        "leveler ${if (it.voiceLeveler) "on" else "off"}, mono ${if (it.monoVoice) "on" else "off"}")
                    applyMic(it)
                }
            }
            launch {
                // Settings › Microphone › Lip sync: the stream eases to the new offset.
                StreamSettings.avOffsetState(this@StreamService).drop(1).collect { ms ->
                    log.line("lip sync offset now ${SessionLogText.signedMs(ms.toLong())}")
                    engine.setAvOffsetNs(ms * 1_000_000L)
                }
            }
            launch { PocketStatus.state.collect { s -> s?.let { log.line("pocket: ${it.label}") } } }
            launch {
                PocketScreen.on.drop(1).collect { on ->
                    pocketTime.set(System.currentTimeMillis(), on)
                    log.line(if (on) "pocket screen on" else "pocket screen off (${SessionLogText.duration(pocketTime.totalMs(System.currentTimeMillis()))} so far)")
                }
            }
            var diagnosed = false
            while (isActive) {
                delay(SNAPSHOT_EVERY_MS)
                log.line(SessionLogText.snapshot(engine.state.value, mic.take(), alertsShown.get(), screenOn, PocketScreen.on.value))
                // The audio path's finer health once ~10 s in (the stereo probe has measured by then); again at the end.
                if (!diagnosed && System.currentTimeMillis() - startedAt >= AUDIO_DIAGNOSTICS_AFTER_MS) {
                    diagnosed = true
                    engine.audioDiagnostics()?.let { log.line(it.line()) }
                }
            }
        }
    }

    /** The mic settings into the running stream (each setter is a plain write; an unchanged one does nothing). */
    private fun applyMic(p: MicPrefs) {
        engine.setMicGain(p.gainDb.toFloat())
        engine.setRawMicGain(p.rawGainDb.toFloat())
        engine.setRawMic(p.raw)
        if (!STREAM_USES_MIC_OPTIONS) return
        engine.setLowCut(p.lowCut)
        engine.setVoiceLeveler(p.voiceLeveler)
        engine.setMonoVoice(p.monoVoice)
        engine.setMicInput(p.input)
        engine.setExternalMicGain(p.externalGainDb.toFloat())
    }

    private var eventsJob: Job? = null

    /**
     * The engine's events into [log] as they happen: link events with reasons, bitrate changes (at most one a second),
     * mic input changes and notes. Subscribed at once (not when the coroutine first runs), so what the engine says
     * while starting is kept. A mic change of kind is also shown on the live screen ([MicNotice]).
     */
    private fun startEvents(log: SessionLog) {
        eventsJob?.cancel()
        val lines = EventLines()
        eventsJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine.events.collect { e ->
                lines.line(System.currentTimeMillis(), e)?.let(log::line)
                if (e is app.irlsupreme.engine.EngineEvent.MicInput) e.message?.let(MicNotice::post)
            }
        }
    }

    // Main thread only, while live.
    private var batteryJob: Job? = null
    private var keyframeJob: Job? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var screenReceiver: BroadcastReceiver? = null
    /** The screen's state for the snapshots (any thread reads it). */
    @Volatile private var screenOn: Boolean? = null
    private val screenOffTime = TimeOn()
    private val pocketTime = TimeOn()

    /**
     * The phone around the stream: battery level and temperature into the engine every [BATTERY_EVERY_MS], Android's
     * thermal status changes straight to the ladder (it steps up at once instead of at its next tick), and the screen
     * going on and off into [log] (Wi-Fi power save with the screen off shows in the links' RTT next to it).
     */
    private fun startDeviceWatch(log: SessionLog) {
        stopDeviceWatch()
        val now = System.currentTimeMillis()
        val pm = getSystemService(PowerManager::class.java)
        val interactive = pm.isInteractive
        screenOn = interactive
        screenOffTime.reset(now, on = !interactive)
        pocketTime.reset(now, on = PocketScreen.on.value)
        batteryJob = scope.launch {
            while (isActive) {
                readBattery()
                delay(BATTERY_EVERY_MS)
            }
        }
        thermalListener = PowerManager.OnThermalStatusChangedListener { status -> engine.onThermalStatusChanged(status) }
            .also { l -> runCatching { pm.addThermalStatusListener(ContextCompat.getMainExecutor(this), l) } }
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val on = intent.action == Intent.ACTION_SCREEN_ON
                if (screenOn == on) return
                screenOn = on
                val t = System.currentTimeMillis()
                screenOffTime.set(t, !on)
                log.line(if (on) "screen on" else "screen off")
            }
        }.also { r ->
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
            ContextCompat.registerReceiver(this, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        if (startedTarget is OutputTarget.Bonded) startKeyframeBackstop(log)
    }

    private fun stopDeviceWatch() {
        batteryJob?.cancel(); batteryJob = null
        keyframeJob?.cancel(); keyframeJob = null
        thermalListener?.let { l -> runCatching { getSystemService(PowerManager::class.java).removeThermalStatusListener(l) } }
        thermalListener = null
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
    }

    /** The battery's level, temperature and charging into the engine. A failed read leaves them unknown. */
    private fun readBattery() {
        runCatching {
            val bm = getSystemService(BatteryManager::class.java)
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 } ?: -1
            // The sticky battery broadcast: no receiver needed, just its last value.
            val sticky = ContextCompat.registerReceiver(this, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            val tenths = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            engine.setBattery(pct, bm.isCharging, if (tenths == Int.MIN_VALUE) Float.NaN else tenths / 10f)
        }.onFailure { Log.w(TAG, "battery read failed: ${it.javaClass.simpleName}") }
    }

    /**
     * Combined connections: the paired server's keyframe backstop ([KeyframeBackstop]). While its slate is on air the
     * status is polled every 400 ms, and the encoder asked for a keyframe when the server waits for one. The same
     * status carries the server's slate checks: they are logged, and a refused or mismatched slate is sent again
     * ([SlateRetry]).
     */
    private fun startKeyframeBackstop(log: SessionLog) {
        keyframeJob?.cancel()
        keyframeJob = scope.launch {
            val pairing = BrbPairingStore(SecretStore(this@StreamService)).load() ?: return@launch
            val client = brbClient(pairing)
            val backstop = KeyframeBackstop()
            val slates = SlateRetry()
            var wasOnSlate = false
            var slateReport = ""
            while (isActive) {
                val st = engine.state.value
                val now = android.os.SystemClock.elapsedRealtime()
                backstop.phone(now, trouble = st.connection != app.irlsupreme.engine.ConnectionPhase.LIVE || st.weakSignal)
                runCatching { client.status(timeoutMs = KEYFRAME_POLL_TIMEOUT_MS) }.getOrNull()?.let { s ->
                    val at = android.os.SystemClock.elapsedRealtime()
                    if (backstop.onStatus(at, s.state, s.needKeyframe)) {
                        engine.requestKeyframe()
                        log.line("server waits for a keyframe: asked the encoder (${backstop.requests} this stream)")
                    }
                    if (backstop.serverOnSlate != wasOnSlate) {
                        wasOnSlate = backstop.serverOnSlate
                        log.line("server ${if (wasOnSlate) (if (s.state == "weak") "shows its Weak signal slate" else "shows its slate") else "is back on the camera"}")
                    }
                    val report = slateReport(s)
                    if (report != slateReport) {
                        slateReport = report
                        if (report.isNotEmpty()) log.line("server slate: $report")
                    }
                    if (slates.onStatus(at, s.slateMatches, s.slateRejected) && engine.resendSlate()) {
                        slates.sent(at)
                        log.line("server slate ${if (s.slateRejected != null) "refused" else "doesn't match the live picture"}: sending it again (${slates.tries} this stream)")
                    }
                }
                delay(backstop.nextPollMs(android.os.SystemClock.elapsedRealtime()))
            }
        }
    }

    /** The server's slate checks as one line for the session log: "phone 1080x1920, live 1080x1920, matches". */
    private fun slateReport(s: BrbStatus): String = listOfNotNull(
        s.slateSource?.let { src -> src + (s.slateSize?.let { " $it" } ?: "") },
        s.liveSize?.let { "live $it" },
        s.slateMatches?.let { if (it) "matches" else "doesn't match" },
        s.slateRejected?.let { "refused: $it" },
    ).joinToString(", ")

    /** One control client per pairing for the stream's polls, so they share a connection. */
    private var brbShared: Pair<BrbPairing, BrbClient>? = null

    @Synchronized
    private fun brbClient(pairing: BrbPairing): BrbClient =
        brbShared?.takeIf { it.first == pairing }?.second ?: BrbClient(pairing).also { brbShared = pairing to it }

    /**
     * Why a stream ended, for the session log. [deliberate]: someone chose to end it, so the BRB server is told
     * ([BrbClient.end]) and stops the Twitch output instead of waiting for a reconnect. A mod's !end counts: it's as
     * deliberate as the streamer's own hold-to-end, and viewers should see the stream end, not a Signal lost screen.
     */
    private enum class EndReason(val words: String, val deliberate: Boolean) {
        USER("you ended the stream", true),
        MOD("a mod ended the stream from chat (!end)", true),
        FAILED_START("the stream didn't start", false),
        SYSTEM("Android stopped the service while live", false),
    }

    /**
     * Engine thread, before the engine stops: tells the paired BRB server this end is on purpose, so it ends the Twitch
     * output when the session closes instead of treating it as a drop. Bounded by [BrbClient.END_TIMEOUT_MS]; a failure
     * is logged and the stop goes on (the server then falls back to its own rules).
     */
    private fun tellServerEnded(log: SessionLog?) {
        if (startedTarget !is OutputTarget.Bonded || !engine.state.value.running) return
        val t0 = System.nanoTime()
        val result = runCatching {
            val pairing = BrbPairingStore(SecretStore(this)).load() ?: throw BrbException("no paired BRB server")
            BrbClient(pairing).end()
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        result.onSuccess {
            log?.line("told the BRB server this end is deliberate ($ms ms)")
        }.onFailure { e ->
            Log.w(TAG, "BRB server end not confirmed: ${e.javaClass.simpleName}: ${e.message}")
            log?.line("couldn't tell the BRB server this end is deliberate ($ms ms): ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * While live via Twitch sign-in: run chat for the ticker, apply mods' chat commands (so mods can switch scenes
     * while the streamer's hands are full), and validate the token hourly (a Twitch requirement for apps using OAuth
     * tokens). Chat that ends for good (sign-in revoked, password changed, refresh refused) turns chat off for this
     * stream; the stream goes on.
     */
    private fun startTwitch(broadcasterId: String) {
        twitchJob?.cancel()
        val twitch = Twitch.get(this)
        val hints = HintCooldown()
        twitchJob = scope.launch {
            launch {
                twitch.chat.state.collect { st ->
                    if (st is ChatConnectionState.Stopped) Log.w(TAG, "chat stopped: ${st.reason}")
                    ChatFeed.setStatus(ModReplies.chatStatus(st))
                }
            }
            launch {
                while (isActive) {
                    delay(60 * 60_000L)
                    runCatching { twitch.session.validate() }
                }
            }
            launch { twitch.chat.alerts.collect { AlertQueue.post(it) } }
            // Which alerts Twitch will actually send this stream: in the log, so a missing follow alert has a reason.
            launch {
                twitch.chat.alertSubscriptions.collect { a ->
                    if (a.notifications !is SubscriptionStatus.Pending) sessionLog?.line("alerts: subs, gifts and raids ${subscriptionWords(a.notifications)}")
                    if (a.follows !is SubscriptionStatus.Pending) sessionLog?.line("alerts: follows ${subscriptionWords(a.follows)}")
                }
            }
            try {
                twitch.chat.messages(broadcasterId).collect { msg ->
                    val result = ChatCommandParser.parse(msg)
                    ChatFeed.add(msg, isCommand = result is CommandResult.Accepted)
                    readAloud(msg, result is CommandResult.Accepted)
                    val reply = when (result) {
                        is CommandResult.Accepted -> apply(result.command)?.let { "[${result.issuedByLogin}] $it" }
                        // A mod's typo ("!end" without the confirm word): tell them, but not on every repeat.
                        is CommandResult.Invalid -> if (hints.allow(result.command, System.currentTimeMillis())) "[${msg.chatterLogin}] ${result.hint}" else null
                        else -> null
                    }
                    if (reply != null) launch {
                        runCatching { twitch.chat.sendChatMessage(broadcasterId, broadcasterId, reply) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TwitchException) {
                Log.w(TAG, "chat ended for this stream: ${e.javaClass.simpleName}: ${e.message}")
                ChatFeed.setStatus(ModReplies.CHAT_OFF)
            }
        }
    }

    /** Applies a mod command (chat or the mod remote; any thread); returns a short acknowledgement for chat (null = none). */
    private fun apply(command: ChatCommand): String? = when (command) {
        ChatCommand.Brb -> { engine.setScene(Scene.BRB); "BRB screen on" }
        ChatCommand.Live -> {
            // Only leaving Privacy turns the mic back on: a streamer who muted it themselves stays muted.
            val wasPrivacy = engine.state.value.scene == Scene.PRIVACY
            engine.setScene(Scene.LIVE)
            if (wasPrivacy) engine.setMicMuted(false)
            ModReplies.backLive(micStillOff = !wasPrivacy && engine.state.value.micMuted)
        }
        ChatCommand.Privacy -> { engine.setScene(Scene.PRIVACY); engine.setMicMuted(true); "Privacy on: privacy screen, mic off" }
        ChatCommand.StartingSoon -> { engine.setScene(Scene.STARTING_SOON); "Starting soon screen on" }
        ChatCommand.Ending -> { engine.setScene(Scene.ENDING); "Ending screen on" }
        ChatCommand.RestartCam -> {
            val p = pocket
            if (p != null) main.post { p.restart() } else engine.restartCamera()
            ModReplies.restartCam(pocket = p != null, externalShown = engine.state.value.activeSource == "camera")
        }
        ChatCommand.End -> { scope.launch { delay(1_500); main.post { stopAndForget(EndReason.MOD) } }; "Ending the stream" }
        is ChatCommand.Quality -> "Quality changes from chat aren't supported yet"
    }

    @Volatile private var pocket: app.irlsupreme.dji.PocketSession? = null

    /** DJI Pocket 3 as the camera, kept streaming (with reconnects) for as long as the phone is live. */
    private fun startPocket() {
        pocket = app.irlsupreme.dji.PocketSession(this, { engine.externalCameraIdleMs() }, { engine.externalCameraConnected() }, hotspot)
            .also { it.start() }
    }

    private var hotspot: PhoneHotspot? = null
    private var beacon: HotspotBeacon? = null

    /**
     * The phone's private hotspot, for the Pocket (if that's its network) and helper phones (if accepted): helpers
     * get its name and password over Bluetooth ([HotspotBeacon]) and join it by themselves. When helpers wanted one
     * and don't get it, [HelperHotspotStatus.problem] says why for the live screen.
     */
    private fun startHotspot(target: OutputTarget) {
        HelperHotspotStatus.problem.value = null
        val forPocket = ExternalCamera.kind(this) == ExternalCamera.Kind.POCKET &&
            ExternalCamera.cameraNet(this) == ExternalCamera.CameraNet.PRIVATE
        val helpers = (target as? OutputTarget.Bonded)?.helpers?.takeIf { Helpers.makeHotspot(this) }
        // A phone that can't be a hotspot and a Wi-Fi client at once would drop its own Wi-Fi link for helpers. On
        // mobile data alone there's nothing to drop.
        val concurrent = runCatching { getSystemService(WifiManager::class.java).isStaApConcurrencySupported }.getOrDefault(false)
        val forHelpers = helpers != null && (concurrent || forPocket || !onWifi())
        if (helpers != null && !forHelpers) {
            Log.i(TAG, "helpers: no hotspot (this phone can't keep its Wi-Fi alongside one)")
            HelperHotspotStatus.problem.value = ModReplies.NO_HOTSPOT_ON_WIFI
        }
        if (!forPocket && !forHelpers) return
        val hs = PhoneHotspot(this).also { hotspot = it }
        if (helpers == null || !forHelpers) return
        var keys: HotspotShare.Keys? = null
        // Called again with new details whenever Android restarts the hotspot mid-stream.
        hs.use(onReady = { info ->
            HelperHotspotStatus.state.value = info.ssid to info.password
            HelperHotspotStatus.problem.value = null
            scope.launch { // PBKDF2 takes a moment: off the main thread (once per stream)
                val k = keys ?: HotspotShare.Keys(helpers.password).also { keys = it }
                main.post {
                    if (hotspot !== hs || hs.current !== info) return@post // the stream ended, or a newer hotspot
                    beacon?.stop()
                    beacon = HotspotBeacon(this@StreamService, k, HotspotShare.Credentials(info.ssid, info.password, info.security))
                        .takeIf { it.start() }
                    if (beacon == null) HelperHotspotStatus.problem.value = ModReplies.NO_BEACON
                }
            }
        }, onFailed = {
            Log.w(TAG, "helpers: no hotspot: $it")
            HelperHotspotStatus.problem.value = "No helper hotspot: $it"
        }, onLost = {
            beacon?.stop(); beacon = null
            HelperHotspotStatus.state.value = null
        })
    }

    /** Whether this phone is on a Wi-Fi network as a client right now. */
    @Suppress("DEPRECATION") // allNetworks: also Wi-Fi without internet, which a hotspot would still knock off
    private fun onWifi(): Boolean = runCatching {
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }.getOrDefault(true)

    private var overlayJob: Job? = null
    private var alertJob: Job? = null

    private var remoteJob: Job? = null

    /**
     * The mod remote: while live through the paired BRB server, every 2 s tell it what viewers see and apply the
     * scenes mods picked there, exactly like their chat commands.
     */
    private fun startRemote() {
        remoteJob?.cancel()
        remoteJob = scope.launch {
            val pairing = BrbPairingStore(SecretStore(this@StreamService)).load() ?: return@launch
            val client = brbClient(pairing)
            while (isActive) {
                val st = engine.state.value
                runCatching { client.pollRemote(st.running, st.scene.name, !st.micMuted) }.getOrNull()?.forEach { scene ->
                    val cmd = when (scene) {
                        "LIVE" -> ChatCommand.Live
                        "BRB" -> ChatCommand.Brb
                        "PRIVACY" -> ChatCommand.Privacy
                        "STARTING_SOON" -> ChatCommand.StartingSoon
                        "ENDING" -> ChatCommand.Ending
                        else -> null
                    }
                    if (cmd != null) {
                        Log.i(TAG, "mod remote: $scene")
                        apply(cmd)
                    }
                }
                delay(2_000)
            }
        }
    }

    private var speaker: ChatSpeaker? = null

    /** Reads [m] into the streamer's headphones if Settings › Twitch › Read chat aloud says so (and headphones are on). */
    private fun readAloud(m: app.irlsupreme.twitch.ChatMessage, isCommand: Boolean) {
        val mode = ReadAloud.entries.firstOrNull { it.name == getSharedPreferences("settings", MODE_PRIVATE).getString("read_aloud", null) } ?: ReadAloud.OFF
        if (!ChatSpeechText.shouldRead(m, mode, isCommand)) return
        val text = ChatSpeechText.utterance(m) ?: return
        val s = speaker ?: ChatSpeaker(this).also { speaker = it }
        s.say(text)
    }

    private var tipsJob: Job? = null

    /**
     * Tips from StreamElements and Streamlabs, if their tokens are set (Settings › Overlays › Tips), as alerts. Any
     * stream, signed in to Twitch or not.
     */
    private fun startTips() {
        tipsJob?.cancel()
        AlertQueue.tipProblems.value = emptySet()
        tipsJob = scope.launch {
            val secrets = SecretStore(this@StreamService)
            val se = secrets.get("tips_se_jwt")?.trim()?.takeIf { it.isNotEmpty() }
            val sl = secrets.get("tips_sl_token")?.trim()?.takeIf { it.isNotEmpty() }
            if (se == null && sl == null) return@launch
            val client = app.irlsupreme.twitch.TipsClient()
            fun watch(name: String, flow: kotlinx.coroutines.flow.Flow<app.irlsupreme.twitch.TwitchAlert.Tip>) = launch {
                try {
                    flow.collect { AlertQueue.post(it) }
                } catch (e: app.irlsupreme.twitch.TwitchException.Unauthorized) {
                    Log.w(TAG, "tips: $name refused its token")
                    AlertQueue.tipProblems.value = AlertQueue.tipProblems.value + name
                }
            }
            se?.let { watch("StreamElements", client.streamElements(it)) }
            sl?.let { watch("Streamlabs", client.streamlabs(it)) }
        }
    }

    /**
     * Shows queued alerts one at a time ([AlertQueue]) on a [width] x [height] stream. While viewers see Privacy, Weak
     * signal, Camera lost or nothing at all, the next alert waits, chime and all ([AlertHold], and the engine's own
     * `alertsAllowed`), so nobody hears a chime over a card that hides its alert; the queue keeps the newest
     * [AlertQueue.MAX_WAITING] meanwhile. Alerts turned off in Settings are dropped, as before.
     */
    private fun startAlerts(width: Int, height: Int) {
        alertJob?.cancel()
        AlertQueue.clear()
        alertJob = scope.launch {
            while (isActive) {
                if (AlertQueue.peek() == null) { AlertQueue.awaitPosted(); continue }
                val s = OverlayStore.load(this@StreamService)
                if (!s.alertsOn) { AlertQueue.poll(); continue }
                if (!engine.alertsAllowed || !AlertHold.allowed(engine.state.value)) { delay(AlertTiming.HOLD_POLL_MS); continue }
                val a = AlertQueue.poll() ?: continue
                showAlert(AlertText.card(a), s, width, height)
            }
        }
    }

    /**
     * One alert: drawn once, handed to the engine's alert layer (which wipes it in, holds it for its
     * dwell and wipes it out), with the chime started so its attack lands on the first fully revealed frame. Returns
     * after the exit and the gap before the next alert.
     */
    private suspend fun showAlert(card: AlertCard, s: OverlaySettings, width: Int, height: Int) {
        val st = engine.state.value
        val onCard = AlertHold.onCard(st.scene)
        // A corner layer only counts where it's drawn: over a card, only if the streamer put the layers on cards too.
        val layers = if (onCard && !s.onCards) emptySet() else OverlayLayout.occupied(s)
        val occupied = LiveElements.alertOccupied(layers, onCard && cardColumnTaken)
        val (bitmap, tier) = OverlayComposer.alertBitmap(card, width, height)
        val at = OverlayComposer.alertPosition(width, height, onCard, occupied, Size(bitmap.width, bitmap.height))
        val cut = AlertTiming.cut(st.thermalRung)
        val enterMs = AlertTiming.enterMs(tier, cut)
        // The kind's own sound (or the chime, or none), read before the alert goes up so it starts on time.
        val sound = if (s.alertSound) AlertSoundChoices.prepare(this, card.kind, card.style) else null
        engine.showAlert(AlertGraphic(bitmap, at.x, at.y, tier, card.dwellMs))
        // The kind only: who sent it and their message stay out of the log.
        sessionLog?.line("alert shown: ${card.style.name.lowercase()} (${alertsShown.incrementAndGet()} this stream)")
        sound?.play(engine, enterMs)?.let { sessionLog?.line(it) }
        try {
            delay(enterMs)
            AlertQueue.show(card) // fully revealed: the Live screen announces it now
            delay(card.dwellMs)
        } finally {
            AlertQueue.show(null)
        }
        delay(AlertTiming.exitMs(cut) + AlertTiming.GAP_MS)
    }

    /** What the overlay loop redraws on: chat, the overlay settings, emotes arriving, the card on screen, card settings. */
    private data class OverlayInputs(
        val lines: List<ChatLine>, val rev: Int, val emotes: Int, val card: Slates.Card?, val cards: Map<Slates.Card, CardText>,
    )

    /** The card on screen's right-hand column (timers, chat) is taken, so an alert on it keeps left. Any thread. */
    @Volatile private var cardColumnTaken = false

    /**
     * Keeps the stream's overlay (chat, text, image: [OverlaySettings]) drawn while live, plus the live elements of
     * the phone-drawn card on screen ([LiveElements]: countdown, time away, chat, socials). Alerts aren't part of it
     * (the engine's alert layer draws them from their own small bitmaps). Redraws when chat or the settings change, at
     * most every [MIN_REDRAW_MS] (a busy chat still moves, in steps), at once when a card comes or goes, once a second
     * on the second while a card's timer is up (and only then; every 5 s from thermal rung 2, when the engine shows no
     * more), and every [REDRAW_EVERY_MS] regardless so old chat lines go; skips identical frames, so a quiet chat
     * costs nothing.
     *
     * Over a chosen card (BRB, Starting soon, Ending) the engine shows the overlay only with its "on cards" switch. When
     * the streamer left that off but the card has elements, the bitmap holds just those and the switch goes on, but
     * only once that bitmap has had time to be uploaded ([LiveElements.switchSettleMs]: a few frames, or a whole hot
     * upload interval from thermal rung 2), so the Live-only layers never show on the card; the web overlay is held
     * back meanwhile, since the switch would let it through too. Memory: at most two overlay bitmaps of the stream's
     * size exist at once (one held by the engine, one being drawn), as before.
     */
    private fun startOverlays(width: Int, height: Int) {
        overlayJob?.cancel()
        overlayJob = scope.launch(Dispatchers.Default) {
            var lastKey: Any? = null
            var lastBrowser: String? = null
            var image: android.graphics.Bitmap? = null
            var imageStamp = -1L
            val clock = CardClock()
            /** Whether the last pushed bitmap drew anything; it stands while the key is unchanged. */
            var lastDrewSomething = false
            /**
             * When the current run of pushes fit for a card (none of the streamer's Live-only layers) began, on
             * elapsedRealtime; null while the newest push has those layers. The engine starts with no overlay, which
             * is fit, so the run starts "long ago".
             */
            var cardSafeSince: Long? = Long.MIN_VALUE / 2
            val cardShown = engine.state
                .map { LiveElements.cardOnScreen(it.scene, it.weakSignal, it.activeSource) }
                .distinctUntilChanged()
            // Emote images arrive after their messages: a new one redraws the chat with it.
            val inputs = combine(ChatFeed.lines, OverlayStore.changes, EmoteCache.version, cardShown, CardStore.state(this@StreamService)) {
                    lines, rev, emotes, card, cards -> OverlayInputs(lines, rev, emotes, card, cards)
                }
                .stateIn(this)
            while (isActive) {
                val drawn = inputs.value
                val now = android.os.SystemClock.elapsedRealtime()
                val s = OverlayStore.load(this@StreamService)
                val card = drawn.card
                val upFor = clock.update(card, now)
                val plan = card?.let { cardPlan(it, s, upFor) }
                // Over a chosen card without "on cards", the bitmap carries only the card's own elements.
                val cardOnly = card != null && card != Slates.Card.CAMERA_LOST && !s.onCards && plan != null
                cardColumnTaken = plan != null && (plan.timers.isNotEmpty() || plan.chat)

                val file = OverlayStore.imageFile(this@StreamService)
                val stamp = if (s.imageOn && file.exists()) file.lastModified() else -1L
                if (stamp != imageStamp) {
                    image?.recycle()
                    image = if (stamp >= 0) OverlayStore.loadImage(this@StreamService) else null
                    imageStamp = stamp
                }
                val chat = OverlayLayout.visibleChat(drawn.lines, System.currentTimeMillis())
                val key = listOf(s, stamp, chat.map { it.id }, EmoteCache.version.value, plan, cardOnly)
                val redraw = key != lastKey
                val bmp = if (redraw) OverlayComposer.compose(s, chat, image, width, height, plan, userLayers = !cardOnly) else null
                if (redraw) lastDrewSomething = bmp != null
                val cardElements = cardOnly && lastDrewSomething
                // The page goes before the switch can let it through, and comes back once the card's elements go.
                val hold = LiveElements.holdBrowser(card, s.onCards, cardElements)
                val url = if (s.browserOn && !hold) OverlayStore.browserUrl(this@StreamService) else null
                if (url != lastBrowser) {
                    lastBrowser = url
                    engine.setBrowserOverlay(url?.let { app.irlsupreme.engine.video.BrowserSource.Content.Url(it) })
                }
                val rung = engine.state.value.thermalRung
                if (redraw) {
                    val wanted = LiveElements.overlayOnCards(s.onCards, cardElements)
                    // Hot, the engine uploads a pushed bitmap up to 5 s late, so the wait for it follows the rung.
                    val settle = LiveElements.switchSettleMs(rung)
                    val wait = LiveElements.switchWaitMs(cardSafeSince, now, settle)
                    val switch = LiveElements.safeSwitch(wanted, heldBitmapFitForCards = wait == 0L)
                    engine.setOverlay(bmp, switch)
                    val fit = bmp == null || cardOnly || s.onCards
                    cardSafeSince = if (!fit) null else cardSafeSince ?: now
                    if (switch != wanted) {
                        // Switch held off for now: draw again once this bitmap must be on the render thread, then flip
                        // it. A card change meanwhile ends the wait (the next pass works out the switch afresh).
                        lastKey = null
                        val shown = drawn.card
                        withTimeoutOrNull(LiveElements.switchWaitMs(cardSafeSince, now, settle).coerceAtLeast(1L)) {
                            inputs.first { it.card != shown }
                        }
                        continue
                    }
                    lastKey = key
                }
                // A timer changes on the card's whole seconds (every 5 s when hot); nothing else ticks.
                val untilNext = if (plan?.ticking == true) {
                    LiveElements.msToNextTick(upFor, LiveElements.tickPeriodMs(rung)) + TICK_SLACK_MS
                } else REDRAW_EVERY_MS
                fun left() = untilNext - (android.os.SystemClock.elapsedRealtime() - now)
                // A card coming or going is drawn at once; anything else waits at least MIN_REDRAW_MS, never past a tick.
                withTimeoutOrNull(minOf(MIN_REDRAW_MS, left()).coerceAtLeast(1L)) { inputs.first { it.card != drawn.card } }
                if (inputs.value.card == drawn.card && left() > 0) withTimeoutOrNull(left()) { inputs.first { it != drawn } }
            }
        }
    }

    /**
     * What the overlay draws over [card], up [upForMs] (null when nothing): its settings from Settings › Scene cards,
     * read fresh each pass so a change mid-stream shows. A failure to read them draws nothing extra rather than
     * stopping the overlay.
     */
    private fun cardPlan(card: Slates.Card, s: OverlaySettings, upForMs: Long): CardOverlayPlan? {
        if (!LiveElements.drawsOn(card)) return null
        return runCatching {
            val style = Slates.style(card)
            val look = LiveElements.look(
                card, background = style.background != null || style.backgroundGif != null, picture = style.picture != null,
                layout = style.layout, web = style.webUrl != null, headline = style.headline ?: card.headline,
            )
            // The streamer's chat layer is already on this card: always on Camera lost (it stands in for Live), and on
            // the chosen cards when they put their layers on cards.
            val userChat = s.chat && (card == Slates.Card.CAMERA_LOST || s.onCards)
            LiveElements.plan(card, CardStore.elements(this, card), CardStore.socials(this), upForMs, userChat, look)
        }.onFailure { Log.w(TAG, "card elements skipped: ${it.javaClass.simpleName}") }.getOrNull()
    }

    private fun stopHotspot() {
        beacon?.stop(); beacon = null
        hotspot?.stop(); hotspot = null
        HelperHotspotStatus.state.value = null
        HelperHotspotStatus.problem.value = null
    }

    private fun stopPocket() {
        pocket?.stop()
        pocket = null
    }

    /** Everything around the engine. Main thread. */
    private fun stopSideFeatures() {
        stopPocket()
        stopHotspot()
        overlayJob?.cancel(); overlayJob = null
        alertJob?.cancel(); alertJob = null
        tipsJob?.cancel(); tipsJob = null
        speaker?.shutdown(); speaker = null
        remoteJob?.cancel(); remoteJob = null
        AlertQueue.clear()
        twitchJob?.cancel(); twitchJob = null
        ChatFeed.clear()
        logJob?.cancel(); logJob = null
        eventsJob?.cancel(); eventsJob = null
        stopDeviceWatch()
    }

    /**
     * Ends the stream: side features now, then (for a deliberate end of a bonded stream) a word to the BRB server, then
     * the engine on [engineExec] (after a start still under way), then the service. End, chat's !end and the mod remote
     * all come here on the main thread; only the first one counts.
     */
    private fun stopAndForget(reason: EndReason, markEnded: Boolean = true): Int {
        if (!live) {
            // Nothing streaming (a second End, or a start without a target): just make sure the service goes.
            if (!ending) { releaseLocks(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            return START_NOT_STICKY
        }
        live = false
        ending = true
        val gen = ++generation
        val log = sessionLog
        sessionLog = null
        log?.essential("ending: ${reason.words}")
        engine.audioDiagnostics()?.let { log?.line(it.line()) }
        val now = System.currentTimeMillis()
        val screenOffMs = screenOffTime.totalMs(now)
        val pocketMs = pocketTime.totalMs(now)
        stopSideFeatures()
        engineExec.execute {
            if (reason.deliberate) tellServerEnded(log)
            stopEngine(markEnded, log, reason.words, screenOffMs, pocketMs)
            main.post {
                if (gen != generation || destroyed) return@post // a new stream started meanwhile: it owns the service
                ending = false
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Engine thread (or onDestroy). A start that failed already cleaned up and keeps its reason in the state. Ends and
     * closes the stream's [log], if any, with [why] and the recap.
     */
    private fun stopEngine(markEnded: Boolean, log: SessionLog? = null, why: String = "", screenOffMs: Long = 0, pocketScreenMs: Long = 0) {
        val wasRunning = engine.state.value.running
        if (wasRunning) {
            try {
                engine.stop()
            } catch (t: Throwable) {
                Log.e(TAG, "engine stop failed", t)
                log?.essential("engine stop threw ${t.javaClass.simpleName}: ${t.message}")
            }
            if (markEnded) SessionStore(this).markEnded(engine.lastSession.value)
        }
        startedTarget = null
        if (log != null) {
            val summary = engine.lastSession.value.takeIf { wasRunning }
            SessionLogText.endLines(why, summary, alertsShown.get(), java.time.ZoneId.systemDefault(), screenOffMs, pocketScreenMs)
                .forEach(log::essential)
            log.close()
        }
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        val log = sessionLog
        sessionLog = null
        if (live) {
            log?.essential("ending: ${EndReason.SYSTEM.words}")
            stopSideFeatures()
        }
        live = false
        scope.cancel()
        // Normally the engine stopped already. If Android ends the service mid-stream, stop it after whatever is queued.
        engineExec.execute { stopEngine(markEnded = true, log, EndReason.SYSTEM.words) }
        engineExec.shutdown()
        releaseLocks()
        super.onDestroy()
    }

    /** [preview]: the stream is in preview, not live yet; the notification says so until Go live. */
    private fun goForeground(preview: Boolean = false) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Live stream", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(app.irlsupreme.R.drawable.ic_stat_fullbars)
            .setContentTitle(if (preview) "Preview: not live yet" else "You're live")
            .setContentText(if (preview) "Tap to check your shot and go live" else "Tap to open the live screen")
            .setContentIntent(open)
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        startForeground(
            NOTIFICATION_ID, n,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "irlsupreme:stream")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLocks == null) {
            wifiLocks = WifiLocks.of(getSystemService(WifiManager::class.java)).apply { acquire("irlsupreme:stream") }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        wifiLocks?.release(); wifiLocks = null
    }

    companion object {
        private const val TAG = "Engine"
        private const val CHANNEL = "live"
        private const val NOTIFICATION_ID = 1
        private const val MIN_REDRAW_MS = 300L
        private const val REDRAW_EVERY_MS = 5_000L
        /** Draw a timer just after its second turns, never just before (which would show the old figure again). */
        private const val TICK_SLACK_MS = 15L
        /** How often the session log writes a full snapshot (changes are written as they happen). */
        private const val SNAPSHOT_EVERY_MS = 5_000L
        /** How often the battery is read into the engine. */
        private const val BATTERY_EVERY_MS = 10_000L
        /** When the audio path's finer health first goes into the log. */
        private const val AUDIO_DIAGNOSTICS_AFTER_MS = 10_000L
        /** A keyframe poll gives up after this: the next one is due soon anyway. */
        private const val KEYFRAME_POLL_TIMEOUT_MS = 1_500L
        const val ACTION_START = "app.irlsupreme.START"
        const val ACTION_STOP = "app.irlsupreme.STOP"
        const val ACTION_SCENE = "app.irlsupreme.SCENE"
        const val ACTION_MIC = "app.irlsupreme.MIC"
        /** Ends the preview and starts sending; [EXTRA_SCENE] optionally names the scene viewers see first. */
        const val ACTION_GO_LIVE = "app.irlsupreme.GO_LIVE"
        /** Start in preview: nothing is sent until [ACTION_GO_LIVE]. */
        const val EXTRA_PREVIEW = "preview"
        const val EXTRA_SCENE = "scene"
        const val EXTRA_MUTED = "muted"
        const val EXTRA_URL = "url"
        const val EXTRA_WIDTH = "w"
        const val EXTRA_HEIGHT = "h"
        const val EXTRA_BITRATE = "bps"
        /** The frame rate asked for, 30 or 60; the service and the engine check 60 against the phone. */
        const val EXTRA_FPS = "fps"
        const val EXTRA_BROADCASTER_ID = "broadcaster"
        const val EXTRA_BOND_HOST = "bond_host"
        const val EXTRA_BOND_PORT = "bond_port"
        const val EXTRA_BOND_STREAM_ID = "bond_stream_id"
        const val EXTRA_BOND_PASSPHRASE = "bond_passphrase"

        fun start(ctx: Context, url: String, width: Int, height: Int, bitrate: Int, broadcasterId: String? = null, fps: Int = 30, preview: Boolean = false) {
            ctx.startForegroundService(
                Intent(ctx, StreamService::class.java).setAction(ACTION_START)
                    .putExtra(EXTRA_URL, url).putExtra(EXTRA_WIDTH, width)
                    .putExtra(EXTRA_HEIGHT, height).putExtra(EXTRA_BITRATE, bitrate).putExtra(EXTRA_FPS, fps)
                    .putExtra(EXTRA_BROADCASTER_ID, broadcasterId).putExtra(EXTRA_PREVIEW, preview),
            )
        }

        /**
         * "Combine connections": stream to a paired BRB server, which sends it on to Twitch (the app sets the
         * server's output first). [streamId] is "live/PUBLISH_ID" for a paired server.
         */
        fun startBonded(
            ctx: Context, host: String, port: Int, streamId: String, passphrase: String?,
            width: Int, height: Int, bitrate: Int, broadcasterId: String? = null, fps: Int = 30, preview: Boolean = false,
        ) {
            ctx.startForegroundService(
                Intent(ctx, StreamService::class.java).setAction(ACTION_START)
                    .putExtra(EXTRA_BOND_HOST, host).putExtra(EXTRA_BOND_PORT, port).putExtra(EXTRA_BOND_STREAM_ID, streamId)
                    .putExtra(EXTRA_BOND_PASSPHRASE, passphrase)
                    .putExtra(EXTRA_WIDTH, width).putExtra(EXTRA_HEIGHT, height).putExtra(EXTRA_BITRATE, bitrate).putExtra(EXTRA_FPS, fps)
                    .putExtra(EXTRA_BROADCASTER_ID, broadcasterId).putExtra(EXTRA_PREVIEW, preview),
            )
        }

        /** Ends the preview: the stream starts sending, on [scene] if given (else whatever is showing). */
        fun goLive(ctx: Context, scene: Scene? = null) {
            ctx.startService(Intent(ctx, StreamService::class.java).setAction(ACTION_GO_LIVE).putExtra(EXTRA_SCENE, scene?.name))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, StreamService::class.java).setAction(ACTION_STOP))
        }
    }
}

/** The words [StreamService] puts on screen and in chat, kept apart so they can be tested. */
internal object ModReplies {
    /** Chat stopped for good this stream; the reason (for the logs) is never shown raw. */
    const val CHAT_OFF = "Chat is off. Sign in to Twitch again after the stream."
    const val NO_HOTSPOT_ON_WIFI = "No helper hotspot: this phone can't run one while it's on Wi‑Fi. Put helpers on the same Wi‑Fi instead."
    const val NO_BEACON = "Helpers can't get the hotspot over Bluetooth. Turn Bluetooth on, or type its name and password on each helper."

    fun chatStatus(state: ChatConnectionState): String = when (state) {
        is ChatConnectionState.Connected -> "Chat connected"
        is ChatConnectionState.Reconnecting -> "Chat reconnecting…"
        is ChatConnectionState.Stopped -> CHAT_OFF
        else -> "Chat connecting…"
    }

    fun backLive(micStillOff: Boolean) = if (micStillOff) "Back live (mic still off)" else "Back live"

    /** [pocket]: the Pocket's feed is restarted; otherwise the phone camera (the backup, if an RTMP camera is on air). */
    fun restartCam(pocket: Boolean, externalShown: Boolean) = when {
        pocket -> "Restarting the Pocket feed"
        externalShown -> "Restarting the phone camera"
        else -> "Restarting camera"
    }
}

/** At most one reply per command per [windowMs], so a mod repeating a typo doesn't make the channel spam back. */
internal class HintCooldown(private val windowMs: Long = 10_000) {
    private val lastMs = HashMap<String, Long>()

    @Synchronized
    fun allow(command: String, nowMs: Long): Boolean {
        val last = lastMs[command]
        if (last != null && nowMs - last < windowMs) return false
        lastMs[command] = nowMs
        return true
    }
}

/** An alert subscription's state in the session log's words. */
internal fun subscriptionWords(s: SubscriptionStatus): String = when (s) {
    SubscriptionStatus.Pending -> "being set up"
    SubscriptionStatus.Active -> "on"
    SubscriptionStatus.NeedsSignIn -> "off: this Twitch sign-in can't see followers (sign in again)"
    is SubscriptionStatus.Failed -> "off: Twitch refused (${s.reason.take(120)})"
}

/**
 * Whether the live screen's Pocket screen (black, dimmed, for a phone in a pocket) is on: the live screen sets it, the
 * session log records when and for how long.
 */
object PocketScreen {
    val on = MutableStateFlow(false)
}

/**
 * Something about the mic the streamer should know now ("External mic disconnected"), for the live screen's notice
 * line; null once shown. Posted by the stream service from the engine's mic events.
 */
object MicNotice {
    val text = MutableStateFlow<Pair<String, Long>?>(null)

    fun post(message: String) { text.value = message to System.currentTimeMillis() }
}
