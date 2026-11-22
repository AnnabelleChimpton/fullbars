package app.irlsupreme.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.irlsupreme.engine.CameraPhase
import app.irlsupreme.engine.EngineState
import app.irlsupreme.ui.Hairline
import app.irlsupreme.ui.theme.FbLiveType
import app.irlsupreme.ui.theme.FbShape
import app.irlsupreme.ui.theme.OverVideo
import kotlinx.coroutines.delay
import java.util.Locale

/*
 * Details: the numbers behind the pill as a two-column table, L3 labels and right-aligned values, Mb/s
 * only. It replaces "Label: value" prose that wrapped into a paragraph nobody could scan while walking.
 */

/** One Details row. [figure]: the value is numbers (mono); words stay in Archivo (mono is for figures only). */
internal data class DetailRow(val label: String, val value: String, val figure: Boolean)

private fun mb(bps: Long): String = String.format(Locale.US, "%.1f", bps / 1_000_000.0)

internal fun thermalLabel(s: Int) = when (s) {
    0 -> "Normal"; 1 -> "Warm"; 2 -> "Hot"; 3 -> "Very hot, quality lowered"; 4 -> "Critical"; 5 -> "Emergency"; 6 -> "Shutting down"
    else -> "Unknown ($s)"
}

private fun cameraLabel(c: CameraPhase) = when (c) {
    CameraPhase.OFF -> "off"; CameraPhase.OPENING -> "opening"; CameraPhase.LIVE -> "on"; CameraPhase.LOST -> "lost"
}

/**
 * The stream rows, then a rule, then one row per link (built separately so the rule can sit between them). What's on
 * air and the heat come first; the upload figure is the instrument line's, so it isn't repeated; the counters that are
 * zero on a healthy stream show only once they aren't. [pocket]: the Pocket 3's own line, with the full reason when it
 * fails (the live notice keeps it short).
 */
internal fun detailRows(state: EngineState, pocket: String?): List<DetailRow> = buildList {
    val external = if (pocket != null) "Pocket 3" else "Other camera"
    val onAir = when (state.activeSource) { "camera" -> external; "phone" -> "Phone camera"; else -> "Card (no camera)" }
    // The phone camera's own state only when it's on air and not running as it should.
    val phase = if (state.activeSource == "phone" && state.camera != CameraPhase.LIVE) " · ${cameraLabel(state.camera)}" else ""
    add(DetailRow("CAMERA", onAir + phase, false))
    (pocket ?: state.externalCamera)?.let { add(DetailRow(external.uppercase(Locale.ROOT), it, false)) }
    add(DetailRow("TEMPERATURE", thermalLabel(state.thermalStatus), false))
    add(DetailRow("VIDEO OUT", "${mb(state.measuredVideoBitrate)} / ${mb(state.targetVideoBitrate.toLong())} Mb/s", true))
    if (state.congested) add(DetailRow("CONGESTED", "yes", false))
    if (state.droppedFrames > 0) add(DetailRow("DROPPED FRAMES", "${state.droppedFrames}", true))
    if (state.encoderRecoveries > 0) add(DetailRow("ENCODER RECOVERIES", "${state.encoderRecoveries}", true))
    if (state.reconnectAttempt > 0) add(DetailRow("RECONNECTS", "${state.reconnectAttempt}", true))
}

/** `up  0.4 Mb/s  125 ms`, or `down`; a helper phone adds its battery and heat. */
internal fun linkRows(state: EngineState): List<DetailRow> = state.links.map { l ->
    val extra = listOfNotNull(l.batteryPercent?.let { "$it%" }, if (l.hot) "hot" else null).joinToString("") { "  $it" }
    DetailRow(
        l.name.uppercase(Locale.ROOT),
        if (l.up) "up  ${String.format(Locale.US, "%.1f", l.kbps / 1000.0)} Mb/s  ${l.rttMs?.let { "$it ms" } ?: "–"}$extra" else "down",
        true,
    )
}

/**
 * The panel under the pill. [hotspot] (name to password) is the helper hotspot for helper phones that join by hand;
 * [helperProblem] replaces it when it couldn't start. The password starts hidden (people screenshot this panel when
 * asking for help); SHOW calls [onSecretShown] so screenshots are blocked until it's hidden again, by a tap, after
 * 30 s, or when Details closes.
 */
@Composable
internal fun LiveDetails(
    state: EngineState,
    cameraLink: String?,
    hotspot: Pair<String, String>?,
    helperProblem: String?,
    onSecretShown: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var reveal by remember { mutableStateOf(false) }
    val showing = reveal && hotspot != null && helperProblem == null
    DisposableEffect(showing) {
        if (showing) onSecretShown(true)
        onDispose { if (showing) onSecretShown(false) }
    }
    LaunchedEffect(showing) { if (showing) { delay(30_000); reveal = false } }

    Column(
        modifier.background(OverVideo.scrim, FbShape.small).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        detailRows(state, cameraLink).forEach { TableRow(it) }
        val links = linkRows(state)
        if (links.isNotEmpty()) {
            Hairline(Modifier.padding(vertical = 4.dp), color = OverVideo.keyOutline)
            links.forEach { TableRow(it) }
        }
        when {
            helperProblem != null -> {
                Hairline(Modifier.padding(vertical = 4.dp), color = OverVideo.keyOutline)
                TableRow(DetailRow("HELPERS", "No hotspot ($helperProblem). Put helpers on the same Wi‑Fi.", false))
            }
            // For helper phones running Moblin (Fullbars helpers get these over Bluetooth): typed in by hand.
            hotspot != null -> {
                Hairline(Modifier.padding(vertical = 4.dp), color = OverVideo.keyOutline)
                TableRow(DetailRow("HOTSPOT", hotspot.first, false))
                PasswordRow(hotspot.second, showing) { reveal = !showing }
            }
        }
        state.lastError?.let {
            Hairline(Modifier.padding(vertical = 4.dp), color = OverVideo.keyOutline)
            TableRow(DetailRow("LAST ERROR", it.take(200), false))
        }
    }
}

@Composable
private fun TableRow(row: DetailRow) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 20.dp).clearAndSetSemantics { contentDescription = "${row.label.lowercase()}: ${row.value}" },
        verticalAlignment = Alignment.Top,
    ) {
        Text(row.label, style = FbLiveType.L3, color = OverVideo.text2, modifier = Modifier.weight(0.45f).padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            row.value, style = if (row.figure) FbLiveType.L2Mono else FbLiveType.L2, color = OverVideo.text,
            textAlign = TextAlign.End, modifier = Modifier.weight(0.55f),
        )
    }
}

/** The hotspot password, hidden as `••••` behind a SHOW key; the whole row is the 48dp target. */
@Composable
private fun PasswordRow(password: String, showing: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = if (showing) "Hide password" else "Show password", role = Role.Button, onClick = onToggle)
            .semantics { contentDescription = if (showing) "Password $password" else "Password hidden" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("PASSWORD", style = FbLiveType.L3, color = OverVideo.text2, modifier = Modifier.weight(0.45f).clearAndSetSemantics {})
        Spacer(Modifier.width(8.dp))
        Row(Modifier.weight(0.55f).clearAndSetSemantics {}, horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Text(if (showing) password else "••••", style = FbLiveType.L2Mono, color = OverVideo.text, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(10.dp))
            Text(if (showing) "HIDE" else "SHOW", style = FbLiveType.L3, color = OverVideo.text)
        }
    }
}
