package app.irlsupreme.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlin.math.pow

/*
 * Design tokens for the "Steady, built" look. Every colour, radius and stroke the app draws comes from here, so a
 * screen can't drift into a fourth radius or a near-miss grey. Other files code against these names, so rename them
 * only together with every call site.
 */

/**
 * The achromatic palette: Ink, Bone and the warm graphite between them. There is no brand hue; the only colours are
 * health, error and the Twitch button. Steady.Ink is #0D0D0C (one step warmer than the old #0D0D0D) so Ink and the
 * s1–s4 ladder read as one family.
 */
object Steady {
    val Bone = Color(0xFFF4F1EA)
    val Graphite = Color(0xFF9AA3AE)
    /** Graphite fails contrast on Bone; this is the light-mode text variant. */
    val GraphiteDark = Color(0xFF5B636D)
    val Ink = Color(0xFF0D0D0C)
    val TwitchPurple = Color(0xFF9146FF)
}

/** Health colours. Text and icons on them are always [onHealth] (Ink passes AA on every one). */
@Immutable
data class HealthColors(
    val great: Color,
    val weak: Color,
    val cameraLost: Color,
    val reconnecting: Color,
    val privacy: Color,
    val neutral: Color,
    val onHealth: Color,
)

/**
 * Brand values: the Live screen and dark setup. Exact, and used only where health matters: the pill, the link meters,
 * the Weak and Camera-lost cards, and battery or temperature once they're a problem. Never on keys, toggles or chat.
 */
val LiveHealth = HealthColors(
    great = Color(0xFF34E07A), weak = Color(0xFFFFC233), cameraLost = Color(0xFFFF8A3D),
    reconnecting = Color(0xFFFF6B6B), privacy = Color(0xFFF2F2F2), neutral = Steady.Graphite, onHealth = Steady.Ink,
)

/** Darker variants for glyphs and values on Bone (all ≥ 4.8:1 on #F4F1EA). */
val LightHealth = HealthColors(
    great = Color(0xFF137A3E), weak = Color(0xFF8A6100), cameraLost = Color(0xFFA84A0F),
    reconnecting = Color(0xFFB42318), privacy = Steady.GraphiteDark, neutral = Steady.GraphiteDark, onHealth = Color.White,
)

val LocalHealthColors = staticCompositionLocalOf { LiveHealth }

/**
 * The app's colour roles. `s1`…`s4` are a surface ladder for sunk panels and pressed/selected states;
 * Home and Settings have no containers, so most screens only ever touch [bg], [rule], [text] and [text2].
 */
@Immutable
data class FbColors(
    val bg: Color,
    val s1: Color,
    val s2: Color,
    val s3: Color,
    val s4: Color,
    /** 1px hairlines and separators. Decorative, so it needs no contrast. */
    val rule: Color,
    /** Tab outline and toggle track off. About 2:1, so it's always paired with a text label. */
    val ruleStrong: Color,
    val text: Color,
    val text2: Color,
    /** Glyph off-states and the switch-off thumb only. */
    val glyphOff: Color,
    /** The one filled CTA per screen. */
    val cta: Color,
    val onCta: Color,
    /** Verified setup failures only; never "not done yet" and never on the Live screen. */
    val error: Color,
    /** The single 8dp dot on the LIVE tab on Home. */
    val onAir: Color,
    /** "Continue with Twitch" only. */
    val twitch: Color,
    val onTwitch: Color,
)

/** App dark (the default). Ratios are against [FbColors.bg] unless noted otherwise; TokenContrastTest checks them. */
val DarkFb = FbColors(
    bg = Steady.Ink,
    s1 = Color(0xFF151412),
    s2 = Color(0xFF1C1B18),
    s3 = Color(0xFF24221F),
    s4 = Color(0xFF2D2B27),
    rule = Color(0xFF2E2C28),
    ruleStrong = Color(0xFF4A4741),
    text = Steady.Bone,
    text2 = Color(0xFFA7A196),
    glyphOff = Steady.Graphite,
    cta = Steady.Bone,
    onCta = Steady.Ink,
    error = Color(0xFFFF6B6B),
    onAir = Color(0xFFFF6B6B),
    twitch = Steady.TwitchPurple,
    onTwitch = Color.White,
)

