package app.irlsupreme.engine.video

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import app.irlsupreme.engine.CameraPhase
import app.irlsupreme.engine.Facing
import app.irlsupreme.engine.gl.GlUtil
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

private const val TAG = "Engine"

/**
 * Whether a frame just latched from the phone camera can go out. While a flip is under way (the old camera closed,
 * the new one not yet streaming) any frame is the old camera's, and the transform no longer matches it. Once the new
 * session is LIVE the old camera can't be producing any more, so frames are the new camera's.
 */
internal fun cameraFrameUsable(switching: Boolean, phase: CameraPhase): Boolean = !switching || phase == CameraPhase.LIVE

/**
 * Fixed-rate clock on System.nanoTime() (CLOCK_MONOTONIC). Not Choreographer/vsync, so it keeps ticking with the
 * screen off. If the render thread falls more than a period behind, ticks are skipped so output timestamps
 * show the real gap instead of bunching up.
 */
class FrameClock(fps: Int) {
    val periodNs = 1_000_000_000L / fps
    private val t0 = System.nanoTime()
    var tick = 0L
        private set

    fun tickTimeNs(): Long = t0 + tick * periodNs

    /** Sleeps until the current tick; returns how many ticks were skipped because we were late. */
    fun awaitTick(): Long {
        val target = tickTimeNs()
        while (true) {
            val now = System.nanoTime()
            if (now >= target) break
            LockSupport.parkNanos(target - now)
        }
        val late = System.nanoTime() - target
        if (late > periodNs) {
            val skipped = late / periodNs
            tick += skipped
            return skipped
        }
        return 0
    }

    fun advance() { tick++ }
}

/**
 * Camera2 phone camera (back or front) -> SurfaceTexture (OES). Camera callbacks run on their own thread; the render
 * thread latches frames with [update]. Reopens itself with backoff after errors/disconnects, so "camera lost" is a
 * state, not an end (the compositor holds the last frame, then shows a slate).
 *
 * Orientation (Android's "Camera orientations" guide): the SurfaceTexture transform already turns
 * the sensor image to the phone's natural (portrait) orientation; [texMatrix] then turns it by the display rotation and
 * centre-crops it to the [width]x[height] output, which is landscape or portrait as the user chose. The live screen is
 * locked to that orientation, so the display rotation only ever flips between its two sides; a rotation of the other
 * shape (e.g. portrait while streaming landscape, from another app) is ignored. Nothing is ever stretched. The front
 * camera is un-mirrored for viewers; [previewTexMatrix] is the selfie (mirrored) view for the phone's own preview.
 *
 * Every open and close bumps a generation number on the camera thread. A callback from an older generation (a camera
 * that finished opening after it was closed, flipped or restarted) closes its device and does nothing else, so the
 * camera can never end up running when nobody wants it (Pocket mode closes it to save heat and battery).
 */
