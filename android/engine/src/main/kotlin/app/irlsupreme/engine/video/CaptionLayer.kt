package app.irlsupreme.engine.video

import app.irlsupreme.engine.captions.CaptionGraphic
import android.opengl.GLES30
import app.irlsupreme.engine.gl.GlUtil
import app.irlsupreme.engine.gl.TextureProgram

/**
 * The live caption strip on the render thread: one texture, replaced whenever the words change, drawn as it is (no
 * motion: captions that slide are harder to read). Render thread only.
 */
internal class CaptionLayer(private val outW: Int, private val outH: Int) {
    private val program = TextureProgram(oes = false)
    private var tex = 0
    private var pos = GlUtil.IDENTITY
    private var cardPos = GlUtil.IDENTITY

    val showing: Boolean get() = tex != 0

    /** Shows [g] in place of the current strip (null: none). Takes [g]'s bitmap. */
    fun offer(g: CaptionGraphic?) {
        free()
        if (g == null) return
        try {
            val b = g.bitmap
            if (b.isRecycled) return
            tex = GlUtil.textureFromBitmap(b)
            val l = g.leftPx.toFloat()
            val t = g.topPx.toFloat()
            pos = GlUtil.rectMatrix(l, t, l + b.width, t + b.height, outW, outH)
            val ct = g.cardTopPx.toFloat()
            cardPos = GlUtil.rectMatrix(l, ct, l + b.width, ct + b.height, outW, outH)
        } finally {
            g.bitmap.recycle()
        }
    }

    /** Draws the strip where it goes over a card ([onCard]: clear of its ruler) or the camera. */
    fun draw(onCard: Boolean) {
        if (tex != 0) program.draw(tex, GlUtil.FLIP_V, if (onCard) cardPos else pos, premultiplied = true)
    }

    fun free() {
        if (tex != 0) GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
        tex = 0
    }
}
