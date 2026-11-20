package app.irlsupreme.ui.live

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.irlsupreme.ui.FbIcons
import app.irlsupreme.ui.drawHatch
import app.irlsupreme.ui.theme.FbHaptics
import app.irlsupreme.ui.theme.FbLiveType
import app.irlsupreme.ui.theme.FbMotion
import app.irlsupreme.ui.theme.FbShape
import app.irlsupreme.ui.theme.HapticPattern
import app.irlsupreme.ui.theme.LocalFbColors
import app.irlsupreme.ui.theme.OverVideo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The control rail: four equal-height keys set apart by width, position and marking. END far left and set
 * apart, SCENES, MUTE, and BRB widest in the thumb arc. State shows by inversion plus a changed word; no hue, no LEDs,
 * and no key is ever shrunk to fit.
 */

/** Keys fade to this while the stream is ending, so a second tap visibly does nothing. */
private const val DISABLED_ALPHA = 0.4f

/** The END band: 4dp of caution hatching across the key's top edge, in the key-outline grey. */
private val BAND = 4.dp

/** The hold ticks sit just outside the END key's outline. */
private val TICK_OFFSET = 3.dp
private val TICK_ARM = 6.dp
private val TICK_STROKE = 2.dp

/** What each key does and says to TalkBack. */
internal class RailActions(
    val enabled: Boolean,
    val scenesState: String,
    val muteState: String,
    val brbName: String,
    val brbState: String,
    val onEnd: () -> Unit,
    val onEarlyRelease: () -> Unit,
    val onScenes: () -> Unit,
    val onMute: () -> Unit,
    val onBrb: () -> Unit,
)

/**
 * The four keys. [vertical]: the landscape column (END at the top, BRB at the bottom-right thumb). [big]: the ≥1.5
 * font-scale layout, 84dp keys with labels wrapping inside them.
 *
 * Sizes come from measuring the labels, not from guesses: each key is at least as wide as its widest word (so no word
 * breaks mid-letter) and all four share the height of the tallest wrapped label (so nothing is clipped). Both use every
 * label a key can show, so the rail keeps its size when a key changes state.
 */
@Composable
internal fun RailKeys(
    end: KeyFace,
    scenes: KeyFace,
    mute: KeyFace,
    brb: KeyFace,
    actions: RailActions,
    vertical: Boolean,
    big: Boolean,
    reduced: Boolean,
    modifier: Modifier = Modifier,
) {
    val keyMod = Modifier.alpha(if (actions.enabled) 1f else DISABLED_ALPHA)
    val needs = rememberKeyNeeds(big)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = keyLabelStyle(big)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val available = maxWidth.value
        val w = if (vertical) FloatArray(4) { available } else railWidths(available, needs)
        val height = remember(w.toList(), style, density, big) {
            val padPx = with(density) { keyPad(big).dp.roundToPx() * 2 }
            val label = KEY_LABELS.withIndex().maxOf { (i, labels) ->
                val inner = (with(density) { w[i].dp.roundToPx() } - padPx).coerceAtLeast(1)
                labels.maxOf { l ->
                    val px = measurer.measure(l, style, softWrap = true, constraints = Constraints(maxWidth = inner), density = density).size.height
                    with(density) { px.toDp().value }
                }
            }
            railKeyHeight(big, label).dp
        }
        if (vertical) {
            Column(Modifier.fillMaxWidth()) {
                EndKey(end, big, actions, reduced, keyMod.fillMaxWidth().height(height))
                Spacer(Modifier.height(Rail.END_GAP.dp))
                ScenesKey(scenes, big, actions, keyMod.fillMaxWidth().height(height))
                Spacer(Modifier.height(Rail.GAP.dp))
                MuteKey(mute, big, actions, keyMod.fillMaxWidth().height(height))
                Spacer(Modifier.height(Rail.GAP.dp))
                BrbKey(brb, big, actions, keyMod.fillMaxWidth().height(height))
            }
            return@BoxWithConstraints
        }
        val fits = w.sum() + Rail.END_GAP + Rail.GAP * 2 <= available + 0.5f
        Row(
            // Never shrink a key: a screen too narrow for the minimums scrolls the rail instead.
            Modifier.then(if (fits) Modifier else Modifier.horizontalScroll(rememberScrollState())),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EndKey(end, big, actions, reduced, keyMod.size(w[0].dp, height))
            Spacer(Modifier.width(Rail.END_GAP.dp))
            ScenesKey(scenes, big, actions, keyMod.size(w[1].dp, height))
            Spacer(Modifier.width(Rail.GAP.dp))
            MuteKey(mute, big, actions, keyMod.size(w[2].dp, height))
            Spacer(Modifier.width(Rail.GAP.dp))
            BrbKey(brb, big, actions, keyMod.size(w[3].dp, height))
        }
    }
}