class CameraSource(
    private val ctx: Context,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    initialFacing: Facing = Facing.BACK,
) {
    private val thread = HandlerThread("engine-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    val texture = GlUtil.genTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
    private val st = SurfaceTexture(texture).apply { setDefaultBufferSize(maxOf(width, height), minOf(width, height)) }
    private val surface = Surface(st)
    private val frameAvailable = AtomicBoolean(false)
    private val stMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    /** Upright + centre-crop transform; replaced whole (never mutated) so the render thread never sees half of one. */
    @Volatile private var transform = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    /** The same, mirrored for a front camera's preview (equal to [transform] for the back camera). */
    @Volatile private var previewTransform = transform
    /** Combined transform used to sample [texture] upright in the output. */
    val texMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    /** Combined transform for the phone's own preview: the selfie view for the front camera, else [texMatrix]. */
    val previewTexMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    // Camera-thread state (and the constructor's).
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var generation = 0
    private var sensorOrientation = 90
    private var bufW = maxOf(width, height)
    private var bufH = minOf(width, height)
    /** Last display rotation the UI reported (-1: none yet) and the one in use. */
    private var reportedRotation = -1
    private var appliedRotation = -1
    @Volatile private var wanted = false
    private var retryDelayMs = 500L
    private val retry = Runnable { openNow() }
    private var wantedFacing = initialFacing
    private var fpsRange: Range<Int>? = null
    /** Stabilisation modes this camera lists; ON is only asked for when it's one of them. */
    private var eisSupported = false
    /** The thermal ladder turns stabilisation off at its second rung ([setStabilizationAllowed]). */
    private var eisAllowed = true
    private var zoomRange: Range<Float>? = null
    private var zoom = 1f

    private val debuggable = ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    @Volatile private var dumpPending: String? = null

    @Volatile var lastFrameNs = 0L
        private set
    @Volatile var phase = CameraPhase.OFF
        private set
    /** The camera actually open (or being opened): may differ from what was asked if the phone has no front camera. */
    @Volatile var facing = initialFacing
        private set
    /** Zoom ratio in use (1 = none). */
    @Volatile var zoomRatio = 1f
        private set
    /** System.nanoTime of the last flip, until the new camera's first frame arrives (0 otherwise). */
    @Volatile var switchingSinceNs = 0L
        private set

    init {
        st.setOnFrameAvailableListener({ frameAvailable.set(true) }, handler)
        updateTransform("start")
    }

    /**
     * Render thread: latch the newest frame (if any). Returns true when a new frame was latched.
     *
     * During a flip the queue can still hold the old camera's last frames, but [updateTransform] has already been
     * run for the new camera, so such a frame would go out upside down or mirrored. Those are drained without
     * touching the matrices or [lastFrameNs], so the picture already on screen holds under the flip hold.
     */
    fun update(nowNs: Long): Boolean {
        if (!frameAvailable.getAndSet(false)) return false
        st.updateTexImage()
        if (!cameraFrameUsable(switchingSinceNs != 0L, phase)) return false
        st.getTransformMatrix(stMatrix)
        Matrix.multiplyMM(texMatrix, 0, stMatrix, 0, transform, 0)
        Matrix.multiplyMM(previewTexMatrix, 0, stMatrix, 0, previewTransform, 0)
        lastFrameNs = nowNs
        switchingSinceNs = 0L
        dumpPending?.let { why ->
            dumpPending = null
            val m = stMatrix.copyOf()
            handler.post { writeDebug(why, m) }
        }
        return true
    }

    /** Degrees the live screen is rotated (Surface.ROTATION_* x 90); call on every change, including 180° flips. */
    fun setDisplayRotation(degrees: Int) {
        handler.post {
            reportedRotation = ((degrees % 360) + 360) % 360
            updateTransform("display rotation $degrees")
        }
    }

    /** Camera thread (or the constructor). */
    private fun updateTransform(why: String) {
        val front = facing == Facing.FRONT
        val (nw, nh) = CameraGeometry.naturalSize(bufW, bufH, sensorOrientation)
        fun fits(r: Int) = r >= 0 && CameraGeometry.matchesOutput(nw, nh, r, width, height)
        val rot = when {
            fits(reportedRotation) -> reportedRotation
            fits(appliedRotation) -> appliedRotation // e.g. a portrait rotation while streaming landscape: keep the last
            else -> CameraGeometry.defaultDisplayRotation(nw, nh, width, height)
        }
        appliedRotation = rot
        val turn = CameraGeometry.rotationForDisplay(rot, front)
        val t = CameraGeometry.texTransform(nw, nh, turn, width, height, mirror = front)
        previewTransform = if (front) CameraGeometry.mirrorOutput(t) else t
        transform = t
        val line = "phone camera ($facing): output ${width}x$height, buffer ${bufW}x$bufH, sensor $sensorOrientation°, " +
            "display $rot° (reported $reportedRotation°), turn $turn° ($why)"
        Log.i(TAG, line)
        if (debuggable) dumpPending = line
    }

    /** Debug builds: the last transform and SurfaceTexture matrix, in files/camera-debug.txt (this phone's logcat drops lines). */
    private fun writeDebug(line: String, st: FloatArray) {
        runCatching {
            val f = java.io.File(ctx.filesDir, "camera-debug.txt")
            val text = "${System.currentTimeMillis()} $line\n  stMatrix ${st.joinToString(" ") { "%.3f".format(it) }}\n" +
                "  transform ${transform.joinToString(" ") { "%.3f".format(it) }}\n"
            f.appendText(text)
            if (f.length() > 64_000) f.writeText(text)
        }
    }

    fun open() {
        wanted = true
        handler.post {
            handler.removeCallbacks(retry)
            openNow()
        }
    }

    /**
     * Switches between the back and front cameras: closes the current one and opens the other into the same
     * SurfaceTexture, so the compositor just holds the last frame for a moment. Zoom goes back to 1.
     */
    fun setFacing(f: Facing) {
        handler.post {
            if (wantedFacing == f) return@post
            wantedFacing = f
            zoom = 1f
            zoomRatio = 1f
            if (!wanted) { facing = f; return@post } // closed (Pocket mode): opens on this side next time
            switchingSinceNs = System.nanoTime()
            closeNow()
            retryDelayMs = 500
            openNow()
        }
    }

    /** Zoom ratio, clamped to what this camera supports (1 when it can't zoom). */
    fun setZoom(ratio: Float) {
        handler.post {
            zoom = ratio
            applyRequest("zoom")
        }
    }

    /** The thermal ladder's second rung turns video stabilisation off (it costs heat); back on when it cools. */
    fun setStabilizationAllowed(allowed: Boolean) {
        handler.post {
            if (eisAllowed == allowed) return@post
            eisAllowed = allowed
            if (eisSupported) applyRequest(if (allowed) "stabilisation back on" else "stabilisation off (heat)")
        }
    }

    @SuppressLint("MissingPermission") // the app only starts the engine after CAMERA is granted
    private fun openNow() {
        if (!wanted || device != null || phase == CameraPhase.OPENING) return
        val cm = ctx.getSystemService(CameraManager::class.java)
        try {
            val lens = if (wantedFacing == Facing.FRONT) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val ids = cm.cameraIdList
            val id = ids.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == lens }
                ?: ids.firstOrNull {
                    cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                } ?: ids.first()
            val chars = cm.getCameraCharacteristics(id)
            facing = if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) Facing.FRONT else Facing.BACK
            if (facing != wantedFacing) Log.w(TAG, "no $wantedFacing camera; using the $facing one")
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            // Ask for a size the camera really produces (an unsupported one is rounded to who-knows-what aspect).
            val offered = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(SurfaceTexture::class.java)?.map { it.width to it.height }.orEmpty()
            val (w, h) = CameraGeometry.chooseBufferSize(offered, width, height)
            bufW = w; bufH = h
            st.setDefaultBufferSize(w, h)
            updateTransform("camera opened")
            val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
            fpsRange = ranges.firstOrNull { it.lower == fps && it.upper == fps }
                ?: ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
            // Forcing ON where it isn't listed costs heat and crop on mid-tier phones, and a HAL that rejects it sends
            // the camera into its retry loop.
            eisSupported = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                ?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
            zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            phase = CameraPhase.OPENING
            val gen = ++generation
            cm.openCamera(id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (gen != generation || !wanted) {
                        // Closed, flipped or restarted while it was opening: nobody wants this one.
                        runCatching { d.close() }
                        return
                    }
                    device = d
                    startSession(d, gen)
                }
                override fun onDisconnected(d: CameraDevice) = lost(d, gen, "disconnected")
                override fun onError(d: CameraDevice, error: Int) = lost(d, gen, "error $error")
            })
            // A camera service that never answers would leave the source OPENING for good.
            handler.postDelayed({
                if (gen == generation && device == null && phase == CameraPhase.OPENING) {
                    Log.w(TAG, "camera didn't open in ${OPEN_TIMEOUT_MS / 1000} s; trying again")
                    generation++
                    phase = CameraPhase.LOST
                    scheduleRetry()
                }
            }, OPEN_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(TAG, "camera open failed", e)
            phase = CameraPhase.LOST
            scheduleRetry()
        }
    }

    private fun startSession(d: CameraDevice, gen: Int) {
        val cfg = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(surface)), executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (gen != generation || !wanted || device !== d) {
                        runCatching { s.close() }
                        return
                    }
                    session = s
                    try {
                        s.setRepeatingRequest(buildRequest(d), null, handler)
                        phase = CameraPhase.LIVE
                        retryDelayMs = 500
                    } catch (e: Exception) {
                        lost(d, gen, "request failed: $e")
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) = lost(d, gen, "configure failed")
            },
        )
        try {
            d.createCaptureSession(cfg)
        } catch (e: Exception) {
            lost(d, gen, "session failed: $e")
        }
    }

    /** Camera thread: the repeating request for the current fps, stabilisation and zoom. */
    private fun buildRequest(d: CameraDevice): CaptureRequest = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
        addTarget(surface)
        fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        val eis = eisSupported && eisAllowed
        set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            if (eis) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
        )
        val range = zoomRange
        val z = if (range == null) 1f else zoom.coerceIn(range.lower, range.upper)
        if (range != null) set(CaptureRequest.CONTROL_ZOOM_RATIO, z)
        zoomRatio = z
    }.build()

    /** Camera thread: re-issues the repeating request after a setting changed (no-op while the camera isn't up). */
    private fun applyRequest(why: String) {
        val d = device ?: return
        val s = session ?: return
        try {
            s.setRepeatingRequest(buildRequest(d), null, handler)
        } catch (e: Exception) {
            Log.w(TAG, "camera request ($why) failed", e)
        }
    }

    private fun lost(d: CameraDevice, gen: Int, why: String) {
        if (gen != generation) { runCatching { d.close() }; return } // an old device closing: expected
        Log.w(TAG, "camera lost: $why")
        runCatching { session?.close() }
        runCatching { d.close() }
        session = null
        device = null
        phase = CameraPhase.LOST
        scheduleRetry()
    }

    private fun scheduleRetry() {
        if (!wanted) return
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(5_000)
    }

    /** Camera thread: closes whatever is open or opening (a device still opening closes itself in onOpened). */
    private fun closeNow() {
        generation++
        handler.removeCallbacks(retry)
        runCatching { session?.close() }
        runCatching { device?.close() }
        session = null; device = null
        phase = CameraPhase.OFF
    }

    fun close() {
        wanted = false
        handler.post { closeNow() }
    }

    /** Render thread. Waits (up to 2 s) for the camera to be closed before the surface it draws into goes away. */
    fun release() {
        wanted = false
        val closed = CountDownLatch(1)
        handler.post { closeNow(); closed.countDown() }
        closed.await(2, TimeUnit.SECONDS)
        thread.quitSafely()
        thread.join(2000)
        surface.release()
        st.release()
        GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
    }

    private companion object {
        const val OPEN_TIMEOUT_MS = 5_000L
    }
}


/** What a scene card's footer says about the stream's sound, drawn as the state really is. */
enum class SlateAudio { ON, MUTED, NONE }

/**
 * The full-frame scene cards: one Canvas component in u = min(w, h) / 100, so a 1080×1920 card and a
 * 1920×1080 card are the same design. Cards differ by word, glyph and layout, never by hue; the only health hues are
 * the Weak signal amber and the Camera lost orange, on their own cards. A streamer's style ([CardStyle]) can add a
 * background, one of four [CardLayout]s and an accent from [ACCENTS] on small marks, but never touches the tag or the
 * audio footer, which stay where they are on every card.
 *
 * The bitmap is static. The motion that tells viewers the stream isn't frozen (grain, the once-per-loop pulse and the
 * ruler cursor) is added in GL per frame by [SlateDrawer], the same way on the phone and in the server's loop, so a
 * card looks identical whichever side is showing it.
 */
object Slates {
    /** The three layouts. They decide surface and composition; the copy decides the rest. */
    enum class Family {
        /** BRB, Privacy: a big headline lower left on Ink. */
        CHOSEN,
        /** Starting soon, Ending: the chosen layout, inverted to Bone so the start and end of a stream read apart. */
        BOOKEND,
        /** Weak signal, Camera lost, Signal lost: a glyph hero, a short rule, a smaller headline. Nobody chose these. */
        AUTOMATIC,
    }

    /**
     * Every card the stream can show instead of the camera, with its copy (sentence case, no filler). [tag] is the
     * mono label top left; [sub] is the line under the headline.
     */
    enum class Card(val tag: String, val headline: String, val sub: String, val family: Family) {
        STARTING_SOON("STARTING SOON", "Starting soon", "Getting the rig ready. Not long now.", Family.BOOKEND),
        BRB("BRB", "Be right back", "Audio's on. Back in a bit.", Family.CHOSEN),
        PRIVACY("PRIVACY", "Privacy", "Camera and mic off for a moment.", Family.CHOSEN),
        ENDING("ENDING", "That's the stream", "Thanks for watching.", Family.BOOKEND),
        /** The automatic one: the stream can't carry a moving picture right now. The mic keeps going. */
        WEAK_SIGNAL("WEAK SIGNAL · AUTO", "Weak signal", "Picture paused to keep the stream up.", Family.AUTOMATIC),
        CAMERA_LOST("CAMERA LOST · AUTO", "Camera lost", "Reconnecting the camera.", Family.AUTOMATIC),
        /**
         * What the server loops while the phone's stream is gone (encoded once per stream by [SlateEncoder]). It used
         * to be the BRB card, which told viewers the streamer chose to step away when the signal had in fact dropped.
         */
        SIGNAL_LOST("SIGNAL LOST · AUTO", "Signal lost", "The stream picks up when the connection's back.", Family.AUTOMATIC),
        ;

