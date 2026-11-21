package app.irlsupreme.service

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import app.irlsupreme.engine.Scene
import app.irlsupreme.engine.StreamEngine
import app.irlsupreme.engine.audio.AudioImporter
import app.irlsupreme.engine.audio.ImportResult
import app.irlsupreme.engine.audio.MusicTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * One card's music. With no file there's no music: how every card has always been. [startFrame]..[endFrame] and
 * [gainDb] come from the file's analysis when it was picked ([AudioImporter.analyzeMusic]).
 */
data class CardMusicSetting(
    /** The stored copy's name in the app's files; a new pick gets a new name, so a card on air crossfades to it. */
    val fileName: String? = null,
    val volumeDb: Int = CardMusicRules.DEFAULT_VOLUME_DB,
    /** "Mic while music plays": on, the streamer's voice is heard over the music. */
    val micOn: Boolean = true,
    val startFrame: Long = 0,
    val endFrame: Long = 0,
    val gainDb: Float = 0f,
    val durationMs: Long = 0,
) {
    val hasFile: Boolean get() = fileName != null
}

/** The rules, pure, so the page, the stream and the tests agree. */
object CardMusicRules {
    const val MIN_VOLUME_DB = -30
    const val MAX_VOLUME_DB = 0
    const val DEFAULT_VOLUME_DB = -12

    fun clampVolume(db: Int): Int = db.coerceIn(MIN_VOLUME_DB, MAX_VOLUME_DB)

    /**
     * The scene called [name], if it's one that can have music: any card the streamer chooses, custom ones included.
     * Never Live; never an automatic card (they aren't scenes: Weak signal and Camera lost stand in for Live, and
     * Signal lost is the BRB server's). Unknown names (a scene from a newer or older version) are null.
     */
    fun sceneFor(name: String): Scene? =
        runCatching { Scene.valueOf(name) }.getOrNull()?.takeIf { it != Scene.LIVE }

    /** What the engine plays for [s] on a card, or null for no music. [dir] holds the stored files. */
    fun track(s: CardMusicSetting?, dir: File): MusicTrack? {
        val name = s?.fileName ?: return null
        return MusicTrack(File(dir, name).path, s.startFrame, s.endFrame, s.gainDb + clampVolume(s.volumeDb), micOff = !s.micOn)
    }

    /** A new stored file's name for the card called [scene]: never one already used. */
    fun newFileName(scene: String, nowMs: Long): String = "${scene.lowercase()}-$nowMs.music"

    /** The session log's lines. Scene names only, never a file's. */
    fun logOn(scene: Scene): String = "card music on (${scene.name})"
    const val LOG_OFF = "card music off"

    const val COPYRIGHT_NOTE = "Use music you have the rights to. Twitch can mute your VOD for copyrighted music."

    /** "3:05" for a length in ms. */
    fun lengthText(ms: Long): String {
        val s = (ms + 500) / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }
}

/**
 * Music under the scene cards (Scene cards › a card › Music), keyed by the scene's name so cards added later work
 * without a change here. The picked file is copied into the app's files (at most 60 MB and 15 minutes) and analysed
 * once; while a card with music is on air (in preview too) the engine decodes it as it plays.
 */
