package app.irlsupreme.ui.theme

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The haptic vocabulary. Each pattern means one thing, so a streamer who can't look at the phone still
 * knows what happened: a click for their own toggle, a double low tick for "worse", three for a lost camera.
 */
enum class HapticPattern { KEY, TOGGLE, WENT_LIVE, WORSE, CAMERA_LOST, RECONNECTING, RECOVERED, ALERT, HOLD_DONE, HOLD_RELEASED, COPIED, SIGNED_IN }

/**
 * Plays [HapticPattern]s with VibrationEffect.Composition primitives (crisper and more distinct than View constants)
 * when the motor supports every primitive in the pattern, and falls back to today's View constants when it doesn't.
 *
 * Touch usage, so the system's touch-feedback intensity applies, and nothing plays when the user turned touch feedback
 * off (HAPTIC_FEEDBACK_ENABLED = 0): a composition would otherwise bypass that switch, which View haptics respect.
 * Health patterns are driven by the debounced `announced` state on the Live screen, never by raw snapshots.
 */
object FbHaptics {
    /** One primitive in a composition: [delayMs] after the previous one ends. */
    data class Step(val primitive: Int, val scale: Float, val delayMs: Int = 0)

    /** One View constant in the fallback, [delayMs] after the previous one. */
    data class Fallback(val constant: Int, val delayMs: Int = 0)

    /** The composition for [p]. Pure, so the vocabulary is unit-tested. */
    fun steps(p: HapticPattern): List<Step> = when (p) {
        HapticPattern.KEY -> listOf(Step(Composition.PRIMITIVE_TICK, 0.4f))
        HapticPattern.TOGGLE -> listOf(Step(Composition.PRIMITIVE_CLICK, 0.7f))
        HapticPattern.WENT_LIVE -> listOf(Step(Composition.PRIMITIVE_QUICK_RISE, 0.5f), Step(Composition.PRIMITIVE_CLICK, 1f, 90))
        HapticPattern.WORSE -> listOf(Step(Composition.PRIMITIVE_LOW_TICK, 0.8f), Step(Composition.PRIMITIVE_LOW_TICK, 0.8f, 110))
        HapticPattern.CAMERA_LOST -> List(3) { Step(Composition.PRIMITIVE_LOW_TICK, 0.8f, if (it == 0) 0 else 90) }
        HapticPattern.RECONNECTING -> listOf(Step(Composition.PRIMITIVE_QUICK_FALL, 1f), Step(Composition.PRIMITIVE_LOW_TICK, 1f, 60))
        HapticPattern.RECOVERED -> listOf(Step(Composition.PRIMITIVE_QUICK_RISE, 0.4f), Step(Composition.PRIMITIVE_TICK, 0.8f, 60))
        HapticPattern.ALERT -> listOf(Step(Composition.PRIMITIVE_TICK, 0.5f), Step(Composition.PRIMITIVE_TICK, 0.5f, 70))
        HapticPattern.HOLD_DONE -> listOf(Step(Composition.PRIMITIVE_THUD, 1f))
        HapticPattern.HOLD_RELEASED -> listOf(Step(Composition.PRIMITIVE_LOW_TICK, 0.4f))
        HapticPattern.COPIED -> listOf(Step(Composition.PRIMITIVE_TICK, 0.6f))
        HapticPattern.SIGNED_IN -> listOf(Step(Composition.PRIMITIVE_CLICK, 0.8f))
    }

    /** What plays on a motor without the primitives: the View constants the Live screen used before. */
    fun fallback(p: HapticPattern): List<Fallback> = when (p) {
        HapticPattern.KEY -> listOf(Fallback(HapticFeedbackConstants.KEYBOARD_TAP))
        HapticPattern.TOGGLE, HapticPattern.WENT_LIVE, HapticPattern.RECOVERED,
        HapticPattern.COPIED, HapticPattern.SIGNED_IN -> listOf(Fallback(HapticFeedbackConstants.CONFIRM))
        HapticPattern.WORSE, HapticPattern.CAMERA_LOST -> listOf(Fallback(HapticFeedbackConstants.REJECT))
        HapticPattern.RECONNECTING, HapticPattern.HOLD_DONE -> listOf(Fallback(HapticFeedbackConstants.LONG_PRESS))
        HapticPattern.ALERT -> listOf(Fallback(HapticFeedbackConstants.CLOCK_TICK), Fallback(HapticFeedbackConstants.CLOCK_TICK, 70))
        HapticPattern.HOLD_RELEASED -> emptyList()
    }

    /** The hold-to-end ticks get firmer as the hold goes on: TICK at 0.2 + 0.4·i/7 for steps 1..7. */
    fun holdStepScale(step: Int): Float = 0.2f + 0.4f * step.coerceIn(1, HOLD_STEPS) / HOLD_STEPS

    /** Hold to end lights 8 ticks; steps 1..7 buzz with [holdStep] and the 8th is [HapticPattern.HOLD_DONE]. */
    const val HOLD_STEPS = 7

    fun play(view: View, p: HapticPattern) {
        if (!enabled(view.context)) return
        if (compose(view.context, steps(p))) return
        fireFallback(view, fallback(p))
    }

    /** One of the hold-to-end perimeter ticks, [step] 1..7; anything else is ignored. */
    fun holdStep(view: View, step: Int) {
        if (step !in 1..HOLD_STEPS || !enabled(view.context)) return
        if (compose(view.context, listOf(Step(Composition.PRIMITIVE_TICK, holdStepScale(step))))) return
        if (step % 2 == 0) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun enabled(context: Context): Boolean =
        Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0

    private fun vibrator(context: Context): Vibrator? =
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator

    /**
     * Plays [steps] as one composition; false when this motor can't, so the caller falls back. Every primitive comes
     * from [steps]/[holdStep], which only use Composition.PRIMITIVE_* constants; lint can't follow them through a list.
     */
    @SuppressLint("WrongConstant")
    private fun compose(context: Context, steps: List<Step>): Boolean {
        val vibrator = vibrator(context) ?: return false
        if (!vibrator.hasVibrator()) return false
        val ids = steps.map { it.primitive }.distinct().toIntArray()
        if (!vibrator.areAllPrimitivesSupported(*ids)) return false
        val effect = VibrationEffect.startComposition().apply {
            steps.forEach { addPrimitive(it.primitive, it.scale, it.delayMs) }
        }.compose()
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
        }
        return true
    }

    private fun fireFallback(view: View, list: List<Fallback>) {
        var at = 0L
        list.forEach { f ->
            at += f.delayMs
            if (at == 0L) view.performHapticFeedback(f.constant) else view.postDelayed({ view.performHapticFeedback(f.constant) }, at)
        }
    }
}
