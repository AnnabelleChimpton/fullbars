package app.irlsupreme.ui

import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.irlsupreme.ui.theme.FbHaptics
import app.irlsupreme.ui.theme.FbMotion
import app.irlsupreme.ui.theme.FbShape
import app.irlsupreme.ui.theme.FbSpace
import app.irlsupreme.ui.theme.FbType
import app.irlsupreme.ui.theme.HapticPattern
import app.irlsupreme.ui.theme.LocalFbColors
import kotlinx.coroutines.delay
import kotlin.math.sqrt

/*
 * The shared primitives for Home, Settings and its pages. A preflight-checklist grammar: a state
 * square, a label on the left, a value on the right, hairlines between rows and no containers. Every stock Material
 * row, switch and app bar is replaced; the names callers already use (NavRow, SwitchRow, RadioRow…) are kept and now
 * draw the new pieces, so screens restyle before they migrate.
 */

// --- Small drawing helpers -----------------------------------------------------------------------------------------

/**
 * Pressed and keyboard-focused rows fill with s2 instead of a ripple: a flat, physical "key down", and a visible focus
 * position for D-pad and keyboard users (who lose the ripple's focus highlight).
 */
@Composable
private fun pressFill(source: MutableInteractionSource, rest: Color = Color.Transparent): Color {
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    return if (pressed || focused) LocalFbColors.current.s2 else rest
}

/** A 1px rule along the bottom edge: the only thing that separates rows. One physical pixel, at any density. */
fun Modifier.bottomRule(color: Color): Modifier = drawBehind {
    drawLine(color, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), strokeWidth = 1f)
}

/**
 * Caution hatching: 135° stripes, 2.5dp on, 3.5dp off, clipped to the bounds. Means caution only: the END
 * band, the Camera lost cap, the Privacy mic-on cap.
 */
fun Modifier.hatch(color: Color): Modifier = drawBehind { drawHatch(color) }

/** [hatch] for a custom draw. [on] and [off] are the stripe and gap, measured across the stripes. */
fun DrawScope.drawHatch(color: Color, on: Float = FbShape.hatchOn.toPx(), off: Float = FbShape.hatchOff.toPx()) {
    val step = (on + off) * sqrt(2f) // horizontal distance between stripes running at 45° up-right
    clipRect {
        var x = -size.height
        while (x < size.width + step) {
            drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = on)
            x += step
        }
    }
}

/**
 * A dashed outline: "not real yet / not public": the Reconnecting pill, the NOT PUBLIC tag, unchecked
 * squares. Drawn inside the bounds so it never changes layout.
 */
fun Modifier.dashedBorder(
    width: Dp,
    color: Color,
    radius: Dp,
    on: Dp = FbShape.dashOn,
    off: Dp = FbShape.dashOff,
): Modifier = drawBehind {
    val w = width.toPx()
    drawRoundRect(
        color, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(on.toPx(), off.toPx()))),
    )
}

/** A full-width 1px rule. */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = LocalFbColors.current.rule) {
    Box(modifier.fillMaxWidth().height(1.dp).drawBehind { drawLine(color, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1f) })
}

// --- Page frame ----------------------------------------------------------------------------------------------------

/**
 * A 48dp glyph key: the back control and the settings control, the only two glyphs outside the Live screen. Pressed
 * shows the s2 fill in rM.
 */
@Composable
fun GlyphButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalFbColors.current
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.size(FbSpace.target)
            .background(pressFill(source), FbShape.medium)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = c.text, modifier = Modifier.size(24.dp)) }
}

/**
 * A full-screen page: a 56dp bar holding only the back glyph, then the title in FbType.title scrolling with the body
 * (no TopAppBar: the title is the page's first line, not chrome). The body scrolls and clears the navigation bar and
 * the keyboard.
 */
@Composable
fun PageScaffold(
    title: String,
    onBack: () -> Unit,
    /** Stays in view while the rows scroll under it (a close-up of what the page changes). */
    pinned: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (isWideLayout()) WidePageScaffold(title, onBack, pinned, content) else TallPageScaffold(title, onBack, pinned, content)
}

@Composable
private fun TallPageScaffold(title: String, onBack: () -> Unit, pinned: (@Composable () -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalFbColors.current
    Column(
        Modifier.fillMaxSize().background(c.bg)
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(FbIcons.Back, contentDescription = "Back", onClick = onBack)
        }
        if (pinned != null) {
            Text(
                title, style = FbType.title, color = c.text,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
                    .semantics { heading() },
            )
            pinned()
            Hairline()
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom))
                .padding(bottom = 24.dp),
        ) {
            if (pinned == null) Text(
                title, style = FbType.title, color = c.text,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
                    .semantics { heading() },
            )
            content()
        }
    }
}

