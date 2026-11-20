package app.irlsupreme.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The four motion tokens and nothing else: Tick, Settle, Enter, Exit. Motion carries a signal or it doesn't
 * happen, so there's no "fade in for polish" spec here to reach for.
 */
object FbMotion {
    /** Text and glyph swaps, meter steps, key inversion, state squares, the headline roll. */
    const val TICK_MS = 120
    /** Tick moves in two steps: the halfway value held this long, then the final value. */
    const val TICK_HOLD_MS = 60
    const val ENTER_MS = 280
    const val EXIT_MS = 180
    const val SETTLE_DAMPING = 0.85f
    const val SETTLE_STIFFNESS = 700f
    /** How far a headline rolls: worse rolls down (out 6dp down, in from 6dp above), better rolls up. */
    const val ROLL_DP = 6

    /** Things arriving: END band wipe, alert wipe-in, dock rise, recap count-up, worse-direction pill growth. */
    val Enter: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** Things leaving: alert wipe-out, sheet dismiss, recap collapse. */
    val Exit: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    /**
     * Two hard steps instead of a curve, so a swap reads as a mechanical click rather than a fade: 0 at the start,
     * 0.5 for the first half, 1 from the halfway point on.
     */
    val TickEasing: Easing = Easing { f -> if (f <= 0f) 0f else if (f < 0.5f) 0.5f else 1f }

    /** The one spring: pill growth toward a better or neutral state, the Scenes sheet rise, the preflight expand. */
    fun <T> settle(): SpringSpec<T> = spring(dampingRatio = SETTLE_DAMPING, stiffness = SETTLE_STIFFNESS)

    fun <T> tick(): AnimationSpec<T> = tween(durationMillis = TICK_MS, easing = TickEasing)

    fun <T> enter(): AnimationSpec<T> = tween(durationMillis = ENTER_MS, easing = Enter)

    fun <T> exit(): AnimationSpec<T> = tween(durationMillis = EXIT_MS, easing = Exit)

    /** The same tokens with reduce motion applied: Tick, Enter and Exit become one-frame cuts and Settle snaps. */
    fun <T> tick(reduced: Boolean): AnimationSpec<T> = if (reduced) snap() else tick()
    fun <T> enter(reduced: Boolean): AnimationSpec<T> = if (reduced) snap() else enter()
    fun <T> exit(reduced: Boolean): AnimationSpec<T> = if (reduced) snap() else exit()
    fun <T> settle(reduced: Boolean): AnimationSpec<T> = if (reduced) snap() else settle()

    /**
     * Which token a size change may use. "Bounce is a reward": a change toward a worse state may only use Enter (or a
     * cut); the spring is for better or neutral changes. Pure, so the rule is unit-tested rather than remembered.
     */
    fun growthFor(direction: Change, reduced: Boolean): Token = when {
        reduced -> Token.CUT
        direction == Change.WORSE -> Token.ENTER
        else -> Token.SETTLE
    }

    /** [growthFor] as a spec, for animate*AsState on the pill's width and height. */
    fun <T> growth(direction: Change, reduced: Boolean): AnimationSpec<T> = when (growthFor(direction, reduced)) {
        Token.SETTLE -> settle()
        Token.ENTER -> enter()
        Token.TICK -> tick()
        Token.EXIT -> exit()
        Token.CUT -> snap()
    }

    /**
     * Vertical offset sign for the headline roll: +1 rolls down (worse), −1 rolls up (better), 0 swaps in place.
     * The outgoing line moves by `sign × ROLL_DP`; the incoming one starts at `−sign × ROLL_DP`.
     */
    fun rollSign(direction: Change): Int = when (direction) {
        Change.WORSE -> 1
        Change.BETTER -> -1
        Change.NEUTRAL -> 0
    }

    /**
     * Whether the user asked for no animations (Developer options or Accessibility "Remove animations"). Read once per
     * screen: it changes rarely, and Settings.Global has no cheap observer. Hold to end stays clock-timed either way.
     */
    fun reduced(context: Context): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    @Composable
    fun rememberReduced(): Boolean {
        val context = LocalContext.current
        return remember(context) { reduced(context) }
    }

    /** The direction of a state change, as the motion rules see it. */
    enum class Change { BETTER, NEUTRAL, WORSE }

    /** The tokens by name, plus a cut. */
    enum class Token { TICK, SETTLE, ENTER, EXIT, CUT }
}
