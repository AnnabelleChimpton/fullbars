package app.irlsupreme.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.irlsupreme.ui.theme.LocalHealthColors

/**
 * The app's glyphs: drawn for Fullbars on a 24dp grid with a 1.5dp stroke, square caps and mitred joins, so
 * they read as the same instrument as the keys and the mark. Tint them with Icon(tint = …); the colour here is only a
 * placeholder that the tint replaces. Icons appear only on Live keys, pill states, the back control and the settings
 * control: Home and Settings rows carry none.
 */
object FbIcons {
    private class Glyph(val name: String) {
        val strokes = mutableListOf<String>()
        val fills = mutableListOf<String>()
        fun stroke(d: String) = apply { strokes += d }
        fun fill(d: String) = apply { fills += d }
        fun build(): ImageVector = ImageVector.Builder(
            name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            fills.forEach { addPath(pathData = addPathNodes(it), fill = SolidColor(Color.Black)) }
            strokes.forEach {
                addPath(
                    pathData = addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Square, strokeLineJoin = StrokeJoin.Miter, strokeLineMiter = 4f,
                )
            }
        }.build()
    }

    private fun glyph(name: String, block: Glyph.() -> Unit): ImageVector = Glyph(name).apply(block).build()

    private const val CARD_FRAME = "M3.75,5.75h16.5v12.5h-16.5z"

