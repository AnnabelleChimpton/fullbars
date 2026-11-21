package app.irlsupreme.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.irlsupreme.ui.theme.FbHaptics
import app.irlsupreme.ui.theme.FbLiveType
import app.irlsupreme.ui.theme.FbShape
import app.irlsupreme.ui.theme.HapticPattern
import app.irlsupreme.ui.theme.OverVideo

/** What the preview's main key says: a Test stream starts a test, anything else goes live. */
internal fun goLiveLabel(isTest: Boolean): String = if (isTest) "START TEST" else "GO LIVE"

/** What TalkBack reads for the main key, with the scene viewers will see first. */
internal fun goLiveSpoken(isTest: Boolean, startOnCard: Boolean): String {
    val what = if (isTest) "Start the test" else "Go live"
    return if (startOnCard) "$what on your Starting soon screen" else "$what on camera"
}

/**
 * The plate while the stream is in preview: nothing is sent yet. "Start on" picks what viewers see first (the camera,
 * or the Starting soon card while you finish setting up), then GO LIVE starts sending; CANCEL closes the preview.
 * [vertical]: the landscape column, everything stacked. [enabled]: false once Go live or Cancel is on its way.
 */
@Composable
internal fun PreviewPlate(
    isTest: Boolean,
    startOnCard: Boolean,
    onStartOn: (card: Boolean) -> Unit,
    onCancel: () -> Unit,
    onGoLive: () -> Unit,
    vertical: Boolean,
    big: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val keyHeight = (if (big) Rail.KEY_HEIGHT_BIG else Rail.KEY_HEIGHT).dp
    val faded = Modifier.alpha(if (enabled) 1f else 0.4f)
    val choices: @Composable (Modifier) -> Unit = { m ->
        Text(
            "START ON", style = FbLiveType.L3, color = OverVideo.text2,
            modifier = Modifier.padding(bottom = 4.dp).semantics { heading() },
        )
        val chips: @Composable (Modifier) -> Unit = { cm ->
            Choice("Camera", selected = !startOnCard, enabled = enabled, modifier = cm) {
                if (startOnCard) { FbHaptics.play(view, HapticPattern.TOGGLE); onStartOn(false) }
            }
            Choice("Starting soon", selected = startOnCard, enabled = enabled, modifier = cm) {
                if (!startOnCard) { FbHaptics.play(view, HapticPattern.TOGGLE); onStartOn(true) }
            }
        }
        if (vertical) {
            Column(m.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Rail.GAP.dp)) { chips(Modifier.fillMaxWidth()) }
        } else {
            Row(m.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Rail.GAP.dp)) { chips(Modifier.weight(1f)) }
        }
    }
    val goLive: @Composable (Modifier) -> Unit = { m ->
        PlateKey(
            goLiveLabel(isTest), primary = true, enabled = enabled,
            spoken = goLiveSpoken(isTest, startOnCard),
            modifier = m.heightIn(min = keyHeight),
        ) { FbHaptics.play(view, HapticPattern.KEY); onGoLive() }
    }
    val cancel: @Composable (Modifier) -> Unit = { m ->
        PlateKey("CANCEL", primary = false, enabled = enabled, spoken = "Cancel. Close the preview without going live", modifier = m.heightIn(min = keyHeight)) {
            FbHaptics.play(view, HapticPattern.KEY); onCancel()
        }
    }
    Column(modifier.fillMaxWidth().then(faded)) {
        choices(Modifier.fillMaxWidth())
        Box(Modifier.heightIn(min = 10.dp))
        if (vertical) {
            Column(verticalArrangement = Arrangement.spacedBy(Rail.GAP.dp)) {
                goLive(Modifier.fillMaxWidth())
                cancel(Modifier.fillMaxWidth())
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                cancel(Modifier.width(Rail.END.dp + 16.dp))
                Box(Modifier.width(Rail.END_GAP.dp))
                goLive(Modifier.weight(1f))
            }
        }
    }
}

/** One "Start on" option: inverted (Bone fill, Ink text) when chosen, outlined otherwise, as the rail's keys are. */
@Composable
private fun Choice(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(FbShape.medium)
            .background(if (selected) OverVideo.keyEngagedFill else Color.Transparent)
            .border(FbShape.stroke, if (selected) OverVideo.keyEngagedFill else OverVideo.keyOutline, FbShape.medium)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = FbLiveType.L2, textAlign = TextAlign.Center,
            color = if (selected) OverVideo.keyEngagedContent else OverVideo.text,
        )
    }
}

/** A plate key: [primary] is the filled GO LIVE, the other the outlined CANCEL. */
@Composable
private fun PlateKey(label: String, primary: Boolean, enabled: Boolean, spoken: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(FbShape.medium)
            .background(if (primary) OverVideo.keyEngagedFill else Color.Transparent)
            .border(FbShape.stroke, if (primary) OverVideo.keyEngagedFill else OverVideo.keyOutline, FbShape.medium)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = if (primary) FbLiveType.L1 else FbLiveType.L3, textAlign = TextAlign.Center,
            color = if (primary) OverVideo.keyEngagedContent else OverVideo.text,
        )
    }
}
