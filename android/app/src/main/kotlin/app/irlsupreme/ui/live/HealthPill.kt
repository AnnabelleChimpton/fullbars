package app.irlsupreme.ui.live

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.irlsupreme.engine.EngineState
import app.irlsupreme.engine.Health
import app.irlsupreme.engine.Scene
import app.irlsupreme.ui.FbIcons
import app.irlsupreme.ui.MeterGlyph
import app.irlsupreme.ui.drawHatch
import app.irlsupreme.ui.firstConnectHint
import app.irlsupreme.ui.plainFailure
import app.irlsupreme.ui.sceneName
import app.irlsupreme.ui.theme.FbLiveType
import app.irlsupreme.ui.theme.FbMotion
import app.irlsupreme.ui.theme.FbShape
import app.irlsupreme.ui.theme.LocalHealthColors
import app.irlsupreme.ui.theme.OverVideo
import kotlinx.coroutines.delay

/*
 * The health pill: one morphing object, the single source of truth on the Live screen. It
 * grows by fill, outline, size and glyph, so every pair of states differs in grayscale too.
 */

/** How the pill is drawn. Each look is one visual state. */
internal enum class PillLook {
    CONNECTING, GREAT, WEAK, HOT, CAMERA_LOST, RECONNECTING, FAILED, PRIVACY, PRIVACY_MIC_ON, SCENE, ENDING, OFF;

    /** Problems take the full slot: the size itself says "look at me". */
    val fullSlot: Boolean get() = this == WEAK || this == HOT || this == CAMERA_LOST || this == RECONNECTING || this == FAILED

    /** A solid light fill (amber, orange): its text is Ink. */
    val lightFill: Boolean get() = this == WEAK || this == HOT || this == CAMERA_LOST
}

/** The pill's glyph, named so the model stays pure. */
internal enum class PillGlyph { METER_STEPPING, METER_FULL, METER_ONE, THERMO, CAM_OFF, ARROWS, X, EYE_OFF, CARD, CARD_CUE, CARD_STOP, STOP }

/**
 * What the pill says. [spoken] is what TalkBack announces: the category and plain reason only, never the attempt count
 * or a raw error. [third] is a nudge under [second] (a first connect that keeps failing); not spoken, since it would
 * re-announce on every try.
 */
internal data class PillModel(
    val look: PillLook,
    val glyph: PillGlyph,
    val headline: String,
    val second: String?,
    val spoken: String,
    val third: String? = null,
)

/**
 * The pill for [health] (the announced health; see LiveScreen). Every card line says
 * what viewers see *and* hear: cards hide the camera, never the mic, so a pill must not imply silence it isn't giving.
 */
