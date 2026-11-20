package app.irlsupreme.engine

/** Where the stream goes. */
sealed interface OutputTarget {
    /** Direct to Twitch (or any RTMP server). The URL contains the stream key and is never logged. */
    class Rtmp(val url: String) : OutputTarget {
        override fun toString() = "Rtmp(<redacted>)"
    }

    /**
     * "Combine connections": SRT over SRTLA to a BRB server (srtla_rec) at [host]:[port]. [passphrase] encrypts the
     * SRT streams (AES-128); a paired server requires it. Never logged.
     */
    data class Bonded(
        val host: String,
        val port: Int,
        val streamId: String = "live",
        val latencyMs: Int = 2_000,
        val passphrase: String? = null,
        /** Helper phones (Moblink) may join this stream's bonding; null: helpers off. */
        val helpers: HelperHosting? = null,
    ) : OutputTarget {
        override fun toString() = "Bonded($host:$port, $streamId, ${latencyMs}ms, encrypted=${passphrase != null}, helpers=${helpers != null})"
    }
}

/** Where the picture comes from. */
sealed interface CameraInput {
    /** The phone's back camera and mic. */
    data object Phone : CameraInput

    /**
     * A camera streaming to the phone over RTMP (DJI Pocket 3, or any RTMP encoder) at rtmp://<phone>:[port]/[path].
     * Its audio replaces the mic while it flows; the phone camera and mic take over when it drops.
     */
    data class Rtmp(val port: Int = 1935, val path: String) : CameraInput
}

/** One bonding link for the live screen's link icons. */
/** A bonding link; [kind] "cellular" | "wifi" | "ethernet" | "helper" (a helper phone's mobile data, via Moblink). */
data class LinkInfo(
    val kind: String,
    val name: String,
    val up: Boolean,
    val kbps: Long,
    val rttMs: Int?,
    /** Helper links: the helper phone's battery and heat, when it reports them. */
    val batteryPercent: Int? = null,
    val hot: Boolean = false,
)

/**
 * How this phone hosts helper phones (Moblink streamer side): helpers on the same network (this phone's hotspot, a
 * travel router) find it over mDNS as [displayName], prove they know [password], and lend their mobile data.
 */
data class HelperHosting(val password: String, val displayName: String, val serviceId: String, val port: Int = 7777) {
    override fun toString() = "HelperHosting($displayName, port $port)"
}

/** What to stream and how. Resolution never changes mid-stream; bitrate adapts within [minVideoBitrate]..[videoBitrate]. */
data class StreamConfig(
    val target: OutputTarget,
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val videoBitrate: Int = 6_000_000,
    val minVideoBitrate: Int = 1_000_000,
    val keyframeIntervalSec: Int = 2,
    val audioSampleRate: Int = 48_000,
    val audioStereo: Boolean = true,
    val audioBitrate: Int = 128_000,
    /** The user's mic level, -20..+30 dB (Settings › Microphone, or the live screen's mic sheet). */
    val micGainDb: Float = 0f,
    /** The phone's own auto level off: the raw mic source ([app.irlsupreme.engine.audio.micSources]). */
    val rawMic: Boolean = false,
    /** The level used while the mic is raw: a raw mic is ~28 dB quieter on a OnePlus 12, so it has its own. */
    val rawMicGainDb: Float = 20f,
    val camera: CameraInput = CameraInput.Phone,
    /** Switch to the Weak signal screen by itself when even the lowest quality can't get through. */
    val autoWeakSignal: Boolean = true,
    /**
     * The Twitch login, printed in the footer of every scene card so a clip of the card still says whose
     * stream it was. Empty leaves the footer's left side blank.
     */
    val channelLogin: String = "",
) {
    /** The user chose a portrait (vertical) stream: [width] < [height]. The live screen is locked to match. */
    val portrait: Boolean get() = height > width
}