        /** The ruler cursor steps on this card. Not on Weak signal (it must stay cheap) or Privacy (it promises stillness). */
        val hasCursor: Boolean get() = this != WEAK_SIGNAL && this != PRIVACY

        /**
         * Grain and pulse on the phone. Not on Weak signal: it's shown because the connection can't carry a picture,
         * so it stays a still card that costs almost nothing to send.
         */
        val moves: Boolean get() = this != WEAK_SIGNAL

        companion object {
            fun of(scene: app.irlsupreme.engine.Scene): Card? = when (scene) {
                app.irlsupreme.engine.Scene.LIVE -> null
                app.irlsupreme.engine.Scene.BRB -> BRB
                app.irlsupreme.engine.Scene.PRIVACY -> PRIVACY
                app.irlsupreme.engine.Scene.STARTING_SOON -> STARTING_SOON
                app.irlsupreme.engine.Scene.ENDING -> ENDING
            }
        }
    }

    /** The words a card actually prints for one audio state. */
    data class Copy(val tag: String, val headline: String, val sub: String, val footer: String)

    @Volatile private var styles: Map<Card, CardStyle> = emptyMap()
    private val styleVersion = java.util.concurrent.atomic.AtomicInteger()

    /**
     * The accent colours a streamer can pick: teal, azure, magenta, olive and steel.
     *
     * Curated, not free, because on stream colour means health: every one sits at least 30° of CIELAB hue
     * and 25 ΔE away from the Live green, the Weak amber, the Camera lost orange, the error red (and their light-theme
     * variants) and from Twitch purple, which is kept for sign-in. All are mid-tones with at least 3:1 against
     * both Ink and Bone, so the same accent shows as a small graphic on the Ink cards and on the Bone bookends
     * (WCAG 1.4.11). An accent is only ever a small mark ([accentOf] says where), never body text.
     */
    val ACCENTS: List<Int> = listOf(
        0xFF0A9396.toInt(), // teal
        0xFF2A86C8.toInt(), // azure
        0xFFD4489C.toInt(), // magenta
        0xFF728F28.toInt(), // olive
        0xFF6E8796.toInt(), // steel
    )

    /**
     * Each of [ACCENTS]' names, in the same order: what TalkBack says and the label beside the swatches. Named with the
     * palette, not worked out from it, so two blues can never end up with the same name.
     */
    val ACCENT_NAMES: List<String> = listOf("Teal", "Azure", "Magenta", "Olive", "Steel")

    /**
     * [style]'s accent if it's one of [ACCENTS], else null (the brand's own). A value from anywhere else (an old
     * setting, a typo) is ignored rather than trusted, so no card can ever pick up a health hue this way.
     *
     * Where an accent shows: the tag's bullet, the rule on the automatic cards (not Weak signal or Camera lost, whose
     * rule is their health colour), the ruler cursor and the caption plate's edge on [CardLayout.FULL_PICTURE].
     */
    fun accentOf(style: CardStyle): Int? = style.accent?.takeIf { it in ACCENTS }

    /** How much Ink lies over [style]'s background: its dim clamped to 0..0.8 (the default when it isn't a number). */
    fun dimOf(style: CardStyle): Float = if (style.dim.isNaN()) DEFAULT_DIM else style.dim.coerceIn(0f, MAX_DIM)

    private const val DEFAULT_DIM = 0.35f
    private const val MAX_DIM = 0.8f

    /** Bumped whenever any card's style changes, so cached card textures know to redraw. */
    val version: Int get() = styleVersion.get()

    /** The streamer's style for [card] (Settings › Scene cards), or the default. */
    fun style(card: Card): CardStyle = styles[card] ?: CardStyle.DEFAULT

    /**
     * Replaces every card's style (one map, swapped whole, so the render thread never sees half an update) and returns
     * the cards whose look changed. Pictures handed in here are only ever read, never recycled: an old one may still
     * be on the render thread mid-draw, and the GC frees it.
     */
    fun setStyles(new: Map<Card, CardStyle>): Set<Card> {
        val old = styles
        val kept = new.filterValues { !it.isDefault }
        styles = kept
        val changed = Card.entries.filter { (old[it] ?: CardStyle.DEFAULT) != (kept[it] ?: CardStyle.DEFAULT) }.toSet()
        if (changed.isNotEmpty()) styleVersion.incrementAndGet()
        return changed
    }

    /**
     * [card]'s copy with [audio] as it really is. Privacy always reads MUTED (it turns the mic off) and Signal lost
     * always NO LIVE AUDIO (the server's loop is silent). BRB only says "Audio's on" when it is: a card never claims
     * sound viewers can't hear.
     */
    fun copy(card: Card, audio: SlateAudio, style: CardStyle = style(card)): Copy {
        val a = shownAudio(card, audio)
        val defaultSub = if (card == Card.BRB && a != SlateAudio.ON) "Back in a bit." else card.sub
        // The tag and the footer are never the streamer's: they say what kind of card this is and what viewers hear.
        return Copy(card.tag, style.headline ?: card.headline, style.sub ?: defaultSub, footerLabel(a))
    }

    /** The audio state a card shows: fixed for Privacy and Signal lost, else the real one. */
    fun shownAudio(card: Card, audio: SlateAudio): SlateAudio = when (card) {
        Card.PRIVACY -> SlateAudio.MUTED
        Card.SIGNAL_LOST -> SlateAudio.NONE
        else -> audio
    }

    fun footerLabel(audio: SlateAudio): String = when (audio) {
        SlateAudio.ON -> "AUDIO ON"
        SlateAudio.MUTED -> "MUTED"
        SlateAudio.NONE -> "NO LIVE AUDIO"
    }

    /** The mark top right: full when the connection is fine, one amber bar on Weak signal, dashed when it's lost. */
    fun markBars(card: Card): IntArray = when (card) {
        Card.WEAK_SIGNAL -> intArrayOf(BrandDraw.BAR_AMBER, BrandDraw.BAR_EMPTY, BrandDraw.BAR_EMPTY)
        Card.SIGNAL_LOST -> intArrayOf(BrandDraw.BAR_DASHED, BrandDraw.BAR_DASHED, BrandDraw.BAR_DASHED)
        else -> intArrayOf(BrandDraw.BAR_FULL, BrandDraw.BAR_FULL, BrandDraw.BAR_FULL)
    }

    // --- Motion: pure, shared by the phone and the server loop -------------------------------------------------------

    /** Film grain amplitude and pulse depth, tuned by eye on a OnePlus 12. */
    const val GRAIN = 0.025f
    const val PULSE = 0.05f
    /** Cursor positions per GOP: 4 divides a 60-frame GOP into steps of 15 frames, so the loop point is invisible. */
    const val CURSOR_STEPS = 4

    /**
     * Which of the [CURSOR_STEPS] positions the cursor is at on [frameIndex] of a [gopFrames]-frame loop. It wraps with
     * the GOP, so a looped GOP (the server) and a running count (the phone) step the same way.
     */
    fun cursorStep(frameIndex: Long, gopFrames: Int): Int {
        val gop = gopFrames.coerceAtLeast(1)
        val i = Math.floorMod(frameIndex, gop.toLong())
        return (i * CURSOR_STEPS / gop).toInt()
    }

    /** Where step [step] sits along the ruler, 0..1: the middle of each quarter (12.5/37.5/62.5/87.5%). */
    fun cursorFraction(step: Int): Float = (step.coerceIn(0, CURSOR_STEPS - 1) + 0.5f) / CURSOR_STEPS

    /** Grain seed for [frameIndex]: fresh every frame, repeating with the GOP so the phone matches the server loop. */
    fun grainSeed(frameIndex: Long, gopFrames: Int): Float =
        (Math.floorMod(frameIndex, gopFrames.coerceAtLeast(1).toLong()) * 0.6180339f) % 1f

