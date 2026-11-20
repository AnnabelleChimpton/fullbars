package app.irlsupreme.engine.video

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import android.view.Surface
import app.irlsupreme.engine.CameraPhase
import app.irlsupreme.engine.EngineStartException
import app.irlsupreme.engine.Facing
import app.irlsupreme.engine.Scene
import app.irlsupreme.engine.StreamConfig
import app.irlsupreme.engine.gl.EglCore
import app.irlsupreme.engine.gl.Fbo
import app.irlsupreme.engine.gl.GlUtil
import app.irlsupreme.engine.gl.LayeredCardProgram
import app.irlsupreme.engine.gl.TextureProgram
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The never-stop video pipeline:
 *  - one render thread ticks a fixed 30 fps clock, independent of sources and of the display;
 *  - each tick composes the current scene into an FBO: the camera's newest frame, or its last frame (hold), or after
 *    [HOLD_NS] the Camera lost card; or a scene card, crossfaded per [SceneRules]; then the layers and the alert;
 *  - a phone-drawn card can carry a looping GIF behind it ([GifBackground], decoded off-thread) or a web page in its
 *    place ([CardWebSource]); until either has a frame, or if it fails, the plain card shows ([CardBackdrops]);
 *  - the FBO is drawn into the encoder's input surface with the tick time as PTS, so output never starves;
 *  - a failed/stalled encoder is replaced off-thread while the clock keeps ticking (~250 ms on test phones), retrying with
 *    backoff for as long as it takes, and saying so in [videoEncoderDown];
 *  - the preview is handed a fenced copy only when it asks for one (its own thread, see [PreviewRenderer]).
 *
 * Nothing that goes wrong inside a tick may end the stream: each source, the overlay upload and the encoder swap are
 * guarded on their own, and a last catch counts anything else in [renderErrors] and moves on to the next tick. (An
 * uncaught throw here would kill the whole app with no BRB screen.) A setup failure is reported by [start] instead.
 */
