package app.irlsupreme.engine.gl

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * EGL14 display + GLES3 context (RECORDABLE) owned by exactly one thread. Pass [share] to create a second context
 * on another thread that can sample this one's textures (used by the preview renderer).
 *
 * The EGL display is process-wide: only the compositor's context may terminate it. Any other independent context
 * (the slate encoder) passes [terminateDisplay] = false, or releasing it would pull EGL out from under the live stream.
 */
class EglCore(share: EglCore? = null, private val terminateDisplay: Boolean = share == null) {
    val display: EGLDisplay = share?.display ?: EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    val config: EGLConfig
    val context: EGLContext
    /** 1x1 pbuffer so the context can be current without a window surface. */
    val idleSurface: EGLSurface
    private val ownsDisplay = share == null

    init {
        if (ownsDisplay) {
            val v = IntArray(2)
            check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize failed" }
        }
        config = chooseConfig() ?: error("no RGBA8888 GLES3 recordable EGLConfig")
        context = EGL14.eglCreateContext(
            display, config, share?.context ?: EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed ${lastError()}" }
        idleSurface = EGL14.eglCreatePbufferSurface(
            display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        makeCurrent(idleSurface)
    }

    private fun chooseConfig(): EGLConfig? {
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        return if (EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0) && n[0] > 0) configs[0] else null
    }

    fun createWindowSurface(surface: Any): EGLSurface {
        val s = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(s != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed ${lastError()}" }
        return s
    }

    fun makeCurrent(s: EGLSurface): Boolean = EGL14.eglMakeCurrent(display, s, s, context)
    fun swap(s: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, s)
    fun setPresentationTime(s: EGLSurface, ns: Long): Boolean = EGLExt.eglPresentationTimeANDROID(display, s, ns)

    fun surfaceSize(s: EGLSurface): Pair<Int, Int> {
        val w = IntArray(1); val h = IntArray(1)
        EGL14.eglQuerySurface(display, s, EGL14.EGL_WIDTH, w, 0)
        EGL14.eglQuerySurface(display, s, EGL14.EGL_HEIGHT, h, 0)
        return w[0] to h[0]
    }

    fun releaseSurface(s: EGLSurface) {
        if (s == EGL14.EGL_NO_SURFACE) return
        EGL14.eglMakeCurrent(display, idleSurface, idleSurface, context)
        EGL14.eglDestroySurface(display, s)
    }

    fun lastError(): String = "0x" + Integer.toHexString(EGL14.eglGetError())

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, idleSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        if (ownsDisplay && terminateDisplay) EGL14.eglTerminate(display)
    }
}

object GlUtil {
    val IDENTITY = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    /** Flips V so a Bitmap uploaded with GLUtils.texImage2D appears upright. */
    val FLIP_V = FloatArray(16).also {
        Matrix.setIdentityM(it, 0); Matrix.translateM(it, 0, 0f, 1f, 0f); Matrix.scaleM(it, 0, 1f, -1f, 1f)
    }

    private val QUAD = floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)
    val quad: FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(QUAD); position(0) }

    fun program(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader compile failed: " + GLES30.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program link failed: " + GLES30.glGetProgramInfoLog(p) }
        return p
    }

    fun genTexture(target: Int): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(target, t[0])
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    fun textureFromBitmap(bmp: Bitmap): Int {
        val t = genTexture(GLES30.GL_TEXTURE_2D)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        return t
    }

    /**
     * A pos matrix that puts the unit quad on the rectangle ([left], [top], [right], [bottom]), given in pixels from the
     * top-left of a [width]x[height] frame (bitmap coordinates), in GL's bottom-up clip space. Small layers (the
     * cursor, an alert) move with this instead of being redrawn into a full-frame bitmap.
     */
    fun rectMatrix(left: Float, top: Float, right: Float, bottom: Float, width: Int, height: Int): FloatArray {
        val x0 = 2f * left / width - 1f
        val x1 = 2f * right / width - 1f
        val y0 = 1f - 2f * bottom / height
        val y1 = 1f - 2f * top / height
        return FloatArray(16).also {
            Matrix.setIdentityM(it, 0)
            Matrix.translateM(it, 0, (x0 + x1) / 2f, (y0 + y1) / 2f, 0f)
            Matrix.scaleM(it, 0, (x1 - x0) / 2f, (y1 - y0) / 2f, 1f)
        }
    }

    /** Draws [quad] as a triangle strip through a program's position and texture-coordinate attributes. */
    fun drawQuad(aPos: Int, aTex: Int) {
        quad.position(0)
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 16, quad)
        GLES30.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES30.glVertexAttribPointer(aTex, 2, GLES30.GL_FLOAT, false, 16, quad)
        GLES30.glEnableVertexAttribArray(aTex)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(aPos)
        GLES30.glDisableVertexAttribArray(aTex)
        quad.position(0)
    }
}

