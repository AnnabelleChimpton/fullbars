package app.irlsupreme.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/*
 * The tokens (Tokens.kt) and type (Type.kt) handed to Compose. Steady, HealthColors, LiveHealth, LightHealth and
 * LocalHealthColors keep their names; they now live in Tokens.kt, same package, so imports are unchanged.
 */

/**
 * Material's scheme built from the surface ladder, so stock pieces we still use (dialogs, text fields, snackbars)
 * match the custom ones. primary = the CTA colour; surfaceTint = the page, which switches tonal elevation off (a tint
 * would warm surfaces off the ladder).
 */
internal fun materialScheme(c: FbColors, dark: Boolean): ColorScheme =
    (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.cta, onPrimary = c.onCta,
        primaryContainer = c.s3, onPrimaryContainer = c.text,
        inversePrimary = c.bg,
        secondary = c.text2, onSecondary = c.bg,
        secondaryContainer = c.s3, onSecondaryContainer = c.text,
        tertiary = c.text2, onTertiary = c.bg,
        tertiaryContainer = c.s3, onTertiaryContainer = c.text,
        background = c.bg, onBackground = c.text,
        surface = c.bg, onSurface = c.text,
        surfaceVariant = c.s3, onSurfaceVariant = c.text2,
        surfaceTint = c.bg,
        inverseSurface = c.text, inverseOnSurface = c.bg,
        error = c.error, onError = c.bg,
        // A failure says so in words next to its cause, not with a red slab.
        errorContainer = c.s1, onErrorContainer = c.text,
        outline = c.ruleStrong, outlineVariant = c.rule,
        scrim = Color.Black,
        surfaceBright = c.s4, surfaceDim = c.bg,
        surfaceContainerLowest = c.bg, surfaceContainerLow = c.s1, surfaceContainer = c.s2,
        surfaceContainerHigh = c.s3, surfaceContainerHighest = c.s4,
    )

private val DarkScheme = materialScheme(DarkFb, dark = true)
private val LightScheme = materialScheme(LightFb, dark = false)

/** Only the allowed radii: rS for the smallest pieces, rM for everything Material would round (dialogs, sheets, fields). */
private val FbShapes = Shapes(
    extraSmall = RoundedCornerShape(FbShape.rS),
    small = RoundedCornerShape(FbShape.rM),
    medium = RoundedCornerShape(FbShape.rM),
    large = RoundedCornerShape(FbShape.rM),
    extraLarge = RoundedCornerShape(FbShape.rM),
)

/** The app theme. [forceDark] for the Live screen, which is always dark whatever the system says. */
@Composable
fun FullbarsTheme(forceDark: Boolean = false, content: @Composable () -> Unit) {
    val dark = forceDark || isSystemInDarkTheme()
    CompositionLocalProvider(
        LocalFbColors provides if (dark) DarkFb else LightFb,
        LocalHealthColors provides if (dark) LiveHealth else LightHealth,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = FullbarsTypography,
            shapes = FbShapes,
            content = content,
        )
    }
}

/** Shorthand for the current [FbColors]: `Fb.colors.text`. */
object Fb {
    val colors: FbColors
        @Composable @ReadOnlyComposable get() = LocalFbColors.current
    val health: HealthColors
        @Composable @ReadOnlyComposable get() = LocalHealthColors.current
}
