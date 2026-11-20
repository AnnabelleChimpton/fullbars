package app.irlsupreme.ui.live

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.irlsupreme.engine.audio.MicLevel
import app.irlsupreme.ui.LevelBar
import app.irlsupreme.ui.micVerdict
import app.irlsupreme.ui.rememberMeterReading
import app.irlsupreme.ui.MeterGlyph
import app.irlsupreme.ui.Tag
import app.irlsupreme.ui.theme.FbLiveType
import app.irlsupreme.ui.theme.FbMotion
import app.irlsupreme.ui.theme.LocalHealthColors
import app.irlsupreme.ui.theme.OverVideo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/*
 * The instrument line: four fixed columns, an L3 label over an L2Mono value. Fixed so nothing jumps rows as
 * the state changes; the 2×2 grid at large type is chosen by font scale, never by state.
 */

/** The timer column's content. */
internal data class TimerCell(val label: String, val text: String, val spoken: String, val pending: Boolean)

/** How the line is laid out: one row of four, a 2×2 grid at large type, or stacked in the landscape column. */
internal enum class InstrumentLayout { ROW, GRID, STACK }

/** Pages through link pairs on its own every few seconds when there are 3+ links; a tap pages too. */
private const val PAGE_MS = 4_000L

@Composable
internal fun InstrumentLine(
    timer: TimerCell,
    links: List<LinkCell>,
    mic: MicCell,
    layout: InstrumentLayout,
    reduced: Boolean,
    modifier: Modifier = Modifier,
    micLevel: StateFlow<MicLevel>? = null,
    onMic: (() -> Unit)? = null,
) {
    var page by remember { mutableIntStateOf(0) }
    val pages = linkPages(links.size)
    if (pages > 1) LaunchedEffect(pages) { while (true) { delay(PAGE_MS); page = (page + 1) % pages } }
    val (pair, index) = linkPage(links, page)
    val onPage: (() -> Unit)? = if (pages > 1) ({ page = (page + 1) % pages }) else null

    when (layout) {
        InstrumentLayout.ROW -> Row(modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            TimerColumn(timer, Modifier.weight(1.15f))
            LinkColumn(pair[0], index, reduced, onPage, Modifier.weight(1f))
            LinkColumn(pair[1], null, reduced, onPage, Modifier.weight(1f))
            MicColumn(mic, micLevel, onMic, Modifier.weight(0.85f))
        }
        InstrumentLayout.GRID -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                TimerColumn(timer, Modifier.weight(1f))
                MicColumn(mic, micLevel, onMic, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                LinkColumn(pair[0], index, reduced, onPage, Modifier.weight(1f))
                LinkColumn(pair[1], null, reduced, onPage, Modifier.weight(1f))
            }
        }
        InstrumentLayout.STACK -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TimerColumn(timer, Modifier.fillMaxWidth())
            LinkColumn(pair[0], index, reduced, onPage, Modifier.fillMaxWidth())
            LinkColumn(pair[1], null, reduced, onPage, Modifier.fillMaxWidth())
            MicColumn(mic, micLevel, onMic, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Label(text: String, color: Color = OverVideo.text2, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = FbLiveType.L3, color = color, maxLines = 2)
        trailing?.let {
            Spacer(Modifier.width(6.dp))
            Text(it, style = FbLiveType.L3.copy(fontFamily = FbLiveType.L2Mono.fontFamily), color = OverVideo.text2)
        }
    }
}

/** The timer: mono Bone, `–:––:––` in text2 until the stream is live. TalkBack hears whole minutes only. */
@Composable
private fun TimerColumn(t: TimerCell, modifier: Modifier) {
    Column(
        modifier.clearAndSetSemantics {
            contentDescription = if (t.pending) "Timer" else t.label.lowercase().replaceFirstChar { it.uppercase() }
            stateDescription = t.spoken
        },
    ) {
        Label(t.label)
        Text(t.text, style = FbLiveType.L2Mono, color = if (t.pending) OverVideo.text2 else OverVideo.text, maxLines = 1)
    }
}

/**
 * One link: label, four bars and the value. Bone when fine, amber label + bars + value when weak, coral dashed bars and
 * DOWN when lost, hollow dashed bars and `···` while connecting. Never green, never 0.0. The bars step one per Tick
 * toward the real level, so a link's first ack climbs rather than jumps.
 */
