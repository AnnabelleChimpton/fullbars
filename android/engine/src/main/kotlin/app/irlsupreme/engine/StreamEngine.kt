package app.irlsupreme.engine

import android.content.Context
import android.media.MediaCodec
import android.os.PowerManager
import android.view.Surface
import app.irlsupreme.engine.abr.DetailRungPolicy
import app.irlsupreme.engine.abr.RtmpRateController
import app.irlsupreme.engine.audio.ActiveInput
import app.irlsupreme.engine.audio.AudioDiagnostics
import app.irlsupreme.engine.audio.AudioPipeline
import app.irlsupreme.engine.audio.AudioSink
import app.irlsupreme.engine.audio.CameraAudio
import app.irlsupreme.engine.audio.DelaySource
import app.irlsupreme.engine.audio.InputChoice
import app.irlsupreme.engine.audio.LowCut
import app.irlsupreme.engine.ingest.IngestListener
import app.irlsupreme.engine.ingest.RtmpIngestServer
import app.irlsupreme.engine.output.BondedOutput
import app.irlsupreme.engine.output.RtmpOutput
import app.irlsupreme.engine.output.StreamOutput
import app.irlsupreme.engine.video.CadenceSnapshot
import app.irlsupreme.engine.video.CameraCapabilities
import app.irlsupreme.engine.video.Compositor
import app.irlsupreme.engine.video.VideoSink
import app.irlsupreme.engine.audio.PcmClip
import srtlakt.abr.BitrateController
import srtlakt.abr.DelayPreset
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The streaming engine: camera + mic -> fixed-clock compositor -> H.264/AAC -> RTMP(S).
 *
 * Owned by a foreground service (never by an Activity), so rotation, backgrounding and screen-off don't touch it.
 * The UI only attaches/detaches a preview surface and sends intents (scene, mute, stop).
 */
class StreamEngine(private val context: Context) {
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()
    /**
     * Guards [_state] against a late monitor tick: [stop] clears [running] under this lock first, and [publish] only
     * writes while it's set, so a stopped engine can never be republished as running.
     */
    private val stateLock = Any()
    @Volatile private var running = false

    private var config: StreamConfig? = null
    @Volatile private var compositor: Compositor? = null
    @Volatile private var audio: AudioPipeline? = null
    /** The last stream's final audio mix ([recentAudio]): replaced when a stream starts, kept after it stops. */
    @Volatile private var mixRing: app.irlsupreme.engine.audio.PcmRing? = null
    @Volatile private var output: StreamOutput? = null
    @Volatile private var monitor: ScheduledExecutorService? = null
    private var ingest: RtmpIngestServer? = null
    private var ingestListener: IngestListener? = null
    private var cameraAudio: CameraAudio? = null
    @Volatile private var cameraStatus: String? = null
    /** When the external camera last started publishing (ms, monotonic), for "connected, no video yet"; 0 if not. */
    @Volatile private var cameraPublishedAtMs = 0L

    /** All media timestamps are rebased to this (µs on the System.nanoTime clock) before muxing. */
    private var baseUs = 0L
    @Volatile private var liveSinceMs = 0L
    private var targetBitrate = 0
    /** Bonded streams: delay-based control from the bonding sender's link stats. */
    @Volatile private var abr: BitrateController? = null
    /** Direct RTMP: control from the send backlog and what leaves the socket. */
    @Volatile private var rtmpAbr: RtmpRateController? = null
    /** The camera's render scale for the bitrate; null unless [StreamConfig.detailRung]. */
    private var detail: DetailRungPolicy? = null
    /** Bonded: the links' delivery while connecting, for the controller's seed; and whether it has started. */
    private var preLive = PreLiveRate()
    private var abrStarted = false
    private var resumeWatch = ResumeWatch()
    /** Direct RTMP: the backlog is past saving (the output flushes it itself); logged once per episode. */
    private var rtmpFlushing = false
    private var rtmpReading = -1L
    private val abrEvents = RateLimit(ABR_EVENT_EVERY_MS)
    private var weak = app.irlsupreme.engine.abr.WeakSignalDetector()
    /** Thermal cap on the bitrate, refreshed by [tick]. */
    @Volatile private var thermalCeiling = Int.MAX_VALUE
    private var ladder = ThermalLadder()
    @Volatile private var headroom = Float.NaN
    private var lastHeadroomMs = 0L
    /** Ticks the stream counted as congested in a row: one blip isn't worth a Weak pill. */
    private val congestedHold = TickHold(2)
    /** Time spent at each thermal rung this stream, ms, and when that was last counted. */
    private val rungMs = LongArray(4)
    private var rungCountedAtMs = 0L
    private var lastDiagMs = 0L
    /** Why the stream runs at another frame rate than asked (empty when it doesn't). */
    @Volatile private var fpsFallback = ""
    /** Bonded streams start low and ramp up; Weak isn't shown for that ramp (see [qualityCeiling]). */
    private var rampedUp = false
    /** Encoded frames that couldn't be sent because the connection was down after first going live. */
    @Volatile private var droppedWhileDown = 0L
    /** The compositor's skipped-tick count when the stream first went live (earlier skips are setup, not drops). */
    @Volatile private var skippedAtLive = 0L
    private var startedAtMs = 0L
    private val bytesSent = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var peakThermal = 0
    @Volatile private var lowestBitrate = 0
    /** The phone camera the user picked; kept across streams. */
    @Volatile private var facing = Facing.BACK

    private val _micLevel = MutableStateFlow(app.irlsupreme.engine.audio.MicLevel.SILENT)
    /**
     * The mic's level as viewers get it (before the user's mute), ~47 times a second while streaming, SILENT otherwise.
     * Separate from [state] so a meter can follow it without the whole live screen recomposing.
     */
    val micLevel: StateFlow<app.irlsupreme.engine.audio.MicLevel> = _micLevel.asStateFlow()
    @Volatile private var micGainDb = 0f
    @Volatile private var rawMic = false
    @Volatile private var rawMicGainDb = 20f
    @Volatile private var lowCut = LowCut.AUTO
    @Volatile private var voiceLeveler = false
    @Volatile private var monoVoice = false
    @Volatile private var micInput = InputChoice.AUTO
    @Volatile private var externalMicGainDb: Float? = null

    /**
     * Lip sync, read by the audio thread once per chunk (plain volatile reads): the camera delay the sound is held
     * back by (0 unless automatic lip sync is on) and the user's own offset. The monitor refreshes the first.
     */
    @Volatile private var appliedVideoDelayNs = 0L
    @Volatile private var avOffsetNs = 0L
    /** The on-screen camera's measured delay, reported whether or not it's applied. */
    @Volatile private var measuredVideoDelayNs = 0L
    @Volatile private var autoLipSync = false

