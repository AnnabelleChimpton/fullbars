package app.irlsupreme.engine

import android.content.Context
import android.media.MediaCodec
import android.os.PowerManager
import android.view.Surface
import app.irlsupreme.engine.audio.AudioPipeline
import app.irlsupreme.engine.audio.AudioSink
import app.irlsupreme.engine.audio.CameraAudio
import app.irlsupreme.engine.ingest.IngestListener
import app.irlsupreme.engine.ingest.RtmpIngestServer
import app.irlsupreme.engine.output.BondedOutput
import app.irlsupreme.engine.output.RtmpOutput
import app.irlsupreme.engine.output.StreamOutput
import app.irlsupreme.engine.video.Compositor
import app.irlsupreme.engine.video.VideoSink
import app.irlsupreme.engine.audio.PcmClip
import srtlakt.abr.BitrateController
import srtlakt.abr.DelayPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private var monitor: ScheduledExecutorService? = null
    private var ingest: RtmpIngestServer? = null
    private var ingestListener: IngestListener? = null
    private var cameraAudio: CameraAudio? = null
    @Volatile private var cameraStatus: String? = null

    /** All media timestamps are rebased to this (µs on the System.nanoTime clock) before muxing. */
    private var baseUs = 0L
    @Volatile private var liveSinceMs = 0L
    private var targetBitrate = 0
    /** Bonded streams: delay-based control from the bonding sender's link stats. */
    private var abr: BitrateController? = null
    private var weak = app.irlsupreme.engine.abr.WeakSignalDetector()
    /** Thermal cap on the bitrate, refreshed by [tick]. */
    @Volatile private var thermalCeiling = Int.MAX_VALUE
    private var ladder = ThermalLadder()
    private var headroom = Float.NaN
    private var lastHeadroomMs = 0L
    private var lastCongestedMs = 0L
    /** Consecutive ticks the output reported congestion: one blip isn't worth a Weak pill. */
    private var congestedTicks = 0
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
        this.config = config
        baseUs = System.nanoTime() / 1000
        targetBitrate = config.videoBitrate
        liveSinceMs = 0
        lastCongestedMs = 0
        congestedTicks = 0
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
                is OutputTarget.Rtmp -> RtmpOutput(t.url, config.width, config.height, config.fps, onConnected)
                is OutputTarget.Bonded -> BondedOutput(
                    context, t.host, t.port, t.streamId, t.latencyMs, config, onConnected, t.passphrase, t.helpers,
                )
            }
            output = out
            abr = if (out is BondedOutput) BitrateController(
                minBps = config.minVideoBitrate,
                maxBps = config.videoBitrate,
                preset = DelayPreset.BALANCED, // matches OutputTarget.Bonded's 2 s SRT latency
            ).also { targetBitrate = it.targetBps } else null
            // Configured with the stream's videoBitrate as always (the phone's BRB slate is encoded with the same settings,
            // so their SPS/PPS match); a bonded stream's starting bitrate is applied as a runtime change.
            val comp = Compositor(context, config, videoSink(out), onEncoderRecovered = {}, initialFacing = facing, onRestartExternal = ::restartIngest)
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
        val ring = app.irlsupreme.engine.audio.PcmRing(
            config.audioSampleRate, if (config.audioStereo) 2 else 1, RECENT_AUDIO_S,
        )
        mixRing = ring
        audio = AudioPipeline(
            config.audioSampleRate, config.audioStereo, config.audioBitrate, audioSink(out),
            unprocessedOk = app.irlsupreme.engine.audio.unprocessedSupported(context),
            onLevel = { _micLevel.value = it },
            mix = ring,
        ).also {
            it.micGainDb = micGainDb
            it.rawMic = rawMic
            it.rawMicGainDb = rawMicGainDb
            it.start()
        }
        (config.camera as? CameraInput.Rtmp)?.let { startIngest(it, config) }
        monitor = Executors.newSingleThreadScheduledExecutor { Thread(it, "engine-monitor") }.also {
            it.scheduleWithFixedDelay(::tick, 500, 500, TimeUnit.MILLISECONDS)
            if (abr != null) it.scheduleWithFixedDelay(::abrTick, 250, 250, TimeUnit.MILLISECONDS)
        }
        synchronized(stateLock) {
            _state.value = EngineState(
                running = true, connection = ConnectionPhase.CONNECTING, targetVideoBitrate = targetBitrate,
                portrait = config.portrait, cameraFacing = facing,
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
            _lastSession.value = SessionSummary(
                startedAtMs = startedAtMs,
                endedAtMs = System.currentTimeMillis(),
                liveSinceMs = liveSinceMs,
                recoveredDrops = out.recoveredDrops,
                encoderRecoveries = comp.encoderRecoveries,
                bytesSent = bytesSent.get(),
                peakThermalStatus = peakThermal,
                lowestBitrate = lowestBitrate,
                lastError = currentError(out, comp),
            )
        }
        ingest?.stop(); ingest = null
        ingestListener = null
        cameraAudio?.close(); cameraAudio = null
        cameraStatus = null
        output?.stop(); output = null
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
        val ca = CameraAudio(ext.clock, cfg.audioSampleRate, if (cfg.audioStereo) 2 else 1)
        cameraAudio = ca
        audio?.camera = ca
        cameraStatus = "waiting for the camera"
        val listener = object : IngestListener {
            override fun onPublish(session: Int, name: String, remote: String) {
                ext.onSession(session)
                ca.onSession(session)
                cameraStatus = "connected"
                android.util.Log.i(TAG, "camera publishing ($name) from $remote, session $session")
            }
            override fun onVideoConfig(session: Int, sps: ByteArray, pps: ByteArray) = ext.onConfig(session, sps, pps)
            override fun onVideo(session: Int, annexB: ByteArray, dtsMs: Long, ptsMs: Long, keyframe: Boolean) =
                ext.onFrame(session, annexB, ptsMs, keyframe)
            override fun onAudioConfig(session: Int, asc: ByteArray) = ca.onConfig(session, asc)
            override fun onAudio(session: Int, aac: ByteArray, ptsMs: Long) = ca.onFrame(session, aac, ptsMs)
            override fun onEnd(session: Int, reason: String) {
                cameraStatus = "camera disconnected"
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

    private fun videoSink(out: StreamOutput) = object : VideoSink {
        override fun onVideoConfig(sps: ByteBuffer, pps: ByteBuffer) { out.setVideoConfig(sps, pps) }
        override fun onVideoFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            val i = rebased(info) ?: return
            if (out.phase == ConnectionPhase.LIVE) {
                out.sendVideo(buffer, i)
                bytesSent.addAndGet(i.size.toLong())
            } else if (liveSinceMs != 0L) droppedWhileDown++ // connecting time isn't a drop: nobody was watching yet
        }
    }

    private fun audioSink(out: StreamOutput) = object : AudioSink {
        override fun onAudioConfig(sampleRate: Int, stereo: Boolean) { out.setAudioConfig(sampleRate, stereo) }
        override fun onAudioFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            val i = rebased(info) ?: return
            out.sendAudio(buffer, i)
            if (out.phase == ConnectionPhase.LIVE) bytesSent.addAndGet(i.size.toLong())
        }
    }

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
     * Bitrate control for direct RTMP is deliberately simple (bonded streams use [abrTick]):
     *  - RTMP send queue congested -> cut 25% (floor [StreamConfig.minVideoBitrate]);
     *  - 10 s without congestion -> +10% back toward the ceiling;
     *  - the ceiling is the chosen bitrate capped by the thermal ladder ([ThermalLadder.CEILING_PERCENT]).
     */
    private fun tickNow() {
        val cfg = config ?: return
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
        val rung = ladder.update(now, thermal, headroom)
        if (rung != comp.thermalRung) {
            android.util.Log.w(TAG, "thermal ladder: rung ${comp.thermalRung} -> $rung (status $thermal, headroom $headroom)")
            comp.thermalRung = rung
        }
        // Rung 3 closes the web overlay (about half a CPU core) until the ladder steps back down.
        comp.browserHeld = rung >= 3
        val ceiling = (cfg.videoBitrate.toLong() * ThermalLadder.CEILING_PERCENT[rung] / 100).toInt().coerceAtLeast(cfg.minVideoBitrate)
        thermalCeiling = ceiling
        val rawCongested = out.congested()
        congestedTicks = if (rawCongested) congestedTicks + 1 else 0
        val congested = congestedTicks >= 2
        if (thermal > peakThermal) peakThermal = thermal
        if (abr != null) { // bonded: abrTick owns the bitrate; just report
            if (out.phase == ConnectionPhase.LIVE && targetBitrate < lowestBitrate) lowestBitrate = targetBitrate
            publish(thermal, congested)
            return
        }
        var next = targetBitrate
        if (out.phase == ConnectionPhase.LIVE) {
            // Direct RTMP: the send queue is all there is to go on.
            val atFloor = targetBitrate <= cfg.minVideoBitrate * 21 / 20
            setWeakSignal(weak.update(now, atFloor, backedUp = rawCongested, clear = !rawCongested))
        } else { weak.reset(); setWeakSignal(false) }
        if (rawCongested) { // the bitrate reacts at once; only the pill waits for a second tick
            lastCongestedMs = now
            next = (targetBitrate * 3 / 4).coerceAtLeast(cfg.minVideoBitrate)
        } else if (now - lastCongestedMs > 10_000 && targetBitrate < ceiling) {
            next = (targetBitrate * 11 / 10).coerceAtMost(ceiling)
            lastCongestedMs = now - 5_000 // step up at most every ~5 s
        }
        next = next.coerceAtMost(ceiling)
        if (next != targetBitrate) {
            targetBitrate = next
            comp.setBitrate(next)
        }
        if (out.phase == ConnectionPhase.LIVE && targetBitrate < lowestBitrate) lowestBitrate = targetBitrate
        publish(thermal, congested)
    }

    private fun abrTick() {
        try {
            abrTickNow()
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "bitrate tick failed; carrying on", t)
        }
    }

    /**
     * Bonded streams, every 250 ms: the delay-based controller reads the bonding sender's link stats and what the
     * encoder actually produced (CBR undershoots on simple scenes), within the thermal ceiling. The encoder
     * follows a new target within ~0.5 s; tiny changes aren't worth a parameter call.
     */
    private fun abrTickNow() {
        val ctl = abr ?: return
        val out = output as? BondedOutput ?: return
        val comp = compositor ?: return
        if (out.phase != ConnectionPhase.LIVE) { weak.reset(); setWeakSignal(false); return }
        val media = comp.measuredBitrate + (config?.audioBitrate ?: 0)
        ctl.update(System.nanoTime() / 1_000_000, out.senderStats(), if (comp.measuredBitrate > 0) media else ctl.targetBps.toLong())
        // The heat cap goes into the controller, so a congestion cut while warm cuts from what's really being sent.
        ctl.capAt(thermalCeiling)
        val next = minOf(ctl.targetBps, thermalCeiling)
        if (kotlin.math.abs(next - targetBitrate) > targetBitrate / 50) {
            val msg = "abr ${targetBitrate / 1000} -> ${next / 1000} kbps: ${ctl.lastAction} (queue ${out.senderStats().queueDelayMs} ms)"
            if (next < targetBitrate) android.util.Log.i(TAG, msg) else android.util.Log.d(TAG, msg)
            targetBitrate = next
            comp.setBitrate(next)
        }
        val queue = out.senderStats().queueDelayMs
        setWeakSignal(
            weak.update(
                System.nanoTime() / 1_000_000,
                atFloor = targetBitrate <= ctl.minBps * 21 / 20,
                backedUp = queue > ctl.preset.backOffQueueMs,
                clear = queue < ctl.preset.rampQueueMs,
            ),
        )
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
        val dropped = droppedWhileDown + if (liveSinceMs != 0L) (comp.skippedTicks - skippedAtLive).coerceAtLeast(0) else 0
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
            externalCamera = cameraStatus?.let { st ->
                val ext = comp.rtmp
                if (st == "connected" && ext != null && ext.width > 0) {
                    "${ext.width}x${ext.height}" + if (a?.usingCamera == true) " · camera audio" else " · phone mic"
                } else st
            },
        )
        synchronized(stateLock) { if (running) _state.value = s }
    }

    private companion object {
        const val TAG = "Engine"
        const val HEADROOM_EVERY_MS = 10_000L
        const val RAMP_GRACE_MS = 20_000L
        /** How much of the stream's final mix [recentAudio] keeps (~2.9 MB at 48 kHz stereo). */
        const val RECENT_AUDIO_S = 15
    }
}

