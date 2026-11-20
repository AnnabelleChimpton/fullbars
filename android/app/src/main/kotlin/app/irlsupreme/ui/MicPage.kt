package app.irlsupreme.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.irlsupreme.engine.audio.MicCheck
import app.irlsupreme.engine.audio.PcmClip
import app.irlsupreme.service.MicSettings
import app.irlsupreme.service.SoundPlayer
import app.irlsupreme.ui.theme.FbType
import app.irlsupreme.ui.theme.LocalFbColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private fun micAllowed(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** How long the sound check records: long enough for a few sentences, short enough to listen to straight away. */
internal const val SOUND_CHECK_SECONDS = 10

/** Whole seconds left of a [totalMs] recording [elapsedMs] in, rounded up, so it reads 10 … 1 and never 0 early. */
internal fun secondsLeft(elapsedMs: Long, totalMs: Long): Int = ((totalMs - elapsedMs).coerceAtLeast(0) + 999).div(1000).toInt()

/**
 * Settings › Microphone: set the level before going live, with a live meter from the real mic (same sources and level
 * stage as a stream, nothing sent), and a sound check to hear how you'll sound. The mic is open only while this page
 * is in front; Android shows its mic dot then.
 */
@Composable
fun MicPage(nav: Navigator) = PageScaffold("Microphone", nav::back) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val prefs by MicSettings.state(ctx).collectAsState()
    var allowed by remember { mutableStateOf(micAllowed(ctx)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    val check = remember { MicCheck(ctx.applicationContext) }
    val player = rememberSoundPlayer()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // The sound check: recording since (elapsedRealtime), or null; then the take, or that nothing came back.
    var recordingSince by remember { mutableStateOf<Long?>(null) }
    var left by remember { mutableIntStateOf(SOUND_CHECK_SECONDS) }
    var take by remember { mutableStateOf<PcmClip?>(null) }
    var noTake by remember { mutableStateOf(false) }

    LaunchedEffect(prefs) {
        check.gainDb = prefs.gainDb.toFloat()
        check.rawGainDb = prefs.rawGainDb.toFloat()
        check.raw = prefs.raw
    }
    DisposableEffect(allowed, lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> { allowed = micAllowed(ctx); if (allowed) check.start() }
                // The mic closes in the background, so a recording in progress would be a gap: drop it.
                Lifecycle.Event.ON_PAUSE -> { check.stop(); recordingSince = null }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (allowed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) check.start()
        onDispose { lifecycle.removeObserver(observer); check.stop() }
    }
    LaunchedEffect(recordingSince) {
        val since = recordingSince ?: return@LaunchedEffect
        val total = SOUND_CHECK_SECONDS * 1000L
        while (true) {
            val elapsed = SystemClock.elapsedRealtime() - since
            if (elapsed >= total) break
            left = secondsLeft(elapsed, total)
            delay(minOf(200L, total - elapsed))
        }
        // The mic check keeps the last few seconds after the level stage: exactly what was said during the countdown.
        val clip = withContext(Dispatchers.Default) { check.recent(SOUND_CHECK_SECONDS) }
        take = clip
        noTake = clip == null
        recordingSince = null
        view.announceForAccessibility(if (clip != null) "Recorded. Play it back to hear yourself." else "Nothing was recorded.")
    }
    val level by check.level.collectAsState()
    val unavailable by check.unavailable.collectAsState()

    SectionHeader("Check your level", top = 8.dp)
    if (!allowed) {
        HelpText("Fullbars needs the mic to show your level.")
        FbButton("Allow the mic", { ask.launch(Manifest.permission.RECORD_AUDIO) }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), kind = FbButtonKind.Secondary)
    } else {
        HelpText("Talk the way you will on stream. Your voice should reach the marked range, and only touch amber when you shout.")
        Spacer(Modifier.height(12.dp))
        MicMeter(level, muted = false)
        if (unavailable) InlineError("Another app is using the mic. Close it to check your level.", modifier = Modifier.padding(top = 8.dp))
        // The mic check is always the phone's own mic: a camera's audio only joins a running stream.
        ClampNotice(
            level, VoiceSource.PHONE, prefs.raw, prefs.activeGainDb,
            onGain = { MicSettings.setGain(ctx, it) }, onRaw = { MicSettings.setRaw(ctx, it) },
            modifier = Modifier.padding(top = 16.dp),
        )
    }

    SectionHeader("Level")
    MicFader(prefs.activeGainDb, { MicSettings.setGain(ctx, it) })
    Spacer(Modifier.height(8.dp))
    AutoLevelRow(prefs.raw) { MicSettings.setRaw(ctx, it) }

    if (allowed) {
        SectionHeader("Sound check")
        HelpText(
            "Hold the phone where it'll be on stream, talk for $SOUND_CHECK_SECONDS seconds, then play it back. " +
                "Listen on headphones if you can, or hold the phone at speaking distance.",
        )
        SoundCheck(
            recording = recordingSince != null, left = left, take = take, noTake = noTake, player = player,
            canRecord = !unavailable,
            onRecord = {
                player.stop()
                take = null
                noTake = false
                left = SOUND_CHECK_SECONDS
                recordingSince = SystemClock.elapsedRealtime()
                view.announceForAccessibility("Recording for $SOUND_CHECK_SECONDS seconds. Talk now.")
            },
            onCancel = { recordingSince = null; view.announceForAccessibility("Recording cancelled.") },
        )
    }

    Footnote(
        null,
        "Fullbars stops your voice from clipping: when something's too loud it turns it down for a moment instead of " +
            "distorting. You can change all of this while you're live too: tap MIC on the live screen.",
        Modifier.padding(top = 16.dp),
    )
}

/**
 * The sound check's one control at a time: Record; while recording, the countdown and Cancel; then Play it back (or
 * Stop) with Record again under it.
 */
@Composable
private fun SoundCheck(
    recording: Boolean,
    left: Int,
    take: PcmClip?,
    noTake: Boolean,
    player: SoundPlayer,
    canRecord: Boolean,
    onRecord: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = LocalFbColors.current
    val pad = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
    when {
        recording -> Row(pad.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Recording. Talk now", style = FbType.row, color = c.text, modifier = Modifier.weight(1f))
            Text(clipTime(left.toFloat()), style = FbType.value, color = c.text, modifier = Modifier.padding(end = 16.dp).clearAndSetSemantics {})
            FbButton("Cancel", onCancel, kind = FbButtonKind.Text)
        }
        take != null -> {
            PlayClipButton("Play it back", take, player, pad)
            FbButton("Record again", onRecord, Modifier.padding(start = 16.dp), kind = FbButtonKind.Text, enabled = canRecord)
        }
        else -> {
            if (noTake) HelpText("Nothing was recorded. Check the meter moves when you talk, then try again.")
            FbButton("Record $SOUND_CHECK_SECONDS seconds", onRecord, pad, kind = FbButtonKind.Secondary, enabled = canRecord)
        }
    }
}
