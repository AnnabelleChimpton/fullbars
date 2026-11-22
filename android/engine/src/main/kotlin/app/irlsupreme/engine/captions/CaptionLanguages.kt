package app.irlsupreme.engine.captions

import android.content.Context
import android.content.Intent
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * The languages this phone can caption on the device: [installed] ones work now, [downloadable] ones once the
 * phone's speech service has fetched them ([download]). [onDevice] false: no on-device recognition at all.
 */
data class CaptionLanguageSupport(
    val onDevice: Boolean,
    val installed: List<String> = emptyList(),
    val pending: List<String> = emptyList(),
    val downloadable: List<String> = emptyList(),
)

/** Asks the phone's on-device speech service about languages, for Settings › Captions. Main thread. */
object CaptionLanguages {
    /** Live captions need Android 13: it's the first that lets an app feed the recogniser its own audio. */
    val supported: Boolean get() = android.os.Build.VERSION.SDK_INT >= 33

    /** Calls [done] once, on the main thread. */
    fun check(ctx: Context, done: (CaptionLanguageSupport) -> Unit) {
        if (!supported || !SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) { done(CaptionLanguageSupport(onDevice = false)); return }
        check33(ctx, done)
    }

    @android.annotation.TargetApi(33)
    private fun check33(ctx: Context, done: (CaptionLanguageSupport) -> Unit) {
        val r = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) }.getOrElse { done(CaptionLanguageSupport(false)); return }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        var answered = false
        fun finish(s: CaptionLanguageSupport) {
            if (answered) return
            answered = true
            runCatching { r.destroy() }
            done(s)
        }
        runCatching {
            r.checkRecognitionSupport(intent, ctx.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(s: RecognitionSupport) = finish(
                    CaptionLanguageSupport(true, s.installedOnDeviceLanguages, s.pendingOnDeviceLanguages, s.supportedOnDeviceLanguages),
                )
                override fun onError(error: Int) = finish(CaptionLanguageSupport(onDevice = true))
            })
        }.onFailure { finish(CaptionLanguageSupport(onDevice = true)) }
    }

    /** Asks the phone's speech service to fetch [language] for on-device use (it downloads in the background). */
    fun download(ctx: Context, language: String) {
        if (supported) download33(ctx, language)
    }

    @android.annotation.TargetApi(33)
    private fun download33(ctx: Context, language: String) {
        val r = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) }.getOrNull() ?: return
        runCatching {
            r.triggerModelDownload(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, language))
        }
        // The service carries on with the download on its own; this instance can go.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { r.destroy() } }, 2_000)
    }
}