    /** The phone's battery, as the app reports it ([setBattery]); -1 / NaN while unknown. */
    @Volatile private var batteryPct = -1
    @Volatile private var batteryTempC = Float.NaN
    @Volatile private var batteryCharging = false
    @Volatile private var batteryStartPct = -1
    @Volatile private var batteryStartMs = 0L
    @Volatile private var batteryLastMs = 0L

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /**
     * Things the session log should record as they happen: link events, bitrate changes (at most one a second), mic
     * input changes and notes. Emitted from engine threads without blocking; a slow collector loses the oldest.
     */
    val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    private fun note(line: String) {
        android.util.Log.i(TAG, line)
        _events.tryEmit(EngineEvent.Note(line))
    }

    private val _lastSession = MutableStateFlow<SessionSummary?>(null)
    /** Summary of the most recent stream, set when it stops (or fails to start). */
    val lastSession: StateFlow<SessionSummary?> = _lastSession.asStateFlow()

    /**
     * Starts streaming. Returns false (with [EngineState.lastError] and [lastSession] saying why, in plain words) when
     * the pipeline couldn't be built, e.g. the encoder rejects the size; nothing is left running then.
     */
    @Synchronized
    fun start(config: StreamConfig): Boolean {
        if (compositor != null) return true
        // The cards and alerts are drawn in the bundled faces; cheap after the first call, and never Roboto on stream.
        app.irlsupreme.engine.video.BrandType.load(context)
        // 60 fps only where the phone camera and encoder can carry it at this size; anything else runs at 30.
        val (fps, why) = EngineRules.chooseFps(config.fps, config.camera is CameraInput.Phone) {
            CameraCapabilities.supports(context, facing, config.width, config.height, it)
        }
        fpsFallback = why
        if (why.isNotEmpty()) note("frame rate: $why")
        @Suppress("NAME_SHADOWING") val config = config.copy(fps = fps)
        this.config = config
        baseUs = System.nanoTime() / 1000
        targetBitrate = config.videoBitrate
        liveSinceMs = 0
        congestedHold.reset()
        rampedUp = false
        droppedWhileDown = 0
        skippedAtLive = 0
        startedAtMs = System.currentTimeMillis()
        bytesSent.set(0)
        peakThermal = 0
        lowestBitrate = config.videoBitrate
        ladder = ThermalLadder()
        headroom = Float.NaN
        lastHeadroomMs = 0
        thermalCeiling = Int.MAX_VALUE
        weak = app.irlsupreme.engine.abr.WeakSignalDetector()
        preLive = PreLiveRate()
        abrStarted = false
        resumeWatch = ResumeWatch()
        rtmpFlushing = false
        rtmpReading = -1L
        rungMs.fill(0)
        rungCountedAtMs = 0
        lastDiagMs = 0
        cameraPublishedAtMs = 0
        autoLipSync = config.autoLipSync
        avOffsetNs = EngineRules.clampAvOffset(config.avOffsetNs)
        appliedVideoDelayNs = 0
        measuredVideoDelayNs = 0
        batteryStartPct = batteryPct
        batteryStartMs = if (batteryPct >= 0) System.currentTimeMillis() else 0
        batteryLastMs = batteryStartMs
        running = true

        val onConnected = {
            compositor?.requestKeyframe()
            if (liveSinceMs == 0L) {
                skippedAtLive = compositor?.skippedTicks ?: 0
                liveSinceMs = System.currentTimeMillis()
            }
        }
        try {
            val out: StreamOutput = when (val t = config.target) {
                // With the context it reconnects as soon as a new network comes up; keyframes go straight to the encoder.
                is OutputTarget.Rtmp -> RtmpOutput(
                    t.url, config.width, config.height, config.fps, onConnected,
                    context = context, onRequestKeyframe = { compositor?.requestKeyframe() },
                )
                is OutputTarget.Bonded -> BondedOutput(
                    context, t.host, t.port, t.streamId, t.latencyMs, config, onConnected, t.passphrase, t.helpers,
                    t.cellularAsSecondary,
                ).also { b -> b.onLinkEvent = { line -> _events.tryEmit(EngineEvent.Link(line)) } }
            }
            output = out
            val t = config.target
            abr = if (out is BondedOutput && t is OutputTarget.Bonded) BitrateController(
                minBps = config.minVideoBitrate,
                maxBps = config.videoBitrate,
                // The thresholds follow the SRT latency the stream really runs with.
                preset = DelayPreset.forLatency(t.latencyMs),
                receiverReorderTolerant = t.receiverReorderTolerant,
            ).also { targetBitrate = it.targetBps } else null
            rtmpAbr = if (out is RtmpOutput) RtmpRateController(
                minBps = config.minVideoBitrate, maxBps = config.videoBitrate, startBps = config.videoBitrate,
            ) else null
            detail = if (config.detailRung) DetailRungPolicy.forFrame(config.width, config.height) else null
            // Configured with the stream's videoBitrate as always (the phone's BRB slate is encoded with the same settings,
            // so their SPS/PPS match); a bonded stream's starting bitrate is applied as a runtime change.
            val comp = Compositor(context, config, videoSink(out), onEncoderRecovered = {}, initialFacing = facing, onRestartExternal = ::restartIngest)
            comp.stabilization = config.stabilization // before the camera opens: it sets the crop
            comp.start() // throws EngineStartException when the video pipeline can't be built
            compositor = comp
            if (abr != null) comp.setBitrate(targetBitrate)
        } catch (e: Throwable) {
            val msg = (e as? EngineStartException)?.message ?: "The stream couldn't start (${e.message ?: e.javaClass.simpleName})"
            android.util.Log.e(TAG, "start failed: $msg", e)
            synchronized(stateLock) { running = false }
            runCatching { output?.stop() }
            output = null
            compositor = null
            abr = null
            rtmpAbr = null
            _lastSession.value = SessionSummary(
                startedAtMs = startedAtMs, endedAtMs = System.currentTimeMillis(), liveSinceMs = 0, recoveredDrops = 0,
                encoderRecoveries = 0, bytesSent = 0, peakThermalStatus = 0, lowestBitrate = config.videoBitrate,
                lastError = msg,
            )
            _state.value = EngineState(running = false, lastError = msg, portrait = config.portrait)
            return false
        }
        val out = output!!
        micGainDb = config.micGainDb
        rawMic = config.rawMic
        rawMicGainDb = config.rawMicGainDb
        lowCut = config.lowCut
        voiceLeveler = config.voiceLeveler
        monoVoice = config.monoVoice
        micInput = config.micInput
        externalMicGainDb = config.externalMicGainDb
        val ring = app.irlsupreme.engine.audio.PcmRing(
            config.audioSampleRate, if (config.audioStereo) 2 else 1, RECENT_AUDIO_S,
        )
        mixRing = ring
        audio = AudioPipeline(
            config.audioSampleRate, config.audioStereo, config.audioBitrate, audioSink(out),
            unprocessedOk = app.irlsupreme.engine.audio.unprocessedSupported(context),
            onLevel = { _micLevel.value = it },
            mix = ring,
            videoDelayNs = DelaySource { appliedVideoDelayNs },
            userAvOffsetNs = DelaySource { avOffsetNs },
            context = context,
            onInputChange = { input, msg -> _events.tryEmit(EngineEvent.MicInput(input, msg)) },
        ).also {
            it.micGainDb = micGainDb
            it.rawMic = rawMic
            it.rawMicGainDb = rawMicGainDb
            it.lowCut = lowCut
            it.voiceLeveler = voiceLeveler
            it.monoVoice = monoVoice
            it.inputChoice = micInput
            it.externalGainDb = externalMicGainDb
            it.start()
        }
        (config.camera as? CameraInput.Rtmp)?.let { startIngest(it, config) }
        monitor = Executors.newSingleThreadScheduledExecutor { Thread(it, "engine-monitor") }.also {
            it.scheduleWithFixedDelay(::tick, 500, 500, TimeUnit.MILLISECONDS)
            it.scheduleWithFixedDelay(::abrTick, 250, 250, TimeUnit.MILLISECONDS)
        }
        synchronized(stateLock) {
            _state.value = EngineState(
                running = true, connection = ConnectionPhase.CONNECTING, targetVideoBitrate = targetBitrate,
                portrait = config.portrait, cameraFacing = facing, fps = config.fps, fpsFallback = fpsFallback,
            )
        }
        return true
    }

