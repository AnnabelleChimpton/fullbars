package app.irlsupreme.ui

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
fun AlertSoundRow(kind: AlertKind) {
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
    Column(Modifier.selectableGroup()) {
        RadioRow(
            "Built-in chime", selected = s.choice == AlertSoundChoice.BUILT_IN,
            onClick = { AlertSoundChoices.setChoice(ctx, kind, AlertSoundChoice.BUILT_IN) },
        )
        RadioRow(
            "Your sound",
            supporting = if (s.hasFile) null else "Pick a sound file first.",
            selected = s.choice == AlertSoundChoice.CUSTOM && s.hasFile,
            enabled = s.hasFile && !busy,
            onClick = { AlertSoundChoices.setChoice(ctx, kind, AlertSoundChoice.CUSTOM) },
        )
        RadioRow(
            "None", selected = s.choice == AlertSoundChoice.NONE,
            onClick = { AlertSoundChoices.setChoice(ctx, kind, AlertSoundChoice.NONE) },
        )
    }
    FbButton(
        when {
            busy -> "Getting it ready…"
            s.hasFile -> "Pick another sound"
            else -> "Pick a sound file"
        },
        { picker.launch(AudioPick.MIME_TYPES) },
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp), kind = FbButtonKind.Secondary, enabled = !busy,
    )
    if (s.hasFile && !busy) {
        FbButton(
            "Remove your sound", { player.stop(); AlertSoundChoices.remove(ctx, kind); cut = false },
            Modifier.padding(horizontal = 16.dp), kind = FbButtonKind.Text,
        )
    }
    error?.let { InlineError(it, modifier = Modifier.padding(top = 8.dp)) }
    HelpText(
        if (cut) "Cut to its first ${AlertSoundRules.MAX_SECONDS} seconds."
        else "mp3, m4a, ogg, wav or flac. Up to ${AlertSoundRules.MAX_SECONDS} seconds; longer sounds are cut.",
    )

    if (s.choice != AlertSoundChoice.NONE) {
        SoundFader("Volume", s.gainDb, AlertSoundRules.MIN_GAIN_DB, AlertSoundRules.MAX_GAIN_DB, { AlertSoundChoices.setGain(ctx, kind, it) })
        preview?.let { PlayClipButton("Play", it, player, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        HelpText("Play is on your phone only. On stream, the sound is mixed in as the alert appears.")
    }
}
