package app.irlsupreme.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.irlsupreme.engine.audio.MicDsp
import app.irlsupreme.engine.audio.MicLevel
import app.irlsupreme.engine.audio.PcmClip
import app.irlsupreme.service.SoundPlayer
import app.irlsupreme.service.gainText
import app.irlsupreme.ui.theme.FbHaptics
import app.irlsupreme.ui.theme.FbShape
import app.irlsupreme.ui.theme.FbType
import app.irlsupreme.ui.theme.HapticPattern
import app.irlsupreme.ui.theme.LocalFbColors
import app.irlsupreme.ui.theme.LocalHealthColors
import kotlin.math.roundToInt

/*
 * The mic controls: a level meter you can read at a glance, a fader for the level, the switch for the phone's own
 * auto level, what to do when the limiter is working all the time, and playing a recording back. Shared by Settings ›
 * Microphone (checking before going live), the live screen's mic sheet and Home's recap.
 */

// --- Meter ballistics (pure) -------------------------------------------------------------------------------------------

/** Where a voice should sit, RMS dBFS: loud enough over street noise, with room for a shout before the limiter. */
internal const val TARGET_LOW_DB = -24f
internal const val TARGET_HIGH_DB = -9f
/** Above this the meter lights amber: the next word may hit the limiter. */
internal const val HOT_DB = -6f
/** Limiter reduction that counts as "too loud": a little on shouts is its job, this much means the level is too high. */
internal const val LOUD_REDUCTION_DB = 6f

/** What a meter shows at one moment: the bar, its slow companion for the words, the peak tick, and "too loud". */
internal data class MeterReading(val barDb: Float, val heldDb: Float, val peakDb: Float, val loud: Boolean) {
    companion object {
        val SILENT = MeterReading(MicLevel.FLOOR_DB, MicLevel.FLOOR_DB, MicLevel.FLOOR_DB, false)
    }
}

/**
 * Turns ~47 level readings a second into something eyes can follow: the bar jumps up at once and falls at 24 dB/s; the
 * words follow a slower 8 dB/s fall after a 1.5 s hold, so they don't flicker between sentences; the peak tick holds
 * for 1.2 s; "too loud" stays up for 1.5 s after the limiter last worked hard. Pure, fed with times, for tests.
 */
internal class MeterBallistics {
    private var bar = MicLevel.FLOOR_DB
    private var held = MicLevel.FLOOR_DB
    private var heldAtMs = 0L
    private var peak = MicLevel.FLOOR_DB
    private var peakAtMs = 0L
    private var loudAtMs = Long.MIN_VALUE / 2
    private var lastMs = -1L

    fun update(level: MicLevel, nowMs: Long): MeterReading {
        val dt = if (lastMs < 0) 0f else (nowMs - lastMs).coerceIn(0, 500) / 1000f
        lastMs = nowMs
        bar = maxOf(level.rmsDb, bar - BAR_FALL_DB_S * dt)
        if (level.rmsDb >= held) { held = level.rmsDb; heldAtMs = nowMs }
        else if (nowMs - heldAtMs > HOLD_MS) held = maxOf(level.rmsDb, held - HELD_FALL_DB_S * dt)
        if (level.peakDb >= peak || nowMs - peakAtMs > PEAK_HOLD_MS) { peak = level.peakDb; peakAtMs = nowMs }
        if (level.reductionDb >= LOUD_REDUCTION_DB) loudAtMs = nowMs
        return MeterReading(bar, held, peak, nowMs - loudAtMs <= LOUD_HOLD_MS)
    }

    companion object {
        const val BAR_FALL_DB_S = 24f
        const val HELD_FALL_DB_S = 8f
        const val HOLD_MS = 1_500L
        const val PEAK_HOLD_MS = 1_200L
        const val LOUD_HOLD_MS = 1_500L
    }
}

/** The meter's verdict, in a few words. */
internal enum class MicVerdict(val words: String) {
    MUTED("Muted. Viewers hear nothing"),
    LOUD("Too loud. Turn it down"),
    SILENT("Can't hear you yet. Say something"),
    QUIET("Quiet. Turn it up or get closer"),
    GOOD("Good level"),
}

internal fun micVerdict(r: MeterReading, muted: Boolean): MicVerdict = when {
    muted -> MicVerdict.MUTED
    r.loud -> MicVerdict.LOUD
    r.heldDb < -50f -> MicVerdict.SILENT
    r.heldDb < -34f -> MicVerdict.QUIET
    else -> MicVerdict.GOOD
}

