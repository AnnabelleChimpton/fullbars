package app.irlsupreme.engine.video

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import app.irlsupreme.engine.gl.GlUtil
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A web page drawn into the stream (the web overlay): a StreamElements or Streamlabs overlay URL,
 * like an OBS browser source.
 *
 * The page renders in a WebView inside a [Presentation] on a private virtual display. The display's surface feeds a
 * SurfaceTexture on the render thread, so the page is GPU-rendered, keeps running with the phone's screen off, and
 * the compositor can blend it over the picture. It renders at [width] x [height] pixels (720p, for heat) but lays out
 * at [cssWidth] x [cssHeight] CSS pixels (1080p), the canvas such overlays are designed for.
 *
 * Its sound is blocked (autoplay needs a gesture): from the phone's speaker it would only echo into the mic. If
 * Android kills the page's renderer (memory), the stream carries on without it and the page reloads a few seconds
 * later.
 *
 * Created, updated and released on the render thread (which owns the texture); the WebView lives on the main thread.
 */
class BrowserSource(
    private val ctx: Context,
    val width: Int,
    val height: Int,
    private val cssWidth: Int,
    private val cssHeight: Int,
    private val content: Content,
    /** What the log calls it: "overlay page", or "card page" for a scene card's web page ([CardWebSource]). */
    private val label: String = "overlay page",
) {
    sealed interface Content {
        /** Logged as scheme and host only: a path or query can carry a widget token. */
        data class Url(val url: String) : Content { override fun toString() = "Url(${safeOrigin(url)})" }
        data class Html(val html: String) : Content { override fun toString() = "Html(${html.length} chars)" }
    }

    val texture: Int = GlUtil.genTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
    private val st = SurfaceTexture(texture).apply { setDefaultBufferSize(width, height) }
    private val surface = Surface(st)
    private val fresh = AtomicBoolean(false)
    val texMatrix = FloatArray(16)
    /** A frame has arrived (until then there's nothing to draw). */
    @Volatile var hasFrame = false
        private set
    /** Times the page's renderer died this stream. */
    @Volatile var crashes = 0
        private set
    /** The page has committed its first visible paint ([WebViewClient.onPageCommitVisible]); main thread sets it. */
    @Volatile private var pageVisible = false
    /**
     * A frame has arrived since the page itself became visible, so the texture holds the page and not the blank
     * window before it. A full-frame card waits for this before replacing the normal card.
     */
    @Volatile var pageShown = false
        private set
    /** The page itself couldn't load (network error, HTTP 4xx/5xx on the page, not on one of its parts). */
    @Volatile var loadFailed = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var webView: WebView? = null
    @Volatile private var closed = false

    init {
        st.setOnFrameAvailableListener { fresh.set(true) }
        main.post(::open)
    }

    /** Render thread, each tick: latch the newest page frame, if any. */
    fun update() {
        if (!fresh.getAndSet(false)) return
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        hasFrame = true
        if (pageVisible) pageShown = true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun open() {
        if (closed) return
        loadFailed = false // a reload after a renderer crash gets a fresh chance
        try {
            val dm = ctx.getSystemService(DisplayManager::class.java)
            // The page should lay out at cssWidth CSS px across width real px. WebView adds a scale factor of its own
            // (~1.24 on the OnePlus 12, at any density), so start from the plain formula and correct it once the page
            // reports its real width ([calibrate]); the corrected density is kept for reloads. (Asking Android 14+ for a
            // 30 Hz display saved nothing measurable on the OnePlus 12 and stopped resize() from taking, so it's not used.)
            if (dpi <= 0) dpi = (160f * width / cssWidth).toInt()
            val vd = dm.createVirtualDisplay("fullbars-overlay", width, height, dpi, surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
            display = vd
            val p = Presentation(ctx, vd.display)
            p.window?.apply {
                setFormat(PixelFormat.TRANSLUCENT)
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }
            val wv = WebView(p.context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true // overlays are JavaScript apps
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = true // no alert sounds out of the phone's speaker
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.textZoom = 100 // ignore the phone's font size: the overlay must look as designed
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        main.postDelayed({ calibrate(view) }, 500)
                    }

                    override fun onPageCommitVisible(view: WebView, url: String?) {
                        if (webView === view) pageVisible = true
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (!request.isForMainFrame || webView !== view) return
                        loadFailed = true
                        Log.w(TAG, "$label: couldn't load ${safeOrigin(request.url.toString())} (error ${error.errorCode})")
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (!request.isForMainFrame || webView !== view || response.statusCode < 400) return
                        loadFailed = true
                        Log.w(TAG, "$label: ${safeOrigin(request.url.toString())} answered HTTP ${response.statusCode}")
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.scheme != "https" && request.url.scheme != "http"

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        crashes++
                        Log.w(TAG, "$label: renderer gone (crashed=${detail.didCrash()}); reloading in ${RELOAD_MS / 1000} s")
                        closeViews()
                        hasFrame = false
                        pageVisible = false
                        pageShown = false
                        main.postDelayed(::open, RELOAD_MS)
                        return true // handled: without this Android kills the whole app
                    }
                }
            }
            webView = wv
            p.setContentView(wv)
            p.show()
            presentation = p
            when (content) {
                is Content.Url -> wv.loadUrl(content.url)
                is Content.Html -> wv.loadDataWithBaseURL("https://localhost/", content.html, "text/html", "utf-8", null)
            }
            Log.i(TAG, "$label: opened at ${width}x$height (${cssWidth}x$cssHeight CSS) $content")
        } catch (e: RuntimeException) {
            Log.w(TAG, "$label: couldn't open", e)
            closeViews()
            loadFailed = true
        }
    }

    /** Debug: crash the page's renderer (Android's documented test URL) to check the stream survives it. */
    fun debugCrash() = main.post { webView?.loadUrl("chrome://kill") }

    private var dpi = 0
    private var calibrations = 0

    /** Corrects the display's density until the page's CSS width is [cssWidth] (within 2%); at most 3 tries per page. */
    private fun calibrate(view: WebView) {
        if (closed || webView !== view) return
        view.evaluateJavascript("window.innerWidth") { r ->
            val w = r?.toFloatOrNull() ?: return@evaluateJavascript
            if (w <= 0f) return@evaluateJavascript
            if (kotlin.math.abs(w - cssWidth) <= cssWidth * 0.02f) {
                Log.i(TAG, "$label: lays out at ${w.toInt()} CSS px wide (wanted $cssWidth), ${dpi} dpi")
                return@evaluateJavascript
            }
            if (calibrations++ >= 3) {
                Log.w(TAG, "$label: still ${w.toInt()} CSS px wide after calibrating (wanted $cssWidth)")
                return@evaluateJavascript
            }
            dpi = (dpi * w / cssWidth).toInt().coerceIn(40, 640)
            display?.resize(width, height, dpi)
            main.postDelayed({ calibrate(view) }, 700)
        }
    }

    private fun closeViews() {
        webView?.let { runCatching { it.stopLoading(); it.destroy() } }
        webView = null
        presentation?.let { runCatching { it.dismiss() } }
        presentation = null
        display?.release()
        display = null
    }

    @Volatile private var viewsClosedAtNs = 0L
    private var retiredAtNs = 0L

    /**
     * Render thread, without waiting: asks the main thread to close the page. The surface and texture stay until
     * [freeIfClosed] sees the page closed (so the display never writes into a released surface), which keeps a swap or
     * a thermal hold from stalling the render thread on a busy main thread.
     */
    fun retire() {
        if (closed) return
        closed = true
        retiredAtNs = System.nanoTime()
        main.post { main.removeCallbacksAndMessages(null); closeViews(); viewsClosedAtNs = System.nanoTime() }
    }

    /**
     * Render thread, each tick after [retire]: frees the GL side once the page is closed (or after [FREE_AFTER_NS] if
     * the main thread never got to it). Returns true when freed.
     */
    fun freeIfClosed(nowNs: Long = System.nanoTime()): Boolean {
        if (viewsClosedAtNs == 0L && nowNs - retiredAtNs < FREE_AFTER_NS) return false
        freeGl()
        return true
    }

    /**
     * Render thread, at teardown. Closes the page on the main thread first, so the display stops writing before the
     * surface goes ([wait] = false when the main thread may itself be waiting for the render thread, e.g. while stopping).
     */
    fun release(wait: Boolean = true) {
        closed = true
        val done = CountDownLatch(1)
        main.post { main.removeCallbacksAndMessages(null); closeViews(); done.countDown() }
        if (wait) done.await(2, TimeUnit.SECONDS)
        freeGl()
    }

    private fun freeGl() {
        surface.release()
        st.release()
        GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
    }

    companion object {
        private const val TAG = "Engine"
        private const val RELOAD_MS = 3_000L
        private const val FREE_AFTER_NS = 3_000_000_000L

        /**
         * "https://host" for logs: never the path, query or user info, which can carry a token (StreamElements widget
         * URLs do). Pure, so it can be tested.
         */
        fun safeOrigin(url: String): String {
            val scheme = url.substringBefore("://", "")
            if (scheme.isEmpty() || scheme.any { !it.isLetterOrDigit() && it != '+' && it != '-' && it != '.' }) return "(url)"
            val host = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
            return "$scheme://$host"
        }
    }
}