/**
 * The key label style. At large type the caps tracking drops to 0: at 200% the 0.12em of L3 adds about 16dp to
 * CANCEL, which is what pushes a word past its key. Normal type keeps the tracked caps.
 */
private fun keyLabelStyle(big: Boolean): TextStyle = if (big) FbLiveType.L3.copy(letterSpacing = 0.em) else FbLiveType.L3

/**
 * The width each key [END, SCENES, MUTE, BRB] needs for its widest single word at the current font scale, measured
 * with the real face. The landscape column reads it too, so it can widen at large type.
 */
@Composable
internal fun rememberKeyNeeds(big: Boolean): FloatArray {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = keyLabelStyle(big)
    return remember(style, density, big) {
        FloatArray(KEY_LABELS.size) { i ->
            keyWords(KEY_LABELS[i]).maxOf { word ->
                val px = measurer.measure(word, style, softWrap = false, maxLines = 1, density = density).size.width
                keyNeed(with(density) { px.toDp().value }, big)
            }
        }
    }
}

/** KEY haptic on finger-down, for every key: the click is felt before the action lands. */
@Composable
private fun rememberKeySource(): MutableInteractionSource {
    val view = LocalView.current
    val source = remember { MutableInteractionSource() }
    LaunchedEffect(source) {
        source.interactions.collect { if (it is PressInteraction.Press) FbHaptics.play(view, HapticPattern.KEY) }
    }
    return source
}

@Composable
private fun ScenesKey(face: KeyFace, big: Boolean, a: RailActions, modifier: Modifier) {
    val source = rememberKeySource()
    val pressed by source.collectIsPressedAsState()
    KeyBody(
        face, pressed, big,
        modifier
            .clickable(
                interactionSource = source, indication = null, enabled = a.enabled, role = Role.Button,
                onClickLabel = if (face.engaged) "Close scenes" else "Choose a scene", onClick = a.onScenes,
            )
            .semantics {
                contentDescription = "Scenes"
                stateDescription = a.scenesState
            },
    )
}

@Composable
private fun MuteKey(face: KeyFace, big: Boolean, a: RailActions, modifier: Modifier) {
    val source = rememberKeySource()
    val pressed by source.collectIsPressedAsState()
    KeyBody(
        face, pressed, big,
        modifier
            .toggleable(value = !face.engaged, interactionSource = source, indication = null, enabled = a.enabled, role = Role.Switch) { a.onMute() }
            .semantics {
                contentDescription = "Microphone"
                stateDescription = a.muteState
            },
    )
}

@Composable
private fun BrbKey(face: KeyFace, big: Boolean, a: RailActions, modifier: Modifier) {
    val source = rememberKeySource()
    val pressed by source.collectIsPressedAsState()
    KeyBody(
        face, pressed, big,
        modifier
            .toggleable(value = face.engaged, interactionSource = source, indication = null, enabled = a.enabled, role = Role.Switch) { a.onBrb() }
            .semantics {
                contentDescription = a.brbName
                stateDescription = a.brbState
            },
    )
}