    /** BRB: a card with a placeholder bar. */
    val Card = glyph("card") { stroke(CARD_FRAME); stroke("M7.5,14.25h5") }
    /** Starting soon: a card with a cue triangle. */
    val CardCue = glyph("card_cue") { stroke(CARD_FRAME); fill("M10.25,9.25L14.75,12L10.25,14.75z") }
    /** Ending: a card with a stop square. */
    val CardStop = glyph("card_stop") { stroke(CARD_FRAME); fill("M9.75,9.75h4.5v4.5h-4.5z") }
    /** Scenes. */
    val Layers = glyph("layers") { stroke("M12,4.5L20.5,9L12,13.5L3.5,9z"); stroke("M3.5,13L12,17.5L20.5,13") }
    val Flip = glyph("flip") {
        stroke("M4.5,10.5A7.6,7.6 0 0 1 17.9,7.3M19.5,13.5A7.6,7.6 0 0 1 6.1,16.7")
        stroke("M18.4,3.8v3.9h-3.9M5.6,20.2v-3.9h3.9")
    }
    val Mic = glyph("mic") {
        stroke("M9,6.75A3,3 0 0 1 15,6.75V11.25A3,3 0 0 1 9,11.25z")
        stroke("M5.75,11.5A6.25,6.25 0 0 0 18.25,11.5M12,17.75v2.5")
    }
    val MicOff = glyph("mic_off") {
        stroke("M15,10.5V6.75A3,3 0 0 0 9,6.35M9,9.5v1.75A3,3 0 0 0 13.6,13.75")
        stroke("M5.75,11.5A6.25,6.25 0 0 0 16.15,16.2M18.25,11.5c0,0.6 -0.1,1.2 -0.25,1.7M12,17.75v2.5M4,4L20,20")
    }
    /** END: a filled square. */
    val Stop = glyph("stop") { fill("M6.5,6.5h11v11h-11z") }
    /** CANCEL. */
    val X = glyph("x") { stroke("M6,6L18,18M18,6L6,18") }
    /** Privacy. */
    val EyeOff = glyph("eye_off") {
        stroke("M3,12s3.3,-6 9,-6c1.5,0 2.8,0.4 4,1M21,12s-3.3,6 -9,6c-1.5,0 -2.8,-0.4 -4,-1")
        stroke("M9.9,14.1A3,3 0 0 1 14.1,9.9M4,20L20,4")
    }
    /** Camera lost. */
    val CamOff = glyph("cam_off") { stroke("M3.75,7.25h9.5v9.5h-9.5zM13.25,10.5l6.5,-3.25v9.5l-6.5,-3.25M3,3L21,21") }
    /** Reconnecting (the pill steps it 45° every 150ms). */
    val Arrows = glyph("arrows") {
        stroke("M4.5,11A7.5,7.5 0 0 1 17.5,6.4M19.5,13A7.5,7.5 0 0 1 6.5,17.6")
        stroke("M17.8,2.8v3.9h-3.9M6.2,21.2v-3.9h3.9")
    }
    /** Hot. */
    val Thermo = glyph("thermo") {
        stroke("M10.5,13.84V4.5H13.5V13.84A3.5,3.5 0 1 1 10.5,13.84z")
        stroke("M12,10.5V17")
    }
    /** Settings: two sliders, replacing the gear. */
    val Sliders = glyph("sliders") {
        stroke("M3.5,7.5H13.25M16.75,7.5H20.5M13.25,5h3.5v5h-3.5z")
        stroke("M3.5,16.5H6.75M10.25,16.5H20.5M6.75,14h3.5v5h-3.5z")
    }
    val Back = glyph("back") { stroke("M19.5,12h-15M10.5,6l-6,6 6,6") }
    val Chevron = glyph("chevron") { stroke("M9,5.5l6.5,6.5L9,18.5") }
    val External = glyph("external") { stroke("M13.5,4.5h6v6M19.5,4.5l-9,9M17,13.5v6H4.5V7h6") }
    val Copy = glyph("copy") { stroke("M8.75,8.75h11.5v11.5h-11.5z"); stroke("M15.25,5.75v-2H3.75v11.5h2") }
    /** TO CAMERA: the camera, back on. */
    val Camera = glyph("camera") { stroke("M3.75,7.25h9.5v9.5h-9.5zM13.25,10.5l6.5,-3.25v9.5l-6.5,-3.25") }
    /** "Continue with Twitch" only. */
    val Twitch = glyph("twitch") {
        fill(
            "M4.3,2L3,5.4v13.8h4.7V22h2.6l2.8,-2.8h3.8L22,14.1V2H4.3zm15.8,11.2l-2.9,2.9h-4.7l-2.5,2.5v-2.5H6V3.9h14.1v9.3z" +
                "M17.3,7h-1.9v5.4h1.9V7zm-5.2,0h-1.9v5.4h1.9V7z",
        )
    }
    val Check = glyph("check") { stroke("M5,12.5l4.5,4.5L19,7.5") }
    val ExpandMore = glyph("expand_more") { stroke("M5.5,9l6.5,6.5L18.5,9") }
    val ExpandLess = glyph("expand_less") { stroke("M5.5,15l6.5,-6.5L18.5,15") }
    val Visibility = glyph("eye") {
        stroke("M2.5,12C5,7.5 8.5,5.75 12,5.75C15.5,5.75 19,7.5 21.5,12C19,16.5 15.5,18.25 12,18.25C8.5,18.25 5,16.5 2.5,12z")
        stroke("M12,9.5a2.5,2.5 0 1 1 0,5a2.5,2.5 0 1 1 0,-5z")
    }
    val Play = glyph("play") { fill("M8,5.5v13L18.5,12z") }
    val Pause = glyph("pause") { fill("M7,5.5h3.5v13H7zM13.5,5.5H17v13h-3.5z") }
    val Dot = glyph("dot") { fill("M12,8a4,4 0 1 1 0,8a4,4 0 1 1 0,-8z") }

    /**
     * The mark, exactly the path of res/drawable/ic_stat_fullbars.xml (never redrawn). Used only where it means
     * something: the wordmark, the Live CTA, the recap verdict, scene cards and the raid slab. For per-bar states
     * (hollow for a drop, dashed for signal lost) draw [MarkGlyph] instead.
     */
    val Mark = glyph("mark") { fill(MARK_PATH) }

    // --- Old names, kept so call sites build until they move to the names above -----------------------------------

    @Deprecated("Use Sliders.", ReplaceWith("FbIcons.Sliders")) val Settings get() = Sliders
    @Deprecated("Use Back.", ReplaceWith("FbIcons.Back")) val ArrowBack get() = Back
    @Deprecated("Use Chevron.", ReplaceWith("FbIcons.Chevron")) val ChevronRight get() = Chevron
    @Deprecated("Use X.", ReplaceWith("FbIcons.X")) val Close get() = X
    @Deprecated("Use EyeOff.", ReplaceWith("FbIcons.EyeOff")) val VisibilityOff get() = EyeOff
    @Deprecated("Use External.", ReplaceWith("FbIcons.External")) val OpenExternal get() = External
    @Deprecated("Use Arrows.", ReplaceWith("FbIcons.Arrows")) val Sync get() = Arrows
    @Deprecated("Use Thermo.", ReplaceWith("FbIcons.Thermo")) val Thermostat get() = Thermo
    @Deprecated("Use Camera.", ReplaceWith("FbIcons.Camera")) val Videocam get() = Camera
    @Deprecated("Use Camera.", ReplaceWith("FbIcons.Camera")) val PhotoCamera get() = Camera

