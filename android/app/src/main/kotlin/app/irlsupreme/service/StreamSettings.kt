package app.irlsupreme.service

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.wifi.WifiManager
import androidx.core.content.edit
import app.irlsupreme.engine.CameraInput
import app.irlsupreme.engine.OutputTarget
import app.irlsupreme.engine.StreamConfig

/**
 * How far behind live a combined-connections stream runs: the SRT buffer that rides out drops. The bitrate control
 * follows it (its thresholds scale with the buffer). A 1 s "Fast" choice stays off the list until paired servers run
 * with a 1 s floor of their own.
 */
enum class StreamDelay(val latencyMs: Int, val title: String, val detail: String) {
    BALANCED(2_000, "Balanced", "About 2 s behind. Rides out most drops."),
    ROCK_SOLID(4_000, "Rock solid", "About 4 s behind. Rides out longer drops on a weak signal."),
}

/** The stream's sound quality, fixed for a whole stream. */
enum class AudioQuality(val bitrate: Int, val title: String, val detail: String) {
    STANDARD(128_000, "Standard", "128 kb/s"),
    HIGH(160_000, "High", "160 kb/s, the most Twitch takes"),
}

/**
 * The stream settings past quality and shape: frame rate, delay, sound quality and the sound's offset from the
 * picture. Stored with the rest of the settings; read by the settings pages and by [StreamService] at go-live.
 */
object StreamSettings {
    private const val KEY_FPS = "stream_fps"
    private const val KEY_DELAY = "stream_delay"
    private const val KEY_AUDIO = "audio_quality"
    private const val KEY_AV_OFFSET = "av_offset_ms"
    /** Set only by the debug build's control receiver: automatic lip sync is unproven on phones. */
    const val KEY_AUTO_LIP_SYNC = "debug_auto_lip_sync"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun fps(ctx: Context): Int = prefs(ctx).getInt(KEY_FPS, 30).takeIf { it in StreamConfig.FPS_CHOICES } ?: 30
    fun setFps(ctx: Context, fps: Int) = prefs(ctx).edit { putInt(KEY_FPS, fps) }

    fun delay(ctx: Context): StreamDelay = MicSettings.enumOr(prefs(ctx).getString(KEY_DELAY, null), StreamDelay.BALANCED)
    fun setDelay(ctx: Context, d: StreamDelay) = prefs(ctx).edit { putString(KEY_DELAY, d.name) }

    fun audio(ctx: Context): AudioQuality = MicSettings.enumOr(prefs(ctx).getString(KEY_AUDIO, null), AudioQuality.STANDARD)
    fun setAudio(ctx: Context, a: AudioQuality) = prefs(ctx).edit { putString(KEY_AUDIO, a.name) }

    fun avOffsetMs(ctx: Context): Int = StreamRules.avOffsetMs(prefs(ctx).getInt(KEY_AV_OFFSET, 0))
    fun setAvOffsetMs(ctx: Context, ms: Int) = prefs(ctx).edit { putInt(KEY_AV_OFFSET, StreamRules.avOffsetMs(ms)) }

    /** Automatic lip sync: debug builds only, and only when the debug control turned it on. */
    fun autoLipSync(ctx: Context): Boolean =
        (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 && prefs(ctx).getBoolean(KEY_AUTO_LIP_SYNC, false)
}

/** What a stream is set up with, from the settings, before it becomes a [StreamConfig]. */
internal data class StreamChoices(
    val width: Int,
    val height: Int,
    val bitrate: Int,
    /** The frame rate asked for (EXTRA_FPS); [StreamRules.fps] decides what runs. */
    val fps: Int = 30,
    val camera: CameraInput = CameraInput.Phone,
    val autoWeakSignal: Boolean = true,
    val mic: MicPrefs = MicPrefs(),
    val audio: AudioQuality = AudioQuality.STANDARD,
    val avOffsetMs: Int = 0,
    val autoLipSync: Boolean = false,
    val channelLogin: String = "",
)

/** The stream service's decisions about settings, pure so they can be tested. */
internal object StreamRules {
    /** The A/V offset setting's range and step, ms. */
    const val AV_OFFSET_MAX_MS = (StreamConfig.MAX_AV_OFFSET_NS / 1_000_000).toInt()
    const val AV_OFFSET_STEP_MS = 10