/**
 * Landscape: the back key and the title stand in a column on the left and stay put, and the rows scroll on the right
 * at a readable width. Sideways a phone is only ~400dp tall, and a bar plus a title on top would eat a third of it;
 * rows stretched across 900dp put every value a long way from its label.
 */
@Composable
private fun WidePageScaffold(title: String, onBack: () -> Unit, pinned: (@Composable () -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalFbColors.current
    Row(
        Modifier.fillMaxSize().background(c.bg)
            .windowInsetsPadding(
                WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            ),
    ) {
        Column(Modifier.width(WIDE_SIDE).fillMaxHeight().padding(start = 4.dp, top = 4.dp)) {
            GlyphButton(FbIcons.Back, contentDescription = "Back", onClick = onBack)
            Text(
                title, style = FbType.title.copy(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading), color = c.text,
                modifier = Modifier.padding(start = 12.dp, end = 16.dp, top = 8.dp).semantics { heading() },
            )
            pinned?.invoke()
        }
        Box(Modifier.fillMaxHeight().width(1.dp).background(c.rule))
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom))
                .padding(top = 8.dp, bottom = 24.dp),
        ) {
            Column(Modifier.widthIn(max = WIDE_CONTENT)) { content() }
        }
    }
}

/** The left column of a wide page, and the most a wide page's rows stretch to. */
val WIDE_SIDE = 232.dp
val WIDE_CONTENT = 640.dp

/**
 * Wide enough for side-by-side layouts: a phone held sideways (or a tablet). By the window, not the device, so split
 * screen gets the tall layout when it's narrow.
 */
@Composable
fun isWideLayout(): Boolean {
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    return wideLayout(cfg.screenWidthDp, cfg.screenHeightDp)
}

/** Pure, for tests: landscape and at least 600dp wide. */
fun wideLayout(widthDp: Int, heightDp: Int): Boolean = widthDp > heightDp && widthDp >= 600

/**
 * A section label: Bone caps on a hairline, visibly stronger than the rows under it, with an optional stamp at the
 * end ("checked 12 s ago"). 24dp above, 8dp below. TalkBack hears it as a heading, in sentence case.
 */
@Suppress("ModifierParameter") // parameter order is fixed: existing call sites depend on it
@Composable
fun SectionHeader(text: String, trailing: String? = null, modifier: Modifier = Modifier, top: Dp = FbSpace.sectionTop) {
    val c = LocalFbColors.current
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = top, bottom = FbSpace.sectionBottom),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(), style = FbType.label, color = c.text,
            modifier = Modifier.clearAndSetSemantics { heading(); contentDescription = text },
        )
        Box(Modifier.weight(1f).padding(start = 12.dp, end = if (trailing != null) 12.dp else 0.dp)) { Hairline() }
        if (trailing != null) Text(trailing, style = FbType.footnote, color = c.text2)
    }
}

// --- State squares and value rows ----------------------------------------------------------------------------------

/**
 * What a preflight row knows about its check. Never green: a check that passed is a filled square, one that hasn't run
 * yet is dashed, and only a verified failure is red.
 */
enum class StateMark {
    /** Filled square: the check returned true. */
    VERIFIED,
    /** 1px dashed square: still waiting. */
    UNCHECKED,
    /** Red outline with a diagonal cross, plus the reason line: only for a verified failure. */
    FAILED,
    /** No square; the row says "optional" in text2. */
    OPTIONAL,
    /** No square and no slot (Settings rows). */
    NONE;

    /** What TalkBack says for the square, or null when there's nothing to say. */
    val spoken: String?
        get() = when (this) {
            VERIFIED -> "Checked"
            UNCHECKED -> "Not checked yet"
            FAILED -> "Failed"
            OPTIONAL -> "Optional"
            NONE -> null
        }
}