    /** Brightness gain for [frameIndex]: one full, gentle pulse per GOP, so the loop point can't be seen. */
    fun pulseGain(frameIndex: Long, gopFrames: Int): Float {
        val gop = gopFrames.coerceAtLeast(1)
        return 1f + PULSE * kotlin.math.sin(2.0 * Math.PI * Math.floorMod(frameIndex, gop.toLong()) / gop).toFloat()
    }

    // --- Geometry (pixels, top-left origin) --------------------------------------------------------------------------

    /** u: a hundredth of the short side. Everything on stream is sized in it. */
    fun unit(width: Int, height: Int): Float = minOf(width, height) / 100f

    /** The ruler's baseline y: 8u above the bottom edge. */
    fun rulerY(width: Int, height: Int): Float = height - 8f * unit(width, height)

    /** The cursor at [step] as (left, top, right, bottom): 1.2u × 3.6u, standing on the ruler's baseline. */
    fun cursorRect(width: Int, height: Int, step: Int): FloatArray {
        val u = unit(width, height)
        val x0 = 8f * u
        val x1 = width - 8f * u
        val cx = x0 + (x1 - x0) * cursorFraction(step)
        val bottom = rulerY(width, height) + RULER_STROKE_U * u / 2
        return floatArrayOf(cx - 0.6f * u, bottom - 3.6f * u, cx + 0.6f * u, bottom)
    }

    /** A headline (or sub line) broken into lines at one size. */
    data class Fit(val size: Float, val lines: List<String>)

    /**
     * Breaks [text] at word boundaries to fit [maxWidth], as large as possible: from [start] down to
     * [floor] it must take at most [lines] lines; failing that it takes up to [overflowLines] at [floor]; and only a
     * text that can't fit even then shrinks below [floor]. [measure] gives a string's width at a size.
     */
    fun fitLines(
        text: String,
        maxWidth: Float,
        start: Float,
        floor: Float,
        measure: (String, Float) -> Float,
        lines: Int = 2,
        overflowLines: Int = 3,
    ): Fit {
        val words = text.split(' ').filter { it.isNotEmpty() }
        fun wrap(size: Float): List<String>? {
            val out = ArrayList<String>()
            var line = ""
            for (w in words) {
                if (measure(w, size) > maxWidth) return null // one word wider than the frame: never fits at this size
                val next = if (line.isEmpty()) w else "$line $w"
                if (line.isEmpty() || measure(next, size) <= maxWidth) line = next else { out += line; line = w }
            }
            if (line.isNotEmpty()) out += line
            return out
        }
        val step = (start - floor).coerceAtLeast(0f) / 20f
        var size = start
        while (true) {
            wrap(size)?.let { if (it.size <= lines) return Fit(size, it) }
            if (step == 0f || size - step < floor - 1e-3f) break
            size -= step
        }
        size = floor
        while (size > floor / 4) {
            wrap(size)?.let { if (it.size <= overflowLines) return Fit(size, it) }
            size *= 0.95f
        }
        return Fit(size, listOf(text))
    }

    // --- Layout planning (pure: sizes in, boxes out) ----------------------------------------------------------------

    /** A rectangle in px, top-left origin. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    /** One line of words, drawn left-aligned from [x] on [baseline]. */
    data class Line(val text: String, val x: Float, val baseline: Float)

    /**
     * Where a card's middle goes for one [CardLayout]: the headline and sub lines, the streamer's [picture] (aspect-fit
     * in its box, or cropped to fill it when [pictureCover]), the automatic cards' glyph [hero] and coloured [rule], and
     * the caption [plate] on [CardLayout.FULL_PICTURE]. The frame (ticks, tag, mark, ruler, footer) isn't in a plan: it
     * never moves, whatever the layout, so viewers always find the card's kind and its sound in the same places.
     */
    data class Plan(
        val headSize: Float,
        val head: List<Line>,
        val subSize: Float,
        val sub: List<Line>,
        val picture: Box? = null,
        val pictureCover: Boolean = false,
        val hero: Box? = null,
        val rule: Box? = null,
        val plate: Box? = null,
    )

    /**
     * What [plan] needs to know about the type, so it can stay pure: the tag's cap height in px, the headline face's
     * cap height as a share of its size, and a width measure for the headline and sub faces (string, size) -> px.
     */
    class Metrics(
        val tagCap: Float,
        val headCapRatio: Float,
        val measureHead: (String, Float) -> Float,
        val measureSub: (String, Float) -> Float,
    )

    /**
     * Lays out a [width]x[height] card of [family] in [layout], with a [picW]x[picH] picture (0 when there's none).
     *
     * Every layout keeps the card grid: words start on the 8u margin (or centre on the frame), stay clear of the tag
     * row and of the ruler's ticks and cursor, and headlines fit with [fitLines] (17u down to 12u on
     * the chosen and bookend cards, 12u on the automatic ones). [CardLayout.CLASSIC] is the original card.
     */
    fun plan(
        width: Int, height: Int, family: Family, layout: CardLayout, headline: String, sub: String, picW: Int, picH: Int, m: Metrics,
    ): Plan {
        val g = Grid(width, height, m.tagCap)
        val auto = family == Family.AUTOMATIC
        val hasPic = picW > 0 && picH > 0
        return when (layout) {
            CardLayout.CLASSIC -> classic(g, auto, headline, sub, picW, picH, m)
            CardLayout.CENTERED -> centered(g, auto, headline, sub, picW, picH, m)
            // With nothing to put beside the words, a chosen card has no "side by side": it keeps the classic layout.
            CardLayout.SIDE_BY_SIDE -> if (!auto && !hasPic) classic(g, auto, headline, sub, 0, 0, m) else sideBySide(g, auto, headline, sub, picW, picH, m)
            CardLayout.FULL_PICTURE -> fullPicture(g, auto, headline, sub, picW, picH, m)
        }
    }

    /** The card's grid in px: the 8u margins, the ruler, and the open area between the tag row and the ruler. */
    private class Grid(val width: Int, val height: Int, tagCap: Float) {
        val u = unit(width, height)
        val left = 8f * u
        val right = width - 8f * u
        val textWidth = right - left
        val ry = rulerY(width, height)
        /** 6u under the tag's caps. */
        val top = 8f * u + tagCap + 6f * u
        /** 8u above the ruler: clear of its 2u ticks and the 3.6u cursor. */
        val bottom = ry - 8f * u
        val area = bottom - top
        val landscape = width >= height
    }

    /** A headline and sub line fitted to one column. */
    private class Words(val head: Fit, val sub: Fit, val headCap: Float) {
        val lead = head.size * 0.92f
        val subLead = sub.size * 1.26f
        /** The first headline baseline to the last sub baseline. */
        val span = (head.lines.size - 1).coerceAtLeast(0) * lead + 0.7f * head.size + (sub.lines.size - 1).coerceAtLeast(0) * subLead
        /** The first headline's cap top to the last sub baseline. */
        val height = headCap + span
    }

    private fun words(headline: String, sub: String, colW: Float, auto: Boolean, u: Float, m: Metrics): Words {
        val head = if (auto) fitLines(headline, colW, 12f * u, 12f * u, m.measureHead) else fitLines(headline, colW, 17f * u, 12f * u, m.measureHead)
        val s = fitLines(sub, colW, 3.8f * u, 3.8f * u, m.measureSub, lines = 2, overflowLines = 2)
        return Words(head, s, m.headCapRatio * head.size)
    }

    /** The automatic cards' rule, its 4u gap and their words, top to last sub baseline. */
    private fun autoHeight(w: Words, u: Float) = AUTO_RULE_H_U * u + 4f * u + w.height

    /** [w]'s lines from [firstBaseline], left-aligned at [x] or centred on [centerX]. */
    private fun place(w: Words, x: Float, firstBaseline: Float, m: Metrics, centerX: Float? = null): Pair<List<Line>, List<Line>> {
        val head = w.head.lines.mapIndexed { i, s ->
            Line(s, if (centerX == null) x else centerX - m.measureHead(s, w.head.size) / 2, firstBaseline + i * w.lead)
        }
        val last = firstBaseline + (w.head.lines.size - 1).coerceAtLeast(0) * w.lead
        val sub = w.sub.lines.mapIndexed { i, s ->
            Line(s, if (centerX == null) x else centerX - m.measureSub(s, w.sub.size) / 2, last + 0.7f * w.head.size + i * w.subLead)
        }
        return head to sub
    }

