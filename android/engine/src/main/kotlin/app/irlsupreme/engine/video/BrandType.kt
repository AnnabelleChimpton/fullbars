package app.irlsupreme.engine.video

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import app.irlsupreme.engine.R
import kotlin.math.sqrt

/**
 * The bundled faces for everything drawn on stream (scene cards, alerts, chat), the same files the app's Compose
 * type uses (res/font, SIL OFL 1.1, texts in assets/licences). One place loads them, so the stream and the phone
 * can't drift to different fonts, and nothing on stream falls back to Roboto.
 *
 * Call [load] once, early (Application/Activity/Service onCreate); it's idempotent and cheap after the first call.
 * Until then every face is Typeface.DEFAULT, so a missed call degrades the look instead of crashing a live stream.
 */
object BrandType {
    private const val TAG = "BrandType"

    @Volatile private var faces: Array<Typeface>? = null
    @Volatile private var warned = false

    /** Font resources in [Face] order. */
    private val resources = intArrayOf(
        R.font.archivo_sc_400, R.font.archivo_sc_500, R.font.archivo_sc_600,
        R.font.archivo_c_700, R.font.archivo_c_800, R.font.archivo_xc_800,
        R.font.jetbrainsmono_400, R.font.jetbrainsmono_500,
    )

    private enum class Face { SC400, SC500, SC600, C700, C800, XC800, MONO400, MONO500 }

    val loaded: Boolean get() = faces != null

    /**
     * Loads the eight faces. In a debuggable build a font that fails to load is a crash, so a broken or missing file
     * shows up on the first run instead of as a quietly wrong-looking stream; a release build logs and falls back.
     */
    fun load(context: Context) {
        if (faces != null) return
        synchronized(this) {
            if (faces != null) return
            val res = context.applicationContext.resources
            val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            faces = Array(resources.size) { i ->
                val face = runCatching { res.getFont(resources[i]) }.getOrNull()
                check(face != null || !debuggable) { "Bundled font ${Face.entries[i]} failed to load" }
                face ?: Typeface.DEFAULT.also { Log.e(TAG, "Bundled font ${Face.entries[i]} failed to load; using the default") }
            }
        }
    }

    private fun face(f: Face): Typeface {
        val all = faces
        if (all == null) {
            if (!warned) { warned = true; Log.w(TAG, "BrandType.load() wasn't called; drawing with the default typeface") }
            return Typeface.DEFAULT
        }
        return all[f.ordinal]
    }

    /** Archivo SemiCondensed 400: chat messages, body. */
    val sc400: Typeface get() = face(Face.SC400)
    /** Archivo SemiCondensed 500: card sub lines, "viewers". */
    val sc500: Typeface get() = face(Face.SC500)
    /** Archivo SemiCondensed 600: chat names, the follow name. */
    val sc600: Typeface get() = face(Face.SC600)
    /** Archivo Condensed 700: caps labels (RAID). */
    val c700: Typeface get() = face(Face.C700)
    /** Archivo Condensed 800: alert names. */
    val c800: Typeface get() = face(Face.C800)
    /** Archivo ExtraCondensed 800: card headlines, the raid name. */
    val xc800: Typeface get() = face(Face.XC800)
    /** JetBrains Mono 400. */
    val mono400: Typeface get() = face(Face.MONO400)
    /** JetBrains Mono 500: tags, footers, amounts, counts. */
    val mono500: Typeface get() = face(Face.MONO500)
}

/**
 * Colours on stream, as Canvas ints. Achromatic except the two automatic cards that describe a health
 * problem: Weak signal (amber) and Camera lost (orange). Alerts and chat never take a health hue.
 */
object StreamPalette {
    const val INK = 0xFF0D0D0C.toInt()
    const val BONE = 0xFFF4F1EA.toInt()
    const val TEXT2 = 0xFFC2C8CF.toInt()
    /** Registration ticks, the ruler. */
    const val TICK = 0xFF8A867E.toInt()
    /** The Weak signal card's glyph and rule only. */
    const val WEAK = 0xFFFFC233.toInt()
    /** The Camera lost card's stroke and rule only. */
    const val CAMERA_LOST = 0xFFFF8A3D.toInt()
    /** Alert plates: Ink at 92% (#20201F over white). */
    const val ALERT_PLATE_ALPHA = 0.92f
    /** Chat line plates: Ink at 88%, the same scrim as the phone's plate. */
    const val CHAT_PLATE_ALPHA = 0.88f
    /** On the Bone bookend cards the ticks are Ink at 40%. */
    const val BOOKEND_TICK_ALPHA = 0.4f
    /** The raid slab's Ink keyline, in u. */
    const val SLAB_KEYLINE_U = 0.35f

