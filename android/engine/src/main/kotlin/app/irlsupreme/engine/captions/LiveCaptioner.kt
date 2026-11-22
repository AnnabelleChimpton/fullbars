package app.irlsupreme.engine.captions

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import app.irlsupreme.engine.CaptionOptions
import app.irlsupreme.engine.CaptionStatus
import java.io.FileOutputStream

/**
 * Runs Android's on-device speech recogniser on the stream's own voice, for live captions. Nothing leaves the phone:
 * only the on-device recogniser is used, and the mic isn't opened a second time: the voice comes from the capture
 * thread through [tap] (see [VoiceTap]), resampled to 16 kHz, and is written into a pipe the recogniser reads
 * (EXTRA_AUDIO_SOURCE, Android 13+), in one segmented session that runs as long as the audio does.
 *
 * Results go to [onPartial] (the growing guess) and [onSegment] (finished words at a pause), on the main thread. A
 * session that ends or fails is started again (after 0.5 s, backing off to 10 s while it keeps failing); a language
 * the phone can't do on the device is reported once as [CaptionStatus.UNAVAILABLE] and not retried.
 *
 * The recogniser's own calls happen on the main thread, as SpeechRecognizer requires. The feeding thread copies the
 * ring into the pipe; a backlog over [MAX_BACKLOG_MS] (the recogniser fell behind) is skipped, so captions stay
 * current rather than drifting later and later.
 */