/** The 10dp state square for [mark]. Decorative: the row carries [StateMark.spoken]. */
@Composable
fun StateSquare(mark: StateMark, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    val c = LocalFbColors.current
    Canvas(modifier.size(size)) {
        val r = CornerRadius(FbShape.rS.toPx())
        when (mark) {
            StateMark.VERIFIED -> drawRoundRect(c.text, cornerRadius = r)
            StateMark.UNCHECKED -> {
                val w = 1.dp.toPx()
                drawRoundRect(
                    c.text2, topLeft = Offset(w / 2, w / 2), size = Size(this.size.width - w, this.size.height - w),
                    cornerRadius = r,
                    style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 1.5.dp.toPx()))),
                )
            }
            StateMark.FAILED -> {
                val w = FbShape.stroke.toPx()
                val inset = w / 2
                drawRoundRect(
                    c.error, topLeft = Offset(inset, inset), size = Size(this.size.width - w, this.size.height - w),
                    cornerRadius = r, style = Stroke(w),
                )
                drawLine(c.error, Offset(inset, inset), Offset(this.size.width - inset, this.size.height - inset), w)
                drawLine(c.error, Offset(this.size.width - inset, inset), Offset(inset, this.size.height - inset), w)
            }
            StateMark.OPTIONAL, StateMark.NONE -> Unit
        }
    }
}

/** The value-row layout rule, pure so it's unit-tested. */
internal object ValueLayout {
    /** A value wider than this share of the row stacks under its label. */
    const val MAX_INLINE_SHARE = 0.55f

    /** True when the value goes under the label, left-aligned, instead of beside it. */
    fun stacks(labelWidth: Int, valueWidth: Int, rowWidth: Int, gap: Int): Boolean =
        valueWidth > rowWidth * MAX_INLINE_SHARE || labelWidth + gap + valueWidth > rowWidth
}

private val MarkSlot = 10.dp
private val MarkGap = 12.dp
private val ValueGap = 16.dp
private val TrailingSlot = 20.dp

/** The default trailing chevron of a row that opens a page. */
private val DefaultChevron: @Composable () -> Unit = {
    Icon(FbIcons.Chevron, contentDescription = null, tint = LocalFbColors.current.text2, modifier = Modifier.size(TrailingSlot))
}

/**
 * A preflight/settings row: [label] on the left, [value] on the right, an optional [sub] line under the value. Values
 * that are figures ([valueIsFigure]) are mono; words are Archivo. When the value is wider than 55% of the row it
 * stacks under the label, left-aligned and wrapping, so a long title is never truncated. With [onClick] the whole row
 * is the target (≥54dp) and ends in a chevron.
 */
@Suppress("ModifierParameter") // parameter order is fixed: existing call sites depend on it
@Composable
fun ValueRow(
    label: String,
    value: String,
    valueIsFigure: Boolean,
    state: StateMark = StateMark.NONE,
    sub: String? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
    subIsFigure: Boolean = valueIsFigure,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    divider: Boolean = true,
) = ValueRow(
    label, AnnotatedString(value), valueIsFigure, state, sub, onClick, modifier, valueColor, subIsFigure, enabled,
    onClickLabel, divider,
)

/** [ValueRow] with a styled value, for a figure plus words ("2 links": the figure mono, the words Archivo). */
@Suppress("ModifierParameter") // parameter order is fixed: existing call sites depend on it
@Composable
fun ValueRow(
    label: String,
    value: AnnotatedString,
    valueIsFigure: Boolean,
    state: StateMark = StateMark.NONE,
    sub: String? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
    subIsFigure: Boolean = valueIsFigure,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    divider: Boolean = true,
) = ValueRowLayout(
    label = label, value = value, valueStyle = if (valueIsFigure) FbType.value else FbType.rowValue, valueColor = valueColor,
    sub = sub ?: if (state == StateMark.OPTIONAL) "optional" else null,
    subStyle = if (subIsFigure) FbType.value else FbType.rowValue, state = state,
    trailing = if (onClick != null) DefaultChevron else null,
    onClick = onClick, onClickLabel = onClickLabel, enabled = enabled, divider = divider, modifier = modifier,
)

