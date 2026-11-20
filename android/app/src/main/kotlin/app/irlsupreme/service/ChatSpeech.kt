package app.irlsupreme.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import app.irlsupreme.twitch.ChatMessage
import java.util.Locale

/** Whose chat is read aloud. */
enum class ReadAloud(val title: String, val detail: String) {
    OFF("Off", "Chat isn't read aloud"),
    TRUSTED("Mods, VIPs and subs", "Only people you've trusted or who support you"),
    EVERYONE("Everyone", "Every message, which can be a lot in a busy chat"),
}

/** What's said for a chat message, and whether it's said at all. Pure, for tests. */
object ChatSpeechText {
    private val url = Regex("""(?i)\b(?:https?://|www\.)\S+""")
    private val repeats = Regex("""(.)\1{4,}""") // "!!!!!!!!" or "hahahahaha"-style runs of one character

    fun shouldRead(m: ChatMessage, mode: ReadAloud, isCommand: Boolean): Boolean = when {
        mode == ReadAloud.OFF || isCommand || m.isBroadcaster || m.isFromSharedChat -> false
        m.text.trimStart().startsWith("!") -> false // someone else's bot command
        mode == ReadAloud.EVERYONE -> true
        else -> m.isModerator || m.isVip || m.isSubscriber
    }

    /**
     * "Sam says this view is unreal", links as "a link", runs of one character shortened, at most [MAX] characters. A
     * notice is said too: "Sam watched 12 streams in a row", then "and says ..." if they wrote something.
     */
    fun utterance(m: ChatMessage): String? {
        val text = repeats.replace(url.replace(m.text, "a link")) { it.value.take(3) }.trim()
        val clipped = if (text.length <= MAX) text else text.take(MAX).substringBeforeLast(' ') + "…"
        return when {
            m.notice != null && text.isEmpty() -> "${m.chatterDisplayName} ${m.notice}"
            m.notice != null -> "${m.chatterDisplayName} ${m.notice}, and says $clipped"
            text.isEmpty() -> null
            else -> "${m.chatterDisplayName} says $clipped"
        }
    }

    const val MAX = 160
}

/**
 * Reads chat aloud into the streamer's headphones, so they can follow chat without looking at the phone. **Only
 * while headphones or earbuds are connected**: from the phone's speaker it would go into the mic and onto the stream.
 * A busy chat doesn't pile up: at most [MAX_QUEUED] messages wait, the oldest dropped.
 */
class ChatSpeaker(private val ctx: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(ctx.applicationContext, this)
    private val audio = ctx.getSystemService(AudioManager::class.java)
    @Volatile private var ready = false
    private var queued = 0

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) { Log.w(TAG, "chat speech: text-to-speech unavailable ($status)"); return }
        tts.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
        )
        runCatching { tts.language = Locale.getDefault() }
        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { synchronized(this@ChatSpeaker) { queued = (queued - 1).coerceAtLeast(0) } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { onDone(id) }
        })
        ready = true
    }

    /** Headphones of any kind: wired, USB, Bluetooth (A2DP, LE audio, hearing aids). */
    fun headphonesConnected(): Boolean = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type in setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID,
        )
    }

    fun say(text: String) {
        if (!ready || !headphonesConnected()) return
        synchronized(this) {
            if (queued >= MAX_QUEUED) { tts.stop(); queued = 0 } // fallen behind: start again from the newest
            queued++
        }
        tts.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), "chat-${System.nanoTime()}")
    }

    fun shutdown() {
        runCatching { tts.stop(); tts.shutdown() }
    }

    private companion object {
        const val TAG = "Engine"
        const val MAX_QUEUED = 3
    }
}