/** How many of [segments] segments spanning [floorDb]..0 dBFS are lit at [db]. */
internal fun litSegments(db: Float, segments: Int, floorDb: Float): Int =
    ((db - floorDb) / -floorDb * segments).toInt().coerceIn(0, segments)

/** The meter's current reading for [level]; recomputed only when a new level arrives. */
@Composable
internal fun rememberMeterReading(level: MicLevel): MeterReading {
    val b = remember { MeterBallistics() }
    return remember(level) { b.update(level, System.currentTimeMillis()) }
}

// --- Drawing -----------------------------------------------------------------------------------------------------------

/**
 * A segmented level bar from [floorDb] to 0 dBFS: lit segments in [on], amber from [HOT_DB] up, unlit ones outlined in
 * [off], and the peak as a thin tick. With [target], a rule under the bar marks where a talking voice should sit.
 */
@Composable
internal fun LevelBar(
    r: MeterReading,
    modifier: Modifier = Modifier,
    segments: Int = 30,
    floorDb: Float = MicLevel.FLOOR_DB,
    height: Dp = 18.dp,
    on: Color = LocalFbColors.current.text,
    off: Color = LocalFbColors.current.rule,
    target: Boolean = true,
) {
    val hot = LocalHealthColors.current.weak
    Canvas(modifier.fillMaxWidth().height(if (target) height + 6.dp else height)) {
        val gap = 2.dp.toPx()
        val segW = (size.width - gap * (segments - 1)) / segments
        val barH = height.toPx()
        val lit = litSegments(r.barDb, segments, floorDb)
        val stroke = 1.dp.toPx()
        for (i in 0 until segments) {
            val left = i * (segW + gap)
            val startDb = floorDb + (-floorDb) * i / segments
            val isHot = startDb >= HOT_DB
            if (i < lit) {
                drawRect(if (isHot) hot else on, Offset(left, 0f), Size(segW, barH))
            } else {
                drawRect(if (isHot) hot.copy(alpha = 0.5f) else off, Offset(left + stroke / 2, stroke / 2), Size(segW - stroke, barH - stroke), style = Stroke(stroke))
            }
        }
        // The peak: a 2dp tick that holds a moment, so a single shout is visible after it's gone.
        if (r.peakDb > floorDb + 1f) {
            val x = ((r.peakDb - floorDb) / -floorDb).coerceIn(0f, 1f) * size.width
            drawRect(if (r.peakDb >= HOT_DB) hot else on, Offset((x - 1.dp.toPx()).coerceAtLeast(0f), 0f), Size(2.dp.toPx(), barH))
        }
        if (target) {
            val x0 = (TARGET_LOW_DB - floorDb) / -floorDb * size.width
            val x1 = (TARGET_HIGH_DB - floorDb) / -floorDb * size.width
            val y = barH + 4.dp.toPx()
            drawRect(on, Offset(x0, y), Size(x1 - x0, 2.dp.toPx()))
        }
    }
}

/** The full meter: the verdict and the peak figure over the bar, a scale under it. Polite live region on the verdict. */
@Composable
internal fun MicMeter(level: MicLevel, muted: Boolean, modifier: Modifier = Modifier) {
    val c = LocalFbColors.current
    val r = rememberMeterReading(level)
    val verdict = micVerdict(r, muted)
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                verdict.words, style = FbType.row, color = if (verdict == MicVerdict.LOUD) LocalHealthColors.current.weak else c.text,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                if (r.peakDb <= MicLevel.FLOOR_DB + 0.5f) "−∞" else "${dbText(r.peakDb)} peak",
                style = FbType.value, color = c.text2, modifier = Modifier.clearAndSetSemantics {},
            )
        }
        Spacer(Modifier.height(10.dp))
        LevelBar(r, Modifier.clearAndSetSemantics {})
        Spacer(Modifier.height(4.dp))
        DbScale(listOf(-60, -40, -24, -9, 0), MicLevel.FLOOR_DB)
    }
}

/** Mono figures under a bar at their dB positions, the first left-aligned and the last right-aligned. */
@Composable
private fun DbScale(marks: List<Int>, floorDb: Float) {
    val c = LocalFbColors.current
    val style = FbType.value.copy(fontSize = FbType.footnote.fontSize, lineHeight = FbType.footnote.lineHeight)
    BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp).clearAndSetSemantics {}) {
        val boxW = 40.dp
        marks.forEachIndexed { i, db ->
            val x = when (i) {
                0 -> 0.dp
                marks.lastIndex -> maxWidth - boxW
                else -> maxWidth * ((db - floorDb) / -floorDb) - boxW / 2
            }
            Text(
                if (db == 0) "0" else "\u2212${-db}", style = style, color = c.text2,
                textAlign = when (i) { 0 -> TextAlign.Start; marks.lastIndex -> TextAlign.End; else -> TextAlign.Center },
                modifier = Modifier.offset(x = x).width(boxW),
            )
        }
    }
}