internal fun pillModel(state: EngineState, health: Health, isTest: Boolean, bonded: Boolean, ending: Boolean): PillModel {
    if (ending) return PillModel(PillLook.ENDING, PillGlyph.STOP, "Ending…", null, "Ending the stream")
    val live = if (isTest) "Test" else "Live"
    val hear = if (state.micMuted) "" else " and hear you"
    val failure = plainFailure(state.lastError, bonded)
    // Before the first connection there's no stream for viewers to see anything of: say why it isn't there yet.
    fun notLiveYet() = PillModel(
        PillLook.RECONNECTING, PillGlyph.ARROWS, "Not live yet", failure ?: "Still trying to connect",
        listOfNotNull("Not live yet", failure).joinToString(". "), third = firstConnectHint(state.reconnectAttempt, bonded),
    )
    // Preview: nothing is sent yet, so nobody sees or hears anything; the plate below says how to go live.
    if (state.preview) return PillModel(
        PillLook.CONNECTING, PillGlyph.EYE_OFF, "Preview · not live", "Only you can see this",
        "Preview, not live. Only you can see this",
    )
    return when (health) {
        Health.GREAT -> PillModel(PillLook.GREAT, PillGlyph.METER_FULL, "$live · Great", null, "$live, great")
        Health.WEAK -> PillModel(PillLook.WEAK, PillGlyph.METER_ONE, "$live · Weak signal", "Quality lowered", "$live, weak signal. Quality lowered")
        Health.HOT -> PillModel(PillLook.HOT, PillGlyph.THERMO, "$live · Phone is hot", "Quality lowered", "$live, phone is hot. Quality lowered")
        Health.CAMERA_LOST -> PillModel(PillLook.CAMERA_LOST, PillGlyph.CAM_OFF, "Camera lost", "Viewers see a slate", "Camera lost. Viewers see a slate")
        Health.WEAK_SCREEN -> PillModel(
            PillLook.WEAK, PillGlyph.METER_ONE, "$live · Weak signal", "Viewers see your Weak signal screen$hear",
            "$live, weak signal. Viewers see your Weak signal screen$hear",
        )
        Health.BRB -> when {
            // Privacy promises silence; with the mic back on it says so, with a hatched (caution) cap.
            state.scene == Scene.PRIVACY && !state.micMuted -> PillModel(
                PillLook.PRIVACY_MIC_ON, PillGlyph.EYE_OFF, "Privacy · mic ON", "Viewers see your privacy screen and hear you",
                "Privacy, mic on. Viewers see your privacy screen and hear you",
            )
            state.scene == Scene.PRIVACY || state.micMuted -> {
                val sees = if (state.scene == Scene.PRIVACY) "privacy screen" else "${sceneName(state.scene)} screen"
                PillModel(PillLook.PRIVACY, PillGlyph.EYE_OFF, "Privacy", "Viewers see your $sees · mic off", "Privacy. Viewers see your $sees, mic off")
            }
            else -> {
                val name = sceneName(state.scene)
                val glyph = when (state.scene) {
                    Scene.STARTING_SOON -> PillGlyph.CARD_CUE
                    Scene.ENDING -> PillGlyph.CARD_STOP
                    else -> PillGlyph.CARD
                }
                PillModel(PillLook.SCENE, glyph, name, "Viewers see your $name screen and hear you", "$name. Viewers see your $name screen and hear you")
            }
        }
        Health.CONNECTING ->
            if (state.liveSinceMs == 0L && failure != null) notLiveYet()
            else PillModel(PillLook.CONNECTING, PillGlyph.METER_STEPPING, "Connecting", "Not live yet", "Connecting. Not live yet.")
        Health.RECONNECTING ->
            if (state.liveSinceMs == 0L) notLiveYet()
            else {
                // Bonded: the BRB server shows the Signal lost card this phone uploaded (BondedOutput.sendSlateOnce), not
                // the BRB card, which would tell viewers you chose to step away.
                val sees = if (bonded) "Viewers see your Signal lost screen" else "Viewers see a frozen frame"
                PillModel(PillLook.RECONNECTING, PillGlyph.ARROWS, "Reconnecting", sees, "Reconnecting. $sees")
            }
        Health.FAILED -> PillModel(
            PillLook.FAILED, PillGlyph.X, "Couldn't connect", failure, listOfNotNull("Couldn't connect", failure).joinToString(". "),
        )
        Health.OFF -> PillModel(PillLook.OFF, PillGlyph.STOP, "Offline", null, "Offline")
    }
}

/** Losses speak assertively; everything else is polite. */
internal fun pillAssertive(health: Health): Boolean =
    health == Health.RECONNECTING || health == Health.FAILED || health == Health.CAMERA_LOST

/**
 * The pill's shape: fully round while it's one or two lines, then a capped radius so a four-line pill at 200% doesn't
 * spend its width on semicircles. Still the one "full" shape on the screen.
 */
private object PillShape : Shape {
    val MAX_RADIUS = 32.dp
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = minOf(size.height / 2, with(density) { MAX_RADIUS.toPx() })
        return Outline.Rounded(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r)))
    }
}

/** The glyph cap on Camera lost and Privacy·mic on: hatched, so caution reads in grayscale too. */
private val CAP = 28.dp

