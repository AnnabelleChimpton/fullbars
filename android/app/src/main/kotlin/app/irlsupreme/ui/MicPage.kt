package app.irlsupreme.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.rememberUpdatedState
import app.irlsupreme.service.AudioQuality
import app.irlsupreme.service.StreamRules
import app.irlsupreme.service.StreamSettings
import app.irlsupreme.ui.theme.FbHaptics
import app.irlsupreme.ui.theme.HapticPattern
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.irlsupreme.engine.audio.InputChoice
import app.irlsupreme.engine.audio.InputClass
import app.irlsupreme.engine.audio.LowCut
import app.irlsupreme.engine.audio.MicAvailability
import app.irlsupreme.engine.audio.MicCheck
import app.irlsupreme.engine.audio.PcmClip
import app.irlsupreme.engine.audio.externalInputs
import app.irlsupreme.service.MicSettings
import app.irlsupreme.service.STREAM_USES_MIC_OPTIONS
import app.irlsupreme.service.lowCutText
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
    // The stream's sound settings, read here and written straight back (no stream reads them until the next go-live).
    var audio by remember { mutableStateOf(StreamSettings.audio(ctx)) }
    var avOffset by remember { mutableIntStateOf(StreamSettings.avOffsetMs(ctx)) }

    // The options a stream doesn't use yet stay hidden, and the check runs without them, as a stream does.
    val options = STREAM_USES_MIC_OPTIONS
    LaunchedEffect(prefs) {
        check.gainDb = prefs.gainDb.toFloat()
        check.rawGainDb = prefs.rawGainDb.toFloat()
        check.externalGainDb = if (options) prefs.externalGainDb.toFloat() else null
        check.inputChoice = if (options) prefs.input else InputChoice.AUTO
        check.lowCut = if (options) prefs.lowCut else LowCut.AUTO
        check.voiceLeveler = options && prefs.voiceLeveler
        check.monoVoice = options && prefs.monoVoice
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
    val availability by check.availability.collectAsState()
    // No mic, or Android feeding it silence because another app (a call, the assistant) has it: either way the
    // check can't hear you, and a recording would be silence.
    val unavailable = availability != MicAvailability.OK
    val input by check.input.collectAsState()
    val external = options && input?.inputClass == InputClass.EXTERNAL
    // Re-listed when the mic in use changes (plugging one in moves the recording to it).
    val plugged = remember(input, allowed) { if (options) externalInputs(ctx) else emptyList() }

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
        InputClipNotice(level, VoiceSource.PHONE, prefs.raw, onRaw = { MicSettings.setRaw(ctx, it) }, modifier = Modifier.padding(top = 16.dp))
        ClampNotice(
            level, VoiceSource.PHONE, prefs.raw, prefs.gainFor(external),
            onGain = { MicSettings.setGain(ctx, it, external) }, onRaw = { MicSettings.setRaw(ctx, it) },
            modifier = Modifier.padding(top = 16.dp),
        )
    }

    SectionHeader("Level", trailing = if (external) input?.name else null)
    MicFader(prefs.gainFor(external), { MicSettings.setGain(ctx, it, external) })
    if (external) HelpText("This level is kept for plugged-in mics. The phone's own mic keeps its own.")
    Spacer(Modifier.height(8.dp))
    AutoLevelRow(prefs.raw) { MicSettings.setRaw(ctx, it) }
    if (options && prefs.raw) {
        SwitchRow(
            "Even out my level",
            supporting = "Turns you up a little when you're far from the phone and down when you shout, slowly, never between words.",
            checked = prefs.voiceLeveler,
            onCheckedChange = { MicSettings.setVoiceLeveler(ctx, it) },
        )
    }

    if (options) {
        SectionHeader("Input")
        Column(Modifier.selectableGroup()) {
            RadioRow(
                "Automatic", supporting = "A plugged-in USB or wired mic when there is one, otherwise the phone's.",
                selected = prefs.input == InputChoice.AUTO, onClick = { MicSettings.setInput(ctx, InputChoice.AUTO) },
            )
            RadioRow("Phone mic", selected = prefs.input == InputChoice.PHONE, onClick = { MicSettings.setInput(ctx, InputChoice.PHONE) })
            RadioRow(
                plugged.firstOrNull()?.name ?: "USB or wired mic",
                supporting = if (plugged.isEmpty()) "Plug one in to use it. Bluetooth mics aren't used: they sound like a phone call." else null,
                selected = prefs.input == InputChoice.EXTERNAL,
                enabled = plugged.isNotEmpty() || prefs.input == InputChoice.EXTERNAL,
                onClick = { MicSettings.setInput(ctx, InputChoice.EXTERNAL) },
            )
        }

        SectionHeader("Low cut", trailing = lowCutText(prefs.lowCut, prefs.raw))
        HelpText("Takes out rumble from wind, handling and traffic, under your voice.")
        Column(Modifier.selectableGroup()) {
            RadioRow(
                "Automatic", supporting = "On with the phone's auto level off; the phone filters it otherwise.",
                selected = prefs.lowCut == LowCut.AUTO, onClick = { MicSettings.setLowCut(ctx, LowCut.AUTO) },
            )
            RadioRow("80 Hz", selected = prefs.lowCut == LowCut.NORMAL, onClick = { MicSettings.setLowCut(ctx, LowCut.NORMAL) })
            RadioRow("120 Hz", supporting = "For wind.", selected = prefs.lowCut == LowCut.WIND, onClick = { MicSettings.setLowCut(ctx, LowCut.WIND) })
            RadioRow("Off", selected = prefs.lowCut == LowCut.OFF, onClick = { MicSettings.setLowCut(ctx, LowCut.OFF) })
        }
        SwitchRow(
            "Mono voice",
            supporting = "The same voice on both sides. Try it if you sound off to one side.",
            checked = prefs.monoVoice,
            onCheckedChange = { MicSettings.setMonoVoice(ctx, it) },
        )
    }

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

    SectionHeader("Sound quality")
    Column(Modifier.selectableGroup()) {
        AudioQuality.entries.forEach { a ->
            RadioRow(a.title, supporting = a.detail, selected = audio == a) { audio = a; StreamSettings.setAudio(ctx, a) }
        }
    }
    HelpText("Set it before you go live. It stays the same for the whole stream.")

    SectionHeader("Lip sync")
    OffsetStepper(avOffset) { avOffset = it; StreamSettings.setAvOffsetMs(ctx, it) }
    HelpText("Delay the sound if viewers hear you before your lips move. The sound can't arrive early, so less than 0 ms does nothing yet.")

    Footnote(
        null,
        "Fullbars stops your voice from clipping: when something's too loud it turns it down for a moment instead of " +
            "distorting. You can change all of this while you're live too: tap MIC on the live screen.",
        Modifier.padding(top = 16.dp),
    )
}