@Composable
private fun ValueRowLayout(
    label: String,
    value: AnnotatedString,
    valueStyle: TextStyle,
    valueColor: Color,
    sub: String?,
    subStyle: TextStyle,
    state: StateMark,
    trailing: (@Composable () -> Unit)?,
    onClick: (() -> Unit)?,
    onClickLabel: String?,
    enabled: Boolean,
    divider: Boolean,
    modifier: Modifier,
) {
    val c = LocalFbColors.current
    val source = remember { MutableInteractionSource() }
    val tappable = onClick != null
    val spoken = state.spoken
    val interaction = if (onClick != null) {
        Modifier.background(pressFill(source)).clickable(
            interactionSource = source, indication = null, enabled = enabled, onClickLabel = onClickLabel,
            role = Role.Button, onClick = onClick,
        )
    } else {
        Modifier.semantics(mergeDescendants = true) {}
    }
    val vColor = if (valueColor.isSpecified) valueColor else c.text
    BoxWithConstraints(
        modifier.fillMaxWidth()
            .then(if (divider) Modifier.bottomRule(c.rule) else Modifier)
            .then(interaction)
            .then(if (spoken != null) Modifier.semantics { stateDescription = spoken } else Modifier)
            .heightIn(min = if (tappable) FbSpace.rowTappable else FbSpace.rowStatic)
            .alpha(if (enabled) 1f else 0.38f),
    ) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val pad = FbSpace.gutter
        val markWidth = if (state == StateMark.NONE) 0.dp else MarkSlot + MarkGap
        val trailWidth = if (trailing != null) TrailingSlot + 8.dp else 0.dp
        val rowPx = constraints.maxWidth - with(density) { (pad * 2 + markWidth + trailWidth).roundToPx() }
        val labelPx = measurer.measure(label, FbType.row, softWrap = false, maxLines = 1).size.width
        val valuePx = maxOf(
            measurer.measure(value, valueStyle, softWrap = false, maxLines = 1).size.width,
            sub?.let { measurer.measure(it, subStyle, softWrap = false, maxLines = 1).size.width } ?: 0,
        )
        val stacked = value.isNotEmpty() && ValueLayout.stacks(labelPx, valuePx, rowPx, with(density) { ValueGap.roundToPx() })
        val vertical = if (tappable) 17.dp else 14.dp // centres one 20sp line in the 54/48dp floor

        Row(Modifier.fillMaxWidth().padding(horizontal = pad, vertical = vertical), verticalAlignment = Alignment.Top) {
            if (state != StateMark.NONE) {
                // Centred on the label's first 20sp line.
                StateSquare(state, Modifier.padding(top = 5.dp))
                Spacer(Modifier.width(MarkGap))
            }
            Column(Modifier.weight(1f)) {
                Text(label, style = FbType.row, color = c.text)
                if (stacked) {
                    Text(value, style = valueStyle, color = vColor, modifier = Modifier.padding(top = 4.dp))
                    if (sub != null) Text(sub, style = subStyle, color = c.text2)
                }
            }
            if (!stacked && value.isNotEmpty()) {
                Column(Modifier.padding(start = ValueGap), horizontalAlignment = Alignment.End) {
                    Text(value, style = valueStyle, color = vColor, textAlign = TextAlign.End)
                    if (sub != null) Text(sub, style = subStyle, color = c.text2, textAlign = TextAlign.End)
                }
            } else if (!stacked && sub != null) {
                Text(sub, style = subStyle, color = c.text2, modifier = Modifier.padding(start = ValueGap))
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
    }
}

/**
 * A row that opens a page or does something. Draws a [ValueRow]: [headline] is the label and [supporting] the value.
 * [icon] is ignored (rows carry no icons); it stays so call sites build. [trailing] defaults to the chevron;
 * pass null for an action row.
 */
@Composable
fun NavRow(
    headline: String,
    supporting: String? = null,
    icon: ImageVector? = null,
    supportingColor: Color = Color.Unspecified,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = DefaultChevron,
    onClick: () -> Unit,
) {
    ValueRowLayout(
        label = headline, value = AnnotatedString(supporting.orEmpty()), valueStyle = FbType.rowValue,
        valueColor = supportingColor, sub = null, subStyle = FbType.rowValue, state = StateMark.NONE,
        trailing = trailing, onClick = onClick, onClickLabel = null, enabled = enabled, divider = true,
        modifier = Modifier,
    )
}

/**
 * The printed toggle: a 60×32dp track with a square thumb and a printed I (on) or O (off), so the state never
 * rests on position or colour alone. On, the track fills with Bone (Ink in light) and the thumb sits right; off, it's
 * an outline with a Graphite thumb on the left. The thumb moves in a Tick (two steps), not a slide.
 * With [onCheckedChange] null it's drawn only, for a row that is itself the toggle.
 */
@Composable
fun PrintedToggle(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = LocalFbColors.current
    val reduced = FbMotion.rememberReduced()
    val thumbX by animateDpAsState(if (checked) 32.dp else 4.dp, FbMotion.tick(reduced), label = "thumb")
    val interactive = if (onCheckedChange != null) {
        Modifier.minimumInteractiveComponentSize().toggleable(
            value = checked, interactionSource = null, indication = null, enabled = enabled, role = Role.Switch,
            onValueChange = onCheckedChange,
        )
    } else {
        Modifier
    }
    Box(modifier.then(interactive), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(60.dp, 32.dp).alpha(if (enabled) 1f else 0.38f)
                .background(if (checked) c.text else Color.Transparent, FbShape.medium)
                .then(if (checked) Modifier else Modifier.border(FbShape.stroke, c.ruleStrong, FbShape.medium)),
        ) {
            Text(
                if (checked) "I" else "O", style = FbType.label, color = if (checked) c.bg else c.text2,
                maxLines = 1,
                modifier = Modifier.align(if (checked) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 11.dp).clearAndSetSemantics {},
            )
            Box(
                Modifier.align(Alignment.CenterStart).offset { IntOffset(thumbX.roundToPx(), 0) }.size(24.dp)
                    .background(if (checked) c.bg else c.glyphOff, FbShape.small),
            )
        }
    }
}