/** Offscreen RGBA framebuffer with a sampleable texture. */
class Fbo(val width: Int, val height: Int) {
    val texture: Int = GlUtil.genTexture(GLES30.GL_TEXTURE_2D)
    val fbo: Int

    init {
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        val f = IntArray(1)
        GLES30.glGenFramebuffers(1, f, 0)
        fbo = f[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
        val st = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        check(st == GLES30.GL_FRAMEBUFFER_COMPLETE) { "FBO incomplete 0x${Integer.toHexString(st)}" }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    fun bind() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, width, height)
    }

    fun release() {
        GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
    }
}

private const val VERT = """
attribute vec4 aPos;
attribute vec4 aTex;
uniform mat4 uTexMatrix;
uniform mat4 uPosMatrix;
varying vec2 vTex;
void main() {
    gl_Position = uPosMatrix * aPos;
    vTex = (uTexMatrix * aTex).xy;
}
"""

// The texture coordinate is highp even where colour is mediump: on GPUs with FP16 varyings a mediump coordinate
// has ~11 bits of mantissa, too few to land on the centre of every texel across 1920 pixels, so the 1:1 copy into
// the encoder would sample between texels and soften the picture. ES 3.0 guarantees highp in fragment shaders.
private const val FRAG_2D = """
precision mediump float;
varying highp vec2 vTex;
uniform sampler2D sTex;
uniform float uAlpha;
void main() {
    vec4 c = texture2D(sTex, vTex);
    gl_FragColor = vec4(c.rgb, c.a * uAlpha);
}
"""

private const val FRAG_OES = """#extension GL_OES_EGL_image_external : require
precision mediump float;
varying highp vec2 vTex;
uniform samplerExternalOES sTex;
uniform float uAlpha;
uniform float uTexAlpha;
void main() {
    vec4 c = texture2D(sTex, vTex);
    gl_FragColor = vec4(c.rgb, mix(1.0, c.a, uTexAlpha) * uAlpha);
}
"""

/**
 * The cards' grain noise, shared by [SlateProgram] and [LayeredCardProgram] so a card with an animated background has
 * exactly the grain of the plain card (and of the server's loop).
 */
private const val GRAIN_HASH = """
uniform float uSeed;
uniform float uGrain;
uniform float uGain;
uniform float uAlpha;
float hash(vec2 p) {
    p = fract(p * vec2(0.1031, 0.1030) + uSeed);
    p += dot(p, p.yx + 33.33);
    return fract((p.x + p.y) * p.x);
}
"""

private const val FRAG_SLATE = """
precision highp float;
varying vec2 vTex;
uniform sampler2D sTex;
""" + GRAIN_HASH + """
void main() {
    vec3 c = texture2D(sTex, vTex).rgb * uGain;
    gl_FragColor = vec4(c + (hash(gl_FragCoord.xy) - 0.5) * uGrain, uAlpha);
}
"""

/**
 * Draws a scene card with film grain and a brightness gain: the slates' gentle motion. A still card encodes to
 * almost nothing (CBR doesn't pad simple content) and Twitch rates such a low, uneven bitrate "unstable"; grain
 * keeps the encoder busy at a steady rate, and the slow pulse makes it read as a living screen, not a frozen one.
 *
 * The server's loop ([app.irlsupreme.engine.video.SlateEncoder]) and the phone's compositor both draw cards through
 * this with the same seed and gain per frame, so the two can't drift apart. [draw]'s alpha < 1 blends the card over
 * what's there (the scene crossfade).
 */
