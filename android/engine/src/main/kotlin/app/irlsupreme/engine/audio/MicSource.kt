package app.irlsupreme.engine.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.util.Log

/**
 * Which Android mic sources to try, in order.
 *
 * With the phone's auto level on ([raw] false), the camcorder source: tuned for video, and on many phones it also
 * levels the voice automatically. That's what most streaming apps use, and it's where "my phone makes me loud and
 * harsh" comes from: the phone turns everything up, room noise included, and pushes loud speech into clipping.
 *
 * With it off, the unprocessed source where the phone says it has one (no gain, no noise suppression, flat), else voice
 * recognition (Android requires it without noise reduction and recommends no auto gain), else the plain mic. The level
 * is then the user's, set with [MicDsp.gainDb].
 */
internal fun micSources(raw: Boolean, unprocessedOk: Boolean): IntArray = when {
    !raw -> intArrayOf(MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.MIC)
    unprocessedOk -> intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
    else -> intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
}

/** An open, recording mic, and the auto-gain effect switched off on it (released with it). */
internal class OpenMic(val record: AudioRecord, val source: Int, private val agc: AutomaticGainControl?) {
    fun close() {
        runCatching { record.stop() }
        runCatching { record.release() }
        agc?.let { runCatching { it.release() } }
    }
}

/**
 * Opens and starts the first of [micSources] that records. With [raw], also switches off the auto-gain effect if the
 * phone attached one to the session (some do, whatever the source). Null if none will record.
 */
@SuppressLint("MissingPermission") // RECORD_AUDIO is checked before anything opens the mic
internal fun openMic(sampleRate: Int, stereo: Boolean, minBytes: Int, raw: Boolean, unprocessedOk: Boolean): OpenMic? {
    val channelMask = if (stereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
    val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
    for (src in micSources(raw, unprocessedOk)) {
        val r = runCatching {
            AudioRecord.Builder()
                .setAudioSource(src)
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(channelMask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                )
                // ~170 ms at 48 kHz stereo: headroom before an overrun if the capture thread stalls. Reads stay
                // chunked and timestamps come from the record, so a bigger buffer adds no latency.
                .setBufferSizeInBytes(maxOf(minBuf, minBytes) * 8)
                .build()
        }.getOrNull() ?: continue
        val started = r.state == AudioRecord.STATE_INITIALIZED && runCatching { r.startRecording() }.isSuccess &&
            r.recordingState == AudioRecord.RECORDSTATE_RECORDING
        if (!started) { runCatching { r.release() }; continue }
        val agc = if (raw && AutomaticGainControl.isAvailable()) {
            runCatching { AutomaticGainControl.create(r.audioSessionId)?.apply { enabled = false } }.getOrNull()
        } else null
        Log.i("Mic", "mic open: source $src, phone auto level ${if (raw) "off" else "on"}")
        return OpenMic(r, src, agc)
    }
    return null
}

/** Whether this phone says it has a truly unprocessed mic source. */
fun unprocessedSupported(context: Context): Boolean = runCatching {
    context.getSystemService(AudioManager::class.java)?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
}.getOrDefault(false)