class Compositor(
    private val ctx: Context,
    private val config: StreamConfig,
    private val sink: VideoSink,
    private val onEncoderRecovered: () -> Unit,
    initialFacing: Facing = Facing.BACK,
    /** "!restartcam" while the external camera is streaming: drop its connection so it reconnects (render thread). */
    private val onRestartExternal: () -> Unit = {},
) {
    @Volatile var scene = Scene.LIVE
    /** Stand in for [Scene.LIVE] with the Weak signal card (set by the engine's bitrate tick). */
    @Volatile var weakSignal = false
    /** Also draw the overlay on the BRB, Starting soon and Ending cards (never on Privacy or Weak signal). */
    @Volatile var overlayOnCards = false
    /**
     * The stream's real audio state, printed in the cards' footers (set by the engine on mute and mic changes). A card
     * on stream is redrawn when it changes, so a card never claims sound viewers can't hear.
     */
    @Volatile var slateAudio = SlateAudio.ON

    private class OverlayUpdate(val bitmap: Bitmap?)
    private val pendingOverlay = AtomicReference<OverlayUpdate?>(null)

    /**
     * The overlay for the coming frames: a premultiplied ARGB bitmap the size of the output, drawn over the scene;
     * null clears it. The compositor takes the bitmap and recycles it after uploading; a newer one replaces one that
     * hasn't been uploaded yet.
     */
    fun setOverlay(bitmap: Bitmap?) {
        pendingOverlay.getAndSet(OverlayUpdate(bitmap))?.bitmap?.recycle()
    }

    private val pendingAlert = AtomicReference<AlertUpdate?>(null)
    /** [atNs]: when it was asked for, so the wipe's timing (and the chime's, timed from the same call) can't drift. */
    private class AlertUpdate(val graphic: AlertGraphic?, val atNs: Long)

    /** See [app.irlsupreme.engine.StreamEngine.showAlert]. The compositor takes the bitmap. */
    fun showAlert(g: AlertGraphic?) {
        pendingAlert.getAndSet(AlertUpdate(g, System.nanoTime()))?.graphic?.bitmap?.recycle()
    }

    /** False while what's on stream holds alerts back (Privacy, Weak signal, Camera lost); see [SceneRules]. */
    @Volatile var alertsAllowed = true
        private set

    @Volatile private var browserWanted: BrowserSource.Content? = null
    @Volatile private var browserGen = 0
    /** Times the overlay page's renderer died this stream. */
    @Volatile var browserCrashes = 0
        private set

    @Volatile private var browserRef: BrowserSource? = null
    /** The newest scene card web page opened, for [debugCrashBrowser]. */
    @Volatile private var cardPageRef: BrowserSource? = null
    /** Debug: crashes the overlay page's renderer and any card page's, to check the stream (and the card) survive. */
    fun debugCrashBrowser() {
        browserRef?.debugCrash()
        cardPageRef?.debugCrash()
    }
    /** Times a scene card's web page renderer died this stream (the normal card showed meanwhile). */
    @Volatile var cardPageCrashes = 0
        private set

    /**
     * Thermal ladder: bitrate, then fps, then overlays. While true the web overlay is closed (it costs
     * about half a CPU core), and it reopens when this goes false.
     */
    @Volatile var browserHeld = false
        set(v) { if (field != v) { field = v; browserGen++ } }

    /**
     * The thermal ladder's rung (set by the engine): from 2, every 5th tick is skipped (~24 fps, the timestamps just
     * show the gap), overlay uploads slow to one per [OVERLAY_HOT_INTERVAL_NS] and the camera's stabilisation goes off;
     * at 3 the preview pauses ([previewPaused]).
     */
    @Volatile var thermalRung = 0
        set(v) {
            if (field == v) return
            field = v
            camera?.setStabilizationAllowed(v < 2)
        }
    /** The preview is paused to save heat (the stream carries on). */
    val previewPaused: Boolean get() = thermalRung >= 3

    /** The browser overlay (a web page drawn under the native overlay); null removes it. */
    fun setBrowser(content: BrowserSource.Content?) {
        browserWanted = content
        browserGen++
    }
    @Volatile var cameraPhase = CameraPhase.OFF
        private set
    @Volatile var encoderRecoveries = 0
        private set
    /** Ticks the render thread fell behind on (frames missing from the stream). Heat-saving skips aren't counted. */
    @Volatile var skippedTicks = 0L
        private set
    /** Ticks dropped on purpose by the thermal ladder. */
    @Volatile var thermalSkips = 0L
        private set
    /** Unexpected errors caught inside ticks this stream (each one cost at most that tick). */
    @Volatile var renderErrors = 0
        private set
    /**
     * Why the video encoder is down (null when it's fine): set when a replacement couldn't be created or never produced
     * a frame, cleared once a replacement produces output. Viewers see a frozen picture meanwhile.
     */
    @Volatile var videoEncoderDown: String? = null
        private set
    @Volatile private var targetBitrate = config.videoBitrate

    private val thread = Thread(::run, "engine-render")
    @Volatile private var running = false
    private val recoveryExecutor = Executors.newSingleThreadExecutor { Thread(it, "engine-venc-recovery") }
    private val pendingEncoder = AtomicReference<VideoEncoder?>(null)
    @Volatile private var encoder: VideoEncoder? = null
    @Volatile private var preview: PreviewRenderer? = null
    private val pendingPreview = AtomicReference<Pair<Surface, IntArray>?>(null)
    @Volatile private var previewDetach = false
    /** Live screen's display rotation (Surface.ROTATION_* x 90), or -1 before it reports one. */
    @Volatile private var displayRotation = -1
    private val ready = CountDownLatch(1)
    @Volatile private var startError: Throwable? = null
    /** What setup was doing, for a plain [EngineStartException] message. */
    @Volatile private var setupStage = "graphics"
    @Volatile private var restartRequested = false
    @Volatile private var wantedFacing = initialFacing
    private var lastErrorLogNs = 0L

    /**
     * Starts the render thread and waits (up to [START_TIMEOUT_S]) for its setup. Throws [EngineStartException] when
     * setup failed (the encoder rejects the size, EGL can't start) or hangs; everything built so far is released.
     */
    fun start() {
        running = true
        thread.start()
        val inTime = ready.await(START_TIMEOUT_S, TimeUnit.SECONDS)
        val err = startError
        if (inTime && err == null) return
        stop()
        val message = when {
            err == null -> "Video took too long to start on this phone"
            setupStage == "encoder" -> "Video encoder couldn't start at ${config.width}x${config.height}"
            else -> "Video couldn't start on this phone"
        }
        throw EngineStartException(message, err)
    }

    fun stop() {
        running = false
        thread.join(3_000)
        // A recovery attempt may still be creating an encoder: stop it, then release anything it left behind.
        recoveryExecutor.shutdownNow()
        runCatching { recoveryExecutor.awaitTermination(2, TimeUnit.SECONDS) }
        pendingEncoder.getAndSet(null)?.let { runCatching { it.release() } }
    }

    fun attachPreview(surface: Surface, width: Int, height: Int) {
        previewDetach = false
        val p = preview
        if (p != null) p.attach(surface, width, height) else pendingPreview.set(surface to intArrayOf(width, height))
    }

    fun detachPreview() {
        pendingPreview.set(null)
        previewDetach = true
        preview?.detach()
    }

    fun setDisplayRotation(degrees: Int) {
        displayRotation = degrees
        camera?.setDisplayRotation(degrees)
    }

    fun setBitrate(bps: Int) {
        targetBitrate = bps
        encoder?.setBitrate(bps)
    }

    fun requestKeyframe() { encoder?.requestKeyframe() }

    /**
     * Mod "!restartcam": handled on the render thread, which knows what's on screen. With the external camera
     * streaming, its connection is dropped so it reconnects; otherwise the phone camera is closed and reopened (and
     * the render loop knows it's open again). The compositor holds/slates meanwhile.
     */
    fun restartCamera() { restartRequested = true }

    /** Back or front phone camera; kept for the rest of the stream. */
    fun setCameraFacing(facing: Facing) {
        wantedFacing = facing
        camera?.setFacing(facing)
    }

    /** Phone camera zoom ratio, clamped to what the camera supports. */
    fun setZoom(ratio: Float) { camera?.setZoom(ratio) }

    val cameraFacing: Facing get() = camera?.facing ?: wantedFacing
    val zoomRatio: Float get() = camera?.zoomRatio ?: 1f

    val measuredBitrate: Long get() = encoder?.measuredBitrate ?: 0

    @Volatile private var camera: CameraSource? = null
    /** The RTMP camera (null with the phone camera only). Created on the render thread; check [rtmpReady]. */
    @Volatile var rtmp: RtmpCameraSource? = null
        private set
    private val rtmpReady = CountDownLatch(1)
    /** What was drawn last tick: "camera", "phone" or "none". */
    @Volatile var activeSource = "phone"
        private set

    /** Waits (briefly) for the RTMP camera source to exist. */
    fun awaitRtmp(): RtmpCameraSource? {
        rtmpReady.await(3, TimeUnit.SECONDS)
        return rtmp
    }

    /**
     * Always the stream's configured bitrate, never the current ABR target: the phone's BRB slate is encoded with
     * these exact settings so their SPS/PPS match, and a recovered encoder must keep matching. The target is applied
     * as a runtime change at swap-in.
     */
    private fun newEncoder() = VideoEncoder(config.width, config.height, config.fps, config.videoBitrate, config.keyframeIntervalSec, sink)

    /** Everything the render thread owns once setup succeeded. Touched only on the render thread. */
    private class Gpu(
        val egl: EglCore,
        val program2d: TextureProgram,
        val programOes: TextureProgram,
        val main: Fbo,
        val previewFbo: Fbo,
        val ext: RtmpCameraSource?,
        val cam: CameraSource,
        val preview: PreviewRenderer,
        var enc: VideoEncoder?,
        var encSurface: EGLSurface,
        val slate: SlateDrawer,
        val alert: AlertLayer,
    ) {
        /**
         * Cards are drawn on first use and kept for the stream (a 1080p card is ~8 MB of GPU memory), one slot per card
         * and [CardLayer], indexed `card.ordinal * layers + layer.ordinal` so the per-tick lookup allocates nothing. A
         * slot is redrawn (replacing its texture) when the footer's audio state or [Slates.version] changes, so a new
         * style shows on the next frame. FOREGROUND slots exist only for cards with an animated background, and are
         * freed with it ([releaseBackdrop]).
         */
        val cardTex = arrayOfNulls<CardTex>(Slates.Card.entries.size * CardLayer.entries.size)
        /** Each card's GIF background and web page, while it's shown (and [BACKDROP_GRACE_NS] after); by ordinal. */
        val backdrops = arrayOfNulls<Backdrop>(Slates.Card.entries.size)
        /** Draws GIF and web cards; made the first time one is shown ([drawCardScene]). */
        var layered: LayeredCardDrawer? = null
        /** The layered programs couldn't be built on this phone: plain cards only. */
        var layeredFailed = false
        /** What's on stream (null: the camera), what it's crossfading from, and since when. */
        var program: Slates.Card? = null
        /** False until the first frame: a stream that starts on a card starts on it, with no fade from nothing. */
        var programKnown = false
        var fadeFrom: Slates.Card? = null
        var fading = false
        var fadeStartNs = 0L
        /** The ruler cursor's step: follows the frame count, held still at thermal rung 2+. */
        var cursorStep = 0
        var overlayTex = 0
        var overlayW = 0
        var overlayH = 0
        var overlayConfig: Bitmap.Config? = null
        var lastOverlayUploadNs = 0L
        /** The card on screen at the last overlay check, and when it last changed (see SCENE_OVERLAY_GRACE_NS). */
        var lastSceneCard: Slates.Card? = null
        var sceneChangedAtNs = 0L
        var browser: BrowserSource? = null
        var browserSeen = 0
        /** Closed pages whose GL side is freed once the main thread has closed them (see [BrowserSource.retire]). */
        val retiring = ArrayList<BrowserSource>()
        var phoneOpen = true
        var extFreshSinceNs = 0L
    }

    /** One cached card texture (see [Gpu.cardTex]). */
    private class CardTex(val audio: SlateAudio, val version: Int, val tex: Int)

    /**
     * What a phone-drawn card shows besides its own bitmap: a looping GIF behind it and/or a web page instead of it.
     * Render thread only. Re-read from [Slates.style] only when [Slates.version] moves, so a tick costs no file checks.
     */
    private class Backdrop {
        var version = -1
        /** The GIF to loop and its identity (path, size, time), so a replaced file restarts the decoder. */
        var gifFile: java.io.File? = null
        var gifStamp: String? = null
        var gif: GifBackground? = null
        /** This GIF couldn't play: the normal card until the style changes. */
        var gifFailed = false
        /** The card's page (already checked https), or null. */
        var webUrl: String? = null
        var web: CardWebSource? = null
        /** This page couldn't load: the normal card (or GIF) until the style changes or the card comes back. */
        var webFailed = false
        var webCrashesSeen = 0
        /** Last tick the card was drawn; sources are let go [BACKDROP_GRACE_NS] after. */
        var lastUsedNs = 0L
        val idle: Boolean get() = gif == null && web == null
    }

    private fun run() {
        val g = try {
            setup()
        } catch (t: Throwable) {
            Log.e(TAG, "video setup failed ($setupStage) at ${config.width}x${config.height}", t)
            startError = t
            null
        } finally {
            rtmpReady.countDown()
            ready.countDown()
        }
        if (g == null) return
        val clock = FrameClock(config.fps)
        try {
            while (running) {
                skippedTicks += clock.awaitTick()
                try {
                    tick(g, clock)
                } catch (t: Throwable) {
                    // Backstop: whatever this was, it cost one tick; the clock moves on and the stream keeps going.
                    renderErrors++
                    logError("render tick", t)
                }
                clock.advance()
            }
        } finally {
            teardown(g)
        }
    }

    /** Render thread: builds the pipeline; on failure releases whatever was built (newest first) and rethrows. */
    private fun setup(): Gpu {
        val undo = ArrayList<() -> Unit>()
        try {
            setupStage = "graphics"
            val egl = EglCore().also { e -> undo += { e.release() } }
            val program2d = TextureProgram(oes = false)
            val programOes = TextureProgram(oes = true)
            val slate = SlateDrawer(config.width, config.height).also { s -> undo += { s.release() } }
            val alert = AlertLayer(config.width, config.height)
            val main = Fbo(config.width, config.height).also { f -> undo += { f.release() } }
            // Preview copy: same shape as the output, long side 1280.
            val previewFbo = (if (config.width >= config.height) Fbo(1280, 1280 * config.height / config.width)
            else Fbo(1280 * config.width / config.height, 1280)).also { f -> undo += { f.release() } }
            val ext = if (config.camera is app.irlsupreme.engine.CameraInput.Rtmp) {
                RtmpCameraSource(app.irlsupreme.engine.ingest.IngestClock()).also { e -> undo += { e.release() } }
            } else null
            rtmp = ext
            rtmpReady.countDown()
            setupStage = "camera"
            val cam = CameraSource(ctx, config.width, config.height, config.fps, wantedFacing).also { c -> undo += { c.release() } }
            if (displayRotation >= 0) cam.setDisplayRotation(displayRotation)
            cam.setStabilizationAllowed(thermalRung < 2)
            cam.open() // with an external camera: the fallback until it appears, then closed while it streams
            val pr = PreviewRenderer(egl, previewFbo.texture, previewFbo.width, previewFbo.height).also { p -> undo += { p.release() } }
            setupStage = "encoder"
            val enc = newEncoder().also { e -> undo += { e.release() } }
            val encSurface = egl.createWindowSurface(enc.inputSurface)
            camera = cam
            cam.setFacing(wantedFacing) // a flip that arrived while setting up
            preview = pr
            pendingPreview.getAndSet(null)?.let { (s, wh) -> pr.attach(s, wh[0], wh[1]) }
            encoder = enc
            return Gpu(egl, program2d, programOes, main, previewFbo, ext, cam, pr, enc, encSurface, slate, alert)
        } catch (t: Throwable) {
            camera = null; preview = null; encoder = null; rtmp = null
            for (u in undo.asReversed()) runCatching { u() }
            throw t
        }
    }

    private fun tick(g: Gpu, clock: FrameClock) {
        val now = clock.tickTimeNs()
        if (g.retiring.isNotEmpty()) g.retiring.removeAll { b -> runCatching { b.freeIfClosed() }.getOrDefault(true) }
        // Thermal rung 2+: every 5th tick isn't drawn or sent (~24 fps). Tick times still only go up, so PTS stay monotonic.
        if (thermalRung >= 2 && clock.tick % 5 == 4L) { thermalSkips++; return }
        val cam = g.cam
        val ext = g.ext

        // 1. Sources (each guarded: one bad frame or a dying source must not take the tick, let alone the stream)
        guarded("phone camera", false) { cam.update(now) }
        val extFresh = ext != null && guarded("external camera", false) {
            ext.update(now)
            ext.lastFrameNs != 0L && now - ext.lastFrameNs <= HOLD_NS
        }
        if (restartRequested) {
            restartRequested = false
            if (extFresh) {
                Log.i(TAG, "restart camera: reconnecting the external camera")
                guarded("external camera restart", Unit) { onRestartExternal() }
            } else {
                Log.i(TAG, "restart camera: reopening the phone camera")
                cam.close(); cam.open(); g.phoneOpen = true
            }
        }
        if (ext != null) {
            // The phone camera is the external camera's fallback: open it 1 s into a dropout, close it again once
            // the external feed has been steady for a while (heat and battery in Pocket mode).
            if (extFresh) {
                if (g.extFreshSinceNs == 0L) g.extFreshSinceNs = now
                if (g.phoneOpen && now - g.extFreshSinceNs > PHONE_CLOSE_AFTER_NS) { cam.close(); g.phoneOpen = false }
            } else {
                g.extFreshSinceNs = 0L
                val quietNs = if (ext.lastFrameNs == 0L) Long.MAX_VALUE else now - ext.lastFrameNs
                if (!g.phoneOpen && quietNs > PHONE_OPEN_AFTER_NS) { cam.open(); g.phoneOpen = true }
            }
        }
        // A flip holds the last frame longer: the other camera takes a moment to open, and a slate flash looks broken.
        val holdNs = if (cam.switchingSinceNs != 0L) FLIP_HOLD_NS else HOLD_NS
        cameraPhase = when {
            extFresh -> CameraPhase.LIVE
            cam.phase == CameraPhase.LIVE && cam.lastFrameNs != 0L && now - cam.lastFrameNs > holdNs -> CameraPhase.LOST
            else -> cam.phase
        }

        // 2. Compose the scene into the main FBO
        g.egl.makeCurrent(g.egl.idleSurface)
        if (browserGen != g.browserSeen) {
            g.browserSeen = browserGen
            g.browser?.let { retireBrowser(g, it) }
            g.browser = guarded("web overlay", null) {
                browserWanted?.takeIf { !browserHeld }?.let { BrowserSource(ctx, browserW, browserH, cssW, cssH, it) }
            }
            browserRef = g.browser
        }
        g.browser?.let { b ->
            try {
                b.update()
                browserCrashes = b.crashes
            } catch (t: Throwable) {
                logError("web overlay (closed until it changes)", t)
                renderErrors++
                retireBrowser(g, b)
            }
        }
        val overlayEveryNs = if (thermalRung >= 2) OVERLAY_HOT_INTERVAL_NS else 0L
        // A scene change lifts the hot-phone throttle for a moment: the overlay for the new scene (a card's timers, or
        // none) must land with it, not up to OVERLAY_HOT_INTERVAL_NS later over the wrong picture.
        val cardNow = Slates.Card.of(scene) ?: if (weakSignal) Slates.Card.WEAK_SIGNAL else null
        if (cardNow != g.lastSceneCard) { g.lastSceneCard = cardNow; g.sceneChangedAtNs = now }
        val sceneJustChanged = now - g.sceneChangedAtNs < SCENE_OVERLAY_GRACE_NS
        if (sceneJustChanged || now - g.lastOverlayUploadNs >= overlayEveryNs) pendingOverlay.getAndSet(null)?.let { u ->
            g.lastOverlayUploadNs = now
            try {
                uploadOverlay(g, u.bitmap)
            } catch (t: Throwable) {
                renderErrors++
                logError("overlay upload", t)
            } finally {
                u.bitmap?.recycle()
            }
        }
        g.main.bind()
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT) // black bars around a letterboxed external camera
        val sceneCard = Slates.Card.of(scene) ?: if (weakSignal) Slates.Card.WEAK_SIGNAL else null
        val gop = config.fps * config.keyframeIntervalSec
        if (thermalRung < 2) g.cursorStep = Slates.cursorStep(clock.tick, gop) // hot: the cursor holds still
        // A switch between the camera and a chosen card crossfades; automatic cards and heat cut.
        if (!g.programKnown) { g.program = sceneCard; g.programKnown = true }
        if (sceneCard != g.program) {
            g.fadeFrom = g.program
            g.fading = SceneRules.crossfades(g.program, sceneCard, thermalRung)
            g.fadeStartNs = now
            g.program = sceneCard
        }
        var fadeIn = 1f
        if (g.fading) {
            fadeIn = ((now - g.fadeStartNs).toFloat() / (SceneRules.CROSSFADE_MS * 1_000_000f)).coerceIn(0f, 1f)
            if (fadeIn >= 1f || thermalRung >= 2) { g.fading = false; fadeIn = 1f }
        }
        var phoneDrawn = false
        fun drawCard(c: Slates.Card, alpha: Float) = drawCardScene(g, c, now, clock.tick, gop, alpha)
        /** The camera, its held last frame, or the Camera lost card (returned true), at [alpha]. */
        fun drawCamera(alpha: Float): Boolean {
            when {
                extFresh -> { g.programOes.draw(ext!!.texture, ext.texMatrix, extFit(ext), alpha); activeSource = "camera" }
                g.phoneOpen && cam.lastFrameNs != 0L && now - cam.lastFrameNs <= holdNs -> {
                    g.programOes.draw(cam.texture, cam.texMatrix, alpha = alpha); activeSource = "phone"; phoneDrawn = true
                }
                // External camera dropped and the phone camera isn't up yet: hold its last frame rather than flash a card.
                ext != null && ext.lastFrameNs != 0L && now - ext.lastFrameNs <= EXT_HOLD_NS -> {
                    g.programOes.draw(ext.texture, ext.texMatrix, extFit(ext), alpha); activeSource = "camera"
                }
                else -> { drawCard(Slates.Card.CAMERA_LOST, alpha); activeSource = "none"; return true }
            }
            return false
        }
        if (g.fading) {
            val from = g.fadeFrom
            if (from == null) drawCamera(1f) else drawCard(from, 1f)
        }
        val cameraLostShown = if (sceneCard == null) drawCamera(fadeIn) else { drawCard(sceneCard, fadeIn); false }
        if (g.fading) phoneDrawn = false // mid-fade the preview copies the blend, never the bare (mirrored) camera
        val allowAlerts = SceneRules.alertsAllowed(if (cameraLostShown) Slates.Card.CAMERA_LOST else sceneCard)
        alertsAllowed = allowAlerts
        pendingAlert.getAndSet(null)?.let { a -> guarded("alert upload", Unit) { g.alert.offer(a.graphic, a.atNs) } }
        if (!allowAlerts && g.alert.showing) g.alert.free() // held scenes: the alert goes at once, never under the card
        val layersAllowed = when (sceneCard) {
            null -> true
            Slates.Card.PRIVACY, Slates.Card.WEAK_SIGNAL -> false // privacy; and the weak card must stay cheap
            else -> overlayOnCards
        }
        fun drawLayers() {
            if (!layersAllowed) return
            val b = g.browser
            if (b != null && b.hasFrame) g.programOes.draw(b.texture, b.texMatrix, premultiplied = true)
            if (g.overlayTex != 0) g.program2d.draw(g.overlayTex, GlUtil.FLIP_V, premultiplied = true)
        }
        /** The alert goes over every layer, on Live and the full-frame BRB / Starting soon / Ending cards alike. */
        fun drawAlert(vpW: Int, vpH: Int) {
            if (allowAlerts) guarded("alert", Unit) { g.alert.draw(now, vpW, vpH, cut = thermalRung >= 2) }
        }
        drawLayers()
        drawAlert(config.width, config.height)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        pruneBackdrops(g, now)

        // 3. Encoder: swap in a recovered instance, then feed this tick
        pendingEncoder.getAndSet(null)?.let { fresh ->
            val surface = try {
                g.egl.createWindowSurface(fresh.inputSurface)
            } catch (t: Throwable) {
                logError("recovered encoder's surface", t)
                null
            }
            if (surface == null) {
                scheduleRecovery(fresh, "couldn't draw into the new encoder")
            } else {
                g.enc = fresh
                encoder = fresh
                g.encSurface = surface
                encoderRecoveries++
                fresh.setBitrate(targetBitrate) // created at the configured bitrate; carry on at the current target
                fresh.requestKeyframe()
                runCatching { onEncoderRecovered() }
            }
        }
        val e = g.enc
        if (e != null && g.encSurface != EGL14.EGL_NO_SURFACE) {
            val sinceOutput = System.nanoTime() - e.lastOutputNs
            val stalled = e.lastOutputNs != 0L && sinceOutput > STALL_MS * 1_000_000 ||
                e.lastOutputNs == 0L && System.nanoTime() - e.createdNs > STARTUP_STALL_MS * 1_000_000
            if (stalled) e.fail(if (e.lastOutputNs == 0L) "no output after ${STARTUP_STALL_MS} ms" else "no output for ${STALL_MS} ms")
            if (videoEncoderDown != null && e.lastOutputNs != 0L) {
                Log.i(TAG, "video encoder back")
                videoEncoderDown = null
            }
            var ok = e.failure == null && g.egl.makeCurrent(g.encSurface)
            if (ok) {
                GLES30.glViewport(0, 0, config.width, config.height)
                g.program2d.draw(g.main.texture)
                g.egl.setPresentationTime(g.encSurface, now)
                ok = g.egl.swap(g.encSurface)
                if (!ok) e.fail("eglSwapBuffers failed ${g.egl.lastError()}")
            }
            if (!ok) {
                g.egl.releaseSurface(g.encSurface)
                g.encSurface = EGL14.EGL_NO_SURFACE
                g.enc = null
                encoder = null
                // One that never produced a frame isn't a blip: say so now rather than after the retries.
                if (e.lastOutputNs == 0L) videoEncoderDown = e.failure ?: "no output"
                scheduleRecovery(e, e.failure)
            }
        }

        // 4. Preview (only when it asks; it draws on its own thread). Paused at thermal rung 3.
        val pr = g.preview
        if (!previewPaused && pr.wantsFrame.get() && !previewDetach) {
            g.egl.makeCurrent(g.egl.idleSurface)
            g.previewFbo.bind()
            if (phoneDrawn && cam.facing == Facing.FRONT) {
                // The streamer sees a selfie (mirror) view of the front camera; viewers don't. Overlays stay readable.
                GLES30.glClearColor(0f, 0f, 0f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                g.programOes.draw(cam.texture, cam.previewTexMatrix)
                drawLayers()
                drawAlert(g.previewFbo.width, g.previewFbo.height)
            } else {
                g.program2d.draw(g.main.texture)
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            val fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            GLES30.glFlush()
            pr.offer(fence)
        }
    }

    /**
     * Draws card [c] at [alpha]: its web page if it has one that's showing, else its GIF behind its foreground if that's
     * playing, else the plain card, exactly as before custom backgrounds. Whatever fails falls back down that list, so
     * the card is never black or frozen.
     */
    private fun drawCardScene(g: Gpu, c: Slates.Card, now: Long, frameIndex: Long, gop: Int, alpha: Float) {
        val show = if (CardBackdrops.animates(c) && !g.layeredFailed) prepareBackdrop(g, c, now) else CardBackdrops.Show.CARD
        if (show != CardBackdrops.Show.CARD) {
            val drawn = guarded("custom card", false) {
                // Built on first use, not at setup: a shader this phone won't compile must never stop a stream starting.
                val layered = g.layered ?: try {
                    LayeredCardDrawer(config.width, config.height).also { g.layered = it }
                } catch (t: Throwable) {
                    g.layeredFailed = true // plain cards for the rest of the stream
                    throw t
                }
                val b = g.backdrops[c.ordinal]
                val w = b?.web
                val gif = b?.gif
                if (show == CardBackdrops.Show.WEB && w != null) {
                    layered.drawPage(w.texture, w.texMatrix, alpha)
                    true
                } else if (show == CardBackdrops.Show.GIF && gif != null) {
                    val fg = card(g, c, CardLayer.FOREGROUND)
                    layered.drawGif(gif.texture, gif.texMatrix, fg, c, Slates.style(c).dim, frameIndex, gop, g.cursorStep, alpha)
                    true
                } else {
                    false
                }
            }
            if (drawn) return
        }
        g.slate.draw(card(g, c, CardLayer.FULL), c, frameIndex, gop, g.cursorStep, moves = c.moves, alpha = alpha)
    }

    /**
     * Render thread, each tick card [c] is drawn: brings its backdrop in line with its style (opening, updating or
     * letting go of its GIF and page) and says what to draw. No allocation unless something starts or the style moved.
     */
    private fun prepareBackdrop(g: Gpu, c: Slates.Card, now: Long): CardBackdrops.Show {
        if (!CardBackdrops.animates(c)) return CardBackdrops.Show.CARD // Weak signal and Signal lost: static, server-side
        val v = Slates.version
        var b = g.backdrops[c.ordinal]
        if (b == null || b.version != v) {
            val style = Slates.style(c)
            val gifFile = style.backgroundGif
            val url = style.webUrl?.trim()?.takeIf { CardWebSource.usable(it) }
            if (gifFile == null && url == null) {
                b?.let { releaseBackdrop(g, c, it) }
                g.backdrops[c.ordinal] = null
                return CardBackdrops.Show.CARD
            }
            if (b == null) b = Backdrop().also { g.backdrops[c.ordinal] = it }
            b.version = v
            val stamp = gifFile?.let { "${it.path}:${it.length()}:${it.lastModified()}" }
            if (stamp != b.gifStamp) {
                b.gif?.let { runCatching { it.release() } }
                b.gif = null
                b.gifFile = gifFile
                b.gifStamp = stamp
                b.gifFailed = false
            }
            if (url != b.webUrl) {
                b.web?.let { retireCardPage(g, it) }
                b.web = null
                b.webUrl = url
                b.webFailed = false
            }
        }
        // A card coming back on after a while away gets its page again, even if it failed last time.
        if (b.idle && now - b.lastUsedNs > BACKDROP_GRACE_NS) b.webFailed = false
        b.lastUsedNs = now

        val wantWeb = CardBackdrops.wantsWeb(c, b.webUrl != null, browserHeld, b.webFailed)
        var webReady = false
        if (wantWeb) {
            val w = b.web ?: guarded("card page", null) {
                makeRoom(g, now, gifs = false)
                CardWebSource(ctx, b.webUrl!!, browserW, browserH, cssW, cssH).also { cardPageRef = it.browser }
            }.also { b.web = it }
            if (w == null) {
                b.webFailed = true
            } else {
                try {
                    w.update()
                    if (w.crashes > b.webCrashesSeen) { cardPageCrashes += w.crashes - b.webCrashesSeen; b.webCrashesSeen = w.crashes }
                    if (w.failed) {
                        b.webFailed = true
                        retireCardPage(g, w)
                        b.web = null
                    } else {
                        webReady = w.ready
                    }
                } catch (t: Throwable) {
                    renderErrors++
                    logError("card page (normal card until the card comes back)", t)
                    b.webFailed = true
                    retireCardPage(g, w)
                    b.web = null
                }
            }
        } else if (b.web != null) {
            retireCardPage(g, b.web!!) // held for heat, or failed: the normal card (or its GIF) stands in
            b.web = null
        }

        val wantGif = CardBackdrops.wantsGif(c, b.gifFile != null, b.gifFailed, wantWeb)
        var gifReady = false
        if (wantGif) {
            val gif = b.gif ?: guarded("card background", null) {
                makeRoom(g, now, gifs = true)
                GifBackground(b.gifFile!!, config.width, config.height)
            }.also { b.gif = it }
            if (gif == null) {
                b.gifFailed = true
            } else {
                gif.minIntervalMs = if (thermalRung >= 2) GifPlan.HOT_INTERVAL_MS else GifPlan.MIN_INTERVAL_MS
                gifReady = guarded("card background upload", false) { gif.update() }
                if (gif.state == GifBackground.State.FAILED) {
                    b.gifFailed = true
                    gifReady = false
                    releaseGif(g, c, b)
                }
            }
        } else if (b.gif != null) {
            releaseGif(g, c, b)
        }
        return CardBackdrops.choose(wantWeb, webReady, wantGif, gifReady)
    }

    /**
     * Before a GIF ([gifs]) or a page opens: lets go of the same kind on cards not drawn this tick, oldest first, until
     * fewer than [MAX_LIVE_BACKDROPS] are open. At most two cards are drawn in a tick (a crossfade), so this caps the
     * memory at two GIFs (~44 MB at worst, see [GifBackground]) and two pages however fast cards change.
     */
    private fun makeRoom(g: Gpu, now: Long, gifs: Boolean) {
        while (true) {
            var open = 0
            var oldest = -1
            for (i in g.backdrops.indices) {
                val b = g.backdrops[i] ?: continue
                if ((if (gifs) b.gif else b.web) == null) continue
                open++
                if (b.lastUsedNs < now && (oldest < 0 || b.lastUsedNs < g.backdrops[oldest]!!.lastUsedNs)) oldest = i
            }
            if (open < MAX_LIVE_BACKDROPS || oldest < 0) return
            val b = g.backdrops[oldest]!!
            if (gifs) releaseGif(g, Slates.Card.entries[oldest], b) else { b.web?.let { retireCardPage(g, it) }; b.web = null }
        }
    }

    /** Lets go of backdrops whose card hasn't been drawn for [BACKDROP_GRACE_NS] (a fixed array: no allocation). */
    private fun pruneBackdrops(g: Gpu, now: Long) {
        for (i in g.backdrops.indices) {
            val b = g.backdrops[i] ?: continue
            if (!b.idle && now - b.lastUsedNs > BACKDROP_GRACE_NS) {
                releaseBackdrop(g, Slates.Card.entries[i], b)
                b.webFailed = false
            }
        }
    }

    private fun releaseBackdrop(g: Gpu, c: Slates.Card, b: Backdrop) {
        b.web?.let { retireCardPage(g, it) }
        b.web = null
        releaseGif(g, c, b)
    }

    /** Frees the GIF and the card's FOREGROUND texture, which only an animated background uses. */
    private fun releaseGif(g: Gpu, c: Slates.Card, b: Backdrop) {
        b.gif?.let { runCatching { it.release() } }
        b.gif = null
        val i = c.ordinal * CardLayer.entries.size + CardLayer.FOREGROUND.ordinal
        g.cardTex[i]?.let { runCatching { GLES30.glDeleteTextures(1, intArrayOf(it.tex), 0) } }
        g.cardTex[i] = null
    }

    /** Closes a card page the same way as the overlay page: the GL side goes once the main thread has closed it. */
    private fun retireCardPage(g: Gpu, w: CardWebSource) {
        runCatching { w.browser.retire() }
        g.retiring += w.browser
        if (cardPageRef === w.browser) cardPageRef = null
    }

    /** [c]'s texture for [layer] with the footer's audio as it is now; an outdated copy in the same slot is freed. */
    private fun card(g: Gpu, c: Slates.Card, layer: CardLayer): Int {
        val audio = Slates.shownAudio(c, slateAudio)
        val v = Slates.version
        val i = c.ordinal * CardLayer.entries.size + layer.ordinal
        val cached = g.cardTex[i]
        if (cached != null && cached.audio == audio && cached.version == v) return cached.tex
        if (cached != null) GLES30.glDeleteTextures(1, intArrayOf(cached.tex), 0)
        g.cardTex[i] = null
        val bmp = Slates.bitmap(config.width, config.height, c, config.channelLogin, audio, style = Slates.style(c), layer = layer)
        val tex = try { GlUtil.textureFromBitmap(bmp) } finally { bmp.recycle() }
        g.cardTex[i] = CardTex(audio, v, tex)
        return tex
    }

    /** The overlay texture: re-specified only on first use or a size change, else updated in place. */
    private fun uploadOverlay(g: Gpu, b: Bitmap?) {
        if (b == null || b.isRecycled) {
            if (g.overlayTex != 0) GLES30.glDeleteTextures(1, intArrayOf(g.overlayTex), 0)
            g.overlayTex = 0; g.overlayW = 0; g.overlayH = 0; g.overlayConfig = null
            return
        }
        if (g.overlayTex == 0) {
            g.overlayTex = GlUtil.textureFromBitmap(b)
        } else {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, g.overlayTex)
            if (b.width == g.overlayW && b.height == g.overlayH && b.config == g.overlayConfig) {
                GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, b)
            } else {
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0)
            }
        }
        g.overlayW = b.width; g.overlayH = b.height; g.overlayConfig = b.config
    }

    private fun retireBrowser(g: Gpu, b: BrowserSource) {
        runCatching { b.retire() }
        g.retiring += b
        if (g.browser === b) { g.browser = null; browserRef = null }
    }

    /** Replaces a failed encoder off the render thread, retrying with backoff (250 ms up to 5 s) while streaming. */
    private fun scheduleRecovery(dead: VideoEncoder, reason: String?) {
        Log.w(TAG, "encoder failed ($reason); recovering, clock keeps ticking")
        try {
            recoveryExecutor.execute { recover(dead) }
        } catch (_: RejectedExecutionException) {
            runCatching { dead.release() } // stopping
        }
    }

    private fun recover(dead: VideoEncoder) {
        runCatching { dead.release() }
        var delayMs = 250L
        var attempt = 0
        while (running) {
            attempt++
            val fresh = try {
                newEncoder()
            } catch (t: Throwable) {
                videoEncoderDown = t.message ?: t.javaClass.simpleName
                Log.e(TAG, "encoder re-create #$attempt failed; next try in $delayMs ms", t)
                null
            }
            if (fresh != null) {
                if (running) pendingEncoder.getAndSet(fresh)?.let { runCatching { it.release() } } else fresh.release()
                return
            }
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                return
            }
            delayMs = (delayMs * 2).coerceAtMost(5_000)
        }
    }

    private inline fun <T> guarded(what: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        renderErrors++
        logError(what, t)
        fallback
    }

    /** Logs at most one render error every 5 s (one that repeats every tick would flood the log). */
    private fun logError(what: String, t: Throwable) {
        val now = System.nanoTime()
        if (now - lastErrorLogNs < 5_000_000_000L && lastErrorLogNs != 0L) return
        lastErrorLogNs = now
        Log.e(TAG, "render: $what failed (${renderErrors} errors this stream); carrying on", t)
    }

    /** Render thread, after the loop: releases everything, each step on its own so one failure can't leak the rest. */
    private fun teardown(g: Gpu) {
        val egl = g.egl
        runCatching { egl.makeCurrent(egl.idleSurface) }
        g.enc?.let { e -> runCatching { egl.releaseSurface(g.encSurface) }; runCatching { e.release() } }
        encoder = null
        pendingEncoder.getAndSet(null)?.let { runCatching { it.release() } }
        runCatching { g.preview.release() }
        preview = null
        camera = null
        runCatching { g.cam.release() }
        runCatching { g.ext?.release() }
        rtmp = null
        for (b in g.backdrops) {
            runCatching { b?.gif?.release() }
            runCatching { b?.web?.browser?.release(wait = false) }
        }
        cardPageRef = null
        val cardTextures = g.cardTex.filterNotNull().map { it.tex }.toIntArray()
        runCatching { if (cardTextures.isNotEmpty()) GLES30.glDeleteTextures(cardTextures.size, cardTextures, 0) }
        runCatching { g.layered?.release() }
        runCatching { if (g.overlayTex != 0) GLES30.glDeleteTextures(1, intArrayOf(g.overlayTex), 0) }
        runCatching { g.slate.release() }
        runCatching { g.alert.free() }
        pendingAlert.getAndSet(null)?.graphic?.bitmap?.recycle()
        runCatching { g.browser?.release(wait = false) }
        for (b in g.retiring) runCatching { b.release(wait = false) }
        browserRef = null
        pendingOverlay.getAndSet(null)?.bitmap?.recycle()
        runCatching { g.main.release() }
        runCatching { g.previewFbo.release() }
        runCatching { egl.release() }
    }

    /** External camera: fitted (never stretched or cropped) into the output, whatever shape either is. */
    private fun extFit(ext: RtmpCameraSource) = CameraGeometry.fitTransform(ext.width, ext.height, config.width, config.height)

    // The page renders at 720p laid out as 1080p, in the stream's shape.
    private val portraitOut = config.height > config.width
    private val browserW = if (portraitOut) 720 else 1280
    private val browserH = if (portraitOut) 1280 else 720
    private val cssW = if (portraitOut) 1080 else 1920
    private val cssH = if (portraitOut) 1920 else 1080

    private companion object {
        const val TAG = "Engine"
        const val START_TIMEOUT_S = 5L
        const val HOLD_NS = 500_000_000L
        const val FLIP_HOLD_NS = 2_000_000_000L
        const val STALL_MS = 1_500L
        const val STARTUP_STALL_MS = 3_000L
        const val PHONE_OPEN_AFTER_NS = 1_000_000_000L
        const val PHONE_CLOSE_AFTER_NS = 10_000_000_000L
        const val EXT_HOLD_NS = 3_000_000_000L
        /** Thermal rung 2+: overlay uploads (8 MB each at 1080p) at most this often; the newest bitmap waits. */
        const val OVERLAY_HOT_INTERVAL_NS = 5_000_000_000L
        /** How long after a scene change overlay uploads skip the hot-phone throttle. */
        const val SCENE_OVERLAY_GRACE_NS = 2_000_000_000L
        /**
         * A card's GIF and page are kept this long after it was last drawn, so a card that blinks off and on (Camera
         * lost for a moment, a quick BRB) doesn't restart them, and let go after so they hold no memory off screen.
         */
        const val BACKDROP_GRACE_NS = 1_000_000_000L
        /** GIFs (and, separately, card pages) open at once: the two cards of a crossfade. */
        const val MAX_LIVE_BACKDROPS = 2
    }
}