    /** The automatic cards' rule at [ruleTop] (10u x 0.6u), then the words 4u under it. */
    private fun autoPlan(
        g: Grid, w: Words, x: Float, ruleTop: Float, m: Metrics, centerX: Float? = null, picture: Box? = null, hero: Box? = null,
    ): Plan {
        val u = g.u
        val rx = if (centerX == null) x else centerX - AUTO_RULE_W_U * u / 2
        val rule = Box(rx, ruleTop, rx + AUTO_RULE_W_U * u, ruleTop + AUTO_RULE_H_U * u)
        val (head, sub) = place(w, x, rule.bottom + 4f * u + w.headCap, m, centerX)
        return Plan(w.head.size, head, w.sub.size, sub, picture = picture, hero = hero, rule = rule)
    }

    /** Today's card: the headline lower left (chosen, bookend) or the glyph hero top left (automatic). */
    private fun classic(g: Grid, auto: Boolean, headline: String, sub: String, picW: Int, picH: Int, m: Metrics): Plan {
        val u = g.u
        val w = words(headline, sub, g.textWidth, auto, u, m)
        if (!auto) {
            // The last headline baseline 30u above the ruler; the picture in the open field above, sitting on it.
            val first = g.ry - 30f * u - (w.head.lines.size - 1) * w.lead
            val pic = pictureBox(
                picW, picH, g.left, g.top, first - w.headCap - 5f * u,
                minOf(g.textWidth * PICTURE_MAX_WIDTH, PICTURE_MAX_U * u * 1.6f), PICTURE_MAX_U * u, true, 6f * u,
            )?.let { Box(it[0], it[1], it[2], it[3]) }
            val (head, subLines) = place(w, g.left, first, m)
            return Plan(w.head.size, head, w.sub.size, subLines, picture = pic)
        }
        // A 20u glyph at 18u; the streamer's picture takes its place (2:1 at most), the coloured rule still says which card.
        val hero = HERO_U * u
        val gy = 18f * u
        val pic = pictureBox(picW, picH, g.left, gy, gy + hero, 2f * hero, hero, false, 6f * u)?.let { Box(it[0], it[1], it[2], it[3]) }
        val glyph = if (pic == null) Box(g.left, gy, g.left + hero, gy + hero) else null
        return autoPlan(g, w, g.left, gy + hero + 4f * u, m, picture = pic, hero = glyph)
    }

    /** The words centred on the frame, the picture (or glyph) centred above them, the whole block centred in the open area. */
    private fun centered(g: Grid, auto: Boolean, headline: String, sub: String, picW: Int, picH: Int, m: Metrics): Plan {
        val u = g.u
        val cx = g.width / 2f
        val w = words(headline, sub, g.textWidth, auto, u, m)
        if (!auto) {
            val gap = 5f * u
            val maxW = minOf(g.textWidth * PICTURE_MAX_WIDTH, PICTURE_MAX_U * u * 1.6f)
            val size = fitSize(picW, picH, maxW, minOf(PICTURE_MAX_U * u, g.area - w.height - gap), 6f * u)
            val above = if (size != null) size[1] + gap else 0f
            val top = g.top + ((g.area - above - w.height) / 2).coerceAtLeast(0f)
            val pic = size?.let { Box(cx - it[0] / 2, top, cx + it[0] / 2, top + it[1]) }
            val (head, subLines) = place(w, g.left, top + above + w.headCap, m, cx)
            return Plan(w.head.size, head, w.sub.size, subLines, picture = pic)
        }
        val hero = HERO_U * u
        val size = fitSize(picW, picH, 2f * hero, hero, 6f * u)
        val heroH = size?.get(1) ?: hero
        val top = g.top + ((g.area - heroH - 4f * u - autoHeight(w, u)) / 2).coerceAtLeast(0f)
        val pic = size?.let { Box(cx - it[0] / 2, top, cx + it[0] / 2, top + it[1]) }
        val glyph = if (pic == null) Box(cx - hero / 2, top, cx + hero / 2, top + hero) else null
        return autoPlan(g, w, g.left, top + heroH + 4f * u, m, centerX = cx, picture = pic, hero = glyph)
    }

    /**
     * Landscape: the picture (or a large glyph) on the left margin, the words beside it, each centred in the open area.
     * Portrait has no room for two columns, so the sides become a stack: the picture over the words, the pair centred.
     */
    private fun sideBySide(g: Grid, auto: Boolean, headline: String, sub: String, picW: Int, picH: Int, m: Metrics): Plan {
        val u = g.u
        val gap = 6f * u
        val hasPic = picW > 0 && picH > 0
        if (g.landscape) {
            val colMax = (g.textWidth - gap) * SIDE_PICTURE_SHARE
            val size = if (hasPic) fitSize(picW, picH, colMax, g.area, 6f * u) else null
            val glyph = if (size == null && auto) minOf(SIDE_HERO_U * u, colMax, g.area) else 0f
            if (size == null && glyph <= 0f) return classic(g, auto, headline, sub, 0, 0, m)
            val vw = size?.get(0) ?: glyph
            val vh = size?.get(1) ?: glyph
            val vTop = g.top + (g.area - vh) / 2
            val visual = Box(g.left, vTop, g.left + vw, vTop + vh)
            val wx = visual.right + gap
            val w = words(headline, sub, g.right - wx, auto, u, m)
            val blockH = if (auto) autoHeight(w, u) else w.height
            val top = g.top + ((g.area - blockH) / 2).coerceAtLeast(0f)
            if (auto) return autoPlan(g, w, wx, top, m, picture = visual.takeIf { size != null }, hero = visual.takeIf { size == null })
            val (head, subLines) = place(w, wx, top + w.headCap, m)
            return Plan(w.head.size, head, w.sub.size, subLines, picture = visual)
        }
        val w = words(headline, sub, g.textWidth, auto, u, m)
        val blockH = if (auto) autoHeight(w, u) else w.height
        val avail = minOf(g.area - blockH - gap, g.area * STACK_PICTURE_SHARE)
        val size = if (hasPic) fitSize(picW, picH, g.textWidth, avail, 6f * u) else null
        val glyph = if (size == null && auto) minOf(STACK_HERO_U * u, avail) else 0f
        if (size == null && glyph < 6f * u) return classic(g, auto, headline, sub, if (auto) picW else 0, if (auto) picH else 0, m)
        val vw = size?.get(0) ?: glyph
        val vh = size?.get(1) ?: glyph
        val top = g.top + ((g.area - vh - gap - blockH) / 2).coerceAtLeast(0f)
        val visual = Box(g.left, top, g.left + vw, top + vh)
        val wordsTop = visual.bottom + gap
        if (auto) return autoPlan(g, w, g.left, wordsTop, m, picture = visual.takeIf { size != null }, hero = visual.takeIf { size == null })
        val (head, subLines) = place(w, g.left, wordsTop + w.headCap, m)
        return Plan(w.head.size, head, w.sub.size, subLines, picture = visual)
    }

    /**
     * The picture fills the frame; the words sit on an Ink caption plate on the lower left, its edge at the 4u
     * title-safe line so the words inside still start on the 8u margin. Without a picture the plate sits on the field
     * or background, and an automatic card keeps its glyph top left so it still says what it is.
     */
    private fun fullPicture(g: Grid, auto: Boolean, headline: String, sub: String, picW: Int, picH: Int, m: Metrics): Plan {
        val u = g.u
        val hasPic = picW > 0 && picH > 0
        val colW = if (g.landscape) minOf(g.textWidth, g.width * PLATE_MAX_SHARE) else g.textWidth
        val w = words(headline, sub, colW, auto, u, m)
        val plateBottom = g.ry - 6f * u
        val first = plateBottom - PLATE_FOOT_U * u - w.span
        val textTop = first - w.headCap
        val ruleTop = textTop - 4f * u - AUTO_RULE_H_U * u
        val plateTop = maxOf((if (auto) ruleTop else textTop) - PLATE_PAD_U * u, g.top)
        val (head, subLines) = place(w, g.left, first, m)
        var widest = if (auto) AUTO_RULE_W_U * u else 0f
        for (l in head) widest = maxOf(widest, m.measureHead(l.text, w.head.size))
        for (l in subLines) widest = maxOf(widest, m.measureSub(l.text, w.sub.size))
        val plate = Box(4f * u, plateTop, minOf(g.left + widest + PLATE_PAD_U * u, g.width - 4f * u), plateBottom)
        val picture = if (hasPic) Box(0f, 0f, g.width.toFloat(), g.height.toFloat()) else null
        val hero = HERO_U * u
        val glyph = if (auto && !hasPic) Box(g.left, 18f * u, g.left + hero, 18f * u + hero) else null
        val rule = if (auto) Box(g.left, ruleTop, g.left + AUTO_RULE_W_U * u, ruleTop + AUTO_RULE_H_U * u) else null
        return Plan(w.head.size, head, w.sub.size, subLines, picture = picture, pictureCover = hasPic, hero = glyph, rule = rule, plate = plate)
    }