/** "−14 dB" with a real minus, rounded. */
internal fun dbText(db: Float): String {
    val v = db.roundToInt()
    return if (v < 0) "−${-v} dB" else "$v dB"
}

// --- Fader -------------------------------------------------------------------------------------------------------------

/**
 * The mic level fader, −20..+30 dB in 1 dB steps: drag or tap the track, or use − and + for one step at a time (easier
 * one-handed while walking). A tick plays on each step. TalkBack sees an adjustable value in dB.
 */
@Composable
internal fun MicFader(gainDb: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalFbColors.current
    val view = LocalView.current
    val min = MicDsp.MIN_GAIN_DB.roundToInt()
    val max = MicDsp.MAX_GAIN_DB.roundToInt()
    val current by rememberUpdatedState(gainDb)
    val set: (Int) -> Unit = { v ->
        val n = v.coerceIn(min, max)
        if (n != current) { FbHaptics.play(view, HapticPattern.KEY); onChange(n) }
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text("Mic level", style = FbType.row, color = c.text, modifier = Modifier.weight(1f).clearAndSetSemantics {})
            Text(gainText(gainDb), style = FbType.value, color = c.text, modifier = Modifier.clearAndSetSemantics {})
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepKey("−", "Turn the mic down") { set(current - 1) }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .semantics {
                        contentDescription = "Mic level"
                        stateDescription = gainSpoken(gainDb)
                        progressBarRangeInfo = ProgressBarRangeInfo(gainDb.toFloat(), min.toFloat()..max.toFloat(), steps = max - min - 1)
                        setProgress { v -> set(v.roundToInt()); true }
                    }
                    .pointerInput(Unit) {
                        fun at(x: Float) = (min + (x / size.width).coerceIn(0f, 1f) * (max - min)).roundToInt()
                        detectTapGestures { set(at(it.x)) }
                    }
                    .pointerInput(Unit) {
                        fun at(x: Float) = (min + (x / size.width).coerceIn(0f, 1f) * (max - min)).roundToInt()
                        detectHorizontalDragGestures { change, _ -> change.consume(); set(at(change.position.x)) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                FaderTrack(gainDb, min, max)
            }
            Spacer(Modifier.width(8.dp))
            StepKey("+", "Turn the mic up") { set(current + 1) }
        }
    }
}