    /** [color] with its alpha replaced by [alpha] (0..1). */
    fun withAlpha(color: Int, alpha: Float): Int = (Math.round(alpha.coerceIn(0f, 1f) * 255) shl 24) or (color and 0xFFFFFF)
}

/**
 * The brand's drawn pieces for Canvas, shared by cards and alerts so they agree with the app.
 * Sizes are in pixels; callers pass u = min(w, h) / 100 where the design measures in u.
 */
object BrandDraw {
    /** Per-bar states for [mark]: the same numbers as the app's MarkBar. */
    const val BAR_EMPTY = 0
    const val BAR_FULL = 1
    const val BAR_AMBER = 2
    const val BAR_DASHED = 3

    /** The mark's three bars on its 24-unit grid (res/drawable/ic_stat_fullbars.xml): left, top, right, bottom. */
    private val BARS = arrayOf(
        floatArrayOf(4f, 16f, 7.5f, 20f),
        floatArrayOf(10f, 12f, 13.5f, 20f),
        floatArrayOf(16f, 4f, 19.5f, 20f),
    )

    /**
     * The mark at ([x], [y]) (top-left), [size] px square. [bars] gives each bar's state left to right ([BAR_EMPTY]
     * hollow, [BAR_FULL], [BAR_AMBER] in [StreamPalette.WEAK], [BAR_DASHED] dashed outline); missing entries are full.
     * The beam is always solid. A hollow tall bar is outlined below the beam, never through it.
     */
    fun mark(c: Canvas, x: Float, y: Float, size: Float, bars: IntArray, color: Int) {
        val s = size / 24f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
        c.drawRect(x + 4f * s, y + 4f * s, x + 19.5f * s, y + 6.5f * s, fill)
        val ow = 1f * s
        BARS.forEachIndexed { i, r ->
            when (val state = bars.getOrElse(i) { BAR_FULL }) {
                BAR_FULL, BAR_AMBER -> {
                    fill.color = if (state == BAR_AMBER) StreamPalette.WEAK else color
                    c.drawRect(x + r[0] * s, y + r[1] * s, x + r[2] * s, y + r[3] * s, fill)
                }
                else -> {
                    val top = if (i == 2) 6.5f else r[1]
                    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE; strokeWidth = ow; this.color = color; strokeJoin = Paint.Join.MITER
                        if (state == BAR_DASHED) pathEffect = DashPathEffect(floatArrayOf(1.5f * s, 1f * s), 0f)
                    }
                    c.drawRect(
                        x + r[0] * s + ow / 2, y + top * s + ow / 2, x + r[2] * s - ow / 2, y + r[3] * s - ow / 2, stroke,
                    )
                }
            }
        }
    }

    /**
     * Caution hatching across [rect]: 135° stripes, 2.5 on and 3.5 off in units of [unit] px (measured across the
     * stripes), clipped to the rect. The same pattern as the END band and the Camera lost cap on the phone.
     */
    fun hatch(c: Canvas, rect: RectF, color: Int, unit: Float) {
        val on = 2.5f * unit
        val step = (2.5f + 3.5f) * unit * sqrt(2f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = on; this.color = color }
        c.save()
        c.clipRect(rect)
        var sx = rect.left - rect.height()
        while (sx < rect.right + step) {
            c.drawLine(sx, rect.bottom, sx + rect.height(), rect.top, p)
            sx += step
        }
        c.restore()
    }

    /**
     * Registration L-ticks at the four corners of a [w]×[h] frame: the corner point 4u in from each edge, 3u arms,
     * 0.35u stroke, square ends. [color] is [StreamPalette.TICK] on Ink cards; bookends pass Ink at 40%.
     */
    fun regTicks(c: Canvas, w: Int, h: Int, u: Float, color: Int = StreamPalette.TICK) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 0.35f * u; this.color = color
            strokeCap = Paint.Cap.SQUARE; strokeJoin = Paint.Join.MITER
        }
        val inset = 4f * u
        val arm = 3f * u
        val path = Path()
        for ((cx, dx) in listOf(inset to 1f, w - inset to -1f)) {
            for ((cy, dy) in listOf(inset to 1f, h - inset to -1f)) {
                path.moveTo(cx + dx * arm, cy)
                path.lineTo(cx, cy)
                path.lineTo(cx, cy + dy * arm)
            }
        }
        c.drawPath(path, p)
    }
}
