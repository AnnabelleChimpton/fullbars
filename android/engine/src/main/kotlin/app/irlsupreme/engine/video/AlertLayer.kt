package app.irlsupreme.engine.video

import android.graphics.Bitmap
import android.opengl.GLES30
import app.irlsupreme.engine.audio.AlertSounds
import app.irlsupreme.engine.gl.GlUtil
import app.irlsupreme.engine.gl.RuleProgram
import app.irlsupreme.engine.gl.TextureProgram
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * How loud an alert is on stream. Loudness is shown by surface and size, never by hue: QUIET and MEDIUM
 * are Ink plates, LOUD is the Bone slab.
 */
enum class AlertTier {
    /** A follow: one line on an Ink plate. */
    QUIET,
    /** Subs, resubs, gift subs, cheers, tips: two lines on an Ink plate. */
    MEDIUM,
    /** A raid: the Bone slab with an Ink keyline. */
    LOUD;

    companion object {
        /** The tier that goes with a chime, so sound and picture always agree on how big the moment is. */
        fun of(style: AlertSounds.Style): AlertTier = when (style) {
            AlertSounds.Style.SOFT -> QUIET
            AlertSounds.Style.BRIGHT -> MEDIUM
            AlertSounds.Style.BIG -> LOUD
        }
    }
}

/**
 * One alert, drawn once by the app (OverlayComposer.alertBitmap) at its final size. The engine takes [bitmap] (it
 * uploads it once and recycles it) and animates it in GL: wipe in, [dwellMs] fully shown with the time-left rule
 * stepping down, wipe out. [leftPx]/[topPx] place its top-left corner in the output frame.
 *
 * The engine draws the time-left rule itself, over the bitmap (see [AlertRule]); the bitmap leaves that strip empty.
 * [scale] is the size the bitmap was drawn at against the frame's u (Small, Large, or shrunk to fit), so the rule
 * scales with it and stays in the strip; [ruleColor] matches the alert's text.
 */
class AlertGraphic(
    val bitmap: Bitmap,
    val leftPx: Int,
    val topPx: Int,
    val tier: AlertTier,
    val dwellMs: Long,
    val scale: Float = 1f,
    val ruleColor: Int = AlertRule.color(tier),
)

/**
 * Where the engine draws an alert's time-left rule inside the alert's bitmap, in u = min(w, h) / 100 of the output
 * frame. The app's alert drawing keeps this strip clear so the rule never lands on type.
 */
object AlertRule {
    /** Segments in the rule; one empties every dwell / [SEGMENTS]. */
    const val SEGMENTS = 12
    /** Inset from the alert's left and right edges: the plates' 3u padding. */
    const val INSET_U = 3f
    /** From the alert's bottom edge up to the rule's bottom edge. */
    const val BOTTOM_U = 1.25f
    const val HEIGHT_U = 0.5f
    /** Space between segments. */
    const val GAP_U = 0.4f
    /** Outline of an emptied segment, at least one pixel. */
    const val LINE_U = 0.12f

    /** Bone on the Ink plates, Ink on the Bone slab. */
    fun color(tier: AlertTier): Int = if (tier == AlertTier.LOUD) StreamPalette.INK else StreamPalette.BONE

    /**
     * The rule inside an alert drawn at [left], [top], [w] x [h] px, where the alert's own unit is [u] (the frame's u
     * times the alert's scale). Pure, so the engine and the app's previews put it in the same place.
     */
    fun box(left: Float, top: Float, w: Float, h: Float, u: Float): Box {
        val bottom = top + h - BOTTOM_U * u
        return Box(left + INSET_U * u, bottom - HEIGHT_U * u, left + w - INSET_U * u, bottom, GAP_U * u, LINE_U * u)
    }

    /** A rule's edges in px, with its segment gap and hollow-segment line. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float, val gap: Float, val line: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }
}

/**
 * An alert's timeline, pure so it can be tested. Time t is in ms from
 * [app.irlsupreme.engine.StreamEngine.showAlert]:
 *  - 0 → [enterMs]: wipe in left to right (Enter curve; 280ms, or 180ms for QUIET);
 *  - [enterMs] → [enterMs] + dwell: fully shown, the rule losing one of 12 segments every dwell / 12;
 *  - then [EXIT_MS] wiping out right to left (Exit curve), and [GAP_MS] before the next alert may start.
 * With `cut` (thermal rung ≥ 2) it cuts in and out; the rule still steps. The phone's animator scale never changes
 * any of this: viewers see the same motion whatever the streamer's accessibility settings are.
 */
object AlertMotion {
    const val ENTER_MS = 280L
    const val QUIET_ENTER_MS = 180L
    const val EXIT_MS = 180L
    const val GAP_MS = 400L