/**
 * The health pill. [model] is built from the announced health, so the pill, its haptic and its announcement change
 * together: after the 3 s debounce, in one Tick. [previous] is the health it last showed, for the motion
 * direction. [slot] is the full-slot width. Text wraps (no maxLines), so at 200% the pill grows down instead of truncating.
 */
@Composable
internal fun HealthPill(
    model: PillModel,
    health: Health,
    previous: Health?,
    assertive: Boolean,
    slot: Dp,
    details: Boolean,
    reduced: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val h = LocalHealthColors.current
    val look = model.look
    val dir = direction(previous, health)
    val fillTarget = when (look) {
        PillLook.WEAK, PillLook.HOT -> h.weak
        PillLook.CAMERA_LOST -> h.cameraLost
        else -> OverVideo.keyline // Ink
    }
    val fill by animateColorAsState(fillTarget, FbMotion.tick(reduced), label = "pillFill")
    val content = if (look.lightFill) OverVideo.keyline else OverVideo.text
    val headlineColor = if (look == PillLook.RECONNECTING || look == PillLook.FAILED) h.reconnecting else content
    // Width grows toward a worse state with Enter, settles toward a better one with the spring, cuts under reduce motion.
    val growth: FiniteAnimationSpec<IntSize> = when (pillGrowth(previous, health, reduced)) {
        FbMotion.Token.SETTLE -> spring(FbMotion.SETTLE_DAMPING, FbMotion.SETTLE_STIFFNESS)
        FbMotion.Token.ENTER -> tween(FbMotion.ENTER_MS, easing = FbMotion.Enter)
        else -> snap()
    }

    Row(
        modifier
            .semantics(mergeDescendants = true) {
                contentDescription = model.spoken
                liveRegion = if (assertive) LiveRegionMode.Assertive else LiveRegionMode.Polite
            }
            .animateContentSize(growth)
            .then(if (look.fullSlot) Modifier.width(slot) else Modifier.widthIn(max = slot))
            .heightIn(min = 48.dp)
            .height(IntrinsicSize.Min)
            .clip(PillShape)
            .background(fill)
            .pillOutline(look, h.reconnecting, h.privacy)
            .clickable(onClickLabel = if (details) "Hide stream details" else "Show stream details", role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val capped = look == PillLook.CAMERA_LOST || look == PillLook.PRIVACY_MIC_ON
        if (capped) {
            // A hatched cap down the pill's left end, the glyph punched out of it on the fill colour.
            val hatch = if (look == PillLook.CAMERA_LOST) OverVideo.keyline else OverVideo.text
            Box(
                Modifier.fillMaxHeight().width(CAP + 16.dp).drawBehind { drawHatch(hatch) },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.padding(start = 6.dp).size(CAP).background(fill), contentAlignment = Alignment.Center) {
                    PillGlyphView(model.glyph, content, h.great, reduced)
                }
            }
            Box(Modifier.width(10.dp))
        } else {
            Box(Modifier.padding(start = 16.dp, end = 10.dp), contentAlignment = Alignment.Center) {
                PillGlyphView(model.glyph, content, h.great, reduced)
            }
        }
        Column(Modifier.padding(end = 20.dp, top = 8.dp, bottom = 8.dp).clearAndSetSemantics {}) {
            // The headline rolls: down toward worse, up toward better, in one Tick.
            val rollPx = with(LocalDensity.current) { FbMotion.ROLL_DP.dp.roundToPx() }
            AnimatedContent(
                targetState = model.headline,
                transitionSpec = { roll(dir, reduced, rollPx) },
                label = "pillHeadline",
            ) { text ->
                Text(text, style = FbLiveType.L1, color = headlineColor)
            }
            model.second?.let { Text(it, style = FbLiveType.L2, color = content) }
            model.third?.let { Text(it, style = FbLiveType.L2.copy(fontWeight = FontWeight.SemiBold), color = content) }
        }
    }
}