/**
 * What a phone-drawn card shows (custom cards), pure so it can be tested: its web page, its GIF behind its foreground,
 * or the plain card. The server's cards (Weak signal, Signal lost) never animate: Signal lost is looped by the server
 * from a clip encoded once, and Weak signal is shown because the connection can't carry a moving picture.
 */
internal object CardBackdrops {
    enum class Show { CARD, GIF, WEB }

    /** The cards the phone draws live and can animate: BRB, Starting soon, Ending, Privacy and Camera lost. */
    fun animates(card: Slates.Card): Boolean = when (card) {
        Slates.Card.BRB, Slates.Card.STARTING_SOON, Slates.Card.ENDING, Slates.Card.PRIVACY, Slates.Card.CAMERA_LOST -> true
        Slates.Card.WEAK_SIGNAL, Slates.Card.SIGNAL_LOST -> false
    }

    /**
     * Whether [card]'s page should be open: it has a usable https page, the thermal ladder isn't holding web pages
     * ([held]: a page costs about half a CPU core) and it hasn't failed to load this time round.
     */
    fun wantsWeb(card: Slates.Card, hasUsableUrl: Boolean, held: Boolean, failed: Boolean): Boolean =
        animates(card) && hasUsableUrl && !held && !failed

    /**
     * Whether [card]'s GIF should play: it has one that hasn't failed, and no page is wanted (the page replaces the
     * whole card, so the GIF would only cost memory behind it; while a page loads, the plain card shows).
     */
    fun wantsGif(card: Slates.Card, hasGif: Boolean, failed: Boolean, wantsWeb: Boolean): Boolean =
        animates(card) && hasGif && !failed && !wantsWeb