@Composable
private fun LinkColumn(cell: LinkCell?, index: String?, reduced: Boolean, onPage: (() -> Unit)?, modifier: Modifier) {
    if (cell == null) {
        Spacer(modifier)
        return
    }
    val h = LocalHealthColors.current
    val (labelColor, mark) = when (cell.state) {
        LinkState.FINE -> OverVideo.text2 to OverVideo.text
        LinkState.WEAK -> h.weak to h.weak
        LinkState.DOWN -> OverVideo.text2 to h.reconnecting
        LinkState.CONNECTING -> OverVideo.text2 to OverVideo.text2
    }
    val shown = rememberStepped(cell.level, reduced)
    val dashed = cell.state == LinkState.DOWN || cell.state == LinkState.CONNECTING
    Column(
        modifier
            .then(if (onPage != null) Modifier.clickable(onClickLabel = "Show the next links", onClick = onPage) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = cell.spoken },
    ) {
        Column(Modifier.clearAndSetSemantics {}) {
            Label(cell.label, labelColor, index)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MeterGlyph(shown, mark, dashed = dashed, modifier = Modifier.padding(bottom = 2.dp))
                Text(cell.value, style = FbLiveType.L2Mono, color = mark, maxLines = 1)
            }
        }
    }
}

/** A level that steps one bar per Tick toward [target] (a cut under reduce motion). */
@Composable
private fun rememberStepped(target: Int, reduced: Boolean): Int {
    var shown by remember { mutableIntStateOf(target) }
    LaunchedEffect(target, reduced) {
        if (reduced) { shown = target; return@LaunchedEffect }
        while (shown != target) {
            delay(FbMotion.TICK_MS.toLong())
            shown += if (target > shown) 1 else -1
        }
    }
    return shown
}

/**
 * MIC: the live level as a 10-segment bar (Bone, amber near the top), or an inverted MUTED / NO AUDIO tag in the same
 * slot, or low battery in amber (labelled PHONE then). A tap opens the mic sheet, whatever it shows. The level is
 * collected here, so ~47 updates a second recompose this column only.
 */
@Composable
private fun MicColumn(mic: MicCell, levelFlow: StateFlow<MicLevel>?, onMic: (() -> Unit)?, modifier: Modifier) {
    val h = LocalHealthColors.current
    val level = levelFlow?.collectAsState()?.value ?: MicLevel.SILENT
    val reading = rememberMeterReading(level)
    val verdict = micVerdict(reading, muted = mic == MicCell.Muted)
    val spoken = when (mic) {
        MicCell.Muted -> "Mic muted"
        MicCell.NoAudio -> "Mic, no audio"
        is MicCell.Battery -> "Battery ${mic.percent} percent"
        MicCell.On -> "Mic on. ${verdict.words}"
    }
    Column(
        modifier
            .then(if (onMic != null) Modifier.clickable(onClickLabel = "Mic level", role = Role.Button, onClick = onMic) else Modifier)
            .clearAndSetSemantics {
                contentDescription = spoken
                if (onMic != null) onClick("Mic level") { onMic(); true }
            },
    ) {
        Label(if (mic is MicCell.Battery) "PHONE" else "MIC")
        Row(Modifier.heightIn(min = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            when (mic) {
                MicCell.Muted -> EngagedTag("MUTED")
                MicCell.NoAudio -> EngagedTag("NO AUDIO")
                is MicCell.Battery -> Text("BAT ${mic.percent}%", style = FbLiveType.L2Mono, color = h.weak, maxLines = 1)
                MicCell.On -> LevelBar(
                    reading, Modifier.padding(end = 4.dp), segments = 10, floorDb = -50f, height = 12.dp,
                    on = OverVideo.text, off = OverVideo.keyOutline, target = false,
                )
            }
        }
    }
}

/** An inverted Bone tag with Ink text: an engaged state in a fixed slot (MUTED, NO AUDIO, NOT ON CAMERA). */
@Composable
internal fun EngagedTag(text: String, modifier: Modifier = Modifier) {
    Tag(text, inverted = true, color = OverVideo.keyEngagedFill, onColor = OverVideo.keyEngagedContent, modifier = modifier)
}