    /** The Enter curve, cubic-bezier(0.2, 0, 0, 1): decelerates into place. */
    val enterEase = CubicBezier(0.2f, 0f, 0f, 1f)
    /** The Exit curve, cubic-bezier(0.4, 0, 1, 1): accelerates away. */
    val exitEase = CubicBezier(0.4f, 0f, 1f, 1f)

    fun enterMs(tier: AlertTier): Long = if (tier == AlertTier.QUIET) QUIET_ENTER_MS else ENTER_MS

    /** When to start the chime after showing the alert, so its attack lands on the first fully revealed frame. */
    fun chimeDelayMs(tier: AlertTier, style: AlertSounds.Style): Long =
        (enterMs(tier) - AlertSounds.attackMs(style)).coerceAtLeast(0)

    /** From show to the moment the next alert may be shown: wipe in, dwell, wipe out and the gap. */
    fun slotMs(tier: AlertTier, dwellMs: Long): Long = enterMs(tier) + dwellMs + EXIT_MS + GAP_MS

    /** When the wipe-out starts: at the end of the dwell, or earlier when the alert was told to go at [exitAtMs]. */
    fun exitStartMs(tier: AlertTier, dwellMs: Long, exitAtMs: Long? = null): Long {
        val natural = enterMs(tier) + dwellMs
        return if (exitAtMs == null) natural else minOf(natural, exitAtMs.coerceAtLeast(0))
    }

    /** How much of the alert's width shows at [tMs], 0..1. */
    fun reveal(tier: AlertTier, dwellMs: Long, tMs: Long, exitAtMs: Long? = null, cut: Boolean = false): Float {
        if (tMs < 0) return 0f
        val exitStart = exitStartMs(tier, dwellMs, exitAtMs)
        fun entered(t: Long): Float = if (cut) 1f else enterEase.transform((t.toFloat() / enterMs(tier)).coerceIn(0f, 1f))
        if (tMs < exitStart) return entered(tMs)
        if (cut) return 0f
        val from = entered(exitStart)
        val p = ((tMs - exitStart).toFloat() / EXIT_MS).coerceIn(0f, 1f)
        return (from * (1f - exitEase.transform(p))).coerceIn(0f, 1f)
    }

    /** True once the alert is gone and its texture can be freed. */
    fun done(tier: AlertTier, dwellMs: Long, tMs: Long, exitAtMs: Long? = null, cut: Boolean = false): Boolean =
        tMs >= exitStartMs(tier, dwellMs, exitAtMs) + if (cut) 0 else EXIT_MS

    /** Filled segments of the time-left rule at [tMs]: all 12 until fully shown, then one fewer every dwell / 12. */
    fun ruleRemaining(tier: AlertTier, dwellMs: Long, tMs: Long): Int {
        val inDwell = tMs - enterMs(tier)
        if (inDwell < 0 || dwellMs <= 0) return AlertRule.SEGMENTS
        val gone = floor(inDwell.toDouble() * AlertRule.SEGMENTS / dwellMs).toInt()
        return (AlertRule.SEGMENTS - gone).coerceIn(0, AlertRule.SEGMENTS)
    }
}

/** A CSS-style cubic-bezier easing from (0,0) to (1,1); [transform] maps time to progress. */
class CubicBezier(private val x1: Float, private val y1: Float, private val x2: Float, private val y2: Float) {
    private fun bez(t: Float, a: Float, b: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * a + 3f * u * t * t * b + t * t * t
    }