/**
 * Scenes are what viewers see; switching never rebuilds the pipeline. Every scene but [LIVE] is a still card drawn on
 * the phone (the camera isn't sent). The automatic Weak signal screen isn't one of these: it stands in for [LIVE] only
 * ([EngineState.weakSignal]).
 */
enum class Scene { LIVE, BRB, PRIVACY, STARTING_SOON, ENDING }

/** The mic's real state: [UNAVAILABLE] couldn't open, [FAILING] stopped delivering, [SILENCED] delivers only zeros. */
enum class AudioInput { OK, UNAVAILABLE, FAILING, SILENCED }

enum class Facing { BACK, FRONT }

/**
 * [CONNECTING] covers every try before the stream first reached the server (with [EngineState.reconnectAttempt]
 * counting failed tries); [RECONNECTING] only ever follows [LIVE].
 */
enum class ConnectionPhase { IDLE, CONNECTING, LIVE, RECONNECTING, FAILED }

/** The stream couldn't start: the pipeline couldn't be built on this phone. [message] is plain words for the user. */
class EngineStartException(message: String, cause: Throwable? = null) : Exception(message, cause)

enum class CameraPhase { OFF, OPENING, LIVE, LOST }

/** One snapshot of everything the UI needs. Published ~2x per second. */
data class EngineState(
    val running: Boolean = false,
    /** The running stream is portrait (the live screen locks to portrait); false for landscape. */
    val portrait: Boolean = false,
    val connection: ConnectionPhase = ConnectionPhase.IDLE,
    val reconnectAttempt: Int = 0,
    val camera: CameraPhase = CameraPhase.OFF,
    val scene: Scene = Scene.LIVE,
    /** Viewers see the Weak signal screen instead of Live: the connection can't carry even the lowest quality. */
    val weakSignal: Boolean = false,
    /** Whether the mic is actually delivering audio (not the user's mute: that's [micMuted]). */
    val audioInput: AudioInput = AudioInput.OK,
    /**
     * How far the thermal ladder has stepped down (0 = not at all): 1 caps the bitrate; 2 also drops to ~24 fps, slows
     * overlay redraws and turns stabilisation off; 3 also closes the web overlay and pauses the preview.
     */
    val thermalRung: Int = 0,
    /** The preview is paused to save heat (the stream itself carries on). */
    val previewPaused: Boolean = false,
    /** The phone camera in use (the back one if the phone has no front camera). */
    val cameraFacing: Facing = Facing.BACK,
    /** The phone camera's zoom ratio (1 = no zoom). */
    val zoomRatio: Float = 1f,
    val micMuted: Boolean = false,
    /** Wall-clock ms when the stream first went live (for the timer), or 0. */
    val liveSinceMs: Long = 0,
    /** Encoder target and what the encoder actually produced over the last ~2 s (they differ: CBR undershoots on simple scenes). */
    val targetVideoBitrate: Int = 0,
    /**
     * The bitrate the stream would use on a clear connection: the chosen quality, capped by the thermal ladder. Weak
     * signal means the target fell well below this (the measured rate undershoots on simple scenes, so it can't say).
     */
    val qualityCeiling: Int = 0,
    val measuredVideoBitrate: Long = 0,
    /** Bytes/s actually leaving through RTMP. */
    val sentBitrate: Long = 0,
    val congested: Boolean = false,
    /** PowerManager.THERMAL_STATUS_* */
    val thermalStatus: Int = 0,
    val encoderRecoveries: Int = 0,
    /**
     * Frames viewers missed since the stream first went live: encoded frames that couldn't be sent while reconnecting,
     * plus render ticks the phone fell behind on. Connecting time and deliberate heat-saving skips don't count.
     */
    val droppedFrames: Long = 0,
    val lastError: String? = null,
    /** What's on screen: "camera" (RTMP camera), "phone", or "none" (slate). */
    val activeSource: String = "phone",
    /** RTMP camera: connected, its decoded size, and whether its audio is in use. */
    val externalCamera: String? = null,
    /** Bonding links (empty when streaming directly over RTMP). */
    val links: List<LinkInfo> = emptyList(),
    /** What could make the audio crackle or drop out, counted since the stream started (for the session log). */
    val audio: AudioCounters = AudioCounters(),
) {
    /** The single health indicator the live screen shows. */
    val health: Health
        get() = when {
            !running -> Health.OFF
            connection == ConnectionPhase.FAILED -> Health.FAILED
            // IDLE: the output is still waiting for the first encoded frames; nothing has reached viewers yet.
            connection == ConnectionPhase.IDLE || connection == ConnectionPhase.CONNECTING -> Health.CONNECTING
            connection == ConnectionPhase.RECONNECTING -> Health.RECONNECTING
            scene != Scene.LIVE -> Health.BRB
            weakSignal -> Health.WEAK_SCREEN
            // "none": viewers see the camera-lost card, whatever state the camera itself reports.
            camera == CameraPhase.LOST || activeSource == "none" -> Health.CAMERA_LOST
            // Rung 1 (Android's "moderate") only trims the bitrate to 85%, which Android itself calls invisible; a OnePlus
            // reaches it in ~12 min at 32 °C. Saying "Phone is hot" in amber for that would read as trouble for most of
            // a long stream, so the pill waits for "severe", as Home's preflight does.
            thermalRung >= 2 -> Health.HOT
            // Not measured < target: CBR undershoots on simple scenes and the 2 s window lags behind a card.
            congested || (qualityCeiling > 0 && targetVideoBitrate < qualityCeiling / 2) -> Health.WEAK
            else -> Health.GREAT
        }
}