    /** Front or back phone camera. Remembered for later streams; the engine reports the one really in use. */
    fun setCameraFacing(facing: Facing) {
        this.facing = facing
        compositor?.setCameraFacing(facing)
        publish()
    }

    /** Phone camera zoom ratio, clamped to what the camera supports; back to 1 on a flip. */
    fun setZoom(ratio: Float) {
        compositor?.setZoom(ratio)
    }

    @Synchronized
    fun stop() {
        synchronized(stateLock) { running = false }
        // The monitor first, and wait for a tick in flight: it reads everything torn down below.
        monitor?.let { m ->
            m.shutdownNow()
            runCatching { m.awaitTermination(1, TimeUnit.SECONDS) }
        }
        monitor = null
        val out = output
        val comp = compositor
        if (out != null && comp != null) {
            countRungTime(System.currentTimeMillis(), comp.thermalRung)
            _lastSession.value = SessionSummary(
                startedAtMs = startedAtMs,
                endedAtMs = System.currentTimeMillis(),
                liveSinceMs = liveSinceMs,
                recoveredDrops = out.recoveredDrops,
                encoderRecoveries = comp.encoderRecoveries,
                bytesSent = bytesSentSoFar(out),
                peakThermalStatus = peakThermal,
                lowestBitrate = lowestBitrate,
                lastError = currentError(out, comp),
                thermalRungMs = rungMs.toList(),
                batteryStartPct = batteryStartPct,
                batteryEndPct = if (batteryStartPct >= 0) batteryPct else -1,
                batterySpanMs = if (batteryStartPct >= 0) batteryLastMs - batteryStartMs else 0,
            )
        }
        ingest?.stop(); ingest = null
        ingestListener = null
        cameraAudio?.close(); cameraAudio = null
        cameraStatus = null
        cameraPublishedAtMs = 0
        output?.stop(); output = null
        abr = null
        rtmpAbr = null
        audio?.stop(); audio = null
        _micLevel.value = app.irlsupreme.engine.audio.MicLevel.SILENT
        compositor?.stop(); compositor = null
        _state.value = EngineState(running = false)
    }

    /**
     * External camera: an RTMP server on the phone ([CameraInput.Rtmp]) feeding the compositor's RTMP camera source
     * (video) and [CameraAudio] (audio), both on one clock so they stay in sync.
     */
    private fun startIngest(input: CameraInput.Rtmp, cfg: StreamConfig) {
        val ext = compositor?.awaitRtmp() ?: return
        // The camera's own capture-to-arrival delay (a clap test), part of what lip sync holds the sound back by.
        ext.clock.captureToArrivalNs = cfg.externalCaptureDelayNs.coerceAtLeast(0)
        val ca = CameraAudio(ext.clock, cfg.audioSampleRate, if (cfg.audioStereo) 2 else 1)
        cameraAudio = ca
        audio?.camera = ca
        cameraStatus = "waiting for the camera"
        val listener = object : IngestListener {
            override fun onPublish(session: Int, name: String, remote: String) {
                ext.onSession(session)
                ca.onSession(session)
                cameraStatus = "connected"
                cameraPublishedAtMs = android.os.SystemClock.elapsedRealtime()
                android.util.Log.i(TAG, "camera publishing ($name) from $remote, session $session")
            }
            override fun onVideoConfig(session: Int, sps: ByteArray, pps: ByteArray) = ext.onConfig(session, sps, pps)
            // DTS too: a camera that sends B-frames is decoded in DTS order on the PTS schedule.
            override fun onVideo(session: Int, annexB: ByteArray, dtsMs: Long, ptsMs: Long, keyframe: Boolean) =
                ext.onFrame(session, annexB, dtsMs, ptsMs, keyframe)
            override fun onAudioConfig(session: Int, asc: ByteArray) = ca.onConfig(session, asc)
            override fun onAudio(session: Int, aac: ByteArray, ptsMs: Long) = ca.onFrame(session, aac, ptsMs)
            override fun onUnsupportedVideo(session: Int, fourcc: String) {
                cameraStatus = EngineRules.unsupportedVideoStatus(fourcc)
                android.util.Log.i(TAG, "camera session $session: unsupported video $fourcc")
            }
            override fun onEnd(session: Int, reason: String) {
                cameraStatus = "camera disconnected"
                cameraPublishedAtMs = 0
                android.util.Log.i(TAG, "camera session $session ended: $reason")
            }
        }
        ingestListener = listener
        ingest = listenForCamera(input, listener)
    }

    private fun listenForCamera(input: CameraInput.Rtmp, listener: IngestListener): RtmpIngestServer? =
        runCatching { RtmpIngestServer(input.port, listener, input.path).start() }.getOrElse {
            cameraStatus = "can't listen on port ${input.port}: ${it.message}"
            android.util.Log.e(TAG, "RTMP ingest failed", it)
            null
        }