/** The track: ticks every 5 dB (taller at 0 dB), the run from 0 dB to the value in ink, and a fader cap. */
@Composable
private fun FaderTrack(gainDb: Int, min: Int, max: Int) {
    val c = LocalFbColors.current
    Canvas(Modifier.fillMaxWidth().height(32.dp)) {
        val span = (max - min).toFloat()
        fun x(db: Int) = (db - min) / span * size.width
        val midY = size.height / 2
        val line = 2.dp.toPx()
        drawRect(c.rule, Offset(0f, midY - line / 2), Size(size.width, line))
        for (db in min..max step 5) {
            val h = if (db == 0) 14.dp.toPx() else 6.dp.toPx()
            drawRect(c.text2, Offset(x(db) - 0.5.dp.toPx(), midY - h / 2), Size(1.dp.toPx(), h))
        }
        val from = minOf(x(0), x(gainDb))
        val to = maxOf(x(0), x(gainDb))
        drawRect(c.text, Offset(from, midY - line / 2), Size(to - from, line))
        val capW = 8.dp.toPx()
        val capH = 28.dp.toPx()
        val cx = x(gainDb).coerceIn(capW / 2, size.width - capW / 2)
        drawRoundRect(
            c.text, Offset(cx - capW / 2, midY - capH / 2), Size(capW, capH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(FbShape.rS.toPx()),
        )
        drawRect(c.bg, Offset(cx - capW / 2, midY - 0.75.dp.toPx()), Size(capW, 1.5.dp.toPx()))
    }
}

/** A 48dp square key with a big sign, outlined like the rail's keys. */
@Composable
private fun StepKey(sign: String, label: String, onClick: () -> Unit) {
    val c = LocalFbColors.current
    Box(
        Modifier
            .size(48.dp)
            .border(1.5.dp, c.ruleStrong, FbShape.medium)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(sign, style = FbType.title, color = c.text, modifier = Modifier.clearAndSetSemantics {})
    }
}

/** "plus 3 decibels", for TalkBack. */
internal fun gainSpoken(db: Int): String = when {
    db > 0 -> "plus $db decibels"
    db < 0 -> "minus ${-db} decibels"
    else -> "0 decibels, unchanged"
}

// --- "Too loud all the time" (pure) -----------------------------------------------------------------------------------

/** Where the voice viewers hear comes from. */
internal enum class VoiceSource { PHONE, CAMERA }

/**
 * The voice source from [app.irlsupreme.engine.EngineState.externalCamera], which ends in "camera audio" while the RTMP
 * camera's sound is the one in the stream (its own mic, or a DJI Mic through it) and "phone mic" otherwise.
 */
internal fun voiceSourceOf(externalCamera: String?): VoiceSource =
    if (externalCamera?.endsWith("camera audio") == true) VoiceSource.CAMERA else VoiceSource.PHONE

/**
 * Notices the limiter working all the time, not just on a shout: over the last [WINDOW_S] seconds, with at least
 * [MIN_VOICE_S] of them having a voice in, [SHARE] or more of the voiced readings were turned down by [CLAMP_DB] or
 * more. A clamped syllable keeps the limiter down for 2 or 3 readings (150 ms release), so talking with every syllable
 * hitting it lands around 20-30%; a good level with the odd shout stays well under. The verdict then holds for
 * [HOLD_MS], so the advice doesn't come and go between sentences while you read it. Pure, fed with times, for tests.
 *
 * Why: in the first real stream the phone's auto level put speech near −1 dBFS with +4 dB on top, so the limiter
 * clamped every word and it sounded harsh; the meter's brief "Too loud" was easy to miss and didn't say what to change.
 */
internal class ClampWatch {
    private val second = LongArray(WINDOW_S) { Long.MIN_VALUE }
    private val voiced = IntArray(WINDOW_S)
    private val clamped = IntArray(WINDOW_S)
    private var holdUntilMs = Long.MIN_VALUE

    fun update(level: MicLevel, nowMs: Long): Boolean {
        val sec = Math.floorDiv(nowMs, 1000L)
        val i = Math.floorMod(sec, WINDOW_S.toLong()).toInt()
        if (second[i] != sec) { second[i] = sec; voiced[i] = 0; clamped[i] = 0 }
        if (level.rmsDb > VOICE_DB) {
            voiced[i]++
            if (level.reductionDb >= CLAMP_DB) clamped[i]++
        }
        var v = 0
        var c = 0
        var voicedSeconds = 0
        for (k in 0 until WINDOW_S) {
            if (second[k] <= sec - WINDOW_S || second[k] > sec) continue
            v += voiced[k]
            c += clamped[k]
            if (voiced[k] > 0) voicedSeconds++
        }
        if (voicedSeconds >= MIN_VOICE_S && c >= SHARE * v) holdUntilMs = nowMs + HOLD_MS
        return nowMs <= holdUntilMs
    }

    companion object {
        const val WINDOW_S = 10
        const val MIN_VOICE_S = 3
        const val VOICE_DB = -45f
        const val CLAMP_DB = 3f
        const val SHARE = 0.15f
        const val HOLD_MS = 8_000L
    }
}

/** How far "Turn down" moves the level: enough to hear the change, small enough not to overshoot. */
internal const val TURN_DOWN_DB = 6

/** What to say and offer when the limiter is working all the time. */
internal data class ClampAdvice(val text: String, val offerAutoLevelOff: Boolean, val offerTurnDown: Boolean)

/**
 * The advice for [source] with the phone's auto level off ([raw]) or on, at [gainDb]. The camera's audio goes out at
 * 0 dB whatever the fader says (the engine levels only the phone mic), so its fix is on the camera. With the phone's
 * auto level on, turning it off is the real fix: the phone has already made the voice loud, maybe clipped, before
 * Fullbars' level stage, which can only turn it down after the fact.
 */
internal fun clampAdvice(source: VoiceSource, raw: Boolean, gainDb: Int): ClampAdvice {
    val why = "Fullbars keeps turning your voice down to stop it clipping, and that sounds harsh."
    val canLower = gainDb > MicDsp.MIN_GAIN_DB.roundToInt()
    return when {
        source == VoiceSource.CAMERA -> ClampAdvice(
            "Fullbars keeps turning the camera's audio down to stop it clipping, and that sounds harsh. " +
                "Turn the mic level down on the camera, or on your DJI Mic.",
            offerAutoLevelOff = false, offerTurnDown = false,
        )
        !raw -> ClampAdvice(
            "$why Turn the phone's auto level off: on many phones it makes the mic far too loud." +
                if (canLower) " Or turn the level down." else "",
            offerAutoLevelOff = true, offerTurnDown = canLower,
        )
        else -> ClampAdvice("$why Turn the level down until this goes away.", offerAutoLevelOff = false, offerTurnDown = canLower)
    }
}

/**
 * Says so when the limiter has been working all the time, with the fix as a button. Starts afresh when the source,
 * the auto level or the level changes, so it goes away as soon as you've acted and only comes back if it's still too
 * loud.
 */
@Composable
internal fun ClampNotice(
    level: MicLevel,
    source: VoiceSource,
    raw: Boolean,
    gainDb: Int,
    onGain: (Int) -> Unit,
    onRaw: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val watch = remember(source, raw, gainDb) { ClampWatch() }
    val clamping = remember(watch, level) { watch.update(level, System.currentTimeMillis()) }
    if (!clamping) return
    val c = LocalFbColors.current
    val advice = clampAdvice(source, raw, gainDb)
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            "Too loud most of the time", style = FbType.row, color = c.text,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(advice.text, style = FbType.help, color = c.text2, modifier = Modifier.padding(top = 2.dp))
        if (advice.offerAutoLevelOff || advice.offerTurnDown) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (advice.offerAutoLevelOff) FbButton("Turn auto level off", { onRaw(true) }, kind = FbButtonKind.Secondary)
                if (advice.offerTurnDown) {
                    FbButton(
                        "Turn down $TURN_DOWN_DB dB", { onGain(gainDb - TURN_DOWN_DB) },
                        kind = if (advice.offerAutoLevelOff) FbButtonKind.Text else FbButtonKind.Secondary,
                    )
                }
            }
        }
    }
}

