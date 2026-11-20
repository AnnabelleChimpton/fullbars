package app.irlsupreme.service

import android.content.Context
import androidx.core.content.edit
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
 */
data class MicPrefs(val gainDb: Int = 0, val raw: Boolean = false, val rawGainDb: Int = RAW_DEFAULT_GAIN_DB) {
    /** The level in use now, the one the fader shows. */
    val activeGainDb: Int get() = if (raw) rawGainDb else gainDb
}

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
    private var flow: MutableStateFlow<MicPrefs>? = null

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Synchronized
    fun state(ctx: Context): StateFlow<MicPrefs> = (flow ?: MutableStateFlow(load(ctx)).also { flow = it }).asStateFlow()

    fun current(ctx: Context): MicPrefs = state(ctx).value

    /** Sets the level of the mode in use. */
    fun setGain(ctx: Context, db: Int) = current(ctx).let { p ->
        save(ctx, if (p.raw) p.copy(rawGainDb = clampGain(db)) else p.copy(gainDb = clampGain(db)))
    }

    fun setRaw(ctx: Context, raw: Boolean) = save(ctx, current(ctx).copy(raw = raw))

    private fun save(ctx: Context, p: MicPrefs) {
        prefs(ctx).edit().putInt(KEY_GAIN, p.gainDb).putBoolean(KEY_RAW, p.raw).putInt(KEY_GAIN_RAW, p.rawGainDb).apply()
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
        return MicPrefs(clampGain(p.getInt(KEY_GAIN, 0)), p.getBoolean(KEY_RAW, false), clampGain(p.getInt(KEY_GAIN_RAW, RAW_DEFAULT_GAIN_DB)))
    }

    /** Never updated since it was installed: no earlier version has run, so there's no choice of the user's to keep. */
    private fun freshInstall(ctx: Context): Boolean = runCatching {
        @Suppress("DEPRECATION") // the flags overload is API 33; minSdk is 31
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        info.firstInstallTime == info.lastUpdateTime
    }.getOrDefault(false)

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

/** "+3 dB", "0 dB", "-6 dB" (with a real minus), for the row values and the fader. */
fun gainText(db: Int): String = when {
    db > 0 -> "+$db dB"
    db < 0 -> "−${-db} dB"
    else -> "0 dB"
}

/** The Settings row's value: the level, and that the phone's auto level is off when it is. */
fun micValue(p: MicPrefs): String = if (p.raw) "${gainText(p.rawGainDb)} · raw" else gainText(p.gainDb)