class SlateProgram {
    private val program = GlUtil.program(VERT, FRAG_SLATE)
    private val uTexMatrix = GLES30.glGetUniformLocation(program, "uTexMatrix")
    private val uPosMatrix = GLES30.glGetUniformLocation(program, "uPosMatrix")
    private val uSeed = GLES30.glGetUniformLocation(program, "uSeed")
    private val uGrain = GLES30.glGetUniformLocation(program, "uGrain")
    private val uGain = GLES30.glGetUniformLocation(program, "uGain")
    private val uAlpha = GLES30.glGetUniformLocation(program, "uAlpha")
    private val sTex = GLES30.glGetUniformLocation(program, "sTex")
    private val aPos = GLES30.glGetAttribLocation(program, "aPos")
    private val aTex = GLES30.glGetAttribLocation(program, "aTex")

    /** [seed]: a new value each frame for new grain; [grain]: its amplitude (0..1); [gain]: brightness multiplier. */
    fun draw(texture: Int, texMatrix: FloatArray, seed: Float, grain: Float, gain: Float, alpha: Float = 1f) {
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(sTex, 0)
        GLES30.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES30.glUniformMatrix4fv(uPosMatrix, 1, false, GlUtil.IDENTITY, 0)
        GLES30.glUniform1f(uSeed, seed)
        GLES30.glUniform1f(uGrain, grain)
        GLES30.glUniform1f(uGain, gain)
        GLES30.glUniform1f(uAlpha, alpha)
        if (alpha < 1f) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        }
        GlUtil.drawQuad(aPos, aTex)
        GLES30.glDisable(GLES30.GL_BLEND)
    }
}

private const val FRAG_RULE = """
precision highp float;
varying vec2 vTex;
uniform vec2 uSize;
uniform float uSegments;
uniform float uRemaining;
uniform float uGap;
uniform float uLine;
uniform vec4 uColor;
void main() {
    vec2 p = vTex * uSize;
    float segW = (uSize.x - uGap * (uSegments - 1.0)) / uSegments;
    float i = floor(p.x / (segW + uGap));
    float lx = p.x - i * (segW + uGap);
    if (lx > segW || i >= uSegments) discard;
    if (i >= uRemaining) {
        bool edge = lx < uLine || lx > segW - uLine || p.y < uLine || p.y > uSize.y - uLine;
        if (!edge) discard;
    }
    gl_FragColor = uColor;
}
"""

/**
 * An alert's time-left rule: equal segments with gaps, the first `remaining` filled and the rest drawn
 * hollow, so one segment empties per step by changing a uniform instead of redrawing the alert.
 */
class RuleProgram {
    private val program = GlUtil.program(VERT, FRAG_RULE)
    private val uTexMatrix = GLES30.glGetUniformLocation(program, "uTexMatrix")
    private val uPosMatrix = GLES30.glGetUniformLocation(program, "uPosMatrix")
    private val uSize = GLES30.glGetUniformLocation(program, "uSize")
    private val uSegments = GLES30.glGetUniformLocation(program, "uSegments")
    private val uRemaining = GLES30.glGetUniformLocation(program, "uRemaining")
    private val uGap = GLES30.glGetUniformLocation(program, "uGap")
    private val uLine = GLES30.glGetUniformLocation(program, "uLine")
    private val uColor = GLES30.glGetUniformLocation(program, "uColor")
    private val aPos = GLES30.glGetAttribLocation(program, "aPos")
    private val aTex = GLES30.glGetAttribLocation(program, "aTex")