    /**
     * "!restartcam" while the external camera streams (called on the render thread): drop its connection by
     * restarting the phone's RTMP server, so the camera reconnects from scratch. Done on its own thread, and never
     * after [stop] (which would leave a server holding the port).
     */
    private fun restartIngest() {
        val input = config?.camera as? CameraInput.Rtmp ?: return
        Thread({
            synchronized(this) {
                val listener = ingestListener
                if (!running || listener == null) return@Thread
                ingest?.stop()
                cameraStatus = "waiting for the camera"
                ingest = listenForCamera(input, listener)
            }
        }, "engine-ingest-restart").apply { isDaemon = true; start() }
    }

    /** Whether an external camera is publishing to the phone's RTMP server right now. */
    fun externalCameraConnected(): Boolean = cameraStatus == "connected"

    /** Milliseconds since the external camera's last frame reached the compositor; null if none this stream. */
    fun externalCameraIdleMs(): Long? {
        val last = compositor?.rtmp?.lastFrameNs ?: return null
        return if (last == 0L) null else (System.nanoTime() - last) / 1_000_000
    }

    fun attachPreview(surface: Surface, width: Int, height: Int) = compositor?.attachPreview(surface, width, height)
    fun detachPreview() = compositor?.detachPreview()
    fun setDisplayRotation(degrees: Int) = compositor?.setDisplayRotation(degrees)

    fun setScene(scene: Scene) {
        compositor?.scene = scene
        publish()
    }

    fun restartCamera() = compositor?.restartCamera()

    /** Asks the video encoder for a keyframe now (the paired server waits for one to put the camera back on air). */
    fun requestKeyframe() { compositor?.requestKeyframe() }

    /** A web page drawn into the stream (an overlay URL, or HTML for tests); null removes it. */
    fun setBrowserOverlay(content: app.irlsupreme.engine.video.BrowserSource.Content?) = compositor?.setBrowser(content)
    /** Debug: crashes the overlay page's renderer, and a scene card's web page if one is open. */
    fun debugCrashBrowserOverlay() = compositor?.debugCrashBrowser()

    /**
     * Mixes an alert chime into the stream's audio, starting [startDelayMs] from now. The caller passes
     * the alert's wipe-in time minus [app.irlsupreme.engine.audio.AlertSounds.attackMs], so the chime's attack lands on
     * the first fully revealed frame.
     */
    fun playAlertChime(style: app.irlsupreme.engine.audio.AlertSounds.Style, startDelayMs: Long = 0) {
        val cfg = config ?: return
        val startAtNs = if (startDelayMs > 0) System.nanoTime() + startDelayMs * 1_000_000 else 0L
        audio?.playEffect(app.irlsupreme.engine.audio.AlertSounds.chime(style, cfg.audioSampleRate, cfg.audioStereo), startAtNs)
    }

    /**
     * Shows [g] on stream: uploaded once, then wiped in, held for its dwell with the time-left rule stepping down,
     * and wiped out, all in GL (no per-frame redraw). Null wipes the current one out now. The engine takes the bitmap.
     * Timed from this call: pair it with `playAlertChime(style, AlertMotion.chimeDelayMs(tier, style))` straight
     * after, and wait [app.irlsupreme.engine.video.AlertMotion.slotMs] before the next one.
     */
    fun showAlert(g: app.irlsupreme.engine.video.AlertGraphic?) {
        val comp = compositor ?: run { g?.bitmap?.recycle(); return }
        comp.showAlert(g)
    }

    /**
     * Whether an alert can be shown (and chimed) right now. False while viewers see Privacy, Weak signal, Camera lost
     * or the server's Signal lost card; the caller holds alerts until this turns true again.
     */
    val alertsAllowed: Boolean
        get() {
            val comp = compositor ?: return false
            return comp.alertsAllowed && output?.let { shownPhase(it) } == ConnectionPhase.LIVE
        }

    /**
     * The phase the streamer is shown (pill, haptics, alert hold): a bonded stream with no link through to the server
     * for a moment is RECONNECTING although its SRT caller still says LIVE (it can't see the dead zone). Only for what's
     * displayed and the alert gate; sending, ABR and stats keep using the output's own phase.
     */
    private fun shownPhase(out: StreamOutput): ConnectionPhase {
        // Polled even when not LIVE, so the timer's "last link seen" stays current across a real reconnect.
        val dead = (out as? BondedOutput)?.noLinksForAWhile == true
        val p = out.phase
        return if (p == ConnectionPhase.LIVE && dead) ConnectionPhase.RECONNECTING else p
    }
    val browserOverlayCrashes: Int get() = compositor?.browserCrashes ?: 0
    /** Times a scene card's web page renderer died this stream (the normal card stood in until it reloaded). */
    val cardPageCrashes: Int get() = compositor?.cardPageCrashes ?: 0

    /** The overlay drawn over the stream (see [Compositor.setOverlay]); the engine takes the bitmap. Null clears it. */
    fun setOverlay(bitmap: android.graphics.Bitmap?, onCards: Boolean) {
        val comp = compositor ?: run { bitmap?.recycle(); return }
        comp.overlayOnCards = onCards
        comp.setOverlay(bitmap)
    }

    /**
     * The last [seconds] (at most 15) of exactly what was handed to the audio encoder, the stream's final mix after
     * mute, so the streamer can hear how they sounded. Kept after the stream stops until the next one starts, so Home
     * can play it back after a Test stream. Null when there's nothing recorded yet. A copy; safe from any thread.
     */
    fun recentAudio(seconds: Int = RECENT_AUDIO_S): PcmClip? = mixRing?.recent(seconds.coerceAtMost(RECENT_AUDIO_S))

    /** The mic level, -20..+30 dB, from the next chunk (see [app.irlsupreme.engine.audio.MicDsp]). */
    fun setMicGain(db: Float) {
        micGainDb = db
        audio?.micGainDb = db
    }

    /** The level for the raw mic (phone auto level off), used whenever that source is open. */
    fun setRawMicGain(db: Float) {
        rawMicGainDb = db
        audio?.rawMicGainDb = db
    }

    /** Turns the phone's own auto level off ([raw]) or on; the mic reopens with the other source at once. */
    fun setRawMic(raw: Boolean) {
        rawMic = raw
        audio?.rawMic = raw
    }

    /** The phone mic's low cut, from the next chunk. */
    fun setLowCut(cut: LowCut) {
        lowCut = cut
        audio?.lowCut = cut
    }

    /** The voice leveler on the raw mic ("Voice"), or off ("Flat"). */
    fun setVoiceLeveler(on: Boolean) {
        voiceLeveler = on
        audio?.voiceLeveler = on
    }

    /** Fold the phone mic to mono. */
    fun setMonoVoice(on: Boolean) {
        monoVoice = on
        audio?.monoVoice = on
    }

    /** Which mic to record from; the mic reopens with a short fade. */
    fun setMicInput(choice: InputChoice) {
        micInput = choice
        audio?.inputChoice = choice
    }