/**
 * The thermal ladder: steps down as soon as the phone heats up, and back up one
 * rung at a time once it has stayed cooler for [recoverAfterMs], so it doesn't flap. Pure and clock-driven, for tests.
 *
 *  - Rung 1: THERMAL_STATUS_MODERATE, or a 10 s headroom forecast of 0.85+. Bitrate ceiling 85%.
 *  - Rung 2: SEVERE, or a forecast of 0.95+. Ceiling 70%, ~24 fps, slower overlay redraws, stabilisation off.
 *  - Rung 3: CRITICAL or worse. Ceiling 50%, web overlay closed, preview paused.
 *
 * The forecast is NaN when the phone doesn't offer one; then only the status counts.
 */
internal class ThermalLadder(private val recoverAfterMs: Long = 60_000) {
    var rung = 0
        private set
    private var coolerSinceMs = -1L

    fun update(nowMs: Long, status: Int, headroom: Float): Int {
        val wanted = rungFor(status, headroom)
        when {
            wanted > rung -> { rung = wanted; coolerSinceMs = -1 }
            wanted == rung -> coolerSinceMs = -1
            coolerSinceMs < 0 -> coolerSinceMs = nowMs
            nowMs - coolerSinceMs >= recoverAfterMs -> {
                rung--
                coolerSinceMs = if (wanted < rung) nowMs else -1 // the next rung up needs its own cool minute
            }
        }
        return rung
    }

    companion object {
        /** Bitrate ceiling per rung, in percent of the chosen bitrate. */
        val CEILING_PERCENT = intArrayOf(100, 85, 70, 50)

        // PowerManager.THERMAL_STATUS_MODERATE / SEVERE / CRITICAL
        fun rungFor(status: Int, headroom: Float): Int = when {
            status >= 4 -> 3
            status >= 3 || headroom >= 0.95f -> 2
            status >= 2 || headroom >= 0.85f -> 1
            else -> 0
        }
    }
}