object CardMusic {
    private var flow: MutableStateFlow<Map<String, CardMusicSetting>>? = null
    private val KEY = Regex("music_(.+)_(file|volume|mic|start|end|gain|ms)")

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("card_music", Context.MODE_PRIVATE)
    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "card_music")
    private fun key(name: String, field: String) = "music_${name}_$field"

    /** The stored copy of [s]'s music, if it has any (for playing it on the phone). */
    fun fileOf(ctx: Context, s: CardMusicSetting): File? = s.fileName?.let { File(dir(ctx), it) }

    @Synchronized
    fun state(ctx: Context): StateFlow<Map<String, CardMusicSetting>> =
        (flow ?: MutableStateFlow(load(ctx)).also { flow = it }).asStateFlow()

    fun setting(ctx: Context, name: String): CardMusicSetting = state(ctx).value[name] ?: CardMusicSetting()

    private fun load(ctx: Context): Map<String, CardMusicSetting> {
        val p = prefs(ctx)
        val names = p.all.keys.mapNotNull { KEY.matchEntire(it)?.groupValues?.get(1) }.toSet()
        return names.associateWith { n ->
            CardMusicSetting(
                fileName = p.getString(key(n, "file"), null)?.takeIf { File(dir(ctx), it).exists() },
                volumeDb = CardMusicRules.clampVolume(p.getInt(key(n, "volume"), CardMusicRules.DEFAULT_VOLUME_DB)),
                micOn = p.getBoolean(key(n, "mic"), true),
                startFrame = p.getLong(key(n, "start"), 0),
                endFrame = p.getLong(key(n, "end"), 0),
                gainDb = p.getFloat(key(n, "gain"), 0f),
                durationMs = p.getLong(key(n, "ms"), 0),
            )
        }
    }

    fun setVolume(ctx: Context, name: String, db: Int) {
        val v = CardMusicRules.clampVolume(db)
        prefs(ctx).edit { putInt(key(name, "volume"), v) }
        update(ctx, name) { it.copy(volumeDb = v) }
    }

    fun setMicOn(ctx: Context, name: String, on: Boolean) {
        prefs(ctx).edit { putBoolean(key(name, "mic"), on) }
        update(ctx, name) { it.copy(micOn = on) }
    }

    /**
     * Copies the picked file in and analyses it (a long song takes a few seconds), then makes it this card's music.
     * Returns null, or what went wrong in plain words; the card keeps its old music then. Blocking: call off the main
     * thread.
     */
    fun pick(ctx: Context, name: String, uri: Uri, onProgress: (Float) -> Unit = {}): String? {
        val tmp = File(dir(ctx), "${name.lowercase()}.tmp")
        val old = setting(ctx, name).fileName
        try {
            AudioPick.copyFailureText(AudioPick.copy(ctx, uri, tmp))?.let { return it }
            return when (val r = AudioImporter.analyzeMusic(tmp, onProgress)) {
                is ImportResult.Failed -> AudioPick.failureText(r.why)
                is ImportResult.Ok -> {
                    val fileName = CardMusicRules.newFileName(name, System.currentTimeMillis())
                    if (!tmp.renameTo(File(dir(ctx), fileName))) return "That music couldn't be saved. Is the phone full?"
                    // The old copy goes. One a running stream is still playing stays readable until it lets go.
                    old?.let { File(dir(ctx), it).delete() }
                    val i = r.value
                    prefs(ctx).edit {
                        putString(key(name, "file"), fileName)
                        putLong(key(name, "start"), i.startFrame)
                        putLong(key(name, "end"), i.endFrame)
                        putFloat(key(name, "gain"), i.gainDb.toFloat())
                        putLong(key(name, "ms"), i.durationMs)
                    }
                    update(ctx, name) {
                        it.copy(fileName = fileName, startFrame = i.startFrame, endFrame = i.endFrame, gainDb = i.gainDb.toFloat(), durationMs = i.durationMs)
                    }
                    null
                }
            }
        } finally {
            tmp.delete()
        }
    }

    /** No more music on this card. Its volume and mic setting are kept for next time. */
    fun remove(ctx: Context, name: String) {
        setting(ctx, name).fileName?.let { File(dir(ctx), it).delete() }
        prefs(ctx).edit {
            for (field in listOf("file", "start", "end", "gain", "ms")) remove(key(name, field))
        }
        update(ctx, name) { it.copy(fileName = null, startFrame = 0, endFrame = 0, gainDb = 0f, durationMs = 0) }
    }

    /**
     * While a stream runs (preview included): plays the music of the card on air, and follows every change of card
     * and of its settings. [log] gets the session log's lines. Runs until cancelled.
     */
    suspend fun follow(ctx: Context, engine: StreamEngine, log: (String) -> Unit) {
        var onScene: Scene? = null
        combine(engine.state.map { it.scene }.distinctUntilChanged(), state(ctx)) { scene, all ->
            val name = scene.name
            scene to CardMusicRules.sceneFor(name)?.let { CardMusicRules.track(all[name], dir(ctx)) }
        }.distinctUntilChanged().collect { (scene, track) ->
            engine.setCardMusic(track)
            if (track != null && onScene != scene) log(CardMusicRules.logOn(scene))
            if (track == null && onScene != null) log(CardMusicRules.LOG_OFF)
            onScene = if (track != null) scene else null
        }
    }

    private fun update(ctx: Context, name: String, change: (CardMusicSetting) -> CardMusicSetting) {
        state(ctx)
        val f = flow ?: return
        f.value = f.value + (name to change(f.value[name] ?: CardMusicSetting()))
    }
}
