package app.irlsupreme.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import app.irlsupreme.engine.audio.PcmClip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plays a [PcmClip] back to the streamer: the mic check's take (Settings › Microphone) or the last seconds of a Test
 * stream (Home's recap), so they can hear what viewers would. Through whatever plays media now: headphones when
 * they're in, otherwise the phone's speaker. One clip at a time; [play] replaces whatever was playing.
 *
 * Its own thread with a streaming AudioTrack, written 20 ms at a time so [stop] takes effect within a few blocks
 * without the UI thread waiting on the audio system. Takes transient audio focus, so music pauses or ducks while you
 * listen and comes back after.
 */
class SoundPlayer(context: Context) {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)

    private val _progress = MutableStateFlow<Float?>(null)

    /** How far through the clip playback is, 0..1, or null when nothing is playing. */
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    /** Bumped by every [play] and [stop]: a playing thread that sees it change winds down. */
    @Volatile private var generation = 0

    @Synchronized
    fun play(clip: PcmClip) {
        val gen = ++generation
        if (clip.pcm.isEmpty() || clip.sampleRate <= 0 || clip.channels !in 1..2) { _progress.value = null; return }
        _progress.value = 0f
        Thread({ run(clip, gen) }, "sound-player").start()
    }

    /** Stops playback, if any. Safe to call any time, from any thread. */
    @Synchronized
    fun stop() {
        generation++
        _progress.value = null
    }

    private fun run(clip: PcmClip, gen: Int) {
        val ch = clip.channels
        // A copy with short fades at each end: the clip starts and stops mid-word, and a waveform cut there clicks.
        val pcm = clip.pcm.copyOf().also { fadeEdges(it, ch, clip.sampleRate) }
        val totalFrames = pcm.size / ch
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val mask = if (ch == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(clip.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(clip.sampleRate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuf, clip.sampleRate / 10 * ch * 2))
                .build()
        }.getOrNull()
        if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "playback: no audio track")
            track?.release()
            finish(gen)
            return
        }
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).build()
        runCatching { audio?.requestAudioFocus(focus) }
        try {
            track.play()
            val step = clip.sampleRate / 50 * ch
            var off = 0
            while (off < pcm.size && gen == generation) {
                val n = track.write(pcm, off, minOf(step, pcm.size - off), AudioTrack.WRITE_BLOCKING)
                if (n < 0) { Log.w(TAG, "playback: write failed ($n)"); break }
                off += n
                publish(gen, track.playbackHeadPosition, totalFrames)
            }
            // Everything is queued: wait for the speaker to reach the end, so the button doesn't flip back early.
            var idle = 0
            var last = -1
            while (gen == generation && off >= pcm.size) {
                val head = track.playbackHeadPosition
                if (head >= totalFrames) break
                // A track that stops moving (output gone) would otherwise keep the clip "playing" for ever.
                if (head == last) { if (++idle > 25) break } else idle = 0
                last = head
                publish(gen, head, totalFrames)
                Thread.sleep(20)
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            Log.w(TAG, "playback stopped", t)
        } finally {
            runCatching { track.pause(); track.flush(); track.stop() }
            track.release()
            runCatching { audio?.abandonAudioFocusRequest(focus) }
            finish(gen)
        }
    }

    private fun publish(gen: Int, head: Int, total: Int) {
        if (gen == generation) _progress.value = (head.toFloat() / total).coerceIn(0f, 1f)
    }

    @Synchronized
    private fun finish(gen: Int) {
        if (gen == generation) _progress.value = null
    }

    private companion object {
        const val TAG = "Sound"
    }
}

/** How long the fades at each end of a played clip last. */
internal const val FADE_MS = 8

/**
 * Fades the first and last [FADE_MS] of interleaved [pcm] in and out, in place, so a clip cut mid-word starts and ends
 * without a click. A clip shorter than both fades fades over half its length each way.
 */
internal fun fadeEdges(pcm: ShortArray, channels: Int, sampleRate: Int) {
    if (channels <= 0) return
    val frames = pcm.size / channels
    val n = minOf(sampleRate * FADE_MS / 1000, frames / 2)
    if (n <= 0) return
    for (f in 0 until n) {
        val g = f.toFloat() / n
        for (c in 0 until channels) {
            val head = f * channels + c
            val tail = (frames - 1 - f) * channels + c
            pcm[head] = (pcm[head] * g).toInt().toShort()
            pcm[tail] = (pcm[tail] * g).toInt().toShort()
        }
    }
}