/** A whole-row toggle: label, at most one help line, and a [PrintedToggle]. The row is the target (Role.Switch). */
@Composable
fun SwitchRow(
    headline: String,
    supporting: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    val c = LocalFbColors.current
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().bottomRule(c.rule)
            .background(pressFill(source))
            .toggleable(
                value = checked, interactionSource = source, indication = null, enabled = enabled, role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .heightIn(min = FbSpace.rowTappable)
            .padding(horizontal = FbSpace.gutter, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).alpha(if (enabled) 1f else 0.38f)) {
            Text(headline, style = FbType.row, color = c.text)
            if (supporting != null) Text(supporting, style = FbType.help, color = c.text2, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(16.dp))
        PrintedToggle(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** The radio: a 20dp circle (the one exception to the three radii, because the circle is what says "radio"). */
@Composable
fun FbRadio(selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = LocalFbColors.current
    Canvas(modifier.size(20.dp).alpha(if (enabled) 1f else 0.38f)) {
        val w = FbShape.stroke.toPx()
        drawCircle(if (selected) c.text else c.text2, radius = size.minDimension / 2 - w / 2, style = Stroke(w))
        if (selected) drawCircle(c.text, radius = 5.dp.toPx())
    }
}

/**
 * A whole-row choice: [FbRadio], the label, an optional mono [value] ("6 Mb/s · 2.8 GB/h") and at most one help line.
 * Put several in a Column with Modifier.selectableGroup().
 */
@Composable
fun RadioRow(
    headline: String,
    supporting: String? = null,
    selected: Boolean,
    enabled: Boolean = true,
    value: String? = null,
    onClick: () -> Unit,
) {
    val c = LocalFbColors.current
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().bottomRule(c.rule)
            .background(pressFill(source))
            .selectable(
                selected = selected, interactionSource = source, indication = null, enabled = enabled,
                role = Role.RadioButton, onClick = onClick,
            )
            .heightIn(min = FbSpace.rowTappable)
            .padding(horizontal = FbSpace.gutter, vertical = 15.dp)
            .alpha(if (enabled) 1f else 0.38f),
        verticalAlignment = Alignment.Top,
    ) {
        FbRadio(selected, enabled = true)
        Spacer(Modifier.width(MarkGap))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(headline, style = FbType.row, color = c.text, modifier = Modifier.weight(1f))
                if (value != null) Text(value, style = FbType.value, color = c.text, modifier = Modifier.padding(start = 12.dp, top = 1.dp))
            }
            if (supporting != null) Text(supporting, style = FbType.help, color = c.text2, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

// --- Buttons and tags ----------------------------------------------------------------------------------------------

/** What an [FbButton] is for. */
enum class FbButtonKind {
    /** The one filled CTA per screen: Bone (Ink in light). Inverts while pressed. */
    Primary,
    /** An outlined action. */
    Secondary,
    /** An underlined word with a 48dp target: "Use a stream key instead", "Done". */
    Text,
    /** "Continue with Twitch", and nothing else. */
    Twitch,
}

/**
 * The app's buttons: rM corners, 56dp, content left-aligned (never centred). [trailing] sits at the far end
 * (the NOT PUBLIC tag, the mark) and makes the button fill its width. Disabled drops to 38% and stops taking taps.
 * [pressHaptic] plays the KEY tick on finger-down (the Home CTA).
 */
@Composable
fun FbButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: FbButtonKind = FbButtonKind.Primary,
    enabled: Boolean = true,
    pressHaptic: Boolean = false,
    textStyle: TextStyle? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = LocalFbColors.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val view = LocalView.current
    if (pressHaptic) LaunchedEffect(pressed) { if (pressed) FbHaptics.play(view, HapticPattern.KEY) }
    val click = Modifier.clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)

    if (kind == FbButtonKind.Text) {
        Box(
            modifier.heightIn(min = FbSpace.target).widthIn(min = FbSpace.target).alpha(if (enabled) 1f else 0.38f)
                .then(click).padding(vertical = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text, style = textStyle ?: FbType.row.copy(fontSize = 15.sp),
                color = if (pressed || focused) c.text2 else c.text, textDecoration = TextDecoration.Underline,
            )
        }
        return
    }

    val down = pressed || focused
    val (fill, content, outline) = when (kind) {
        FbButtonKind.Primary -> if (down) Triple(c.bg, c.cta, c.cta) else Triple(c.cta, c.onCta, null)
        FbButtonKind.Twitch -> if (down) Triple(c.onTwitch, c.twitch, c.twitch) else Triple(c.twitch, c.onTwitch, null)
        else -> Triple(if (down) c.s2 else Color.Transparent, c.text, c.ruleStrong)
    }
    Row(
        modifier.heightIn(min = 56.dp).alpha(if (enabled) 1f else 0.38f)
            .background(fill, FbShape.medium)
            .then(if (outline != null) Modifier.border(FbShape.stroke, outline, FbShape.medium) else Modifier)
            .then(click)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (kind == FbButtonKind.Twitch) {
            Icon(FbIcons.Twitch, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text, style = textStyle ?: if (kind == FbButtonKind.Primary) FbType.cta else FbType.row, color = content,
            modifier = if (trailing != null) Modifier.weight(1f) else Modifier,
        )
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/**
 * A small caps tag, rS. [dashed] means "not real yet / not public" (NOT PUBLIC); [inverted] fills it, for an engaged
 * state (MUTED, NO AUDIO, NOT ON CAMERA). [color] defaults to the theme's text colour; [onColor] is the text on an
 * inverted fill (defaults to the page colour). TalkBack reads the words as given, not spelled out as capitals.
 */
@Suppress("ModifierParameter") // parameter order is fixed: existing call sites depend on it
@Composable
fun Tag(
    text: String,
    dashed: Boolean = false,
    inverted: Boolean = false,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    onColor: Color = Color.Unspecified,
) {
    val c = LocalFbColors.current
    val fg = if (color.isSpecified) color else c.text
    val textColor = if (inverted) (if (onColor.isSpecified) onColor else c.bg) else fg
    val frame = when {
        inverted -> Modifier.background(fg, FbShape.small)
        dashed -> Modifier.dashedBorder(1.dp, fg, FbShape.rS, on = 3.dp, off = 2.dp)
        else -> Modifier.border(1.dp, fg, FbShape.small)
    }
    Text(
        text.uppercase(), style = FbType.label, color = textColor, maxLines = 1,
        modifier = modifier.then(frame).padding(horizontal = 6.dp, vertical = 3.dp)
            .clearAndSetSemantics { contentDescription = text },
    )
}

// --- Text blocks ---------------------------------------------------------------------------------------------------

/** The superscript mark for footnote [n]: ¹ ² ³, then (4), (5)… Append it to the one help line that needs it. */
fun footnoteMark(n: Int): String = when (n) {
    1 -> "¹"
    2 -> "²"
    3 -> "³"
    else -> "($n)"
}

/**
 * A numbered note at the page foot, for anything longer than one help line. [link] is appended as an underlined
 * link that calls [onLink] (the licences link on Settings).
 */
@Composable
fun Footnote(number: Int?, text: String, modifier: Modifier = Modifier, link: String? = null, onLink: (() -> Unit)? = null) {
    val c = LocalFbColors.current
    val body = buildAnnotatedString {
        if (number != null) append(footnoteMark(number) + " ")
        append(text)
        if (link != null && onLink != null) {
            append(' ')
            withLink(
                LinkAnnotation.Clickable(
                    tag = "footnote", styles = TextLinkStyles(SpanStyle(color = c.text, textDecoration = TextDecoration.Underline)),
                    linkInteractionListener = { onLink() },
                ),
            ) { append(link) }
        }
    }
    Text(body, style = FbType.footnote, color = c.text2, modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp))
}

/** The one help line under a section or row: Archivo 13sp in text2. */
@Composable
fun HelpText(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = FbType.help, color = LocalFbColors.current.text2,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * A verified failure next to the thing that caused it: a crossed square and the reason in error red, announced by
 * TalkBack, with an optional fix. Not for steps that simply aren't done yet: those are UNCHECKED, never red.
 */
@Suppress("ModifierParameter") // parameter order is fixed: existing call sites depend on it
@Composable
fun InlineError(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val c = LocalFbColors.current
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.Top,
    ) {
        StateSquare(StateMark.FAILED, Modifier.padding(top = 4.dp))
        Column(Modifier.weight(1f).padding(start = MarkGap)) {
            Text(text, style = FbType.help, color = c.error)
            if (actionLabel != null && onAction != null) FbButton(actionLabel, onAction, kind = FbButtonKind.Text)
        }
    }
}

/**
 * Copies [text], flagged sensitive when [sensitive] so Android 13+ hides it from the clipboard preview and keyboard
 * suggestions. Literal key: ClipDescription.EXTRA_IS_SENSITIVE is API 33, and older versions just ignore it.
 */
fun copyText(ctx: Context, label: String, text: String, sensitive: Boolean = false) {
    val clip = ClipData.newPlainText(label, text)
    if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

/** How long the copy key reads COPIED after a tap. */
internal const val COPIED_MS = 600L

/**
 * A 48dp copy key: on tap it copies, inverts and reads COPIED for 600ms with the COPIED haptic, so you know it worked
 * without a toast. Used by [CodeBlock] and the device-code plate.
 */
@Composable
fun CopyKey(text: String, modifier: Modifier = Modifier, sensitive: Boolean = false, label: String = "Fullbars") {
    val c = LocalFbColors.current
    val ctx = LocalContext.current
    val view = LocalView.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(COPIED_MS); copied = false } }
    val source = remember { MutableInteractionSource() }
    val rest = pressFill(source)
    Box(
        modifier.heightIn(min = FbSpace.target).widthIn(min = FbSpace.target)
            .background(if (copied) c.text else rest, FbShape.medium)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = "Copy") {
                copyText(ctx, label, text, sensitive)
                FbHaptics.play(view, HapticPattern.COPIED)
                copied = true
            }
            .semantics {
                contentDescription = "Copy"
                if (copied) stateDescription = "Copied"
            },
        contentAlignment = Alignment.Center,
    ) {
        if (copied) {
            Text("COPIED", style = FbType.label, color = c.bg, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp))
        } else {
            Icon(FbIcons.Copy, contentDescription = null, tint = c.text, modifier = Modifier.size(24.dp))
        }
    }
}

/**
 * A shell command or address in mono on a sunk s1 plate, selectable, with a [CopyKey]. [sensitive] for anything that
 * works like a password (helper password, mod remote link, camera addresses): it shows masked, with an eye to reveal
 * it, and masks itself again after [REVEAL_MS], when the app goes to the background, or when the page closes. Copy
 * works while it's masked. [label] is what TalkBack calls it ("Helper password, hidden").
 */
@Composable
fun CodeBlock(text: String, modifier: Modifier = Modifier, sensitive: Boolean = false, label: String = "Code") {
    val c = LocalFbColors.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(shown) { if (shown) { delay(REVEAL_MS); shown = false } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { shown = false }
    val masked = sensitive && !shown
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .background(c.s1, FbShape.small).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (masked) {
            // A fixed number of dots, so the length doesn't show either.
            Text(
                MASK, style = FbType.value, color = c.text,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp).clearAndSetSemantics { contentDescription = "$label, hidden" },
            )
        } else {
            SelectionContainer(Modifier.weight(1f).padding(vertical = 10.dp)) { Text(text, style = FbType.value, color = c.text) }
        }
        if (sensitive) {
            GlyphButton(
                if (shown) FbIcons.EyeOff else FbIcons.Visibility,
                contentDescription = if (shown) "Hide $label" else "Show $label",
                onClick = { shown = !shown },
            )
        }
        CopyKey(text, sensitive = sensitive)
    }
}