    /** A [w]x[h] picture scaled to fit [maxW]x[maxH], keeping its shape, as (width, height); null when [maxH] < [minHeight]. */
    fun fitSize(w: Int, h: Int, maxW: Float, maxH: Float, minHeight: Float): FloatArray? {
        if (w <= 0 || h <= 0 || maxW <= 0f || maxH < minHeight) return null
        val s = minOf(maxW / w, maxH / h)
        return floatArrayOf(w * s, h * s)
    }

    /**
     * The part of a [srcW]x[srcH] image that covers a [dstW]x[dstH] frame with nothing stretched: the frame's shape,
     * centred, as large as the image allows (source px). Null for an empty image or frame.
     */
    fun coverCrop(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Box? {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return null
        val s = maxOf(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
        val cw = dstW / s
        val ch = dstH / s
        val l = (srcW - cw) / 2
        val t = (srcH - ch) / 2
        return Box(l, t, l + cw, t + ch)
    }

    /**
     * The size a GIF's first frame is decoded at: never upscaled, and at most [MAX_BACKGROUND_PIXELS] (one 1080p frame),
     * whatever the file says, so a huge GIF can't blow the memory budget. Drawing then scales it to cover the card.
     */
    fun backgroundDecodeSize(w: Int, h: Int): IntArray {
        if (w <= 0 || h <= 0) return intArrayOf(1, 1)
        val s = minOf(1.0, kotlin.math.sqrt(MAX_BACKGROUND_PIXELS.toDouble() / (w.toDouble() * h)))
        return intArrayOf((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
    }

    /** The automatic cards' glyph, in u; the larger one beside the words (landscape) and over them (portrait). */
    private const val HERO_U = 20f
    private const val SIDE_HERO_U = 36f
    private const val STACK_HERO_U = 28f
    /** The automatic cards' rule, in u. */
    private const val AUTO_RULE_W_U = 10f
    private const val AUTO_RULE_H_U = 0.6f
    /** Side by side: the picture's largest share of the width (landscape) or of the open area's height (portrait). */
    private const val SIDE_PICTURE_SHARE = 0.5f
    private const val STACK_PICTURE_SHARE = 0.6f
    /** The caption plate: at most this share of a landscape frame's width; its padding and the room under the last line, in u. */
    private const val PLATE_MAX_SHARE = 0.6f
    private const val PLATE_PAD_U = 4f
    private const val PLATE_FOOT_U = 3.6f
    /** The largest GIF frame kept in memory: 1920 x 1080 pixels (8.3 MB as ARGB). */
    const val MAX_BACKGROUND_PIXELS = 1920 * 1080

    // --- Backgrounds ------------------------------------------------------------------------------------------------

    /**
     * First frames of background GIFs, decoded once and kept by file, modification time and length (so a replaced file
     * is decoded again, and a half-written one that failed isn't stuck as "unreadable"). At most two entries of at most
     * [MAX_BACKGROUND_PIXELS] each: about 16.6 MB worst case, enough for the server's two cards to have different GIFs.
     * A file that fails to decode is remembered as null, so it isn't retried on every redraw. Evicted frames are never
     * recycled: another thread may be mid-draw with one, and the GC frees it.
     */
    private object GifFrames {
        private const val MAX_ENTRIES = 2
        private val frames = object : LinkedHashMap<String, Bitmap?>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>?) = size > MAX_ENTRIES
        }

        fun get(file: java.io.File): Bitmap? {
            val key = "${file.absolutePath}|${file.lastModified()}|${file.length()}"
            synchronized(frames) { if (frames.containsKey(key)) return frames[key] }
            // Decoded outside the lock, so a slow file never holds up a card drawn on another thread.
            val bmp = try {
                android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(file)) { d, info, _ ->
                    val size = backgroundDecodeSize(info.size.width, info.size.height)
                    d.setTargetSize(size[0], size[1])
                    d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE // drawn on a software Canvas
                }
            } catch (e: Exception) {
                Log.w(TAG, "card background GIF unreadable; drawing the card without it: ${e.javaClass.simpleName}")
                null
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "card background GIF too large; drawing the card without it")
                null
            }
            synchronized(frames) { frames[key] = bmp }
            return bmp
        }
    }

    private fun usable(b: Bitmap?): Boolean = b != null && !b.isRecycled && b.width > 0 && b.height > 0

    /**
     * What a whole card ([CardLayer.FULL]) draws behind itself: the still [CardStyle.background], else the first frame of
     * [CardStyle.backgroundGif] (how the server's Signal lost and Weak signal loops, and any card the compositor can't
     * animate, show a GIF), else nothing. May decode a GIF the first time: see [prepareBackgrounds].
     */
    private fun backgroundFor(style: CardStyle): Bitmap? =
        style.background?.takeIf { usable(it) } ?: style.backgroundGif?.let { GifFrames.get(it) }?.takeIf { usable(it) }

    /**
     * Decodes the first frame of every card's background GIF now, so drawing a card later (on the render thread, or the
     * slate encoder's) finds it ready and never waits on a file. Blocking: call off the main thread after [setStyles].
     */
    fun prepareBackgrounds() {
        for (s in styles.values) if (!usable(s.background)) s.backgroundGif?.let { GifFrames.get(it) }
    }

    /**
     * Whether [style] puts imagery behind the words on [layer]: a background (still or a GIF's first frame), the
     * compositor's animated background under [CardLayer.FOREGROUND], or a picture filling the frame. Over imagery a card
     * uses the Ink card's colours (a Bone bookend's Ink words would vanish into a dimmed photo) and sets its tag, mark
     * and footer on small Ink plates, so what kind of card it is and what viewers hear can always be read.
     */
    fun showsImagery(style: CardStyle, layer: CardLayer = CardLayer.FULL): Boolean =
        layer == CardLayer.FOREGROUND || (style.layout == CardLayout.FULL_PICTURE && usable(style.picture)) ||
            (layer == CardLayer.FULL && backgroundFor(style) != null)

    /**
     * The ruler cursor's colour on [card]: the accent if one is set, else Ink on a Bone bookend and Bone everywhere
     * else. [SlateDrawer] draws the cursor per frame in GL, so it should ask once when the card's texture is made, not
     * per frame.
     */
    fun cursorColor(card: Card, style: CardStyle = style(card), layer: CardLayer = CardLayer.FULL): Int =
        cursorColor(card.family == Family.BOOKEND && !showsImagery(style, layer), accentOf(style))

    internal fun cursorColor(onBone: Boolean, accent: Int?): Int = accent ?: if (onBone) StreamPalette.INK else StreamPalette.BONE

    // --- Drawing ----------------------------------------------------------------------------------------------------

