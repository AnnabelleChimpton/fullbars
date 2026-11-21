package app.irlsupreme.service

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import app.irlsupreme.engine.StreamEngine
import app.irlsupreme.engine.audio.AlertClip
import app.irlsupreme.engine.audio.AlertSounds
import app.irlsupreme.engine.audio.AudioImporter
import app.irlsupreme.engine.audio.ImportMath
import app.irlsupreme.engine.audio.ImportResult
import app.irlsupreme.engine.audio.PcmClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** What an alert kind sounds like on stream. */
enum class AlertSoundChoice { BUILT_IN, CUSTOM, NONE }

/** One kind's sound: [BUILT_IN] at 0 dB is how alerts have always sounded. [hasFile]: a sound was picked. */
data class AlertSoundSetting(
    val choice: AlertSoundChoice = AlertSoundChoice.BUILT_IN,
    val gainDb: Int = 0,
    val hasFile: Boolean = false,
)

/** The rules, pure, so the page, the stream and the tests agree. */
object AlertSoundRules {
    const val MIN_GAIN_DB = -24
    const val MAX_GAIN_DB = 6
    /** A picked sound's longest; longer ones are cut. */
    const val MAX_SECONDS = ImportMath.ALERT_MAX_S

    fun clampGain(db: Int): Int = db.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)

    /** What really plays: "Your sound" with no file (it was removed) falls back to the chime. */
    fun effective(s: AlertSoundSetting): AlertSoundChoice =
        if (s.choice == AlertSoundChoice.CUSTOM && !s.hasFile) AlertSoundChoice.BUILT_IN else s.choice

    /** The built-in chime each kind has always had. */
    fun chimeFor(kind: AlertKind): AlertSounds.Style = when (kind) {
        AlertKind.FOLLOW -> AlertSounds.Style.SOFT
        AlertKind.RAID -> AlertSounds.Style.BIG
        else -> AlertSounds.Style.BRIGHT
    }

    /** The session log's line for a sound that isn't the chime; null for the chime (as before). Never a file name. */
    fun logLine(choice: AlertSoundChoice, kind: AlertKind): String? = when (choice) {
        AlertSoundChoice.BUILT_IN -> null
        AlertSoundChoice.CUSTOM -> "alert sound: custom (${kind.name.lowercase()})"
        AlertSoundChoice.NONE -> "alert sound: none (${kind.name.lowercase()})"
    }

    /** "+3 dB", "0 dB", "−12 dB". */
    fun gainText(db: Int): String = when {
        db > 0 -> "+$db dB"
        db < 0 -> "−${-db} dB"
        else -> "0 dB"
    }
}

/**
 * Each alert kind's sound (Customize alerts › a kind › Sound): the built-in chime, the streamer's own sound, or none,
 * each at its own volume. Settings live in their own preferences; a picked sound is decoded once, when it's picked,
 * into a short clip in the app's files (at most 10 s of 48 kHz stereo, under 2 MB).
 */