/** How long a revealed [CodeBlock] stays readable. */
internal const val REVEAL_MS = 30_000L
private const val MASK = "••••••••••••"


/** Text-field colours on the ladder: a ruleStrong outline at rest, Bone when focused, no fill. */
@Composable
private fun fieldColors(): TextFieldColors {
    val c = LocalFbColors.current
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.text, unfocusedTextColor = c.text, disabledTextColor = c.text2,
        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
        cursorColor = c.text, errorCursorColor = c.error,
        focusedBorderColor = c.text, unfocusedBorderColor = c.ruleStrong, errorBorderColor = c.error,
        focusedLabelColor = c.text, unfocusedLabelColor = c.text2, errorLabelColor = c.error,
        focusedSupportingTextColor = c.text2, unfocusedSupportingTextColor = c.text2,
        focusedTrailingIconColor = c.text, unfocusedTrailingIconColor = c.text2,
    )
}

/**
 * A secret text field (stream key, Wi-Fi password, tip tokens): masked with a show/hide eye, and marked as a password
 * so the keyboard doesn't learn it or suggest it. Masking is the protection that always holds: MainActivity sets
 * FLAG_SECURE only while you're not live (and a debug screenshot launch turns it off), so don't rely on it here.
 */
@Composable
fun SecretField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, supporting: String? = null) {
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label) }, singleLine = true,
        textStyle = FbType.value,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            GlyphButton(
                if (shown) FbIcons.EyeOff else FbIcons.Visibility,
                contentDescription = if (shown) "Hide $label" else "Show $label",
                onClick = { shown = !shown },
            )
        },
        supportingText = supporting?.let { { Text(it) } },
        shape = FbShape.medium, colors = fieldColors(),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** A plain text field with the page's horizontal padding; [keyboardOptions] for a number, [isError] for a bad one. */