/**
 * The sound's offset from the picture, −300..+300 ms in 10 ms steps: − and + keys, the value, and Reset when it isn't
 * 0. TalkBack sees an adjustable value.
 */
@Composable
private fun OffsetStepper(ms: Int, onChange: (Int) -> Unit) {
    val c = LocalFbColors.current
    val view = LocalView.current
    val current by rememberUpdatedState(ms)
    val set: (Int) -> Unit = { v ->
        val n = StreamRules.avOffsetMs(v)
        if (n != current) { FbHaptics.play(view, HapticPattern.KEY); onChange(n) }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Sound delay"
                stateDescription = offsetSpoken(ms)
                progressBarRangeInfo = ProgressBarRangeInfo(
                    ms.toFloat(), -StreamRules.AV_OFFSET_MAX_MS.toFloat()..StreamRules.AV_OFFSET_MAX_MS.toFloat(),
                    steps = 2 * StreamRules.AV_OFFSET_MAX_MS / StreamRules.AV_OFFSET_STEP_MS - 1,
                )
                setProgress { v -> set(v.roundToInt()); true }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Sound delay", style = FbType.row, color = c.text, modifier = Modifier.weight(1f).clearAndSetSemantics {})
        if (ms != 0) FbButton("Reset", { set(0) }, kind = FbButtonKind.Text)
        StepKey("−", "Sound 10 ms earlier") { set(current - StreamRules.AV_OFFSET_STEP_MS) }
        Text(
            offsetText(ms), style = FbType.value, color = c.text, textAlign = TextAlign.Center,
            modifier = Modifier.width(88.dp).clearAndSetSemantics {},
        )
        StepKey("+", "Sound 10 ms later") { set(current + StreamRules.AV_OFFSET_STEP_MS) }
    }
}

/** "+40 ms", "−20 ms" (a real minus), "0 ms". */
internal fun offsetText(ms: Int): String = when {
    ms > 0 -> "+$ms ms"
    ms < 0 -> "−${-ms} ms"
    else -> "0 ms"
}

/** For TalkBack: "sound 40 milliseconds later", "no delay". */
internal fun offsetSpoken(ms: Int): String = when {
    ms > 0 -> "sound $ms milliseconds later"
    ms < 0 -> "sound ${-ms} milliseconds earlier"
    else -> "no delay"
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