    /**
     * Draws the rule into [posMatrix]'s rectangle, [widthPx]×[heightPx] in the current viewport. [color] is an opaque
     * ARGB int; [gapPx] separates segments; hollow segments are outlined [linePx] thick.
     */
    fun draw(
        posMatrix: FloatArray,
        widthPx: Float,
        heightPx: Float,
        segments: Int,
        remaining: Int,
        gapPx: Float,
        linePx: Float,
        color: Int,
    ) {
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uTexMatrix, 1, false, GlUtil.IDENTITY, 0)
        GLES30.glUniformMatrix4fv(uPosMatrix, 1, false, posMatrix, 0)
        GLES30.glUniform2f(uSize, widthPx, heightPx)
        GLES30.glUniform1f(uSegments, segments.toFloat())
        GLES30.glUniform1f(uRemaining, remaining.toFloat())
        GLES30.glUniform1f(uGap, gapPx)
        GLES30.glUniform1f(uLine, linePx)
        GLES30.glUniform4f(
            uColor, (color shr 16 and 0xff) / 255f, (color shr 8 and 0xff) / 255f, (color and 0xff) / 255f, 1f,
        )
        GlUtil.drawQuad(aPos, aTex)
    }
}

/** Draws a 2D or external-OES texture over the current viewport (optionally transformed and alpha-blended). */
class TextureProgram(private val oes: Boolean) {
    private val program = GlUtil.program(VERT, if (oes) FRAG_OES else FRAG_2D)
    private val uTexMatrix = GLES30.glGetUniformLocation(program, "uTexMatrix")
    private val uPosMatrix = GLES30.glGetUniformLocation(program, "uPosMatrix")
    private val uAlpha = GLES30.glGetUniformLocation(program, "uAlpha")
    private val uTexAlpha = GLES30.glGetUniformLocation(program, "uTexAlpha") // external textures only
    private val sTex = GLES30.glGetUniformLocation(program, "sTex")
    private val aPos = GLES30.glGetAttribLocation(program, "aPos")
    private val aTex = GLES30.glGetAttribLocation(program, "aTex")

    /** [premultiplied]: blend a premultiplied-alpha texture (an Android Bitmap) over what's there, e.g. an overlay. */
    fun draw(
        texture: Int,
        texMatrix: FloatArray = GlUtil.IDENTITY,
        posMatrix: FloatArray = GlUtil.IDENTITY,
        alpha: Float = 1f,
        premultiplied: Boolean = false,
    ) {
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(if (oes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(sTex, 0)
        GLES30.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES30.glUniformMatrix4fv(uPosMatrix, 1, false, posMatrix, 0)
        GLES30.glUniform1f(uAlpha, alpha)
        // An external texture's own alpha is used only when blending it (a web page); camera frames are opaque.
        if (uTexAlpha >= 0) GLES30.glUniform1f(uTexAlpha, if (premultiplied) 1f else 0f)
        if (premultiplied) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        } else if (alpha < 1f) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        }
        GlUtil.drawQuad(aPos, aTex)
        GLES30.glDisable(GLES30.GL_BLEND)
    }
}

private const val VERT_LAYERED = """
attribute vec4 aPos;
attribute vec4 aTex;
uniform mat4 uFgMatrix;
uniform mat4 uBgMatrix;
varying vec2 vFg;
varying vec2 vBg;
void main() {
    gl_Position = aPos;
    vFg = (uFgMatrix * aTex).xy;
    vBg = (uBgMatrix * aTex).xy;
}
"""

/** The body both layered variants share: premultiplied foreground over the background, then gain and grain. */
private const val LAYERED_MAIN = """
void main() {
    vec4 fg = texture2D(sFg, vFg);
    vec3 c = (fg.rgb + background() * (1.0 - fg.a)) * uGain;
    gl_FragColor = vec4(c + (hash(gl_FragCoord.xy) - 0.5) * uGrain, uAlpha);
}
"""

/** An animated background (a GIF frame, cropped by uBgMatrix) dimmed toward Ink, under the card's foreground layer. */
private const val FRAG_LAYERED_2D = """
precision highp float;
varying vec2 vFg;
varying vec2 vBg;
uniform sampler2D sFg;
uniform sampler2D sBg;
uniform vec3 uInk;
uniform float uDim;
""" + GRAIN_HASH + """
vec3 background() { return mix(texture2D(sBg, vBg).rgb, uInk, uDim); }
""" + LAYERED_MAIN