@android.annotation.TargetApi(33)
class LiveCaptioner(
    private val ctx: Context,
    sampleRate: Int,
    private val options: CaptionOptions,
    private val onPartial: (String) -> Unit,
    private val onSegment: (String) -> Unit,
    private val onStatus: (CaptionStatus, String?) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val ring = ShortRing(VoiceDownsampler.OUT_RATE * RING_SECONDS)
    private val downsampler = VoiceDownsampler(sampleRate, ring)
    /** While a test clip is being fed ([feedClip]), the voice is ignored (the ring has one writer at a time). */
    @Volatile private var clipFeeding = false
    /** Hand this to the audio pipeline: the capture thread calls it with every chunk of voice. */
    val tap: VoiceTap = VoiceTap { v, f, c, a -> if (!clipFeeding) downsampler.onVoice(v, f, c, a) }

    /** Debug builds: feeds a 16 kHz mono 16-bit clip in real time instead of the voice, then goes back to the voice. */
    fun feedClip(pcm: ByteArray) {
        Thread({
            clipFeeding = true
            try {
                val start = android.os.SystemClock.elapsedRealtime()
                var i = 0
                while (i + 1 < pcm.size && !stopped) {
                    val s = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort()
                    while (!ring.write(s) && !stopped) Thread.sleep(5)
                    i += 2
                    if (i % 640 == 0) {
                        val wait = start + i / 32 - android.os.SystemClock.elapsedRealtime()
                        if (wait > 0) Thread.sleep(wait)
                    }
                }
            } finally { clipFeeding = false }
        }, "captions-clip").apply { isDaemon = true }.start()
    }

    // Main-thread state.
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var session = 0
    private var readEnd: ParcelFileDescriptor? = null
    private var writeEnd: ParcelFileDescriptor? = null
    @Volatile private var stopped = false
    private var paused = false
    private var unavailable = false
    private var retryMs = FIRST_RETRY_MS
    /** Sessions started after the first, for the session log. */
    @Volatile var restarts = 0
        private set

    fun start() = main.post { if (!stopped) begin() }

    /** Stops for good (the stream ended, or captions were turned off). */
    fun stop() = main.post {
        stopped = true
        end()
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    /** Pauses while the phone is too hot, and resumes once it cools; nothing is captioned meanwhile. */
    fun setPaused(p: Boolean) = main.post {
        if (paused == p || stopped || unavailable) return@post
        paused = p
        if (p) { end(); onStatus(CaptionStatus.PAUSED, null) } else begin()
    }

    private fun begin() {
        if (stopped || paused || unavailable) return
        end()
        val gen = ++session
        val r = recognizer ?: runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) }.getOrElse {
            giveUp("this phone has no on-device speech recognition"); return
        }.also { recognizer = it }
        val (read, write) = ParcelFileDescriptor.createPipe().let { it[0] to it[1] }
        readEnd = read; writeEnd = write
        r.setRecognitionListener(Listener(gen))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, options.language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, options.maskOffensive)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, VoiceDownsampler.OUT_RATE)
        }
        ring.skipTo(0) // start from now, not from whatever piled up while it was down
        try {
            r.startListening(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "captions: couldn't start listening", t)
            retry(gen, "couldn't start")
            return
        }
        feed(gen, write)
        onStatus(CaptionStatus.STARTING, null)
    }

    /** The feeding thread for one session: copies the ring into the pipe until the session moves on. */
    private fun feed(gen: Int, write: ParcelFileDescriptor) {
        Thread({
            val shorts = ShortArray(CHUNK)
            val bytes = ByteArray(CHUNK * 2)
            runCatching {
                FileOutputStream(write.fileDescriptor).use { os ->
                    while (gen == session && !stopped) {
                        ring.skipTo(VoiceDownsampler.OUT_RATE * MAX_BACKLOG_MS / 1000)
                        val n = ring.read(shorts)
                        if (n == 0) { Thread.sleep(IDLE_SLEEP_MS); continue }
                        for (i in 0 until n) {
                            val v = shorts[i].toInt()
                            bytes[2 * i] = v.toByte()
                            bytes[2 * i + 1] = (v shr 8).toByte()
                        }
                        os.write(bytes, 0, 2 * n) // blocks only this thread if the recogniser reads slowly
                    }
                }
            } // a closed pipe (the session ended) is the normal way out
        }, "captions-feed").apply { isDaemon = true }.start()
    }

    /** Main thread: ends the current session (its feeding thread stops on its own). */
    private fun end() {
        session++
        runCatching { recognizer?.cancel() }
        runCatching { writeEnd?.close() }
        runCatching { readEnd?.close() }
        writeEnd = null; readEnd = null
    }

    private fun retry(gen: Int, why: String) {
        if (gen != session || stopped || paused || unavailable) return
        end()
        // A recogniser whose service went away is made afresh.
        runCatching { recognizer?.destroy() }
        recognizer = null
        val wait = retryMs
        retryMs = (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
        Log.w(TAG, "captions: $why; starting again in $wait ms")
        main.postDelayed({ if (!stopped && !paused) { restarts++; begin() } }, wait)
    }

    private fun giveUp(why: String) {
        unavailable = true
        end()
        onStatus(CaptionStatus.UNAVAILABLE, why)
    }

    private inner class Listener(private val gen: Int) : RecognitionListener {
        private fun current() = gen == session && !stopped
        override fun onReadyForSpeech(params: Bundle?) {
            if (!current()) return
            retryMs = FIRST_RETRY_MS
            onStatus(CaptionStatus.LISTENING, null)
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle) {
            if (current()) first(partialResults)?.let(onPartial)
        }
        override fun onSegmentResults(segmentResults: Bundle) {
            if (current()) first(segmentResults)?.let(onSegment)
        }
        override fun onResults(results: Bundle) {
            if (current()) first(results)?.let(onSegment)
        }
        override fun onEndOfSegmentedSession() { if (current()) retry(gen, "the session ended") }
        override fun onError(error: Int) {
            if (!current()) return
            when (error) {
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    giveUp("${options.language} isn't available for on-device recognition (error $error)")
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> giveUp("no microphone permission")
                else -> retry(gen, "error $error")
            }
        }
    }

    private fun first(b: Bundle): String? = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private companion object {
        const val TAG = "Engine"
        const val RING_SECONDS = 4
        const val MAX_BACKLOG_MS = 1_000
        const val CHUNK = 320 // 20 ms at 16 kHz
        const val IDLE_SLEEP_MS = 10L
        const val FIRST_RETRY_MS = 500L
        const val MAX_RETRY_MS = 10_000L
    }
}
