package app.irlsupreme.engine.video

import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import app.irlsupreme.engine.gl.EglCore
import app.irlsupreme.engine.gl.TextureProgram
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Draws the composite to the on-screen preview on its OWN thread and EGL context (shared with the compositor).
 *
 * Creating/destroying the preview's window surface on the render thread stalls the stream for
 * ~100 ms at every screen on/off. Here, attach/detach and all window-surface work happen on this thread; the
 * compositor only blits into [frameTexture] when [wantsFrame] is set and hands over a GL fence.
 */
class PreviewRenderer(shareWith: EglCore, val frameTexture: Int, private val frameW: Int, private val frameH: Int) {
    private val thread = HandlerThread("engine-preview").apply { start() }
    private val handler = Handler(thread.looper)
    private lateinit var egl: EglCore
    private lateinit var program: TextureProgram
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var surfaceW = 0
    private var surfaceH = 0
    private val pos = FloatArray(16)

    /** True when the preview can take a new frame (has a surface and finished the previous one). */
    val wantsFrame = AtomicBoolean(false)
    /**
     * False if this thread's EGL setup failed: the preview then stays blank but the stream carries on (an exception
     * on a Handler thread would take the whole app down with it).
     */
    @Volatile private var ready = false

    init {
        handler.post {
            try {
                egl = EglCore(shareWith)
                program = TextureProgram(oes = false)
                ready = true
            } catch (t: Throwable) {
                Log.e("Engine", "preview couldn't start; the stream carries on without it", t)
            }
        }
    }

    fun attach(surface: Surface, width: Int, height: Int) = handler.post {
        if (!ready) return@post
        detachNow()
        try {
            windowSurface = egl.createWindowSurface(surface)
            surfaceW = width; surfaceH = height
            wantsFrame.set(true)
        } catch (e: Exception) {
            Log.w("Engine", "preview attach failed", e)
            windowSurface = EGL14.EGL_NO_SURFACE
        }
    }

    fun resize(width: Int, height: Int) = handler.post { surfaceW = width; surfaceH = height }

    fun detach() = handler.post { detachNow() }

    private fun detachNow() {
        wantsFrame.set(false)
        if (ready && windowSurface != EGL14.EGL_NO_SURFACE) {
            egl.releaseSurface(windowSurface)
            windowSurface = EGL14.EGL_NO_SURFACE
        }
    }

    /** Compositor thread: [frameTexture] holds a new frame, ready once [fence] signals. */
    fun offer(fence: Long) {
        wantsFrame.set(false)
        handler.post {
            if (!ready || windowSurface == EGL14.EGL_NO_SURFACE) {
                GLES30.glDeleteSync(fence)
                return@post
            }
            if (!egl.makeCurrent(windowSurface)) {
                GLES30.glDeleteSync(fence)
                detachNow()
                return@post
            }
            GLES30.glWaitSync(fence, 0, GLES30.GL_TIMEOUT_IGNORED)
            GLES30.glDeleteSync(fence)
            GLES30.glViewport(0, 0, surfaceW, surfaceH)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            letterbox()
            program.draw(frameTexture, posMatrix = pos)
            egl.swap(windowSurface)
            wantsFrame.set(true)
        }
    }

    /** Fit the frame inside the view without stretching. */
    private fun letterbox() {
        Matrix.setIdentityM(pos, 0)
        if (surfaceW <= 0 || surfaceH <= 0) return
        val frame = frameW.toFloat() / frameH
        val view = surfaceW.toFloat() / surfaceH
        if (view > frame) Matrix.scaleM(pos, 0, frame / view, 1f, 1f) else Matrix.scaleM(pos, 0, 1f, view / frame, 1f)
    }

    fun release() {
        handler.post {
            detachNow()
            if (ready) egl.release()
            ready = false
        }
        thread.quitSafely()
        thread.join(2_000)
    }

}