    /** The level for a USB or wired mic, dB (null: the phone mic's level). */
    fun setExternalMicGain(db: Float?) {
        externalMicGainDb = db
        audio?.externalGainDb = db
    }

    /** The mic the recording is really using right now (null before one opens or while not streaming). */
    val activeMicInput: ActiveInput? get() = audio?.activeInput

    /** The audio path's finer health for the session log (clips, jitter, leveler, encoder); null while not streaming. */
    fun audioDiagnostics(): AudioDiagnostics? = audio?.diagnostics()

    /**
     * Combined connections: encode and send the slates to the server again (it refused them, or they don't match the
     * live picture). False when nothing started: not bonded, an upload still running, or none sent yet.
     */
    fun resendSlate(): Boolean = (output as? BondedOutput)?.resendSlate() ?: false

    /** The user's A/V offset, ns (+: the sound later), clamped to +-300 ms; eases in at the audio's own pace. */
    fun setAvOffsetNs(ns: Long) {
        avOffsetNs = EngineRules.clampAvOffset(ns)
    }

    /** Debug: hold the sound back by the camera's measured delay (see [StreamConfig.autoLipSync]). */
    fun setAutoLipSync(on: Boolean) {
        autoLipSync = on
        appliedVideoDelayNs = if (on) measuredVideoDelayNs else 0
    }

    /**
     * The phone's battery, from the app (it polls BatteryManager): [percent] 0..100, [temperatureC] or NaN. At 15% or
     * less and not charging the thermal ladder stays at least on its first rung. Safe from any thread.
     */
    fun setBattery(percent: Int, charging: Boolean, temperatureC: Float = Float.NaN) {
        batteryPct = percent
        batteryCharging = charging
        batteryTempC = temperatureC
        if (!running) return
        val now = System.currentTimeMillis()
        if (batteryStartPct < 0 && percent >= 0) {
            batteryStartPct = percent
            batteryStartMs = now
        }
        batteryLastMs = now
    }

    /**
     * Android's thermal status changed (the app's PowerManager listener): step the ladder up at once instead of at
     * the next tick. Stepping down still waits for the cool minute. Safe from any thread.
     */
    fun onThermalStatusChanged(status: Int) {
        val m = monitor ?: return
        runCatching {
            m.execute {
                try {
                    val comp = compositor ?: return@execute
                    val now = System.currentTimeMillis()
                    applyThermal(now, status, comp, immediate = true)
                    if (status > peakThermal) peakThermal = status
                    capToCeiling()
                    publish(thermal = status)
                } catch (t: Throwable) {
                    android.util.Log.e(TAG, "thermal step failed; carrying on", t)
                }
            }
        }
    }

    /** The live screen is being touched: the preview keeps its full rate through the hot-phone halving. */
    fun setPreviewTouchBoost(on: Boolean) { compositor?.previewTouchBoost = on }

    /** Halve the preview's rate from the first thermal rung while it isn't touched (with [setPreviewTouchBoost]). */
    fun setPreviewHalving(on: Boolean) { compositor?.previewHalving = on }

    /** Stop feeding the preview (a dark pocket screen) without detaching it; the stream is untouched. */
    fun setPreviewSuppressed(on: Boolean) { compositor?.previewSuppressed = on }

    fun setMicMuted(muted: Boolean) {
        audio?.muted = muted
        updateSlateAudio()
        publish()
    }

    /** What the cards' footers say about the sound: what viewers really hear right now. */
    private fun updateSlateAudio() {
        val comp = compositor ?: return
        val a = audio
        comp.slateAudio = when {
            a == null -> app.irlsupreme.engine.video.SlateAudio.NONE
            a.muted -> app.irlsupreme.engine.video.SlateAudio.MUTED
            a.audioInput != AudioInput.OK -> app.irlsupreme.engine.video.SlateAudio.NONE
            else -> app.irlsupreme.engine.video.SlateAudio.ON
        }
    }

    private fun rebased(info: MediaCodec.BufferInfo): MediaCodec.BufferInfo? {
        val pts = info.presentationTimeUs - baseUs
        if (pts < 0) return null
        return MediaCodec.BufferInfo().apply { set(info.offset, info.size, pts, info.flags) }
    }

