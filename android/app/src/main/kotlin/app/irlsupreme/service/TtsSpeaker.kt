package app.irlsupreme.service

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import app.irlsupreme.engine.audio.AlertClip
import app.irlsupreme.engine.audio.AudioImporter
import app.irlsupreme.engine.audio.ImportMath
import app.irlsupreme.engine.audio.ImportResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns a viewer's message into a clip for the stream with the phone's own text-to-speech, offline: synthesised to a
 * file (never played on the phone), decoded and levelled like an alert sound ([AudioImporter.decodeSpeech]). One at a
 * time. Its own TextToSpeech, apart from chat read aloud, because pitch and speed are set per message.
 */
class TtsSpeaker(context: Context) : TextToSpeech.OnInitListener {
    private val ctx = context.applicationContext
    private val ready = CompletableDeferred<Boolean>()
    private val tts = TextToSpeech(ctx, this)
    private val lock = Mutex()
    private val done = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val dir = File(ctx.cacheDir, "tts").apply { mkdirs() }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "tts: text-to-speech unavailable ($status)")
            ready.complete(false)
            return
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { id?.let { done.remove(it)?.complete(true) } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { id?.let { done.remove(it)?.complete(false) } }
            override fun onError(id: String?, code: Int) { id?.let { done.remove(it)?.complete(false) } }
        })
        ready.complete(true)
    }

    /** Debug: every voice the engine reports, one per line (name, locale, quality, network, features). */
    suspend fun describeVoices(): String {
        if (withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } != true) return "not ready"
        return runCatching {
            tts.voices.orEmpty().sortedBy { it.name }.joinToString("\n") {
                "${it.name}\t${it.locale.toLanguageTag()}\tq${it.quality}\tnet=${it.isNetworkConnectionRequired}\t${it.features.orEmpty().sorted()}"
            }
        }.getOrDefault("failed")
    }

    /**
     * [text] in the diva voice ([TtsVoice]), as a clip at 48 kHz stereo; null if the phone can't. Takes a second or so
     * for a long message.
     */
    suspend fun synthesize(text: String): AlertClip? = lock.withLock {
        if (withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } != true) return null
        val id = "tts-${System.nanoTime()}"
        val file = File(dir, "$id.wav")
        try {
            val finished = CompletableDeferred<Boolean>().also { done[id] = it }
            val started = withContext(Dispatchers.Main) {
                runCatching {
                    // The diva's base voice if the phone has one, else its default.
                    val installed = tts.voices.orEmpty().filter { "notInstalled" !in it.features.orEmpty() }
                    val v = TtsVoice.BASES.firstNotNullOfOrNull { n -> installed.firstOrNull { it.name == n } } ?: tts.defaultVoice
                    v?.let { tts.voice = it }
                    tts.setPitch(TtsVoice.PITCH)
                    tts.setSpeechRate(TtsVoice.RATE)
                    tts.synthesizeToFile(text, Bundle(), file, id) == TextToSpeech.SUCCESS
                }.getOrDefault(false)
            }
            if (!started || withTimeoutOrNull(SYNTH_TIMEOUT_MS) { finished.await() } != true) return null
            val clip = withContext(Dispatchers.Default) {
                when (val r = AudioImporter.decodeSpeech(file)) {
                    is ImportResult.Ok -> r.value
                    is ImportResult.Failed -> null
                }
            } ?: return null
            clip
        } finally {
            done.remove(id)
            file.delete()
        }
    }

    fun shutdown() {
        runCatching { tts.stop(); tts.shutdown() }
        dir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val TAG = "Engine"
        const val INIT_TIMEOUT_MS = 5_000L
        const val SYNTH_TIMEOUT_MS = 15_000L
    }
}