// --- Playing a recording back ------------------------------------------------------------------------------------------

/** A player for this screen: stops when the app goes to the background or the screen leaves, so nothing plays on. */
@Composable
internal fun rememberSoundPlayer(): SoundPlayer {
    val ctx = LocalContext.current
    val player = remember { SoundPlayer(ctx.applicationContext) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) player.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player.stop() }
    }
    return player
}

/** "0:07": a clip's length or position, in whole seconds. */
internal fun clipTime(seconds: Float): String {
    val s = seconds.coerceAtLeast(0f).toInt()
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/**
 * Plays [clip] through [player], or stops it: [label] at rest, "Stop" while playing, with the time in mono at the end
 * (the length at rest, how far it's got while playing).
 */
@Composable
internal fun PlayClipButton(label: String, clip: PcmClip, player: SoundPlayer, modifier: Modifier = Modifier) {
    val c = LocalFbColors.current
    val progress by player.progress.collectAsState()
    val p = progress
    val total = clip.seconds
    FbButton(
        if (p != null) "Stop" else label,
        onClick = { if (p != null) player.stop() else player.play(clip) },
        kind = FbButtonKind.Secondary,
        modifier = modifier.fillMaxWidth(),
        trailing = {
            Text(
                if (p != null) "${clipTime(p * total)} / ${clipTime(total + 0.5f)}" else clipTime(total + 0.5f),
                style = FbType.value, color = c.text2, modifier = Modifier.clearAndSetSemantics {},
            )
        },
    )
}

// --- The whole panel ---------------------------------------------------------------------------------------------------

/**
 * Meter, the too-loud advice when it applies, fader and the auto-level switch. [source] is where viewers' sound comes
 * from, so the advice points at the right fix.
 */
@Composable
internal fun MicPanel(
    level: MicLevel,
    muted: Boolean,
    gainDb: Int,
    onGain: (Int) -> Unit,
    raw: Boolean,
    onRaw: (Boolean) -> Unit,
    source: VoiceSource = VoiceSource.PHONE,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MicMeter(level, muted)
        ClampNotice(level, source, raw, gainDb, onGain, onRaw)
        MicFader(gainDb, onGain)
        AutoLevelRow(raw, onRaw)
    }
}

/** The phone's auto level switch, with the one line that says why you'd turn it off. */
@Composable
internal fun AutoLevelRow(raw: Boolean, onRaw: (Boolean) -> Unit) = SwitchRow(
    "Phone's auto level",
    supporting = "Some phones turn everything up on their own, room noise and all, and loud talking distorts. " +
        "Turn it off to get the raw mic and set the level here.",
    checked = !raw,
    onCheckedChange = { onRaw(!it) },
)