/** A web page (premultiplied, external texture) over a plain Ink field. */
private const val FRAG_LAYERED_OES = """#extension GL_OES_EGL_image_external : require
precision highp float;
varying vec2 vFg;
varying vec2 vBg;
uniform samplerExternalOES sFg;
uniform vec3 uInk;
""" + GRAIN_HASH + """
vec3 background() { return uInk; }
""" + LAYERED_MAIN

/**
 * A scene card built from layers in one pass: a premultiplied foreground over a background, with the cards' gain and
 * grain and a scene-crossfade alpha, so a layered card fades as one picture (two blended draws would let the
 * background show through the words mid-fade).
 *
 * [webPage] false: the foreground is the card's [app.irlsupreme.engine.video.CardLayer.FOREGROUND] bitmap texture and
 * the background a GIF frame texture, dimmed toward Ink by `dim`. [webPage] true: the foreground is a web page's
 * external texture and the background plain Ink (a transparent page shows the brand's field, never black).
 */
class LayeredCardProgram(private val webPage: Boolean) {
    private val program = GlUtil.program(VERT_LAYERED, if (webPage) FRAG_LAYERED_OES else FRAG_LAYERED_2D)
    private val uFgMatrix = GLES30.glGetUniformLocation(program, "uFgMatrix")
    private val uBgMatrix = GLES30.glGetUniformLocation(program, "uBgMatrix")
    private val sFg = GLES30.glGetUniformLocation(program, "sFg")
    private val sBg = GLES30.glGetUniformLocation(program, "sBg") // -1 for a web page
    private val uInk = GLES30.glGetUniformLocation(program, "uInk")
    private val uDim = GLES30.glGetUniformLocation(program, "uDim") // -1 for a web page
    private val uSeed = GLES30.glGetUniformLocation(program, "uSeed")
    private val uGrain = GLES30.glGetUniformLocation(program, "uGrain")
    private val uGain = GLES30.glGetUniformLocation(program, "uGain")
    private val uAlpha = GLES30.glGetUniformLocation(program, "uAlpha")
    private val aPos = GLES30.glGetAttribLocation(program, "aPos")
    private val aTex = GLES30.glGetAttribLocation(program, "aTex")

    /**
     * [fg]/[fgMatrix]: the foreground texture and its texture matrix; [bg]/[bgMatrix]: the background's (ignored for a
     * web page); [ink]: the field colour (ARGB); [dim]: 0..1 of Ink over the background; [seed]/[grain]/[gain] as in
     * [SlateProgram] (grain 0 and gain 1 for none); [alpha] < 1 blends the whole card over what's there.
     */
    fun draw(
        fg: Int, fgMatrix: FloatArray, bg: Int, bgMatrix: FloatArray, ink: Int, dim: Float,
        seed: Float, grain: Float, gain: Float, alpha: Float,
    ) {
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(if (webPage) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES30.GL_TEXTURE_2D, fg)
        GLES30.glUniform1i(sFg, 0)
        if (!webPage) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bg)
            GLES30.glUniform1i(sBg, 1)
            GLES30.glUniform1f(uDim, dim.coerceIn(0f, 1f))
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0) // every other program assumes unit 0
        }
        GLES30.glUniformMatrix4fv(uFgMatrix, 1, false, fgMatrix, 0)
        GLES30.glUniformMatrix4fv(uBgMatrix, 1, false, bgMatrix, 0)
        GLES30.glUniform3f(uInk, (ink shr 16 and 0xff) / 255f, (ink shr 8 and 0xff) / 255f, (ink and 0xff) / 255f)
        GLES30.glUniform1f(uSeed, seed)
        GLES30.glUniform1f(uGrain, grain)
        GLES30.glUniform1f(uGain, gain)
        GLES30.glUniform1f(uAlpha, alpha)
        if (alpha < 1f) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        }
        GlUtil.drawQuad(aPos, aTex)
        GLES30.glDisable(GLES30.GL_BLEND)
    }
}