object AlertSoundChoices {
    private var flow: MutableStateFlow<Map<AlertKind, AlertSoundSetting>>? = null

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("alert_sounds", Context.MODE_PRIVATE)
    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "alert_sounds")
    private fun clipFile(ctx: Context, k: AlertKind) = File(dir(ctx), "${k.name.lowercase()}.clip")
    private fun key(k: AlertKind, field: String) = "sound_${k.name.lowercase()}_$field"

    @Synchronized
    fun state(ctx: Context): StateFlow<Map<AlertKind, AlertSoundSetting>> =
        (flow ?: MutableStateFlow(load(ctx)).also { flow = it }).asStateFlow()

    fun setting(ctx: Context, k: AlertKind): AlertSoundSetting = state(ctx).value[k] ?: AlertSoundSetting()

    private fun load(ctx: Context): Map<AlertKind, AlertSoundSetting> = AlertKind.entries.associateWith { k ->
        val p = prefs(ctx)
        AlertSoundSetting(
            choice = p.getString(key(k, "choice"), null)
                ?.let { runCatching { AlertSoundChoice.valueOf(it) }.getOrNull() } ?: AlertSoundChoice.BUILT_IN,
            gainDb = AlertSoundRules.clampGain(p.getInt(key(k, "gain"), 0)),
            hasFile = clipFile(ctx, k).exists(),
        )
    }

    fun setChoice(ctx: Context, k: AlertKind, c: AlertSoundChoice) {
        prefs(ctx).edit { putString(key(k, "choice"), c.name) }
        update(ctx, k) { it.copy(choice = c) }
    }

    fun setGain(ctx: Context, k: AlertKind, db: Int) {
        val v = AlertSoundRules.clampGain(db)
        prefs(ctx).edit { putInt(key(k, "gain"), v) }
        update(ctx, k) { it.copy(gainDb = v) }
    }

    /** The result of picking a sound: null error and whether it was cut to 10 s, or what went wrong. */
    data class Picked(val error: String?, val cut: Boolean = false)

    /** Decodes the picked file and makes it this kind's sound. Blocking (a few seconds): call off the main thread. */
    fun pick(ctx: Context, k: AlertKind, uri: Uri): Picked {
        val tmp = File(ctx.cacheDir, "alert_pick_${k.name.lowercase()}")
        try {
            AudioPick.copyFailureText(AudioPick.copy(ctx, uri, tmp))?.let { return Picked(it) }
            return when (val r = AudioImporter.decodeAlert(tmp)) {
                is ImportResult.Failed -> Picked(AudioPick.failureText(r.why))
                is ImportResult.Ok -> {
                    val ok = runCatching { dir(ctx).mkdirs(); r.value.save(clipFile(ctx, k)) }.isSuccess
                    if (!ok) return Picked("That sound couldn't be saved. Is the phone full?")
                    prefs(ctx).edit { putString(key(k, "choice"), AlertSoundChoice.CUSTOM.name) }
                    update(ctx, k) { it.copy(choice = AlertSoundChoice.CUSTOM, hasFile = true) }
                    Picked(null, r.cut)
                }
            }
        } finally {
            tmp.delete()
        }
    }

    /** Forgets this kind's own sound; it goes back to the chime. */
    fun remove(ctx: Context, k: AlertKind) {
        clipFile(ctx, k).delete()
        prefs(ctx).edit { remove(key(k, "choice")) }
        update(ctx, k) { it.copy(choice = AlertSoundChoice.BUILT_IN, hasFile = false) }
    }

    /** This kind's own sound, read from its file; null if there isn't one. Blocking. */
    fun clip(ctx: Context, k: AlertKind): AlertClip? = AlertClip.load(clipFile(ctx, k))

    /**
     * What [k] would sound like on stream, at its volume, for playing on the phone: the chime or the streamer's own
     * sound; null for None. Blocking.
     */
    fun previewClip(ctx: Context, k: AlertKind): PcmClip? {
        val s = setting(ctx, k)
        val pcm = when (AlertSoundRules.effective(s)) {
            AlertSoundChoice.NONE -> return null
            AlertSoundChoice.BUILT_IN -> AlertSounds.chime(AlertSoundRules.chimeFor(k), ImportMath.RATE, true)
            AlertSoundChoice.CUSTOM -> clip(ctx, k)?.pcm ?: AlertSounds.chime(AlertSoundRules.chimeFor(k), ImportMath.RATE, true)
        }
        val g = ImportMath.dbToGain(s.gainDb.toDouble())
        val out = ShortArray(pcm.size) { (pcm[it] * g).toInt().coerceIn(-32768, 32767).toShort() }
        return PcmClip(out, ImportMath.RATE, 2)
    }

    /**
     * Gets an alert's sound ready before it's shown (reading a picked sound's file takes a moment), so [Prepared.play]
     * can start it on the alert's own timing straight after. [style] is the chime the alert would have had.
     */
    suspend fun prepare(ctx: Context, k: AlertKind, style: AlertSounds.Style): Prepared = withContext(Dispatchers.IO) {
        val s = setting(ctx, k)
        var choice = AlertSoundRules.effective(s)
        val clip = if (choice == AlertSoundChoice.CUSTOM) clip(ctx, k) else null
        if (choice == AlertSoundChoice.CUSTOM && clip == null) choice = AlertSoundChoice.BUILT_IN
        Prepared(k, choice, style, s.gainDb, clip)
    }

    class Prepared internal constructor(
        private val kind: AlertKind,
        private val choice: AlertSoundChoice,
        private val style: AlertSounds.Style,
        private val gainDb: Int,
        private val clip: AlertClip?,
    ) {
        /**
         * Starts the sound so its hit lands when the alert is fully shown, [enterMs] from now (as the chime always
         * has). Returns the session log's line, if any.
         */
        fun play(engine: StreamEngine, enterMs: Long): String? {
            when (choice) {
                AlertSoundChoice.BUILT_IN ->
                    engine.playAlertChime(style, AlertTiming.chimeDelayMs(enterMs, AlertSounds.attackMs(style)), gainDb.toFloat())
                AlertSoundChoice.CUSTOM -> clip?.let {
                    engine.playAlertSound(it, gainDb.toFloat(), AlertTiming.chimeDelayMs(enterMs, it.attackMs))
                }
                AlertSoundChoice.NONE -> Unit
            }
            return AlertSoundRules.logLine(choice, kind)
        }
    }

    private fun update(ctx: Context, k: AlertKind, change: (AlertSoundSetting) -> AlertSoundSetting) {
        state(ctx)
        val f = flow ?: return
        f.value = f.value + (k to change(f.value[k] ?: AlertSoundSetting()))
    }
}