@Composable
fun PlainField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label) }, singleLine = true,
        textStyle = FbType.rowValue, keyboardOptions = keyboardOptions, isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        shape = FbShape.medium, colors = fieldColors(),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** Deprecated: groups have no container now. Rows separate themselves with hairlines; this only adds the top rule. */
@Deprecated("No containers on Home or Settings: put the rows straight under a SectionHeader.")
@Composable
fun RowGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Hairline()
        content()
    }
}

/**
 * Deprecated: no cards. Draws as a hairline-bounded block: title, text and actions, no container and no icon
 * ([icon] and [container] are ignored; [content] still colours the title if given).
 */
@Deprecated("No cards: use a SectionHeader with ValueRows, HelpText and an FbButton.")
@Composable
fun InfoCard(
    icon: ImageVector,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    container: Color = Color.Transparent,
    content: Color = Color.Unspecified,
    actions: @Composable (() -> Unit)? = null,
) {
    val c = LocalFbColors.current
    Column(modifier.fillMaxWidth()) {
        Hairline()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = FbType.row, color = if (content.isSpecified) content else c.text, modifier = Modifier.semantics { heading() })
            Text(text, style = FbType.help, color = c.text2)
            if (actions != null) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
        Hairline()
    }
}

/** A confirm dialog on the s1 plate, rM, with Text buttons; the confirm label says what happens ("Forget"). */
@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalFbColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = FbType.cta) },
        text = { Text(text, style = FbType.help) },
        confirmButton = { FbButton(confirmLabel, onClick = { onDismiss(); onConfirm() }, kind = FbButtonKind.Text) },
        dismissButton = { FbButton("Cancel", onClick = onDismiss, kind = FbButtonKind.Text) },
        shape = FbShape.medium, containerColor = c.s1, titleContentColor = c.text, textContentColor = c.text2,
        tonalElevation = 0.dp,
    )
}

