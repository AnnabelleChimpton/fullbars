package app.irlsupreme.engine.video

import android.opengl.GLES11Ext
import android.opengl.GLES30
import app.irlsupreme.engine.gl.GlUtil

/**
 * The detail rung's arithmetic, pure so it can be tested. At low bitrates a 1080p encoder spends its bits on detail it
 * can't keep, and the picture breaks into blocks. Drawing only the camera at a lower resolution and scaling it back
 * up gives the encoder a softer, cheaper picture to code, while text overlays, alerts and chat stay sharp (they're
 * drawn afterwards at full resolution). The encoder's size and parameter sets never change, so the server's slate
 * splice is unaffected.
 */
object DetailScale {
    /** The scales offered: full, three quarters, half. */
    val STEPS = floatArrayOf(1f, 0.75f, 0.5f)

    /** [scale] snapped to the nearest offered step (anything odd, NaN included, means full detail). */
    fun snap(scale: Float): Float = when {
        scale.isNaN() || scale > 0.875f -> 1f
        scale > 0.625f -> 0.75f
        else -> 0.5f
    }

    /** The intermediate size for a [width]x[height] output at [scale]: rounded to even, at least 2x2. */
    fun size(width: Int, height: Int, scale: Float): IntArray {
        fun even(v: Float) = ((v / 2f).toInt() * 2).coerceAtLeast(2)
        return intArrayOf(even(width * scale), even(height * scale))
    }

    /**
     * The texture-space offsets for the 4-tap filter: a quarter of one destination pixel along x ([out] 0..1) and
     * along y ([out] 2..3), through [texMatrix] (column-major, direction only). [posScaleX]/[posScaleY]: how much of
     * the destination the quad covers (a letterboxed external camera covers less). With the taps at +-a quarter
     * pixel, a half-scale draw averages exactly the 2x2 source texels under each destination pixel (each tap lands
     * on a texel centre); at three quarters each tap is itself a bilinear blend, a little wider than a box.
     */
    fun tapOffsets(texMatrix: FloatArray, dstW: Int, dstH: Int, posScaleX: Float, posScaleY: Float, out: FloatArray) {
        val dx = 0.25f / (dstW * posScaleX)
        val dy = 0.25f / (dstH * posScaleY)
        out[0] = texMatrix[0] * dx; out[1] = texMatrix[1] * dx
        out[2] = texMatrix[4] * dy; out[3] = texMatrix[5] * dy
    }
}

private const val VERT_DETAIL = """
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

private const val FRAG_DETAIL = """#extension GL_OES_EGL_image_external : require
precision highp float;
varying vec2 vTex;
uniform samplerExternalOES sTex;
uniform vec2 uDx;
uniform vec2 uDy;
void main() {
    vec4 c = texture2D(sTex, vTex + uDx + uDy) + texture2D(sTex, vTex + uDx - uDy)
           + texture2D(sTex, vTex - uDx + uDy) + texture2D(sTex, vTex - uDx - uDy);
    gl_FragColor = vec4(c.rgb * 0.25, 1.0);
}
"""

/**
 * Draws an external-OES camera frame into a smaller framebuffer with a 4-tap box filter (see [DetailScale]), so the
 * downscale doesn't alias. Render thread only; allocates nothing per frame.
 */
internal class DetailDownscaler {
    private val program = GlUtil.program(VERT_DETAIL, FRAG_DETAIL)
    private val uTexMatrix = GLES30.glGetUniformLocation(program, "uTexMatrix")
    private val uPosMatrix = GLES30.glGetUniformLocation(program, "uPosMatrix")
    private val uDx = GLES30.glGetUniformLocation(program, "uDx")
    private val uDy = GLES30.glGetUniformLocation(program, "uDy")
    private val sTex = GLES30.glGetUniformLocation(program, "sTex")
    private val aPos = GLES30.glGetAttribLocation(program, "aPos")
    private val aTex = GLES30.glGetAttribLocation(program, "aTex")
    private val taps = FloatArray(4)

    /** Into the bound [dstW]x[dstH] framebuffer, opaque. */
    fun draw(texture: Int, texMatrix: FloatArray, posMatrix: FloatArray, dstW: Int, dstH: Int) {
        DetailScale.tapOffsets(texMatrix, dstW, dstH, posMatrix[0], posMatrix[5], taps)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES30.glUniform1i(sTex, 0)
        GLES30.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES30.glUniformMatrix4fv(uPosMatrix, 1, false, posMatrix, 0)
        GLES30.glUniform2f(uDx, taps[0], taps[1])
        GLES30.glUniform2f(uDy, taps[2], taps[3])
        GlUtil.drawQuad(aPos, aTex)
    }

    fun release() {
        runCatching { GLES30.glDeleteProgram(program) }
    }
}