    // RtmpOutput counts what it really hands to its client (frames it gated or dropped aren't sent): see bytesSentSoFar.
    private fun videoSink(out: StreamOutput) = object : VideoSink {
        private val count = out !is RtmpOutput
        override fun onVideoConfig(sps: ByteBuffer, pps: ByteBuffer) { out.setVideoConfig(sps, pps) }
        override fun onVideoFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            val i = rebased(info) ?: return
            if (out.phase == ConnectionPhase.LIVE) {
                out.sendVideo(buffer, i)
                if (count) bytesSent.addAndGet(i.size.toLong())
            } else if (liveSinceMs != 0L) droppedWhileDown++ // connecting time isn't a drop: nobody was watching yet
        }
    }

    private fun audioSink(out: StreamOutput) = object : AudioSink {
        private val count = out !is RtmpOutput
        override fun onAudioConfig(sampleRate: Int, stereo: Boolean) { out.setAudioConfig(sampleRate, stereo) }
        override fun onAudioFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            val i = rebased(info) ?: return
            out.sendAudio(buffer, i)
            if (count && out.phase == ConnectionPhase.LIVE) bytesSent.addAndGet(i.size.toLong())
        }
    }

    private fun bytesSentSoFar(out: StreamOutput): Long = (out as? RtmpOutput)?.bytesQueued ?: bytesSent.get()

    /**
     * Every 500 ms: thermal ladder, bitrate and state. A throw here must not end the monitor (a scheduled task that
     * throws is never run again, which would freeze the bitrate and the live screen), so it's logged and skipped.
     */
    private fun tick() {
        try {
            tickNow()
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "monitor tick failed; carrying on", t)
        }
    }

    /**
     * Every 500 ms: the thermal ladder and its bitrate ceiling, congestion for the pill, lip sync, link diagnostics and
     * the state. The bitrate itself is [abrTick]'s, for both kinds of output.
     */
    private fun tickNow() {
        config ?: return
        val out = output ?: return
        val comp = compositor ?: return
        val now = System.currentTimeMillis()
        val pm = context.getSystemService(PowerManager::class.java)
        val thermal = runCatching { pm.currentThermalStatus }.getOrDefault(0)
        // The forecast sees heat coming before the status changes; Android rate-limits it, so every 10 s.
        if (now - lastHeadroomMs >= HEADROOM_EVERY_MS) {
            lastHeadroomMs = now
            headroom = runCatching { pm.getThermalHeadroom(10) }.getOrDefault(Float.NaN)
        }
        applyThermal(now, thermal, comp, immediate = false)
        if (thermal > peakThermal) peakThermal = thermal
        capToCeiling()
        // Bonded: the links queueing past the delay preset's back-off point (SRT's own congestion flag only sees the
        // localhost hop). Direct RTMP: the client's send queue. Either way it must hold for two ticks.
        val bonded = out as? BondedOutput
        val ctl = abr
        val rawCongested = if (bonded != null && ctl != null) {
            EngineRules.bondedCongested(bonded.senderStats().queueDelayMs, ctl.preset)
        } else out.congested()
        val congested = congestedHold.update(out.phase == ConnectionPhase.LIVE && rawCongested)
        if (out.phase == ConnectionPhase.LIVE && targetBitrate < lowestBitrate) lowestBitrate = targetBitrate
        // Lip sync: the on-screen camera's delay, measured always, applied only when automatic lip sync is on.
        val measured = EngineRules.videoDelayNs(
            comp.activeSource, comp.cameraLatencyNs, comp.rtmp?.clock?.micAlignDelayNs, cameraAudio = audio?.usingCamera == true,
        )
        measuredVideoDelayNs = measured
        appliedVideoDelayNs = if (autoLipSync) measured else 0
        // One caller at a steady pace: its resend rates are per window.
        if (bonded != null && now - lastDiagMs >= DIAG_EVERY_MS) {
            lastDiagMs = now
            bonded.linkDiagnostics()
        }
        publish(thermal, congested)
    }

    /** Steps the ladder (at once when [immediate], from the thermal listener) and applies the rung and its ceiling. */
    private fun applyThermal(now: Long, status: Int, comp: Compositor, immediate: Boolean) {
        val cfg = config ?: return
        countRungTime(now, comp.thermalRung)
        val low = ThermalLadder.batteryLow(batteryPct, batteryCharging)
        val rung = if (immediate) ladder.stepUp(status, headroom, low) else ladder.update(now, status, headroom, low)
        if (rung != comp.thermalRung) {
            val why = "status $status, headroom $headroom" + if (low) ", battery $batteryPct%" else ""
            android.util.Log.w(TAG, "thermal ladder: rung ${comp.thermalRung} -> $rung ($why)")
            _events.tryEmit(EngineEvent.Note("heat rung ${comp.thermalRung} -> $rung ($why)"))
            comp.thermalRung = rung
        }
        // Rung 3 closes the web overlay (about half a CPU core) until the ladder steps back down.
        comp.browserHeld = rung >= 3
        thermalCeiling = (cfg.videoBitrate.toLong() * ThermalLadder.CEILING_PERCENT[rung] / 100).toInt().coerceAtLeast(cfg.minVideoBitrate)
    }

    private fun countRungTime(now: Long, rung: Int) {
        if (rungCountedAtMs != 0L && now > rungCountedAtMs) rungMs[rung.coerceIn(0, 3)] += now - rungCountedAtMs
        rungCountedAtMs = now
    }

    /** The heat cap applies at once, live or not; the controllers follow on their next update. */
    private fun capToCeiling() {
        if (targetBitrate > thermalCeiling) applyTarget(thermalCeiling, "heat cap", 0)
    }

    /** Monitor thread: a new target for the encoder, if it's worth a parameter call; logged, and as an event at most 1/s. */
    private fun applyTarget(next: Int, reason: String, queueMs: Int) {
        if (next == targetBitrate) return
        val mustCap = targetBitrate > thermalCeiling && next < targetBitrate
        if (!mustCap && !EngineRules.worthApplying(next, targetBitrate)) return
        val msg = "abr ${targetBitrate / 1000} -> ${next / 1000} kbps: $reason (queue $queueMs ms)"
        if (next < targetBitrate) android.util.Log.i(TAG, msg) else android.util.Log.d(TAG, msg)
        if (abrEvents.allow(System.nanoTime() / 1_000_000)) _events.tryEmit(EngineEvent.Bitrate(msg))
        targetBitrate = next
        compositor?.setBitrate(next)
    }

    private fun abrTick() {
        try {
            abrTickNow()
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "bitrate tick failed; carrying on", t)
        }
    }

    /** Every 250 ms: the bitrate, from the bonded or the direct-RTMP controller. */
    private fun abrTickNow() {
        val out = output ?: return
        val comp = compositor ?: return
        val now = System.nanoTime() / 1_000_000
        if (out is BondedOutput) bondedTick(now, out, comp) else rtmpTick(now, out)
    }

    /**
     * Bonded streams: the delay-based controller reads the bonding sender's link stats and what the encoder actually
     * produced (CBR undershoots on simple scenes), within the thermal ceiling. The encoder follows a new target within
     * ~0.5 s; tiny changes aren't worth a parameter call. While connecting, the links' delivery is measured for the
     * controller's start; when SRT comes back after a restart, the controller resumes lower.
     */
    private fun bondedTick(now: Long, out: BondedOutput, comp: Compositor) {
        val ctl = abr ?: return
        val stats = out.senderStats()
        val live = out.phase == ConnectionPhase.LIVE
        val resumed = resumeWatch.update(live)
        if (!live) {
            if (!abrStarted) preLive.observe(now, stats.deliveredBps)
            weak.reset(); setWeakSignal(false)
            return
        }
        if (!abrStarted) {
            abrStarted = true
            val measured = preLive.averageBps(now)
            if (EngineRules.worthSeeding(measured, ctl.targetBps)) {
                ctl.seed(measured)
                note("abr: starting from ${measured / 1000} kbps the links delivered while connecting")
            } else {
                // Before going live the links carry only the handshake, so this is the usual case: say so in the log.
                note("abr: starting from ${ctl.targetBps / 1000} kbps (links delivered ${measured / 1000} kbps while connecting, too little to start higher)")
            }
        } else if (resumed) {
            ctl.onResume()
            note("abr: stream back after a restart, resuming lower")
        }
        val media = comp.measuredBitrate + (config?.audioBitrate ?: 0)
        ctl.update(now, stats, if (comp.measuredBitrate > 0) media else ctl.targetBps.toLong())
        // The heat cap goes into the controller, so a congestion cut while warm cuts from what's really being sent.
        ctl.capAt(thermalCeiling)
        val queue = stats.queueDelayMs
        applyTarget(minOf(ctl.targetBps, thermalCeiling), ctl.lastAction, queue)
        updateDetail(now, comp)
        setWeakSignal(
            weak.update(
                now,
                atFloor = targetBitrate <= ctl.minBps * 21 / 20,
                backedUp = queue > ctl.preset.backOffQueueMs,
                clear = queue < ctl.preset.rampQueueMs,
            ),
        )
    }

    /**
     * Direct RTMP: [RtmpRateController] from the send backlog and what leaves the socket, within the thermal ceiling.
     * A backlog past saving is flushed by the output itself (and restarted from a keyframe); it's only logged here.
     */
    private fun rtmpTick(now: Long, out: StreamOutput) {
        val ctl = rtmpAbr ?: return
        if (out.phase != ConnectionPhase.LIVE) { weak.reset(); setWeakSignal(false); return }
        // The output measures on its own 250 ms timer, which drifts against this one: use each reading once, or one
        // keyframe burst read twice looks like a sustained backlog.
        val reading = out.meterReadings
        if (reading == rtmpReading) return
        rtmpReading = reading
        val queue = out.queueDelayMs
        ctl.update(now, queue, out.throughputBps, thermalCeiling)
        if (ctl.flushNow != rtmpFlushing) {
            rtmpFlushing = ctl.flushNow
            if (rtmpFlushing) android.util.Log.w(TAG, "rtmp backlog $queue ms is past saving: the output clears it")
        }
        applyTarget(minOf(ctl.targetBps, thermalCeiling), ctl.lastAction, queue)
        compositor?.let { updateDetail(now, it) }
        setWeakSignal(
            weak.update(
                now,
                atFloor = targetBitrate <= ctl.minBps * 21 / 20,
                backedUp = queue > RtmpRateController.BACKLOG_MS,
                clear = queue < RtmpRateController.CLEAR_MS,
            ),
        )
    }

    /** The camera's render scale for the bitrate, when [StreamConfig.detailRung] is on. */
    private fun updateDetail(now: Long, comp: Compositor) {
        val d = detail ?: return
        val scale = d.update(now, targetBitrate)
        if (scale != comp.detailScale) {
            comp.detailScale = scale
            note("detail scale $scale at ${targetBitrate / 1000} kbps")
        }
    }

    /** Debug builds: force the Weak signal screen on or off (null: automatic again), to check how it looks. */
    @Volatile private var forcedWeakSignal: Boolean? = null
    fun forceWeakSignal(on: Boolean?) {
        forcedWeakSignal = on
        setWeakSignal(weak.active)
    }

    /** Shows or hides the Weak signal screen (if the user hasn't turned it off). */
    private fun setWeakSignal(on: Boolean) {
        val comp = compositor ?: return
        val show = forcedWeakSignal ?: (on && config?.autoWeakSignal == true)
        if (comp.weakSignal == show) return
        comp.weakSignal = show
        android.util.Log.i(TAG, if (show) "weak signal screen on (episode ${weak.episodes}; back after ${weak.exitHold / 1000} s clear)" else "weak signal screen off")
        publish()
    }

    /** Every current problem, output first; null when there are none. */
    private fun currentError(out: StreamOutput, comp: Compositor): String? = listOfNotNull(
        out.lastFailure,
        comp.videoEncoderDown?.let { "Video encoder down: $it" },
        audio?.lastError,
    ).joinToString(" · ").ifEmpty { null }

    /**
     * The bitrate a clear connection would carry (see [EngineState.qualityCeiling]). A bonded stream starts well below
     * it and ramps up, so it's withheld (0: no Weak from the bitrate) until the target first gets halfway or the
     * stream has been live for [RAMP_GRACE_MS].
     */
    private fun qualityCeiling(cfg: StreamConfig): Int {
        val ceiling = minOf(cfg.videoBitrate, thermalCeiling)
        if (abr == null) return ceiling
        if (!rampedUp && targetBitrate >= ceiling / 2) rampedUp = true
        val graceOver = liveSinceMs != 0L && System.currentTimeMillis() - liveSinceMs >= RAMP_GRACE_MS
        return if (rampedUp || graceOver) ceiling else 0
    }

    private fun publish(thermal: Int = _state.value.thermalStatus, congested: Boolean = _state.value.congested) {
        val comp = compositor ?: return
        val out = output ?: return
        val cfg = config ?: return
        val a = audio
        updateSlateAudio()
        val bonded = out as? BondedOutput
        val st = bonded?.senderStats()
        val bondCtl = abr
        val rtmpCtl = rtmpAbr
        val meter = comp.encoderMeter
        val ext = comp.rtmp
        // Encoded frames lost while reconnecting, frames the output itself dropped while live, and render ticks missed.
        val dropped = droppedWhileDown + out.droppedFrames +
            if (liveSinceMs != 0L) (comp.skippedTicks - skippedAtLive).coerceAtLeast(0) else 0
        val s = EngineState(
            running = true,
            portrait = cfg.portrait,
            connection = shownPhase(out),
            reconnectAttempt = out.reconnectAttempt,
            camera = comp.cameraPhase,
            scene = comp.scene,
            weakSignal = comp.weakSignal,
            audioInput = a?.audioInput ?: AudioInput.OK,
            thermalRung = comp.thermalRung,
            previewPaused = comp.previewPaused,
            cameraFacing = comp.cameraFacing,
            zoomRatio = comp.zoomRatio,
            micMuted = a?.muted == true,
            liveSinceMs = liveSinceMs,
            targetVideoBitrate = targetBitrate,
            qualityCeiling = qualityCeiling(cfg),
            measuredVideoBitrate = comp.measuredBitrate,
            sentBitrate = out.sentBitrate,
            congested = congested,
            thermalStatus = thermal,
            encoderRecoveries = comp.encoderRecoveries,
            droppedFrames = dropped,
            lastError = currentError(out, comp),
            links = out.links(),
            audio = a?.counters() ?: AudioCounters(),
            activeSource = comp.activeSource,
            externalCamera = EngineRules.externalCameraStatus(
                cameraStatus, ext?.width ?: 0, ext?.height ?: 0,
                cameraPublishedAtMs.let { if (it == 0L) 0L else android.os.SystemClock.elapsedRealtime() - it },
                a?.usingCamera == true,
            ),
            abrAction = bondCtl?.lastAction ?: rtmpCtl?.lastAction ?: "",
            queueMs = st?.queueDelayMs ?: out.queueDelayMs,
            throughputBps = out.throughputBps,
            overhead = bondCtl?.overhead ?: 0.0,
            nakRate = bondCtl?.nakRate ?: 0.0,
            capacityBps = st?.capacityBps ?: 0,
            naks = st?.naks ?: 0,
            overCapSent = st?.overCapSent ?: 0,
            droppedNoLink = st?.droppedNoLink ?: 0,
            droppedLocal = st?.droppedLocal ?: 0,
            sendErrors = st?.sendErrors ?: 0,
            libraryDroppedFrames = bonded?.libraryDroppedFrames ?: 0,
            provenLinks = st?.provenLinks ?: 0,
            clientSendDrops = st?.clientSendDrops ?: 0,
            droppedDuringHandshake = st?.droppedDuringHandshake ?: 0,
            thermalSkips = comp.thermalSkips,
            renderErrors = comp.renderErrors,
            thermalHeadroom = headroom,
            phoneBatteryPct = batteryPct,
            batteryTempC = batteryTempC,
            batteryCharging = batteryCharging,
            fps = cfg.fps,
            fpsFallback = fpsFallback,
            detailScale = comp.detailScale,
            encoder = comp.encoderInfo?.describe().orEmpty(),
            outputFps = meter?.framesPerSecond ?: 0.0,
            lastIdrBytes = meter?.lastIdrBytes ?: 0,
            maxIdrIntervalMs = meter?.maxIdrIntervalMs ?: 0,
            videoDelayMs = (measuredVideoDelayNs / 1_000_000).toInt(),
            avDelayMs = ((a?.avDelayNs ?: 0L) / 1_000_000).toInt(),
            cadence = cadenceCounters(comp.cadenceSnapshot()),
            ingest = ext?.clock?.health(System.nanoTime()),
            micInput = a?.activeInput,
            captureStats = comp.cameraCaptureStats,
        )
        synchronized(stateLock) { if (running) _state.value = s }
    }

    private companion object {
        const val TAG = "Engine"
        const val HEADROOM_EVERY_MS = 10_000L
        const val RAMP_GRACE_MS = 20_000L
        /** How often the bonded links' diagnostics window turns over (their resend rates are per window). */
        const val DIAG_EVERY_MS = 5_000L
        /** At most one bitrate line a second in [events]. */
        const val ABR_EVENT_EVERY_MS = 1_000L
        /** How much of the stream's final mix [recentAudio] keeps (~2.9 MB at 48 kHz stereo). */
        const val RECENT_AUDIO_S = 15
    }
}