@Composable
fun VSpace(dp: Int) = Spacer(Modifier.height(dp.dp))

/** Supporting text that may be long: two lines, ellipsized. */
@Composable
fun TwoLine(text: String, color: Color = Color.Unspecified) =
    Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis, color = color)

val ContentPadding = PaddingValues(horizontal = 16.dp)

/** A row that folds what follows it in or out, with the state said in words for TalkBack. */
@Composable
internal fun ExpandRow(label: String, expanded: Boolean, onToggle: () -> Unit) {
    val c = LocalFbColors.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        Modifier.fillMaxWidth().bottomRule(c.rule)
            .background(if (pressed) c.s2 else Color.Transparent)
            .clickable(source, indication = null, role = Role.Button, onClickLabel = if (expanded) "Hide" else "Show", onClick = onToggle)
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .heightIn(min = FbSpace.rowTappable)
            .padding(horizontal = FbSpace.gutter, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = FbType.row, color = c.text, modifier = Modifier.weight(1f))
        Icon(if (expanded) FbIcons.ExpandLess else FbIcons.ExpandMore, contentDescription = null, tint = c.text2, modifier = Modifier.size(20.dp))
    }
}

/** The visible name over a [KeyChoice] row ("Text size", "Where alerts go"), so a row of keys never stands unlabelled. */
@Composable
internal fun ChoiceLabel(text: String) {
    Text(text, style = FbType.rowValue, color = LocalFbColors.current.text, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
}
