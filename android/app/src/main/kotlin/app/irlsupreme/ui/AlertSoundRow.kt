package app.irlsupreme.ui

import app.irlsupreme.ui.theme.LocalFbColors
import app.irlsupreme.ui.theme.FbType
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.irlsupreme.engine.audio.PcmClip
import app.irlsupreme.service.AlertKind
import app.irlsupreme.service.AlertSoundChoice
import app.irlsupreme.service.AlertSoundChoices
import app.irlsupreme.service.AlertSoundRules
import app.irlsupreme.service.AlertSoundSetting
import app.irlsupreme.service.AudioPick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One alert kind's sound: the built-in chime, your own sound, or none, at its own volume, with Play to hear it on the
 * phone first (never on stream). For Customize alerts' page for [kind].
 */
@Composable
fun AlertSoundRow(kind: AlertKind, soundsOn: Boolean = true, onSoundsOn: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by AlertSoundChoices.state(ctx).collectAsState()
    val s = all[kind] ?: AlertSoundSetting()
    val player = rememberSoundPlayer()
    var busy by remember(kind) { mutableStateOf(false) }
    var error by rememberSaveable(kind) { mutableStateOf<String?>(null) }
    var cut by rememberSaveable(kind) { mutableStateOf(false) }
    // The kind is captured at pick time, so switching kinds while a file is read can't misfile it.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; error = null; cut = false
            player.stop()
            val r = withContext(Dispatchers.IO) { AlertSoundChoices.pick(ctx, kind, uri) }
            busy = false
            error = r.error
            cut = r.cut
        }
    }
    // What Play plays: rebuilt when the choice, the volume or the file changes.
    var preview by remember(kind) { mutableStateOf<PcmClip?>(null) }
    LaunchedEffect(kind, s) {
        player.stop()
        preview = withContext(Dispatchers.IO) { AlertSoundChoices.previewClip(ctx, kind) }
    }

    SectionHeader("Sound")
    if (!soundsOn) {
        // One neutral line instead of choices that would do nothing.
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Alert sounds are off in Overlays.", style = FbType.help, color = LocalFbColors.current.text2, modifier = Modifier.weight(1f))
            FbButton("Turn on", onSoundsOn, kind = FbButtonKind.Text)
        }
        return
    }
    Column(Modifier.selectableGroup()) {
        RadioRow(
            "Built-in chime", selected = s.choice == AlertSoundChoice.BUILT_IN,
            onClick = { AlertSoundChoices.setChoice(ctx, kind, AlertSoundChoice.BUILT_IN) },
        )
        // With no file yet, choosing it picks one.
        RadioRow(
            "Your sound",
            supporting = when {
                busy -> "Getting it ready…"
                !s.hasFile -> "Pick a sound file, up to ${AlertSoundRules.MAX_SECONDS} seconds."
                else -> null
            },
            selected = s.choice == AlertSoundChoice.CUSTOM && s.hasFile,
            enabled = !busy,
            onClick = { if (s.hasFile) AlertSoundChoices.setChoice(ctx, kind, AlertSoundChoice.CUSTOM) else picker.launch(AudioPick.MIME_TYPES) },
        )
        RadioRow(
            "None", selected = s.choice == AlertSoundChoice.NONE,
            onClick = { AlertSoundChoices.setChoice(ctx, kind, AlertSoundChoice.NONE) },
        )
    }
    if (s.hasFile && !busy) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FbButton("Pick another sound", { picker.launch(AudioPick.MIME_TYPES) }, kind = FbButtonKind.Text)
            FbButton("Remove your sound", { player.stop(); AlertSoundChoices.remove(ctx, kind); cut = false }, kind = FbButtonKind.Text)
        }
    }
    error?.let { InlineError(it, modifier = Modifier.padding(top = 8.dp)) }
    if (cut) HelpText("Cut to its first ${AlertSoundRules.MAX_SECONDS} seconds.")

    if (s.choice != AlertSoundChoice.NONE) {
        SoundFader("Volume", s.gainDb, AlertSoundRules.MIN_GAIN_DB, AlertSoundRules.MAX_GAIN_DB, { AlertSoundChoices.setGain(ctx, kind, it) })
        preview?.let { PlayClipButton("Play on phone", it, player, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
    }
}