    /** An A/V offset in range, on the 10 ms grid. */
    fun avOffsetMs(ms: Int): Int {
        val c = ms.coerceIn(-AV_OFFSET_MAX_MS, AV_OFFSET_MAX_MS)
        return Math.round(c / AV_OFFSET_STEP_MS.toFloat()) * AV_OFFSET_STEP_MS
    }

    /**
     * The frame rate to ask the engine for: 60 only when it was asked for, the phone camera is the camera, and
     * [supported] (the camera and encoder can carry it at this size) says so; anything else is 30. [supported] is
     * only called when it matters (it reads camera characteristics).
     */
    fun fps(requested: Int, phoneCamera: Boolean, supported: () -> Boolean): Int =
        if (requested == 60 && phoneCamera && supported()) 60 else 30

    /** The lowest bitrate Auto quality may go to for this frame size (1 Mbps stays the floor for 1080p). */
    fun minBitrate(width: Int, height: Int): Int = StreamConfig.minBitrateFor(width, height)

    /** A combined-connections target with the chosen delay as its SRT latency. */
    fun bonded(host: String, port: Int, streamId: String, passphrase: String?, delay: StreamDelay, helpers: app.irlsupreme.engine.HelperHosting?) =
        OutputTarget.Bonded(host, port, streamId, latencyMs = delay.latencyMs, passphrase = passphrase, helpers = helpers)

    /**
     * The stream's config from what the streamer chose. The mic options go to the stream as the Microphone page shows
     * them ([STREAM_USES_MIC_OPTIONS]), so the page's check, the live sheet and the stream sound the same.
     */
    fun config(target: OutputTarget, c: StreamChoices, fps: Int): StreamConfig {
        val mic = c.mic
        val options = STREAM_USES_MIC_OPTIONS
        return StreamConfig(
            target = target,
            width = c.width,
            height = c.height,
            fps = fps,
            videoBitrate = c.bitrate,
            minVideoBitrate = minBitrate(c.width, c.height).coerceAtMost(c.bitrate),
            audioBitrate = c.audio.bitrate,
            camera = c.camera,
            autoWeakSignal = c.autoWeakSignal,
            micGainDb = mic.gainDb.toFloat(),
            rawMic = mic.raw,
            rawMicGainDb = mic.rawGainDb.toFloat(),
            lowCut = if (options) mic.lowCut else app.irlsupreme.engine.audio.LowCut.AUTO,
            voiceLeveler = options && mic.voiceLeveler,
            monoVoice = options && mic.monoVoice,
            micInput = if (options) mic.input else app.irlsupreme.engine.audio.InputChoice.AUTO,
            externalMicGainDb = if (options) mic.externalGainDb.toFloat() else null,
            avOffsetNs = avOffsetMs(c.avOffsetMs) * 1_000_000L,
            autoLipSync = c.autoLipSync,
            channelLogin = c.channelLogin,
        )
    }
}

/**
 * The stream's two Wi-Fi locks, always taken and let go together. LOW_LATENCY only acts while the screen is on and
 * the app is in front; HIGH_PERF keeps Wi-Fi out of power save with the screen off (Android 12 and 13; later versions
 * ignore it, harmlessly). Not reference counted: acquiring twice holds once.
 */
internal class WifiLocks(private val create: (mode: Int, tag: String) -> Lock) {
    /** One Android Wi-Fi lock (a fake in tests). */
    interface Lock {
        val isHeld: Boolean
        fun acquire()
        fun release()
    }

    private var held: List<Lock> = emptyList()

    val isHeld: Boolean get() = held.isNotEmpty()

    @Suppress("DEPRECATION") // HIGH_PERF: deprecated on 34+, still the only lock that works with the screen off on 31-33
    fun acquire(tag: String) {
        if (held.isNotEmpty()) return
        held = listOf(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WifiManager.WIFI_MODE_FULL_HIGH_PERF).mapNotNull { mode ->
            runCatching { create(mode, tag).also { it.acquire() } }.getOrNull()
        }
    }

    fun release() {
        held.forEach { l -> runCatching { if (l.isHeld) l.release() } }
        held = emptyList()
    }

    companion object {
        /** The real locks, from [WifiManager]. */
        fun of(wifi: WifiManager) = WifiLocks { mode, tag ->
            val l = wifi.createWifiLock(mode, tag).apply { setReferenceCounted(false) }
            object : Lock {
                override val isHeld get() = l.isHeld
                override fun acquire() = l.acquire()
                override fun release() = l.release()
            }
        }
    }
}
