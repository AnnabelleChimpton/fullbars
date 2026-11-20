package app.irlsupreme.service

import android.content.Context
import androidx.core.content.edit
import app.irlsupreme.engine.audio.InputChoice
import app.irlsupreme.engine.audio.LowCut
import app.irlsupreme.engine.audio.MicDsp
import app.irlsupreme.engine.audio.unprocessedSupported
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * The mic's levels in whole dB (-20..+30): [gainDb] with the phone's auto level on, [rawGainDb] with it off ([raw]).
 * Two levels, because a raw mic is ~28 dB quieter (OnePlus 12): switching modes keeps each one's own setting instead of
 * dropping you by 28 dB or boosting an already loud mic.
 *
 * A USB or wired mic has its own level, [externalGainDb], 0 dB to start: it arrives at its own level, nothing like
 * the phone's raw mic, so it never gets [RAW_DEFAULT_GAIN_DB]. [input] is which mic to use.
 *
 * [lowCut] is the phone mic's high-pass filter; [voiceLeveler] the "Voice" preset (on) or "Flat" (off) for the raw
 * mic; [monoVoice] folds a stereo phone mic to one voice on both channels.
 */
data class MicPrefs(
    val gainDb: Int = 0,
    val raw: Boolean = false,
    val rawGainDb: Int = RAW_DEFAULT_GAIN_DB,
    val input: InputChoice = InputChoice.AUTO,
    val externalGainDb: Int = 0,
    val lowCut: LowCut = LowCut.AUTO,
    val voiceLeveler: Boolean = true,
    val monoVoice: Boolean = false,
) {
    /** The level in use now for the phone's own mic, the one the fader shows. */
    val activeGainDb: Int get() = if (raw) rawGainDb else gainDb

    /** The level the fader shows and sets while [external] (a USB or wired mic) is the mic in use, or the phone's. */
    fun gainFor(external: Boolean): Int = if (external) externalGainDb else activeGainDb
}

/**
 * Whether a stream uses the mic options past the level and the phone's auto level: [MicPrefs.input],
 * [MicPrefs.externalGainDb], [MicPrefs.lowCut], [MicPrefs.voiceLeveler] and [MicPrefs.monoVoice]. Turn it on in the
 * same change that passes them to the stream (StreamConfig, the engine's live setters). Until then Settings ›
 * Microphone hides them and runs its check the way a stream runs today, so the preview never sounds unlike the
 * stream and the fader keeps setting the level a stream uses, plugged-in mic or not.
 */
const val STREAM_USES_MIC_OPTIONS = false

/** A raw mic's starting level: most of the gap to the camcorder source, the limiter covering the rest. */
const val RAW_DEFAULT_GAIN_DB = 20

/**
 * The mic settings, shared by Settings › Microphone, the live screen's mic sheet and the stream's config, so a change in
 * one shows in the others at once. Stored with the rest of the settings.
 */
object MicSettings {
    private const val KEY_GAIN = "mic_gain_db"
    private const val KEY_RAW = "mic_raw"
    private const val KEY_GAIN_RAW = "mic_gain_raw_db"
    private const val KEY_INPUT = "mic_input"
    private const val KEY_GAIN_EXT = "mic_gain_external_db"
    private const val KEY_LOW_CUT = "mic_low_cut"
    private const val KEY_LEVELER = "mic_voice_leveler"
    private const val KEY_MONO = "mic_mono"
    private const val KEY_CLIP_PROMPT = "mic_input_clip_prompt_dismissed"
    private var flow: MutableStateFlow<MicPrefs>? = null

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Synchronized
    fun state(ctx: Context): StateFlow<MicPrefs> = (flow ?: MutableStateFlow(load(ctx)).also { flow = it }).asStateFlow()

    fun current(ctx: Context): MicPrefs = state(ctx).value

    /** Sets the level of the mode in use; with [external], the USB or wired mic's level instead. */
    fun setGain(ctx: Context, db: Int, external: Boolean = false) = current(ctx).let { p ->
        save(ctx, when {
            external -> p.copy(externalGainDb = clampGain(db))
            p.raw -> p.copy(rawGainDb = clampGain(db))
            else -> p.copy(gainDb = clampGain(db))
        })
    }

    fun setRaw(ctx: Context, raw: Boolean) = save(ctx, current(ctx).copy(raw = raw))
    fun setInput(ctx: Context, input: InputChoice) = save(ctx, current(ctx).copy(input = input))
    fun setLowCut(ctx: Context, lowCut: LowCut) = save(ctx, current(ctx).copy(lowCut = lowCut))
    fun setVoiceLeveler(ctx: Context, on: Boolean) = save(ctx, current(ctx).copy(voiceLeveler = on))
    fun setMonoVoice(ctx: Context, on: Boolean) = save(ctx, current(ctx).copy(monoVoice = on))

