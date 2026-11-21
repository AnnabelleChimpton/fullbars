package app.irlsupreme.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.irlsupreme.service.ColorMath
import app.irlsupreme.ui.theme.FbShape
import app.irlsupreme.ui.theme.FbSpace
import app.irlsupreme.ui.theme.FbType
import app.irlsupreme.ui.theme.LocalFbColors

/**
 * A colour choice: "Default" (when [defaultLabel] isn't null), the [presets], and Custom, which opens a picker for any
 * colour. A radio group; every swatch is named for TalkBack, and the chosen one gets a ring and a mark inside, so the
 * choice never rests on colour alone. [name]: what the row is ("Accent", "Text colour").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorChoice(
    name: String,
    selected: Int?,
    presets: List<Int>,
    onPick: (Int?) -> Unit,
    defaultLabel: String? = "Default",
    presetName: (Int) -> String = ColorMath::name,
    modifier: Modifier = Modifier,
) {
    val c = LocalFbColors.current
    var picking by remember { mutableStateOf(false) }
    val custom = selected != null && selected !in presets
    FlowRow(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics { contentDescription = name }.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (defaultLabel != null) {
            val chosen = selected == null
            Box(
                Modifier.heightIn(min = FbSpace.target)
                    .background(if (chosen) c.text else Color.Transparent, FbShape.medium)
                    .then(if (chosen) Modifier else Modifier.border(FbShape.stroke, c.ruleStrong, FbShape.medium))
                    .selectable(chosen, role = Role.RadioButton) { onPick(null) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text(defaultLabel, style = FbType.rowValue, color = if (chosen) c.bg else c.text) }
        }
        presets.forEach { p -> Swatch(p, p == selected, presetName(p)) { onPick(p) } }
        // Custom: the chosen custom colour itself, or a hue ring to say "any colour".
        Box(
            Modifier.size(FbSpace.target)
                .then(if (custom) Modifier.border(FbShape.outline, c.text, FbShape.small) else Modifier)
                .selectable(custom, role = Role.RadioButton) { picking = true }
                .semantics {
                    contentDescription = "Custom colour"
                    if (custom && selected != null) stateDescription = "${ColorMath.name(selected)}, ${ColorMath.toHex(selected)}"
                },
            contentAlignment = Alignment.Center,
        ) {
            val fill = if (custom && selected != null) Modifier.background(Color(selected), FbShape.small)
            else Modifier.background(Brush.sweepGradient(HUES.map { Color(it) }), FbShape.small)
            Box(Modifier.size(34.dp).then(fill), contentAlignment = Alignment.Center) {
                if (custom && selected != null) Box(Modifier.size(10.dp).background(Color(ColorMath.readableOn(selected, WHITE, BLACK))))
                else Text("+", style = FbType.row, color = Color(BLACK))
            }
        }
    }
    if (picking) ColorDialog(
        title = name,
        initial = selected ?: presets.firstOrNull() ?: DEFAULT_PICK,
        onDone = { onPick(it); picking = false },
        onDismiss = { picking = false },
    )
}

@Composable
private fun Swatch(argb: Int, chosen: Boolean, name: String, onPick: () -> Unit) {
    val c = LocalFbColors.current
    Box(
        Modifier.size(FbSpace.target)
            .then(if (chosen) Modifier.border(FbShape.outline, c.text, FbShape.small) else Modifier)
            .selectable(chosen, role = Role.RadioButton, onClick = onPick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(34.dp).background(Color(argb), FbShape.small), contentAlignment = Alignment.Center) {
            if (chosen) Box(Modifier.size(10.dp).background(Color(ColorMath.readableOn(argb, WHITE, BLACK))))
        }
    }
}

/** Any colour: hue, saturation and brightness sliders, and the hex code, which can also be typed. */
@Composable
fun ColorDialog(title: String, initial: Int, onDone: (Int) -> Unit, onDismiss: () -> Unit) {
    val c = LocalFbColors.current
    val start = remember(initial) { ColorMath.toHsv(initial) }
    var h by remember { mutableFloatStateOf(start[0]) }
    var s by remember { mutableFloatStateOf(start[1]) }
    var v by remember { mutableFloatStateOf(start[2]) }
    val argb = ColorMath.fromHsv(h, s, v)
    var hex by remember { mutableStateOf(ColorMath.toHex(initial)) }
    fun fromSliders() { hex = ColorMath.toHex(ColorMath.fromHsv(h, s, v)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = FbType.cta) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp).background(Color(argb), FbShape.small).border(FbShape.stroke, c.ruleStrong, FbShape.small))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(ColorMath.name(argb), style = FbType.row, color = c.text)
                        if (ColorMath.looksLikeSignal(argb)) {
                            Text("Close to a warning colour: viewers might read it as one.", style = FbType.footnote, color = c.text2)
                        }
                    }
                }
                GradientSlider("Hue", h / 360f, Brush.horizontalGradient(HUES.map { Color(it) }), "${h.toInt()}°") { h = it * 360f; fromSliders() }
                GradientSlider(
                    "Saturation", s,
                    Brush.horizontalGradient(listOf(Color(ColorMath.fromHsv(h, 0f, v)), Color(ColorMath.fromHsv(h, 1f, v)))), "${(s * 100).toInt()}%",
                ) { s = it; fromSliders() }
                GradientSlider(
                    "Brightness", v,
                    Brush.horizontalGradient(listOf(Color(BLACK), Color(ColorMath.fromHsv(h, s, 1f)))), "${(v * 100).toInt()}%",
                ) { v = it; fromSliders() }
                OutlinedTextField(
                    value = hex,
                    onValueChange = { t ->
                        hex = t.take(7)
                        ColorMath.parseHex(t)?.let { parsed -> ColorMath.toHsv(parsed).let { h = it[0]; s = it[1]; v = it[2] } }
                    },
                    label = { Text("Hex code") },
                    singleLine = true,
                    isError = ColorMath.parseHex(hex) == null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    textStyle = FbType.value,
                    shape = FbShape.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { FbButton("Use colour", onClick = { onDone(argb) }, kind = FbButtonKind.Text) },
        dismissButton = { FbButton("Cancel", onClick = onDismiss, kind = FbButtonKind.Text) },
        shape = FbShape.medium, containerColor = c.s1, titleContentColor = c.text, textContentColor = c.text2,
        tonalElevation = 0.dp,
    )
}

/** A 0..1 slider over a gradient of what it changes, read out as [spoken]. */
@Composable
private fun GradientSlider(label: String, value: Float, track: Brush, spoken: String, onChange: (Float) -> Unit) {
    val c = LocalFbColors.current
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = FbType.label, color = c.text2)
            Text(spoken, style = FbType.value, color = c.text2)
        }
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(10.dp).padding(horizontal = 10.dp).background(track, FbShape.small))
            Slider(
                value = value, onValueChange = onChange,
                colors = SliderDefaults.colors(
                    thumbColor = c.text, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
                ),
                modifier = Modifier.semantics { contentDescription = label; stateDescription = spoken },
            )
        }
    }
}

private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLACK = 0xFF000000.toInt()
private const val DEFAULT_PICK = 0xFF2A86C8.toInt()
private val HUES = listOf(
    0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FFFF.toInt(),
    0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFFFF0000.toInt(),
)