    /*
     * Row and status icons that are on their way out (rows carry no icons; state is a StateMark). These keep Google's
     * Material Icons paths (Apache License 2.0) only until the last call site is gone; don't use them in new code.
     */
    private fun legacy(name: String, vararg paths: String): ImageVector = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply { paths.forEach { addPath(pathData = addPathNodes(it), fill = SolidColor(Color.Black)) } }.build()

    private const val NO_ROW_ICONS = "Rows carry no icons; state is a StateMark."

    @Deprecated(NO_ROW_ICONS) val CheckCircle = legacy("check_circle", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM10,17l-5,-5 1.41,-1.41L10,14.17l7.59,-7.59L19,8l-9,9z")
    @Deprecated(NO_ROW_ICONS) val Warning = legacy("warning", "M1,21h22L12,2 1,21zM13,18h-2v-2h2v2zM13,14h-2v-4h2v4z")
    @Deprecated(NO_ROW_ICONS) val Error = legacy("error", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-2h2v2zM13,13h-2V7h2v6z")
    @Deprecated(NO_ROW_ICONS) val Info = legacy("info", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-6h2v6zM13,9h-2V7h2v2z")
    @Deprecated(NO_ROW_ICONS) val Wifi = legacy("wifi", "M1,9l2,2c4.97,-4.97 13.03,-4.97 18,0l2,-2C16.93,2.93 7.08,2.93 1,9zM9,17l3,3 3,-3c-1.65,-1.66 -4.34,-1.66 -6,0zM5,13l2,2c2.76,-2.76 7.24,-2.76 10,0l2,-2C15.14,9.14 8.87,9.14 5,13z")
    @Deprecated(NO_ROW_ICONS) val WifiOff = legacy("wifi_off", "M23.64,7c-0.45,-0.34 -4.93,-4 -11.64,-4 -1.5,0 -2.89,0.19 -4.15,0.48L18.18,13.8 23.64,7zM17.04,15.22L3.27,1.44 2,2.72l2.05,2.06C1.91,5.76 0.59,6.82 0.36,7l11.63,14.49 0.01,0.01 0.01,-0.01 3.9,-4.86 3.32,3.32 1.27,-1.27 -3.46,-3.46z")
    @Deprecated(NO_ROW_ICONS) val Cellular = legacy("signal_cellular", "M2,22h20V2z")
    @Deprecated("Use MeterGlyph.") val SignalBars = legacy("signal_cellular_alt", "M17,4h3v16h-3zM5,14h3v6H5zM11,9h3v11h-3z")
    @Deprecated(NO_ROW_ICONS) val Phone = legacy("smartphone", "M17,1.01L7,1c-1.1,0 -2,0.9 -2,2v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3c0,-1.1 -0.9,-1.99 -2,-1.99zM17,19H7V5h10v14z")
    @Deprecated(NO_ROW_ICONS) val Merge = legacy("call_merge", "M17,20.41L18.41,19 15,15.59 13.59,17 17,20.41zM7.5,8H11v5.59L5.59,19 7,20.41l6,-6V8h3.5L12,3.5 7.5,8z")
    @Deprecated(NO_ROW_ICONS) val Broadcast = legacy("sensors", "M7.76,16.24C6.67,15.16 6,13.66 6,12s0.67,-3.16 1.76,-4.24l1.42,1.42C8.45,9.9 8,10.9 8,12c0,1.1 0.45,2.1 1.17,2.83L7.76,16.24zM16.24,16.24C17.33,15.16 18,13.66 18,12s-0.67,-3.16 -1.76,-4.24l-1.42,1.42C15.55,9.9 16,10.9 16,12c0,1.1 -0.45,2.1 -1.17,2.83L16.24,16.24zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2s2,-0.9 2,-2S13.1,10 12,10zM20,12c0,2.21 -0.9,4.21 -2.35,5.65l1.42,1.42C20.88,17.26 22,14.76 22,12s-1.12,-5.26 -2.93,-7.07l-1.42,1.42C19.1,7.79 20,9.79 20,12zM6.35,6.35L4.93,4.93C3.12,6.74 2,9.24 2,12s1.12,5.26 2.93,7.07l1.42,-1.42C4.9,16.21 4,14.21 4,12S4.9,7.79 6.35,6.35z")
    @Deprecated(NO_ROW_ICONS) val HighQuality = legacy("high_quality", "M19,4H5c-1.11,0 -2,0.9 -2,2v12c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2zM11,15H9.5v-2h-2v2H6V9h1.5v2.5h2V9H11v6zM18,14c0,0.55 -0.45,1 -1,1h-0.75v1.5h-1.5V15H14c-0.55,0 -1,-0.45 -1,-1v-4c0,-0.55 0.45,-1 1,-1h3c0.55,0 1,0.45 1,1v4zM14.5,13.5h2v-3h-2v3z")
    @Deprecated(NO_ROW_ICONS) val Science = legacy("science", "M19.8,18.4L14,10.67V6.5l1.35,-1.69C15.61,4.48 15.38,4 14.96,4H9.04C8.62,4 8.39,4.48 8.65,4.81L10,6.5v4.17L4.2,18.4C3.71,19.06 4.18,20 5,20h14c0.82,0 1.29,-0.94 0.8,-1.6z")
    @Deprecated(NO_ROW_ICONS) val Battery = legacy("battery_std", "M15.67,4H14V2h-4v2H8.33C7.6,4 7,4.6 7,5.33v15.33C7,21.4 7.6,22 8.33,22h7.33c0.74,0 1.34,-0.6 1.34,-1.33V5.33C17,4.6 16.4,4 15.67,4z")
    @Deprecated(NO_ROW_ICONS) val Bluetooth = legacy("bluetooth", "M17.71,7.71L12,2h-1v7.59L6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 11,14.41V22h1l5.71,-5.71 -4.3,-4.29 4.3,-4.29zM13,5.83l1.88,1.88L13,9.59V5.83zM14.88,16.29L13,18.17v-3.76l1.88,1.88z")
    @Deprecated(NO_ROW_ICONS) val Link = legacy("link", "M3.9,12c0,-1.71 1.39,-3.1 3.1,-3.1h4V7H7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5h4v-1.9H7c-1.71,0 -3.1,-1.39 -3.1,-3.1zM8,13h8v-2H8v2zM17,7h-4v1.9h4c1.71,0 3.1,1.39 3.1,3.1s-1.39,3.1 -3.1,3.1h-4V17h4c2.76,0 5,-2.24 5,-5s-2.24,-5 -5,-5z")
    @Deprecated(NO_ROW_ICONS) val QrScanner = legacy("qr_code_scanner", "M9.5,6.5v3h-3v-3H9.5M11,5H5v6h6V5L11,5zM9.5,14.5v3h-3v-3H9.5M11,13H5v6h6V13L11,13zM17.5,6.5v3h-3v-3H17.5M19,5h-6v6h6V5L19,5zM13,13h1.5v1.5H13V13zM14.5,14.5H16V16h-1.5V14.5zM16,13h1.5v1.5H16V13zM13,16h1.5v1.5H13V16zM14.5,17.5H16V19h-1.5V17.5zM16,16h1.5v1.5H16V16zM17.5,14.5H19V16h-1.5V14.5zM17.5,17.5H19V19h-1.5V17.5zM22,7h-2V4h-3V2h5V7zM22,22v-5h-2v3h-3v2H22zM2,22h5v-2H4v-3H2V22zM2,2v5h2V4h3V2H2z")
    @Deprecated(NO_ROW_ICONS) val Delete = legacy("delete", "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z")
    @Deprecated(NO_ROW_ICONS) val CellularOff = legacy("signal_cellular_off", "M21,1l-8.59,8.59L21,18.18V1zM4.77,4.5L3.5,5.77l6.36,6.36L1,21h17.73l2,2L22,21.73 4.77,4.5z")
}

/** res/drawable/ic_stat_fullbars.xml: two short bars, a beam, and the tall bar that holds it up. */
internal const val MARK_PATH = "M4,16h3.5v4h-3.5z M10,12h3.5v8h-3.5z M4,4h15.5v2.5h-15.5z M16,4h3.5v16h-3.5z"

/** The meter glyph's geometry, shared by the drawing and its test. */
internal object Meter {
    const val BARS = 4
    val barWidth = 3.dp
    val gap = 2.dp
    val heights = listOf(5.dp, 8.dp, 11.dp, 14.dp)
    val outline = 1.2.dp
    val width: Dp = barWidth * BARS + gap * (BARS - 1)
    val height: Dp = heights.last()

    /** Which bars are filled at [level] (clamped to 0..4). */
    fun filled(level: Int): List<Boolean> = List(BARS) { it < level.coerceIn(0, BARS) }
}

/**
 * Four stepped bars, 3dp wide with 2dp gaps, 5/8/11/14dp tall. The first [level] are filled; the rest are 1.2dp
 * outlines, dashed when [dashed] (connecting, or the link is lost). Decorative: the text next to it says the state.
 */
@Suppress("ModifierParameter") // parameter order is fixed: existing call sites depend on it
@Composable
fun MeterGlyph(level: Int, color: Color, dashed: Boolean = false, modifier: Modifier = Modifier) {
    Canvas(modifier.size(Meter.width, Meter.height)) {
        val bw = Meter.barWidth.toPx()
        val step = bw + Meter.gap.toPx()
        val ow = Meter.outline.toPx()
        val dash = if (dashed) PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 1.5.dp.toPx())) else null
        Meter.filled(level).forEachIndexed { i, on ->
            val h = Meter.heights[i].toPx()
            val left = i * step
            val top = size.height - h
            if (on) {
                drawRect(color, Offset(left, top), Size(bw, h))
            } else {
                drawRect(color, Offset(left + ow / 2, top + ow / 2), Size(bw - ow, h - ow), style = Stroke(ow, pathEffect = dash))
            }
        }
    }
}

/** Per-bar states for [MarkGlyph] and engine BrandDraw.mark: the same numbers on screen and on stream. */
object MarkBar {
    const val EMPTY = 0
    const val FULL = 1
    const val AMBER = 2
    const val DASHED = 3