    fun transform(x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        // Bisection on x(t): monotonic for control points in 0..1, and 30 halvings is far below a pixel.
        var lo = 0f
        var hi = 1f
        var t = x
        repeat(30) {
            val bx = bez(t, x1, x2)
            if (abs(bx - x) < 1e-5f) return bez(t, y1, y2)
            if (bx < x) lo = t else hi = t
            t = (lo + hi) / 2f
        }
        return bez(t, y1, y2)
    }
}

/**
 * The on-stream alert: each alert is uploaded once into its own texture and then only moved, clipped and
 * stepped per frame: a scissor on x for the wipe, and a uniform for the time-left rule. Nothing is redrawn per frame,
 * so the 1080p overlay keeps its slow cadence. Render thread only.
 */
internal class AlertLayer(private val outW: Int, private val outH: Int) {
    private val program = TextureProgram(oes = false)
    private val rule = RuleProgram()
    private val u = minOf(outW, outH) / 100f

    private var tex = 0
    private var tier = AlertTier.QUIET
    private var dwellMs = 0L
    private var ruleBox = AlertRule.box(0f, 0f, 0f, 0f, 0f)
    private var ruleColor = AlertRule.color(AlertTier.QUIET)
    private var left = 0f
    private var top = 0f
    private var w = 0f
    private var h = 0f
    private var startNs = 0L
    private var exitAtMs: Long? = null
    private var pos = GlUtil.IDENTITY
    private var rulePos = GlUtil.IDENTITY

    val showing: Boolean get() = tex != 0

    /**
     * A new alert ([g], shown at [atNs] on the System.nanoTime clock) replaces the current one at once; null starts
     * the current one's wipe-out at [atNs]. Takes [g]'s bitmap.
     */
    fun offer(g: AlertGraphic?, atNs: Long) {
        if (g == null) {
            if (tex != 0 && exitAtMs == null) exitAtMs = ((atNs - startNs) / 1_000_000).coerceAtLeast(0)
            return
        }
        free()
        try {
            val b = g.bitmap
            if (b.isRecycled) return
            tex = GlUtil.textureFromBitmap(b)
            tier = g.tier
            dwellMs = g.dwellMs
            left = g.leftPx.toFloat()
            top = g.topPx.toFloat()
            w = b.width.toFloat()
            h = b.height.toFloat()
            startNs = atNs
            exitAtMs = null
            pos = GlUtil.rectMatrix(left, top, left + w, top + h, outW, outH)
            ruleBox = AlertRule.box(left, top, w, h, u * g.scale)
            ruleColor = g.ruleColor
            rulePos = GlUtil.rectMatrix(ruleBox.left, ruleBox.top, ruleBox.right, ruleBox.bottom, outW, outH)
        } finally {
            g.bitmap.recycle()
        }
    }

    /**
     * Draws the alert as it is at [nowNs] into the current viewport ([vpW]×[vpH], the output frame scaled), then frees
     * it once it's gone. [cut]: thermal rung ≥ 2, no wipes.
     */
    fun draw(nowNs: Long, vpW: Int, vpH: Int, cut: Boolean) {
        if (tex == 0) return
        val t = (nowNs - startNs) / 1_000_000
        if (AlertMotion.done(tier, dwellMs, t, exitAtMs, cut)) { free(); return }
        val reveal = AlertMotion.reveal(tier, dwellMs, t, exitAtMs, cut)
        if (reveal <= 0f) return
        val sx = vpW.toFloat() / outW
        val sy = vpH.toFloat() / outH
        val x0 = (left * sx).roundToInt()
        val x1 = ((left + w * reveal) * sx).roundToInt()
        val y0 = ((outH - top - h) * sy).roundToInt()
        val y1 = ((outH - top) * sy).roundToInt()
        if (x1 <= x0 || y1 <= y0) return
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        GLES30.glScissor(x0, y0, x1 - x0, y1 - y0)
        try {
            program.draw(tex, GlUtil.FLIP_V, pos, premultiplied = true)
            val b = ruleBox
            rule.draw(
                rulePos, b.width * sx, b.height * sy, AlertRule.SEGMENTS, AlertMotion.ruleRemaining(tier, dwellMs, t),
                gapPx = b.gap * sx, linePx = maxOf(1f, b.line * sx), color = ruleColor,
            )
        } finally {
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        }
    }

    /** Takes the alert off at once (what's on stream now holds alerts back). */
    fun free() {
        if (tex != 0) GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
        tex = 0
        exitAtMs = null
    }
}