    /** What to draw this tick: whatever is wanted and has a frame, else the plain card. */
    fun choose(wantsWeb: Boolean, webReady: Boolean, wantsGif: Boolean, gifReady: Boolean): Show = when {
        wantsWeb && webReady -> Show.WEB
        wantsGif && gifReady -> Show.GIF
        else -> Show.CARD
    }
}

/**
 * Draws a card from layers ([LayeredCardProgram]): a GIF frame under the card's [CardLayer.FOREGROUND], with the same
 * grain, pulse and ruler cursor [SlateDrawer] gives the plain card (same seeds, same geometry, same colours), or a web
 * page over Ink. Render thread only; nothing is allocated per frame.
 */
internal class LayeredCardDrawer(width: Int, height: Int) {
    private val gifProgram = LayeredCardProgram(webPage = false)
    private val pageProgram = LayeredCardProgram(webPage = true)
    private val flat = TextureProgram(oes = false)
    private val cursorColors = CursorTextures()
    private val cursors = Array(Slates.CURSOR_STEPS) { step ->
        val r = Slates.cursorRect(width, height, step)
        GlUtil.rectMatrix(r[0], r[1], r[2], r[3], width, height)
    }

    /**
     * [bg]: the GIF texture, cropped to fill by [bgMatrix]; [fg]: [card]'s FOREGROUND texture (premultiplied, a bitmap);
     * [dim]: the style's Ink over the GIF (clamped to the 0..0.8 the setting allows).
     */
    fun drawGif(
        bg: Int, bgMatrix: FloatArray, fg: Int, card: Slates.Card, dim: Float, frameIndex: Long, gopFrames: Int, cursorStep: Int,
        alpha: Float,
    ) {
        val moves = card.moves
        gifProgram.draw(
            fg, GlUtil.FLIP_V, bg, bgMatrix, StreamPalette.INK, dim.coerceIn(0f, MAX_DIM),
            seed = Slates.grainSeed(frameIndex, gopFrames), grain = if (moves) Slates.GRAIN else 0f,
            gain = if (moves) Slates.pulseGain(frameIndex, gopFrames) else 1f, alpha = alpha,
        )
        if (cursorStep in 0 until Slates.CURSOR_STEPS && card.hasCursor) {
            // Over the GIF the card uses its Ink colours, so the cursor follows (the accent if one is set).
            val color = cursorColors.forColor(Slates.cursorColor(card, Slates.style(card), CardLayer.FOREGROUND))
            flat.draw(color, posMatrix = cursors[cursorStep], alpha = alpha)
        }
    }

