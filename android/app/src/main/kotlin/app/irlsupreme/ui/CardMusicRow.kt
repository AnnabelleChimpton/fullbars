package app.irlsupreme.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.irlsupreme.engine.audio.ImportMath
import app.irlsupreme.service.AudioPick
import app.irlsupreme.service.CardMusic
import app.irlsupreme.service.CardMusicRules
import app.irlsupreme.service.CardMusicSetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Music under one card, the scene called [sceneName]: pick a file, its volume under your voice, whether your mic stays
 * on while it plays, Play to hear it on the phone (never on stream), and Remove. For Scene cards' section of each card
 * you choose; automatic cards can't have music, and for them this shows nothing.
 */
@Composable
fun CardMusicRow(sceneName: String) {
    if (CardMusicRules.sceneFor(sceneName) == null) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by CardMusic.state(ctx).collectAsState()
    val s = all[sceneName] ?: CardMusicSetting()
    var busy by remember(sceneName) { mutableStateOf(false) }
    var progress by remember(sceneName) { mutableFloatStateOf(0f) }
    var error by rememberSaveable(sceneName) { mutableStateOf<String?>(null) }
    val preview = rememberMusicPreview()
    // The card is captured at pick time, so switching cards while a long file is read can't misfile it.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; error = null; progress = 0f
            preview.stop()
            error = withContext(Dispatchers.IO) { CardMusic.pick(ctx, sceneName, uri) { p -> progress = p } }
            busy = false
        }
    }
    // A new volume is heard at once while Play is on.
    LaunchedEffect(s.volumeDb, s.gainDb) { preview.setGainDb(s.gainDb + s.volumeDb) }
    LaunchedEffect(sceneName) { preview.stop() }

    if (!s.hasFile) {
        FbButton(
            if (busy) (if (progress > 0f) "Getting it ready… ${(progress * 100).toInt()}%" else "Getting it ready…") else "Add music",
            { picker.launch(AudioPick.MIME_TYPES) },
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp), kind = FbButtonKind.Secondary, enabled = !busy,
        )
        error?.let { InlineError(it, modifier = Modifier.padding(top = 8.dp)) }
        HelpText("Plays under your voice while this card is up. ${CardMusicRules.COPYRIGHT_NOTE}")
        return
    }
    // Picked: one row that says what it is and whether your mic stays on, opening to the rest.
    var open by rememberSaveable(sceneName) { mutableStateOf(false) }
    ExpandRow(musicSummary(s.durationMs, s.micOn), open, onToggle = { open = !open })
    if (!open) return
    error?.let { InlineError(it, modifier = Modifier.padding(top = 8.dp)) }
    SoundFader("Volume", s.volumeDb, CardMusicRules.MIN_VOLUME_DB, CardMusicRules.MAX_VOLUME_DB, { CardMusic.setVolume(ctx, sceneName, it) })
    SwitchRow(
        "Mic while music plays",
        supporting = if (s.micOn) "Viewers hear you over the music." else "Viewers hear only the music.",
        checked = s.micOn,
        onCheckedChange = { CardMusic.setMicOn(ctx, sceneName, it) },
    )
    val playing by preview.playing.collectAsState()
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        FbButton(
            if (playing) "Stop" else "Play on phone",
            { if (playing) preview.stop() else preview.play(ctx, CardMusic.fileOf(ctx, s), s.startFrame, s.gainDb + s.volumeDb) },
            kind = FbButtonKind.Secondary,
        )
        FbButton(if (busy) "Getting it ready…" else "Change", { picker.launch(AudioPick.MIME_TYPES) }, kind = FbButtonKind.Text, enabled = !busy)
        FbButton("Remove", { preview.stop(); CardMusic.remove(ctx, sceneName) }, kind = FbButtonKind.Text)
    }
}

/** The music row once there's music: "Music · 2:31, mic on". The mic's state shows without opening it. */
internal fun musicSummary(durationMs: Long, micOn: Boolean): String =
    "Music · ${CardMusicRules.lengthText(durationMs)}, mic ${if (micOn) "on" else "off"}"

/** Plays a card's music on the phone, looping, until stopped; stops when the page goes. */
@Composable
private fun rememberMusicPreview(): MusicPreview {
    val p = remember { MusicPreview() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, p) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) p.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); p.stop() }
    }
    return p
}

/**
 * A MediaPlayer on the stored music, from its trimmed start, at about its stream level (it can't go above full
 * scale, so a quiet file sounds a little quieter here than on stream).
 */
private class MusicPreview {
    private var player: MediaPlayer? = null
    val playing = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var gainDb = 0f

    fun play(ctx: Context, file: File?, startFrame: Long, gainDb: Float) {
        stop()
        if (file == null || !file.exists()) return
        this.gainDb = gainDb
        val mp = MediaPlayer()
        val ok = runCatching {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            mp.setDataSource(ctx.applicationContext, android.net.Uri.fromFile(file))
            mp.isLooping = true
            mp.prepare()
            mp.seekTo(ImportMath.framesToMs(startFrame).toInt())
            applyVolume(mp)
            mp.start()
        }.onFailure { Log.w("CardMusic", "preview failed: ${it.javaClass.simpleName}") }.isSuccess
        if (!ok) { mp.release(); return }
        player = mp
        playing.value = true
    }

    fun setGainDb(db: Float) {
        gainDb = db
        player?.let { applyVolume(it) }
    }

    private fun applyVolume(mp: MediaPlayer) {
        val v = ImportMath.dbToGain(gainDb.toDouble()).coerceIn(0f, 1f)
        runCatching { mp.setVolume(v, v) }
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        playing.value = false
    }
}