    /**
     * [card] as a [width]x[height] bitmap. [channel] is the footer's login; [audio] the real audio state (see [copy]);
     * [cursorStep] 0..3 draws the ruler cursor at that step, -1 none (the GL draws it per frame, see [SlateDrawer]).
     * [layer] [CardLayer.FOREGROUND] leaves out the field and background (transparent there) for the compositor to lay
     * over an animated background; the compositor dims the GIF itself, so the words read the same over it as over a still.
     * Uses [BrandType]'s faces; call [BrandType.load] first.
     */
    fun bitmap(
        width: Int, height: Int, card: Card, channel: String, audio: SlateAudio, cursorStep: Int = -1, style: CardStyle = style(card),
        layer: CardLayer = CardLayer.FULL,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), width, height, card, channel, audio, cursorStep, style, layer)
        return bmp
    }

    /** Draws [card] into [c] (see [bitmap]). */
    fun draw(
        c: Canvas, width: Int, height: Int, card: Card, channel: String, audio: SlateAudio, cursorStep: Int = -1, style: CardStyle = style(card),
        layer: CardLayer = CardLayer.FULL,
    ) {
        val u = unit(width, height)
        val background = if (layer == CardLayer.FULL) backgroundFor(style) else null
        val picture = style.picture?.takeIf { usable(it) }
        val fullPicture = style.layout == CardLayout.FULL_PICTURE && picture != null
        val overImagery = layer == CardLayer.FOREGROUND || background != null || fullPicture
        // A bookend is Bone only on its own field: over imagery it takes the Ink card's colours so its words stay legible.
        val bookend = card.family == Family.BOOKEND && !overImagery
        val ink = StreamPalette.INK
        val field = if (bookend) StreamPalette.BONE else ink
        val primary = if (bookend) ink else StreamPalette.BONE
        val secondary = if (bookend) StreamPalette.withAlpha(ink, 0.6f) else StreamPalette.TEXT2
        val tick = if (bookend) StreamPalette.withAlpha(ink, StreamPalette.BOOKEND_TICK_ALPHA) else StreamPalette.TICK
        val accent = accentOf(style)
        val words = copy(card, audio, style)
        val left = 8f * u
        val right = width - 8f * u
        val textWidth = right - left
        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // The field, then the background cropped to fill and Ink over it at the streamer's dim. FOREGROUND skips the
        // field, the background and the dim: the compositor draws the GIF and mixes Ink into it at the same dim in one
        // pass (LayeredCardDrawer), so dimming here too would darken it twice.
        if (layer == CardLayer.FULL) c.drawColor(field)
        background?.let { drawCover(c, it, width, height, bitmapPaint) }
        if (background != null) c.drawColor(StreamPalette.withAlpha(ink, dimOf(style)))
        if (fullPicture) drawCover(c, picture!!, width, height, bitmapPaint)
        BrandDraw.regTicks(c, width, height, u, tick)

        // Tag top left: a square bullet (hollow for Privacy: nothing is coming through), then the mono label.
        val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = BrandType.mono500; textSize = 2.4f * u; letterSpacing = 0.12f; color = primary
        }
        val capH = capHeight(mono)
        val bullet = 1.2f * u
        val by = 8f * u + (capH - bullet) / 2
        val chipPad = 1.2f * u
        if (overImagery) {
            chip(c, left - chipPad, 8f * u - chipPad, left + bullet + 1.2f * u + mono.measureText(words.tag) + chipPad, 8f * u + capH + chipPad, u)
        }
        val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent ?: primary }
        if (card == Card.PRIVACY) {
            val sw = 0.25f * u
            bp.style = Paint.Style.STROKE; bp.strokeWidth = sw
            c.drawRect(left + sw / 2, by + sw / 2, left + bullet - sw / 2, by + bullet - sw / 2, bp)
        } else {
            c.drawRect(left, by, left + bullet, by + bullet, bp)
        }
        c.drawText(words.tag, left + bullet + 1.2f * u, 8f * u + capH, mono)

        // The mark top right, 6u, its beam level with the tag's top and its right edge on the 8u margin.
        val ms = 6f * u / 24f
        if (overImagery) chip(c, right - 15.5f * ms - chipPad, 8f * u - chipPad, right + chipPad, 8f * u + 16f * ms + chipPad, u)
        BrandDraw.mark(c, right - 19.5f * ms, 8f * u - 4f * ms, 6f * u, markBars(card), primary)

        // The ruler: a hairline baseline with minor ticks every 2u and major every 10u, standing up from it.
        val ry = rulerY(width, height)
        val rp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tick }
        val half = RULER_STROKE_U * u / 2
        c.drawRect(left, ry - half, right, ry + half, rp)
        var k = 0
        while (left + k * 2f * u <= right + 0.01f * u) {
            val x = left + k * 2f * u
            val tall = if (k % 5 == 0) 2f * u else 1f * u
            c.drawRect(x - half, ry - tall, x + half, ry + half, rp)
            k++
        }
        if (cursorStep >= 0 && card.hasCursor) {
            val r = cursorRect(width, height, cursorStep)
            c.drawRect(r[0], r[1], r[2], r[3], Paint().apply { color = cursorColor(bookend, accent) })
        }

        // Footer 4u below the ruler: the channel left, the audio state right.
        mono.color = secondary
        val fy = ry + 4f * u
        val audioW = mono.measureText(words.footer)
        val login = ellipsize(channel.uppercase(java.util.Locale.ROOT), textWidth - audioW - 4f * u - (if (overImagery) 2f * chipPad else 0f), mono)
        if (overImagery) {
            chip(c, right - audioW - chipPad, fy - capH - chipPad, right + chipPad, fy + chipPad, u)
            if (login.isNotEmpty()) chip(c, left - chipPad, fy - capH - chipPad, left + mono.measureText(login) + chipPad, fy + chipPad, u)
        }
        c.drawText(words.footer, right - audioW, fy, mono)
        if (login.isNotEmpty()) c.drawText(login, left, fy, mono)

        // The middle: words, picture and glyph where the layout puts them.
        val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = BrandType.xc800; color = primary; letterSpacing = -0.01f }
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = BrandType.sc500; color = secondary }
        fun measurer(p: Paint): (String, Float) -> Float = { s, size -> p.textSize = size; p.measureText(s) }
        head.textSize = CAP_PROBE
        val metrics = Metrics(capH, capHeight(head) / CAP_PROBE, measurer(head), measurer(sub))
        val plan = plan(
            width, height, card.family, style.layout, words.headline, words.sub, picture?.width ?: 0, picture?.height ?: 0, metrics,
        )
        plan.plate?.let { p ->
            val r = android.graphics.RectF(p.left, p.top, p.right, p.bottom)
            val rr = 0.5f * u
            c.drawRoundRect(r, rr, rr, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = StreamPalette.withAlpha(ink, StreamPalette.ALERT_PLATE_ALPHA) })
            // The edge: a 0.6u bar down the plate's left side, like a chat line's tick.
            val save = c.save()
            c.clipPath(Path().apply { addRoundRect(r, rr, rr, Path.Direction.CW) })
            c.drawRect(p.left, p.top, p.left + 0.6f * u, p.bottom, Paint().apply { color = accent ?: StreamPalette.BONE })
            c.restoreToCount(save)
        }
        if (picture != null && !plan.pictureCover) plan.picture?.let { drawPictureIn(c, picture, it, u, bitmapPaint) }
        plan.hero?.let { drawHero(c, card, it.left, it.top, it.width, it.width / HERO_U) }
        plan.rule?.let { r ->
            val color = when (card) {
                Card.WEAK_SIGNAL -> StreamPalette.WEAK
                Card.CAMERA_LOST -> StreamPalette.CAMERA_LOST
                else -> accent ?: if (plan.plate != null) StreamPalette.BONE else primary
            }
            c.drawRect(r.left, r.top, r.right, r.bottom, Paint().apply { this.color = color })
        }
        // Words on the plate are always Bone on Ink, whatever the card's surface.
        head.color = if (plan.plate != null) StreamPalette.BONE else primary
        head.textSize = plan.headSize
        for (l in plan.head) c.drawText(l.text, l.x, l.baseline, head)
        // A free-standing sub line over imagery is Bone, not text2: it's small, and a dimmed photo can be mid-grey.
        sub.color = when {
            plan.plate != null -> StreamPalette.TEXT2
            overImagery -> StreamPalette.BONE
            else -> secondary
        }
        sub.textSize = plan.subSize
        for (l in plan.sub) c.drawText(l.text, l.x, l.baseline, sub)
    }

    /** The size the headline face is measured at to find its cap height as a share of the size. */
    private const val CAP_PROBE = 200f

    /** Small Ink plates (the over-video scrim, rS) behind the frame's words when imagery is behind them. */
    private fun chip(c: Canvas, l: Float, t: Float, r: Float, b: Float, u: Float) {
        val rr = 0.5f * u
        c.drawRoundRect(l, t, r, b, rr, rr, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = StreamPalette.withAlpha(StreamPalette.INK, StreamPalette.CHAT_PLATE_ALPHA) })
    }

    /** [img] cropped to cover the whole [w]x[h] frame (see [coverCrop]). */
    private fun drawCover(c: Canvas, img: Bitmap, w: Int, h: Int, paint: Paint) {
        val s = coverCrop(img.width, img.height, w, h) ?: return
        val src = Rect(s.left.toInt(), s.top.toInt(), Math.round(s.right).coerceAtMost(img.width), Math.round(s.bottom).coerceAtMost(img.height))
        c.drawBitmap(img, src, android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()), paint)
    }

    /** [pic] into [box] (already its shape), with the brand's small (rS) corners. */
    private fun drawPictureIn(c: Canvas, pic: Bitmap, box: Box, u: Float, paint: Paint) {
        val r = android.graphics.RectF(box.left, box.top, box.right, box.bottom)
        val save = c.save()
        c.clipPath(Path().apply { addRoundRect(r, 0.6f * u, 0.6f * u, Path.Direction.CW) })
        c.drawBitmap(pic, null, r, paint)
        c.restoreToCount(save)
    }

    /** A picture's share of the text width, and its tallest, in u, on the BRB / Privacy / Starting soon / Ending cards. */
    private const val PICTURE_MAX_WIDTH = 0.7f
    private const val PICTURE_MAX_U = 40f

    /**
     * Where a [w]x[h] picture goes (left, top, right, bottom in px), or null when the band can't fit [minHeight]. Pure,
     * for tests.
     */
    fun pictureBox(
        w: Int, h: Int, x: Float, top: Float, bottom: Float, maxW: Float, maxH: Float, bottomAlign: Boolean, minHeight: Float,
    ): FloatArray? {
        if (w <= 0 || h <= 0) return null
        val bandH = minOf(bottom - top, maxH)
        if (bandH < minHeight || maxW <= 0f) return null
        val scale = minOf(maxW / w, bandH / h)
        val dw = w * scale
        val dh = h * scale
        val y = if (bottomAlign) bottom - dh else top
        return floatArrayOf(x, y, x + dw, y + dh)
    }

    /**
     * The automatic cards' hero glyph in a [g]-px box at ([x], [y]). [u] sizes its strokes: the card's u for the 20u
     * glyph, scaled with the glyph when a layout draws it larger, so a big glyph doesn't turn spindly.
     */
    private fun drawHero(c: Canvas, card: Card, x: Float, y: Float, g: Float, u: Float) {
        val s = g / 20f
        when (card) {
            Card.CAMERA_LOST -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = 1.2f * u; color = StreamPalette.CAMERA_LOST
                    strokeCap = Paint.Cap.SQUARE; strokeJoin = Paint.Join.MITER
                }
                c.drawRect(x + 1f * s, y + 5f * s, x + 13f * s, y + 16f * s, p)
                val lens = Path().apply {
                    moveTo(x + 13f * s, y + 8.5f * s); lineTo(x + 19f * s, y + 5.5f * s)
                    lineTo(x + 19f * s, y + 15.5f * s); lineTo(x + 13f * s, y + 12.5f * s)
                }
                c.drawPath(lens, p)
                c.drawLine(x + 1f * s, y + 1f * s, x + 19f * s, y + 19f * s, p)
            }
            else -> {
                // Three rising bars. Weak signal: the first lit amber, the rest hollow (1 of 3). Signal lost: all three
                // dashed and empty, achromatic.
                val bw = 4f * s
                val gap = 2.5f * s
                val heights = floatArrayOf(0.4f, 0.7f, 1f)
                val stroke = 0.6f * u
                val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = stroke; color = StreamPalette.TEXT2
                    strokeJoin = Paint.Join.MITER
                    if (card == Card.SIGNAL_LOST) pathEffect = DashPathEffect(floatArrayOf(1.2f * u, 0.8f * u), 0f)
                }
                val amber = Paint().apply { color = StreamPalette.WEAK }
                for (i in 0 until 3) {
                    val l = x + i * (bw + gap)
                    val t = y + g * (1f - heights[i])
                    if (card == Card.WEAK_SIGNAL && i == 0) {
                        c.drawRect(l, t, l + bw, y + g, amber)
                    } else {
                        c.drawRect(l + stroke / 2, t + stroke / 2, l + bw - stroke / 2, y + g - stroke / 2, outline)
                    }
                }
            }
        }
    }

    private fun capHeight(p: Paint): Float {
        val b = Rect()
        p.getTextBounds("H", 0, 1, b)
        return b.height().toFloat()
    }

    /** [s] cut with an ellipsis to fit [max] px (empty when nothing fits). */
    private fun ellipsize(s: String, max: Float, p: Paint): String {
        if (s.isEmpty() || p.measureText(s) <= max) return s
        var n = s.length
        while (n > 0 && p.measureText(s.substring(0, n) + "…") > max) n--
        return if (n == 0) "" else s.substring(0, n) + "…"
    }

    /** The ruler's baseline and tick stroke, in u. */
    private const val RULER_STROKE_U = 0.25f

    /**
     * Render thread: [card] uploaded as a GL texture (the bitmap is recycled). [layer] as in [bitmap]: the server's
     * slates and any card without a moving background take [CardLayer.FULL].
     */
    fun texture(
        width: Int, height: Int, card: Card, channel: String = "", audio: SlateAudio = SlateAudio.NONE, layer: CardLayer = CardLayer.FULL,
    ): Int {
        val bmp = bitmap(width, height, card, channel, audio, style = style(card), layer = layer)
        return GlUtil.textureFromBitmap(bmp).also { bmp.recycle() }
    }
}