/** App light. The CTA inverts to Ink; the Live screen never uses this (it is always dark). */
val LightFb = FbColors(
    bg = Steady.Bone,
    s1 = Color(0xFFEFEBE3),
    s2 = Color(0xFFE9E5DC),
    s3 = Color(0xFFE3DED4),
    s4 = Color(0xFFDCD7CC),
    rule = Color(0xFFD6D0C4),
    ruleStrong = Color(0xFFA9A396),
    text = Steady.Ink,
    text2 = Steady.GraphiteDark,
    glyphOff = Steady.GraphiteDark,
    cta = Steady.Ink,
    onCta = Steady.Bone,
    error = Color(0xFFB42318),
    onAir = Color(0xFFB42318),
    twitch = Steady.TwitchPurple,
    onTwitch = Color.White,
)

val LocalFbColors = staticCompositionLocalOf { DarkFb }

/**
 * Colour over live video. Every pair is measured against the worst case, the scrim composited over pure
 * white (#2A2A29), so it holds over any frame the camera sees. Graphite is retired here: it fails that test.
 */
object OverVideo {
    /** Ink at 88%: the plate, chat lines, Details panel and notice tags. Over white it becomes #2A2A29. */
    const val SCRIM_ALPHA = 0.88f
    val scrim = Steady.Ink.copy(alpha = SCRIM_ALPHA)
    /** 12.74:1 over the worst case. */
    val text = Steady.Bone
    /** 8.52:1: instrument labels, chat messages, the connecting `···`. */
    val text2 = Color(0xFFC2C8CF)
    /** 3.96:1 (5.36 on Ink): 1.5dp key outlines, the END hatch band, the Connecting pill outline. */
    val keyOutline = Color(0xFF8A867E)
    val keyEngagedFill = Steady.Bone
    val keyEngagedContent = Steady.Ink
    /** The 1.5dp keyline on every pill: 19.44:1 against white, so a light pill never melts into a white sky. */
    val keyline = Steady.Ink
    /** The scrim's colour where the video under it is pure white: what every over-video ratio is measured against. */
    val worstCase = Color(0xFF2A2A29)
}

/**
 * Three radii and a handful of strokes. Only [rS], [rM] and fully round (the health pill) are allowed, plus
 * the 20dp radio circle; nothing else.
 */
object FbShape {
    /** Tags, chat lines, slate plates, thumbnails, the Details panel, state squares. */
    val rS = 2.dp
    /** Keys, CTA and bus tabs, toggle track, Scenes sheet top corners, device-code plate. */
    val rM = 6.dp
    /** Icons, keys and the pill keyline. */
    val stroke = 1.5.dp
    /** Outlined pills (Privacy, chosen scene). */
    val outline = 2.dp
    /** The Reconnecting dashed outline. */
    val dash = 2.5.dp
    /** Dash pattern for "not real yet / not public": 6 on, 4 off. */
    val dashOn = 6.dp
    val dashOff = 4.dp
    /** Hatching means caution, only: 135°, 2.5 on, 3.5 off. */
    val hatchOn = 2.5.dp
    val hatchOff = 3.5.dp

    val small = RoundedCornerShape(rS)
    val medium = RoundedCornerShape(rM)
}

/** Spacing: a 4dp grid. Named so the gutters and row floors can't drift screen by screen. */
object FbSpace {
    val gutter = 16.dp
    val liveGutter = 12.dp
    /** Tappable rows: the whole row is the target. */
    val rowTappable = 54.dp
    val rowStatic = 48.dp
    val sectionTop = 24.dp
    val sectionBottom = 8.dp
    /** The minimum touch target, never scaled down. */
    val target = 48.dp
}

/**
 * WCAG 2.x contrast maths. Pure, so the token ratios noted here are checked by a unit test rather than trusted, and so
 * other streams can assert a new pair before they ship it.
 */
object Wcag {
    private fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    /** Relative luminance of an opaque sRGB colour (alpha is ignored; composite first with [over]). */
    fun luminance(argb: Int): Double =
        0.2126 * channel((argb shr 16) and 0xFF) + 0.7152 * channel((argb shr 8) and 0xFF) + 0.0722 * channel(argb and 0xFF)

    /** Contrast ratio between two opaque colours, 1.0 to 21.0. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    fun contrast(a: Color, b: Color): Double = contrast(a.toArgb(), b.toArgb())

    /** [fg] at [alpha] composited over opaque [bg], rounded to 8-bit channels the way the screen shows it. */
    fun over(fg: Int, alpha: Float, bg: Int): Int {
        fun mix(shift: Int): Int {
            val f = (fg shr shift) and 0xFF
            val b = (bg shr shift) and 0xFF
            return Math.round(f * alpha + b * (1 - alpha)).coerceIn(0, 255)
        }
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** A translucent [fg] (its own alpha) over opaque [bg]. */
    fun over(fg: Color, bg: Color): Int = over(fg.copy(alpha = 1f).toArgb(), fg.alpha, bg.toArgb())
}