/** The compositor's frame timing, flattened for the session log ([EngineState.cadence]); all zero before the first window. */
internal fun cadenceCounters(c: CadenceSnapshot?): CadenceCounters {
    if (c == null) return CadenceCounters()
    val ext = c.external
    return CadenceCounters(
        phoneRepeatTicks = c.phone.repeatTicksWhileLive,
        phoneSourceGaps = c.phone.sourceGaps,
        phoneOverwritten = c.phone.overwritten,
        externalRepeatTicks = ext?.repeatTicksWhileLive ?: 0,
        externalSourceGaps = ext?.sourceGaps ?: 0,
        externalOverwritten = ext?.overwritten ?: 0,
        externalLateDrops = c.externalLateDrops,
        tickP50Ms = c.tickWorkP50Ms,
        tickP99Ms = c.tickWorkP99Ms,
        tickMaxMs = c.tickWorkMaxMs,
        lateTicks = c.lateTicks,
        skippedTicks = c.skippedTicks,
        outputFps = c.outputFps,
        phaseLocked = c.phaseLocked,
        cameraLatencyP50Ms = c.cameraLatencyP50Ms ?: Float.NaN,
        cameraLatencyP95Ms = c.cameraLatencyP95Ms ?: Float.NaN,
    )
}

/**
 * The thermal ladder: steps down as soon as the phone heats up, and back up one rung at a time once it has stayed
 * cooler for [recoverAfterMs], so it doesn't flap. Pure and clock-driven, for tests.
 *
 *  - Rung 1: THERMAL_STATUS_MODERATE, or a 10 s headroom forecast of 0.85+. Bitrate ceiling 85%.
 *  - Rung 2: SEVERE, or a forecast of 0.95+. Ceiling 70%, an even lower frame rate, slower overlay redraws.
 *  - Rung 3: CRITICAL or worse. Ceiling 50%, web overlay closed, preview paused.
 *
 * The forecast has hysteresis: a rung is entered at its threshold ([rungFor]) but only counts as left once the
 * forecast is well under it ([coolRungFor]: below 0.88 for rung 2, below 0.78 for rung 1), so a forecast wandering
 * around a threshold doesn't step it up and down. A low battery (15% or less, not charging: [batteryLow]) holds it on
 * rung 1 at least. The forecast is NaN when the phone doesn't offer one; then only the status counts.
 */
