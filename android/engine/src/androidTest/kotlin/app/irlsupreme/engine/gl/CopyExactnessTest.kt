package app.irlsupreme.engine.gl

import android.opengl.GLES30
import junit.framework.TestCase
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The compositor copies its finished frame into the encoder 1:1 through the plain 2D program. That copy must be
 * pixel-exact: a texture coordinate with too little precision samples between texels and blurs the whole picture,
 * which shows first on GPUs with FP16 varyings (several Mali parts). A one-pixel checkerboard is the harshest input:
 * any blending at all turns 0/255 into grey.
 */
class CopyExactnessTest : TestCase() {
    fun testCheckerboardSurvivesTheCopy1080p() = copyCheckerboard(1920, 1080)

    fun testCheckerboardSurvivesTheCopyPortrait() = copyCheckerboard(1080, 1920)

    private fun copyCheckerboard(w: Int, h: Int) {
        val egl = EglCore(terminateDisplay = false)
        try {
            val src = Fbo(w, h)
            val dst = Fbo(w, h)
            val board = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            for (y in 0 until h) for (x in 0 until w) {
                val v = if ((x + y) and 1 == 0) 0 else 255
                board.put(v.toByte()).put(v.toByte()).put(v.toByte()).put(255.toByte())
            }
            board.position(0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, src.texture)
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, board)

            val program = TextureProgram(oes = false)
            dst.bind()
            program.draw(src.texture)
            val out = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, out)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            assertEquals("GL error", GLES30.GL_NO_ERROR, GLES30.glGetError())

            var bad = 0
            var firstBad = ""
            for (i in 0 until w * h) {
                for (c in 0 until 3) {
                    val v = out.get(i * 4 + c).toInt() and 0xff
                    val want = if ((i % w + i / w) and 1 == 0) 0 else 255
                    if (v != want) {
                        if (bad == 0) firstBad = "pixel (${i % w}, ${i / w}) channel $c = $v, want $want"
                        bad++
                    }
                }
            }
            assertEquals("copy not exact: $bad wrong values, first $firstBad", 0, bad)
            src.release()
            dst.release()
        } finally {
            egl.release()
        }
    }
}