    /**
     * The recap verdict: all bars full when held the whole way, one hollow bar per drop, up to 3. Bars empty from the
     * short end, so the tall bar holding the beam goes last.
     */
    fun forDrops(drops: Int): IntArray = IntArray(3) { if (it < drops.coerceIn(0, 3)) EMPTY else FULL }
}

/**
 * The mark with per-bar states ([MarkBar]): the recap verdict (one hollow bar per drop) and anywhere else a bar has to
 * say something. [bars] is left to right; the beam is always drawn. Amber uses the theme's Weak health colour.
 */
@Composable
fun MarkGlyph(bars: IntArray, color: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val amber = LocalHealthColors.current.weak
    Canvas(modifier.size(size)) { drawMark(bars, color, amber) }
}

/** Bar rectangles of the mark on its 24-unit grid: left, top, right, bottom. The tall bar starts under the beam. */
private val markBars = listOf(
    floatArrayOf(4f, 16f, 7.5f, 20f),
    floatArrayOf(10f, 12f, 13.5f, 20f),
    floatArrayOf(16f, 4f, 19.5f, 20f),
)

private fun DrawScope.drawMark(bars: IntArray, color: Color, amber: Color) {
    val s = size.minDimension / 24f
    drawRect(color, Offset(4f * s, 4f * s), Size(15.5f * s, 2.5f * s))
    val ow = 1f * s
    markBars.forEachIndexed { i, r ->
        val state = bars.getOrElse(i) { MarkBar.FULL }
        val (l, t, rr, b) = r.toList()
        when (state) {
            MarkBar.FULL, MarkBar.AMBER -> drawRect(
                if (state == MarkBar.AMBER) amber else color, Offset(l * s, t * s), Size((rr - l) * s, (b - t) * s),
            )
            else -> {
                val top = if (i == 2) 6.5f else t // hollow tall bar: outline below the beam, not through it
                val dash = if (state == MarkBar.DASHED) PathEffect.dashPathEffect(floatArrayOf(1.5f * s, 1f * s)) else null
                drawRect(
                    color, Offset(l * s + ow / 2, top * s + ow / 2), Size((rr - l) * s - ow, (b - top) * s - ow),
                    style = Stroke(ow, pathEffect = dash),
                )
            }
        }
    }
}
