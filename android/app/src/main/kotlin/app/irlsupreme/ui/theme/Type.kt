package app.irlsupreme.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.irlsupreme.engine.R as EngineR

/*
 * Typography. Archivo and JetBrains Mono are bundled once, in the engine (the stream graphics need the
 * same faces), and every Material slot is remapped to these roles so nothing falls back to Roboto.
 */

/**
 * Archivo, one static instance per role. Compose picks a font by weight only, so each weight maps to exactly one
 * width: 400–600 are SemiCondensed (body, values, labels), 700–800 are Condensed (headlines, caps, the CTA). The
 * ExtraCondensed display cut shares weight 800, so it lives in [ArchivoDisplay].
 */
val Archivo = FontFamily(
    Font(EngineR.font.archivo_sc_400, FontWeight.Normal),
    Font(EngineR.font.archivo_sc_500, FontWeight.Medium),
    Font(EngineR.font.archivo_sc_600, FontWeight.SemiBold),
    Font(EngineR.font.archivo_c_700, FontWeight.Bold),
    Font(EngineR.font.archivo_c_800, FontWeight.ExtraBold),
)

/** Archivo ExtraCondensed 800: the display face (first run, recap verdict). */
val ArchivoDisplay = FontFamily(Font(EngineR.font.archivo_xc_800, FontWeight.ExtraBold))

/** JetBrains Mono, for figures only: numbers, units, durations, codes, IPs, Mb/s. Words in value position use Archivo. */
val JetMono = FontFamily(
    Font(EngineR.font.jetbrainsmono_400, FontWeight.Normal),
    Font(EngineR.font.jetbrainsmono_500, FontWeight.Medium),
)

/**
 * Tabular figures everywhere a number can change, so digits don't jitter as they tick. Mono is tabular by design, so
 * this only changes Archivo. The slashed/dotted zero stays off: JetBrains Mono's plain 0 already reads.
 */
private const val TNUM = "tnum"

/** Glyphs centred in the stated line height, so the 4dp-grid spacing lands where it should. */
private val LineBox = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(
    family: FontFamily,
    weight: FontWeight,
    size: Int,
    line: Int,
    tracking: TextUnit = 0.em,
) = TextStyle(
    fontFamily = family, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking,
    fontFeatureSettings = TNUM, lineHeightStyle = LineBox,
)

/**
 * The app scale. `label` is set in caps: pass the text already upper-cased (`text.uppercase()`), since a TextStyle
 * can't transform case and TalkBack should read the real word anyway (caps are spelled out letter by letter otherwise).
 */
object FbType {
    val display = style(ArchivoDisplay, FontWeight.ExtraBold, 52, 48, (-0.01).em)
    val displayS = style(ArchivoDisplay, FontWeight.ExtraBold, 28, 30, (-0.005).em)
    val title = style(Archivo, FontWeight.ExtraBold, 30, 32)
    val wordmark = style(Archivo, FontWeight.ExtraBold, 23, 26)
    val cta = style(Archivo, FontWeight.ExtraBold, 22, 26)
    /** The Live CTA on Home ("Go live", "Go live now") is bigger than Test's: the two differ in more than a word. */
    val ctaLive = style(Archivo, FontWeight.ExtraBold, 26, 30)
    val row = style(Archivo, FontWeight.SemiBold, 16, 20)
    /** Words in value position: logins, "Phone · back", "On · ready", titles. */
    val rowValue = style(Archivo, FontWeight.Medium, 15, 20)
    /** Figures in value position. */
    val value = style(JetMono, FontWeight.Medium, 14, 18)
    val label = style(Archivo, FontWeight.Bold, 11, 14, 0.12.em)
    val help = style(Archivo, FontWeight.Normal, 13, 18)
    val footnote = style(Archivo, FontWeight.Normal, 12, 16)
    /** The recap duration. */
    val duration = style(JetMono, FontWeight.Normal, 60, 64, (-0.02).em)
    /** The Twitch device code. */
    val code = style(JetMono, FontWeight.Medium, 32, 36, 0.08.em)
}

/**
 * The Live scale: exactly three steps, so the screen over video can't grow a fourth size. L3 is caps (see [FbType]).
 * Units keep their case ("AT&T Mb/s", never "MB/S"). Chat names are L2 at SemiBold (`L2.copy(fontWeight = SemiBold)`).
 */
object FbLiveType {
    /** The pill headline. */
    val L1 = style(Archivo, FontWeight.Bold, 18, 22)
    /** Pill line 2; chat messages use it at Normal weight. */
    val L2 = style(Archivo, FontWeight.Medium, 14, 18)
    /** Instrument values and every live figure. */
    val L2Mono = style(JetMono, FontWeight.Medium, 14, 18)
    /** Instrument labels and key labels, in caps. */
    val L3 = style(Archivo, FontWeight.Bold, 11, 14, 0.12.em)
}

/**
 * Every Material slot remapped to a role, so stock components (dialogs, text fields, snackbars) speak the same type.
 * Dialog titles use headlineSmall, buttons labelLarge, text fields bodyLarge.
 */
internal val FullbarsTypography = Typography(
    displayLarge = FbType.display,
    displayMedium = FbType.display,
    displaySmall = FbType.displayS,
    headlineLarge = FbType.title,
    headlineMedium = FbType.wordmark,
    headlineSmall = FbType.cta,
    titleLarge = FbType.wordmark,
    titleMedium = FbType.row,
    titleSmall = FbType.row.copy(fontSize = 14.sp, lineHeight = 18.sp),
    bodyLarge = FbType.help.copy(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = FbType.help,
    bodySmall = FbType.footnote,
    labelLarge = FbType.row.copy(fontSize = 15.sp),
    labelMedium = FbType.rowValue.copy(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = FbType.label,
)

/** Deprecated: the device condensed family is gone. Archivo Condensed (700/800) stands in so old call sites still build. */
@Deprecated("Use FbType / FbLiveType roles (Archivo).", ReplaceWith("Archivo", "app.irlsupreme.ui.theme.Archivo"))
val Condensed = FontFamily(
    Font(EngineR.font.archivo_c_700, FontWeight.Bold),
    Font(EngineR.font.archivo_c_800, FontWeight.ExtraBold),
)

/** Deprecated: figures are JetBrains Mono now. */
@Deprecated("Use FbType.value or FbLiveType.L2Mono.", ReplaceWith("FbLiveType.L2Mono", "app.irlsupreme.ui.theme.FbLiveType"))
val Numeric = TextStyle(fontFamily = JetMono, fontFeatureSettings = TNUM)

/** Deprecated: code-like text is FbType.value (or FbType.code for the device code). */
@Deprecated("Use FbType.value, or FbType.code for the device code.", ReplaceWith("FbType.value", "app.irlsupreme.ui.theme.FbType"))
val Mono = FbType.value.copy(lineHeight = TextUnit.Unspecified) // old call sites resize it; a fixed line would clip