/**
 * END. Before the stream was ever live it's a one-tap CANCEL with no band. After lock-on the hatched band wipes in
 * left to right (Enter) and it takes a 2 s hold, timed by the clock ([HoldSession]): 8 perimeter ticks light one per
 * 250ms with a firming tick each, the 8th is the THUD and the end. Releasing early drains the ticks, plays the low
 * tick and shows the hint. TalkBack gets the deliberate "End stream" long-click action and no plain click.
 */
@Composable
private fun EndKey(face: KeyFace, big: Boolean, a: RailActions, reduced: Boolean, modifier: Modifier) {
    if (!face.band) {
        val source = rememberKeySource()
        val pressed by source.collectIsPressedAsState()
        KeyBody(
            face, pressed, big,
            modifier
                .clickable(interactionSource = source, indication = null, enabled = a.enabled, role = Role.Button, onClick = a.onEnd)
                .semantics { contentDescription = "Cancel going live" },
        )
        return
    }
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val end by rememberUpdatedState(a.onEnd)
    val early by rememberUpdatedState(a.onEarlyRelease)
    // The band wipes in once, 120ms after CANCEL turns to END; it is simply there on a later recomposition.
    val band = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (reduced) band.snapTo(1f)
        else {
            delay(FbMotion.TICK_MS.toLong())
            band.animateTo(1f, tween(FbMotion.ENTER_MS, easing = FbMotion.Enter))
        }
    }
    var lit by remember { mutableIntStateOf(0) }
    val drain = remember { Animatable(1f) }
    var pressed by remember { mutableStateOf(false) }

    KeyBody(
        face, pressed, big,
        modifier
            .drawWithContent {
                drawContent()
                if (lit > 0) drawHoldTicks(lit, OverVideo.text.copy(alpha = drain.value))
            }
            .pointerInput(a.enabled) {
                if (!a.enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    FbHaptics.play(view, HapticPattern.KEY)
                    pressed = true
                    var finished = false
                    val hold: Job = scope.launch {
                        drain.snapTo(1f)
                        lit = 0
                        var session: HoldSession? = null
                        fun light(ticks: List<Int>) {
                            ticks.forEach { i ->
                                lit = i
                                if (i < HoldSession.TICKS) FbHaptics.holdStep(view, i)
                                else if (!finished) {
                                    finished = true
                                    FbHaptics.play(view, HapticPattern.HOLD_DONE)
                                    end()
                                }
                            }
                        }
                        // The guard ends it even if frames stop arriving; whichever reaches 2 s first wins.
                        val guard = launch {
                            delay(HoldSession.HOLD_MS)
                            if (!finished) light((session ?: HoldSession(0L, reduced)).onGuard())
                        }
                        val t0 = withFrameNanos { it }
                        val s = HoldSession(t0, reduced).also { session = it }
                        while (!s.done && !finished) withFrameNanos { now -> light(s.onFrame(now)) }
                        guard.cancel()
                    }
                    waitForUpOrCancellation()
                    pressed = false
                    if (!finished) {
                        hold.cancel()
                        FbHaptics.play(view, HapticPattern.HOLD_RELEASED)
                        early()
                        scope.launch {
                            if (reduced) drain.snapTo(0f)
                            else drain.animateTo(0f, tween(FbMotion.EXIT_MS, easing = FbMotion.Exit))
                            lit = 0
                            drain.snapTo(1f)
                        }
                    }
                }
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "End stream"
                if (a.enabled) onLongClick(label = "End stream") { end(); true } else disabled()
            },
        bandFraction = band.value,
    )
}

/**
 * The 8 hold ticks, clockwise from the top centre: straight ticks at the edge midpoints and L-ticks at the corners
 * (the registration marks of the cards), [lit] of them drawn.
 */