/**
 * The audio path's health, counted since the stream started, so a session log can say why it sounded the way it did.
 * Counts only go up; the camera's buffer, rate and sync are the latest values.
 */
data class AudioCounters(
    /** Times the phone's mic lost audio because capture fell behind (an AudioRecord overrun: a click or a gap). */
    val micOverruns: Int = 0,
    /** Chunks (~21 ms each) of the final mix that never reached the encoder (restarting, or stuck full). */
    val encoderDrops: Int = 0,
    /** Chunks processed, and those where the limiter took off more than 6 dB (many: the mic level is too hot). */
    val chunks: Int = 0,
    val heavyLimitChunks: Int = 0,
    /** RTMP camera audio: times it ran dry (each a short faded silence). */
    val cameraUnderruns: Int = 0,
    /** RTMP camera audio: lost frames filled with faded silence of the same length. */
    val cameraGapFills: Int = 0,
    /** RTMP camera audio: times it aligned to the picture again after a jump. */
    val cameraResyncs: Int = 0,
    /** RTMP camera audio in hand (ms), its drift-correcting resample (ppm), and its offset from the picture (ms, +: early). */
    val cameraBufferMs: Int = 0,
    val cameraRatePpm: Int = 0,
    val cameraSyncMs: Int = 0,
)

/** [BRB]: any scene but Live (BRB, Privacy, Starting soon, Ending). [WEAK_SCREEN]: the automatic Weak signal screen. */
enum class Health { OFF, CONNECTING, GREAT, WEAK, WEAK_SCREEN, HOT, CAMERA_LOST, BRB, RECONNECTING, FAILED }

/** What happened in one stream, for the end-of-stream recap. */
data class SessionSummary(
    val startedAtMs: Long,
    val endedAtMs: Long,
    /** 0 if the stream never reached the server. */
    val liveSinceMs: Long,
    val recoveredDrops: Int,
    val encoderRecoveries: Int,
    val bytesSent: Long,
    val peakThermalStatus: Int,
    val lowestBitrate: Int,
    val lastError: String?,
) {
    val liveDurationMs: Long get() = if (liveSinceMs == 0L) 0 else endedAtMs - liveSinceMs
}