internal class ThermalLadder(private val recoverAfterMs: Long = 60_000) {
    var rung = 0
        private set
    private var coolerSinceMs = -1L

    /** Every tick: steps up at once, down one rung per cool [recoverAfterMs]. */
    fun update(nowMs: Long, status: Int, headroom: Float, batteryLow: Boolean = false): Int {
        val floor = if (batteryLow) 1 else 0
        val enter = maxOf(rungFor(status, headroom), floor)
        val stay = maxOf(coolRungFor(status, headroom), floor)
        when {
            enter > rung -> { rung = enter; coolerSinceMs = -1 }
            stay >= rung -> coolerSinceMs = -1
            coolerSinceMs < 0 -> coolerSinceMs = nowMs
            nowMs - coolerSinceMs >= recoverAfterMs -> {
                rung--
                coolerSinceMs = if (stay < rung) nowMs else -1 // the next rung up needs its own cool minute
            }
        }
        return rung
    }

    /**
     * A thermal status change reported by Android's listener: steps up at once if it calls for a higher rung (and a
     * report that the phone is still this warm restarts the cool minute), but never steps down: that's [update]'s,
     * after the cool minute.
     */
    fun stepUp(status: Int, headroom: Float, batteryLow: Boolean = false): Int {
        val floor = if (batteryLow) 1 else 0
        val enter = maxOf(rungFor(status, headroom), floor)
        val stay = maxOf(coolRungFor(status, headroom), floor)
        if (enter > rung) {
            rung = enter
            coolerSinceMs = -1
        } else if (stay >= rung) {
            coolerSinceMs = -1
        }
        return rung
    }

    companion object {
        /** Bitrate ceiling per rung, in percent of the chosen bitrate. */
        val CEILING_PERCENT = intArrayOf(100, 85, 70, 50)
        /** At or under this battery level, and not charging, the ladder stays on rung 1 at least. */
        const val BATTERY_LOW_PCT = 15

        // PowerManager.THERMAL_STATUS_MODERATE / SEVERE / CRITICAL
        /** The rung the phone's heat calls for (entering it). */
        fun rungFor(status: Int, headroom: Float): Int = when {
            status >= 4 -> 3
            status >= 3 || headroom >= 0.95f -> 2
            status >= 2 || headroom >= 0.85f -> 1
            else -> 0
        }

        /** The highest rung the phone is still too warm to leave: the forecast must drop well under a rung's threshold. */
        fun coolRungFor(status: Int, headroom: Float): Int = when {
            status >= 4 -> 3
            status >= 3 || headroom >= 0.88f -> 2
            status >= 2 || headroom >= 0.78f -> 1
            else -> 0
        }

        /** [percent] -1 means unknown, which never counts as low. */
        fun batteryLow(percent: Int, charging: Boolean): Boolean = percent in 0..BATTERY_LOW_PCT && !charging
    }
}