private fun DrawScope.drawHoldTicks(lit: Int, color: Color) {
    val o = TICK_OFFSET.toPx()
    val arm = TICK_ARM.toPx()
    val w = size.width
    val h = size.height
    val ticks = listOf(
        listOf(Offset(w / 2 - arm, -o), Offset(w / 2 + arm, -o)),
        listOf(Offset(w - arm, -o), Offset(w + o, -o), Offset(w + o, arm)),
        listOf(Offset(w + o, h / 2 - arm), Offset(w + o, h / 2 + arm)),
        listOf(Offset(w + o, h - arm), Offset(w + o, h + o), Offset(w - arm, h + o)),
        listOf(Offset(w / 2 + arm, h + o), Offset(w / 2 - arm, h + o)),
        listOf(Offset(arm, h + o), Offset(-o, h + o), Offset(-o, h - arm)),
        listOf(Offset(-o, h / 2 + arm), Offset(-o, h / 2 - arm)),
        listOf(Offset(-o, arm), Offset(-o, -o), Offset(arm, -o)),
    )
    val stroke = Stroke(TICK_STROKE.toPx(), cap = StrokeCap.Square, join = StrokeJoin.Miter)
    ticks.take(lit.coerceIn(0, ticks.size)).forEach { pts ->
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            pts.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(path, color, style = stroke)
    }
}

/**
 * A key's face: rM, 1.5dp key-outline grey, a glyph over an L3 label. Engaged inverts it (Bone fill, Ink content) in a
 * Tick; pressed fills s3. The label wraps inside the key at large type rather than ever being cut or shrunk.
 */
@Composable
private fun KeyBody(face: KeyFace, pressed: Boolean, big: Boolean, modifier: Modifier, bandFraction: Float = 0f) {
    val s3 = LocalFbColors.current.s3
    val reduced = FbMotion.rememberReduced()
    val fill by animateColorAsState(
        when {
            face.engaged -> OverVideo.keyEngagedFill
            pressed -> s3
            else -> Color.Transparent
        },
        if (pressed) snap() else FbMotion.tick(reduced), label = "keyFill",
    )
    val content by animateColorAsState(
        if (face.engaged) OverVideo.keyEngagedContent else OverVideo.text, FbMotion.tick(reduced), label = "keyContent",
    )
    val outline = if (face.engaged) OverVideo.keyEngagedFill else OverVideo.keyOutline
    val labelStyle = keyLabelStyle(big)
    Box(
        modifier
            .clip(FbShape.medium)
            .background(fill)
            .border(FbShape.stroke, outline, FbShape.medium)
            .drawWithContent {
                drawContent()
                if (bandFraction > 0f && !face.engaged) {
                    clipRect(right = size.width * bandFraction, bottom = BAND.toPx()) { drawHatch(OverVideo.keyOutline) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            // The same padding, glyph and gap that railKeyHeight and keyNeed size the key from.
            Modifier.padding(horizontal = keyPad(big).dp, vertical = Rail.PAD_V.dp).clearAndSetSemantics {},
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Rail.GLYPH_GAP.dp),
        ) {
            Icon(glyphIcon(face.glyph), contentDescription = null, tint = content, modifier = Modifier.size(Rail.GLYPH.dp))
            Text(face.label, style = labelStyle, color = content, textAlign = TextAlign.Center, softWrap = true)
        }
    }
}

private fun glyphIcon(g: KeyGlyph): ImageVector = when (g) {
    KeyGlyph.X -> FbIcons.X
    KeyGlyph.STOP -> FbIcons.Stop
    KeyGlyph.LAYERS -> FbIcons.Layers
    KeyGlyph.MIC -> FbIcons.Mic
    KeyGlyph.MIC_OFF -> FbIcons.MicOff
    KeyGlyph.CARD -> FbIcons.Card
    KeyGlyph.CAMERA -> FbIcons.Camera
}