/**
 * The streamer's own headline, line under it and picture for one card (Settings › Scene cards); null keeps the default.
 * The tag ("SIGNAL LOST · AUTO") and the audio footer are never customised: they are how viewers know an automatic card
 * is automatic and what they can hear.
 */
data class CardStyle(
    val headline: String? = null,
    val sub: String? = null,
    val picture: Bitmap? = null,
    /**
     * A still image behind the card, cropped to fill the frame (never stretched) under Ink at
     * [dim]. Only read, never recycled, like [picture].
     */
    val background: Bitmap? = null,
    /**
     * An animated GIF behind the card. The phone-drawn cards loop it (the compositor draws it,
     * then the card's [CardLayer.FOREGROUND] on top); the server's cards (Signal lost, Weak signal) use its first frame.
     */
    val backgroundGif: java.io.File? = null,
    /**
     * How much Ink lies over a background (0..0.8, see [Slates.dimOf]) so the words stay readable. Not part of
     * [isDefault]: without a background there's nothing to dim. A [CardLayout.FULL_PICTURE] picture isn't dimmed (its
     * words sit on a plate).
     */
    val dim: Float = 0.35f,
    /** Where the words and picture sit. */
    val layout: CardLayout = CardLayout.CLASSIC,
    /** One of [Slates.ACCENTS], or null for the brand's own; anything else is ignored ([Slates.accentOf]). */
    val accent: Int? = null,
    /**
     * An https page shown full-frame in place of the card, for total control (a StreamElements
     * scene, your own HTML). Phone-drawn cards only; ignored on Signal lost and Weak signal, which the server loops.
     */
    val webUrl: String? = null,
) {
    val isDefault: Boolean
        get() = headline == null && sub == null && picture == null && background == null && backgroundGif == null &&
            layout == CardLayout.CLASSIC && accent == null && webUrl == null

    companion object {
        val DEFAULT = CardStyle()

        /** Blank or whitespace-only text means "the default", so an emptied field never prints an empty headline. */
        fun of(headline: String?, sub: String?, picture: Bitmap?) =
            CardStyle(headline?.trim()?.ifEmpty { null }, sub?.trim()?.ifEmpty { null }, picture)
    }
}

/**
 * Where a card's words and picture sit ([Slates.plan]). Only the middle moves: the tag top
 * left, the mark top right, and the ruler and audio footer at the bottom stay put on every layout. On the automatic
 * cards the glyph stands in for the picture when there's none.
 */
enum class CardLayout {
    /** The original layout: headline lower left, picture above it (automatic cards: the glyph top left, words under it). */
    CLASSIC,
    /** The words centred on the frame, the picture above them; the block centred between the tag row and the ruler. */
    CENTERED,
    /**
     * Picture on the left margin, words beside it (landscape); stacked picture over words in portrait. A chosen or
     * bookend card with no picture keeps [CLASSIC].
     */
    SIDE_BY_SIDE,
    /**
     * The picture fills the frame, undimmed; the words sit on an Ink caption plate lower left, its edge in the accent.
     * The tag, mark and footer get small Ink plates so they read over any picture.
     */
    FULL_PICTURE,
}

/**
 * Which part of a card [Slates.bitmap] draws. [FULL] is the whole card (its field, background
 * and words); [FOREGROUND] is everything but the field and background, on transparent, for the compositor to lay over
 * an animated background. FOREGROUND leaves out the Ink dim too: the compositor mixes Ink into the GIF at
 * [CardStyle.dim] itself, then draws this layer on top (premultiplied alpha). It uses the Ink card's colours with
 * plates behind the tag, mark and footer, since it always sits over imagery.
 */
enum class CardLayer { FULL, FOREGROUND }