    /**
     * A card's web page, full-frame over Ink. No grain, pulse, cursor, tag or footer: the page is entirely the
     * streamer's (their own design and words), which is the point of choosing one.
     */
    fun drawPage(tex: Int, texMatrix: FloatArray, alpha: Float) {
        pageProgram.draw(tex, texMatrix, 0, GlUtil.IDENTITY, StreamPalette.INK, 0f, seed = 0f, grain = 0f, gain = 1f, alpha = alpha)
    }

    fun release() {
        cursorColors.release()
    }

    private companion object {
        /** [CardStyle.dim]'s documented ceiling: past it the background is just Ink. */
        const val MAX_DIM = 0.8f
    }
}

/**
 * What viewers see change, and when, pure so it can be tested. A card is null for the camera.
 * On stream, motion follows only the thermal ladder: the phone's animator scale never changes what viewers see.
 */
internal object SceneRules {
    /** The scene crossfade: linear alpha over the Enter duration. */
    const val CROSSFADE_MS = 280L

    /**
     * Whether a switch from [from] to [to] crossfades. Camera ⇄ chosen card (BRB, Starting soon, Ending, and back from
     * Privacy) fades; the automatic cards (Weak signal, Camera lost) cut, because they report a fault the moment it's
     * real; going into Privacy cuts, because it promises the camera is off now; at thermal rung 2+ everything cuts.
     */
    fun crossfades(from: Slates.Card?, to: Slates.Card?, thermalRung: Int): Boolean {
        if (from == to || thermalRung >= 2) return false
        if (from?.family == Slates.Family.AUTOMATIC || to?.family == Slates.Family.AUTOMATIC) return false
        return to != Slates.Card.PRIVACY
    }

    /**
     * Whether an alert may show over [shown]: on the camera and the full-frame BRB, Starting soon and Ending cards.
     * Privacy, Weak signal, Camera lost and Signal lost hold it (and its chime) until they're gone.
     */
    fun alertsAllowed(shown: Slates.Card?): Boolean = when (shown) {
        null, Slates.Card.BRB, Slates.Card.STARTING_SOON, Slates.Card.ENDING -> true
        Slates.Card.PRIVACY, Slates.Card.WEAK_SIGNAL, Slates.Card.CAMERA_LOST, Slates.Card.SIGNAL_LOST -> false
    }
}