    /**
     * Whether the streamer said "Not now" to turning the phone's auto level off when their mic was clipping before
     * Fullbars. Asked once; the setting itself is never changed without them.
     */
    fun inputClipPromptDismissed(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_CLIP_PROMPT, false)
    fun dismissInputClipPrompt(ctx: Context) = prefs(ctx).edit { putBoolean(KEY_CLIP_PROMPT, true) }

    private fun save(ctx: Context, p: MicPrefs) {
        prefs(ctx).edit {
            putInt(KEY_GAIN, p.gainDb).putBoolean(KEY_RAW, p.raw).putInt(KEY_GAIN_RAW, p.rawGainDb)
            putString(KEY_INPUT, p.input.name).putInt(KEY_GAIN_EXT, p.externalGainDb)
            putString(KEY_LOW_CUT, p.lowCut.name).putBoolean(KEY_LEVELER, p.voiceLeveler).putBoolean(KEY_MONO, p.monoVoice)
        }
        state(ctx)
        flow?.value = p
    }

    private fun load(ctx: Context): MicPrefs {
        val p = prefs(ctx)
        if (!p.contains(KEY_RAW)) {
            // Decided once and stored, so a later update can't flip it: an existing install keeps auto level on, as it
            // has always streamed; only a fresh install on a phone with a flat source starts with it off.
            p.edit { putBoolean(KEY_RAW, defaultRaw(freshInstall(ctx), unprocessedSupported(ctx))) }
        }
        return MicPrefs(
            gainDb = clampGain(p.getInt(KEY_GAIN, 0)),
            raw = p.getBoolean(KEY_RAW, false),
            rawGainDb = clampGain(p.getInt(KEY_GAIN_RAW, RAW_DEFAULT_GAIN_DB)),
            input = enumOr(p.getString(KEY_INPUT, null), InputChoice.AUTO),
            externalGainDb = clampGain(p.getInt(KEY_GAIN_EXT, 0)),
            lowCut = enumOr(p.getString(KEY_LOW_CUT, null), LowCut.AUTO),
            voiceLeveler = p.getBoolean(KEY_LEVELER, true),
            monoVoice = p.getBoolean(KEY_MONO, false),
        )
    }

    /** Never updated since it was installed: no earlier version has run, so there's no choice of the user's to keep. */
    private fun freshInstall(ctx: Context): Boolean = runCatching {
        @Suppress("DEPRECATION") // the flags overload is API 33; minSdk is 31
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        info.firstInstallTime == info.lastUpdateTime
    }.getOrDefault(false)

    /** A stored enum's name back to the enum; [default] for nothing stored, or a name a later version dropped. */
    internal inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    fun clampGain(db: Int): Int = db.coerceIn(MicDsp.MIN_GAIN_DB.roundToInt(), MicDsp.MAX_GAIN_DB.roundToInt())
}

/**
 * Whether the phone's auto level starts off ([MicPrefs.raw]). Off for a fresh install on a phone with an unprocessed
 * mic: on the OnePlus 12 the camcorder source (auto level on) peaks near −1 dBFS, ~28 dB hotter than unprocessed, so
 * talking pushes it into the limiter all the time and the first stream sounded harsh. The unprocessed source is flat
 * (no gain of the phone's own), so [RAW_DEFAULT_GAIN_DB] and the meter set the level; without one, "raw" falls back to
 * sources that may still level the voice, where a +20 dB start could be far too loud, so auto level stays on. Existing
 * installs keep what they had: they have streamed with auto level on, and the mic page says when it's too hot.
 */
internal fun defaultRaw(freshInstall: Boolean, unprocessed: Boolean): Boolean = freshInstall && unprocessed

/** The low cut's row value: "Auto (80 Hz)" on the raw mic, "Auto (off)" with the phone's auto level, "120 Hz"... */
fun lowCutText(cut: LowCut, raw: Boolean): String = when (cut) {
    LowCut.AUTO -> if (raw) "Auto (80 Hz)" else "Auto (off)"
    LowCut.OFF -> "Off"
    else -> "${cut.hz} Hz"
}

/** "+3 dB", "0 dB", "-6 dB" (with a real minus), for the row values and the fader. */
fun gainText(db: Int): String = when {
    db > 0 -> "+$db dB"
    db < 0 -> "−${-db} dB"
    else -> "0 dB"
}

/** The Settings row's value: the level, and that the phone's auto level is off when it is. */
fun micValue(p: MicPrefs): String = if (p.raw) "${gainText(p.rawGainDb)} · raw" else gainText(p.gainDb)
