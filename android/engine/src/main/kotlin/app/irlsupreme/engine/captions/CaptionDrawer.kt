package app.irlsupreme.engine.captions

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import app.irlsupreme.engine.CaptionPosition
import app.irlsupreme.engine.CaptionSize
import app.irlsupreme.engine.video.BrandType
import app.irlsupreme.engine.video.StreamPalette

/** The caption strip, drawn once per change: [bitmap] goes at [leftPx], [topPx] in the output frame. */
class CaptionGraphic(val bitmap: Bitmap, val leftPx: Int, val topPx: Int)

/**
 * Where the caption strip sits and how big its type is, in u = min(w, h) / 100 of the output frame, like the cards
 * and alerts. Pure, so it's tested without a canvas.
 *
 * Bone type on Ink plates at the chat plates' 88%, one plate per line like broadcast captions, centred, two lines.
 * Bottom (the default) sits clear of the cards' ruler and its cursor (8u + 3.6u up from the bottom edge, see
 * Slates.rulerY), so the strip never covers a card's footer; Top sits clear of the cards' tag row. Lines are kept to a
 * readable measure: 70% of a landscape frame, 86% of a portrait one.
 */
object CaptionGeometry {
    const val MAX_LINES = 2
    /** From the frame's edge to the strip, u: the cards' 8u margin, the ruler's 3.6u cursor, and a gap. */
    const val MARGIN_U = 13f
    /** A plate's padding left and right of its line, u. */
    const val PAD_U = 1.6f
    /** Line height as a multiple of the type size. */
    const val LINE_HEIGHT = 1.32f

    fun unit(w: Int, h: Int): Float = minOf(w, h) / 100f

    /** The type size in px for [size]. */
    fun textPx(size: CaptionSize, w: Int, h: Int): Float = unit(w, h) * when (size) {
        CaptionSize.SMALL -> 4f
        CaptionSize.MEDIUM -> 5f
        CaptionSize.LARGE -> 6.5f
    }

    /** The widest a line of text may be, px. */
    fun maxTextWidth(w: Int, h: Int): Float = w * (if (w >= h) 0.70f else 0.86f) - 2 * PAD_U * unit(w, h)

    /** The strip's top edge for [lines] lines at [size]. */
    fun top(position: CaptionPosition, size: CaptionSize, lines: Int, w: Int, h: Int): Int {
        val stripH = lines * textPx(size, w, h) * LINE_HEIGHT
        val m = MARGIN_U * unit(w, h)
        return (if (position == CaptionPosition.TOP) m else h - m - stripH).toInt()
    }
}

/** Draws the caption strip with the bundled type. Any thread; [BrandType] must have been loaded. */
class CaptionDrawer(private val outW: Int, private val outH: Int, private val size: CaptionSize, private val position: CaptionPosition) {
    private val textPx = CaptionGeometry.textPx(size, outW, outH)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = BrandType.sc500
        textSize = textPx
        color = StreamPalette.BONE
    }
    private val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = StreamPalette.INK
        alpha = (StreamPalette.CHAT_PLATE_ALPHA * 255).toInt()
    }
    val maxWidth: Float = CaptionGeometry.maxTextWidth(outW, outH)

    fun measure(s: String): Float = paint.measureText(s)

    /** The strip for [lines] (oldest first), or null for none. */
    fun draw(lines: List<String>): CaptionGraphic? {
        if (lines.isEmpty()) return null
        val u = CaptionGeometry.unit(outW, outH)
        val pad = CaptionGeometry.PAD_U * u
        val lineH = textPx * CaptionGeometry.LINE_HEIGHT
        val widths = lines.map { minOf(measure(it), maxWidth) }
        val bw = (widths.max() + 2 * pad).toInt().coerceAtLeast(1)
        val bh = (lines.size * lineH).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fm = paint.fontMetrics
        lines.forEachIndexed { i, line ->
            val tw = widths[i]
            val x0 = (bw - tw) / 2f - pad
            val y0 = i * lineH
            c.drawRect(RectF(x0, y0, x0 + tw + 2 * pad, y0 + lineH), plate)
            // A word too long for a line is squeezed to fit rather than cut off.
            val natural = measure(line)
            paint.textScaleX = if (natural > maxWidth) maxWidth / natural else 1f
            val baseline = y0 + (lineH - (fm.descent - fm.ascent)) / 2f - fm.ascent
            c.drawText(line, x0 + pad, baseline, paint)
        }
        paint.textScaleX = 1f
        val left = (outW - bw) / 2
        val top = CaptionGeometry.top(position, size, lines.size, outW, outH)
        return CaptionGraphic(bmp, left, top)
    }
}
