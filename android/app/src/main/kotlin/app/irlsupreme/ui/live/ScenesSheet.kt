package app.irlsupreme.ui.live

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.irlsupreme.engine.Scene
import app.irlsupreme.ui.FbRadio
import app.irlsupreme.ui.Hairline
import app.irlsupreme.ui.sceneName
import app.irlsupreme.ui.theme.FbShape
import app.irlsupreme.ui.theme.FbType
import app.irlsupreme.ui.theme.LocalFbColors

/*
 * The Scenes sheet: attached to the rail, no scrim, so the preview, pill and instrument line stay in view
 * while you choose. Each row shows the real card it would put on stream and what viewers get.
 */

/**
 * What choosing [scene] does, in one line. Cards hide the camera, never the mic, unless the mic is already off or the
 * card's music turns it off ([musicMicOff]).
 */
internal fun sceneConsequence(scene: Scene, muted: Boolean, musicMicOff: Boolean = false): String = when {
    scene == Scene.LIVE -> "Your camera"
    scene == Scene.PRIVACY -> "Card · camera and mic off"
    muted -> "Card · mic off"
    musicMicOff -> "Card · they hear your music"
    else -> "Card · they hear you"
}

/**
 * The sheet body. [scenes]: the ones to offer, the built-ins then the streamer's own that exist. [thumbs]: the last
 * camera frame for Live and the engine's card for the rest (null while drawing).
 * [flipValue]: "Back"/"Front", or null when the phone camera isn't the one being sent (the row is then disabled).
 */
@Composable
internal fun ScenesSheet(
    scene: Scene,
    muted: Boolean,
    scenes: List<Scene>,
    thumbs: Map<Scene, ImageBitmap?>,
    landscapeThumbs: Boolean,
    flipValue: String?,
    onFlip: () -> Unit,
    onChoose: (Scene) -> Unit,
    modifier: Modifier = Modifier,
    /** The scenes whose music turns the mic off. */
    musicMicOff: Set<Scene> = emptySet(),
    /** Test streams only: sends a pretend alert through the real alert path. Null hides the row. */
    onTestAlert: ((app.irlsupreme.service.SampleAlert) -> Unit)? = null,
    /** Whether alerts are on (Settings > Overlays); the row says so when they're off. */
    alertsOn: Boolean = true,
    /** Turns the Pocket screen on (null hides the row). */
    onPocketScreen: (() -> Unit)? = null,
) {
    val c = LocalFbColors.current
    Column(
        modifier
            .background(c.s1, RoundedCornerShape(topStart = FbShape.rM, topEnd = FbShape.rM))
            .semantics { paneTitle = "Scenes" },
    ) {
        Hairline(color = c.rule)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp)) {
                Text("SCENES", style = FbType.label, color = c.text2, modifier = Modifier.semantics { heading() })
                Text("Weak signal and Camera lost switch on their own.", style = FbType.help, color = c.text2)
            }
            Column(Modifier.selectableGroup()) {
                scenes.forEach { s ->
                    Hairline(color = c.rule)
                    SceneRow(s, s == scene, sceneConsequence(s, muted, s in musicMicOff), thumbs[s], landscapeThumbs) { onChoose(s) }
                }
            }
        }
        if (onTestAlert != null) {
            Hairline(color = c.rule)
            TestAlertRow(alertsOn, onTestAlert)
        }
        Hairline(color = c.rule)
        FlipRow(flipValue, onFlip)
        if (onPocketScreen != null) {
            Hairline(color = c.rule)
            PocketRow(onPocketScreen)
        }
    }
}

/** "Pocket screen": the screen goes black and dim while the stream carries on, for a phone in a pocket or bag. */
@Composable
private fun PocketRow(onClick: () -> Unit) {
    val c = LocalFbColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClickLabel = "Turn on the pocket screen", role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Pocket screen", style = FbType.row, color = c.text)
            Text("Black screen, stream carries on. Keeps the phone cooler.", style = FbType.help, color = c.text2)
        }
    }
}

/**
 * "Test an alert" (Test streams only): one key per kind; a tap sends that pretend alert through the real path, so it
 * wipes in on stream with its chime exactly as a real one would. Never in a public stream, where viewers would take it
 * for real.
 */
@Composable
private fun TestAlertRow(alertsOn: Boolean, onTestAlert: (app.irlsupreme.service.SampleAlert) -> Unit) {
    val c = LocalFbColors.current
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)) {
        Text("Test an alert", style = FbType.row, color = c.text)
        Text(
            if (alertsOn) "Only in Test streams. Shows on stream with its sound, like a real one."
            else "Alerts are off in Settings > Overlays.",
            style = FbType.help, color = c.text2,
        )
        if (alertsOn) {
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                app.irlsupreme.service.SampleAlert.entries.forEach { k ->
                    Box(
                        Modifier
                            .heightIn(min = 48.dp)
                            .border(FbShape.stroke, c.ruleStrong, FbShape.medium)
                            .clickable(onClickLabel = "Send a test ${k.title.lowercase()} alert", role = Role.Button) { onTestAlert(k) }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(k.title, style = FbType.rowValue, color = c.text) }
                }
            }
        }
    }
}

@Composable
private fun SceneRow(scene: Scene, selected: Boolean, consequence: String, thumb: ImageBitmap?, landscape: Boolean, onClick: () -> Unit) {
    val c = LocalFbColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 66.dp)
            .background(if (selected) c.s3 else c.s1)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (w, h) = if (landscape) 64.dp to 36.dp else 36.dp to 64.dp
        Box(
            Modifier.size(w, h).clip(FbShape.small).background(c.bg).border(1.dp, c.rule, FbShape.small),
        ) {
            if (thumb != null) {
                Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(sceneName(scene), style = FbType.row, color = c.text)
            Text(consequence, style = FbType.help, color = c.text2)
        }
        Spacer(Modifier.width(12.dp))
        FbRadio(selected)
    }
}

/** "Flip camera" with the side in use. A tap flips and the sheet stays open, so you can check the result. */
@Composable
private fun FlipRow(value: String?, onFlip: () -> Unit) {
    val c = LocalFbColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = value != null, onClickLabel = "Flip camera", role = Role.Button, onClick = onFlip)
            .semantics {
                contentDescription = "Flip camera"
                stateDescription = if (value != null) "$value camera" else "Not on the phone camera"
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Flip camera", style = FbType.row, color = if (value != null) c.text else c.text2, modifier = Modifier.clearAndSetSemantics {})
        Text(value ?: "Camera link", style = FbType.rowValue, color = c.text2, modifier = Modifier.clearAndSetSemantics {})
    }
}