/** Outline per look: the keyline on everything, the Connecting grey, dashed coral, solid privacy/Bone. */
private fun Modifier.pillOutline(look: PillLook, coral: Color, privacy: Color): Modifier = this then when (look) {
    PillLook.CONNECTING, PillLook.ENDING, PillLook.OFF -> Modifier.border(FbShape.stroke, OverVideo.keyOutline, PillShape)
    PillLook.GREAT, PillLook.WEAK, PillLook.HOT, PillLook.CAMERA_LOST -> Modifier.border(FbShape.stroke, OverVideo.keyline, PillShape)
    PillLook.RECONNECTING -> Modifier.drawBehind {
        val w = FbShape.dash.toPx()
        val r = minOf(size.height / 2, PillShape.MAX_RADIUS.toPx()) - w / 2
        drawRoundRect(
            coral, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), cornerRadius = CornerRadius(r),
            style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(FbShape.dashOn.toPx(), FbShape.dashOff.toPx()))),
        )
    }
    PillLook.FAILED -> Modifier.border(FbShape.outline, coral, PillShape)
    PillLook.PRIVACY, PillLook.PRIVACY_MIC_ON -> Modifier.border(FbShape.outline, privacy, PillShape)
    PillLook.SCENE -> Modifier.border(FbShape.outline, OverVideo.text, PillShape)
}

/** The headline roll: out [FbMotion.ROLL_DP] in the direction of change, in from the other side; a swap when neutral. */
private fun roll(dir: FbMotion.Change, reduced: Boolean, rollPx: Int): ContentTransform {
    if (reduced) return EnterTransition.None togetherWith ExitTransition.None
    val sign = FbMotion.rollSign(dir)
    val spec = tween<IntOffset>(FbMotion.TICK_MS, easing = FbMotion.TickEasing)
    val fade = tween<Float>(FbMotion.TICK_MS, easing = FbMotion.TickEasing)
    return ContentTransform(
        slideInVertically(spec) { -sign * rollPx } + fadeIn(fade),
        slideOutVertically(spec) { sign * rollPx } + fadeOut(fade),
        sizeTransform = SizeTransform(clip = true),
    )
}

/** The pill glyph. Connecting steps its meter 1→4 every 250ms; Reconnecting steps its arrows 45° every 150ms. Both freeze under reduce motion. */
@Composable
private fun PillGlyphView(glyph: PillGlyph, tint: Color, great: Color, reduced: Boolean) {
    when (glyph) {
        PillGlyph.METER_FULL -> MeterGlyph(4, great)
        PillGlyph.METER_ONE -> MeterGlyph(1, tint)
        PillGlyph.METER_STEPPING -> {
            var level by remember { mutableIntStateOf(2) }
            if (!reduced) LaunchedEffect(Unit) { while (true) { delay(250); level = level % 4 + 1 } }
            MeterGlyph(level, tint, dashed = true)
        }
        PillGlyph.ARROWS -> {
            var step by remember { mutableIntStateOf(0) }
            if (!reduced) LaunchedEffect(Unit) { while (true) { delay(150); step = (step + 1) % 8 } }
            Icon(FbIcons.Arrows, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp).rotate(step * 45f))
        }
        else -> Icon(iconFor(glyph), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

private fun iconFor(g: PillGlyph): ImageVector = when (g) {
    PillGlyph.THERMO -> FbIcons.Thermo
    PillGlyph.CAM_OFF -> FbIcons.CamOff
    PillGlyph.X -> FbIcons.X
    PillGlyph.EYE_OFF -> FbIcons.EyeOff
    PillGlyph.CARD -> FbIcons.Card
    PillGlyph.CARD_CUE -> FbIcons.CardCue
    PillGlyph.CARD_STOP -> FbIcons.CardStop
    else -> FbIcons.Stop
}

/** Plain memory, not snapshot state: reading the previous value must not itself trigger a recomposition. */
private class PreviousHealth { var prev: Health? = null; var cur: Health? = null }

/** Remembers the health the pill showed before [now], for the direction of its motion. */
@Composable
internal fun rememberPrevious(now: Health): Health? {
    val p = remember { PreviousHealth() }
    if (p.cur != now) {
        p.prev = p.cur
        p.cur = now
    }
    return p.prev
}
