package app.irlsupreme.engine

import app.irlsupreme.engine.abr.DetailRungPolicy
import app.irlsupreme.engine.audio.ActiveInput
import app.irlsupreme.engine.audio.InputChoice
import app.irlsupreme.engine.audio.LowCut
import app.irlsupreme.engine.ingest.IngestHealth
import app.irlsupreme.engine.video.CaptureStats
import app.irlsupreme.engine.video.StabilizationMode

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
        /**
         * The server waits out reordering before it asks for a resend (the paired stay-live server does; a stock
         * srtla_rec or SLS doesn't). The bitrate control then reads a high resend rate as a reason to stop ramping.
         */
        val receiverReorderTolerant: Boolean = true,
        /** Mobile data only carries what Wi-Fi can't (see the link manager). Off: every link shares the load. */
        val cellularAsSecondary: Boolean = false,
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

/**
 * A bonding link, for the live screen's link icons and the session log; [kind] "cellular" | "wifi" | "ethernet" |
 * "helper" (a helper phone's mobile data, via Moblink).
 */
data class LinkInfo(
    val kind: String,
    val name: String,
    val up: Boolean,
    val kbps: Long,
    val rttMs: Int?,
    /** Helper links: the helper phone's battery and heat, when it reports them. */
    val batteryPercent: Int? = null,
    val hot: Boolean = false,
    /** How much this link is queueing (its send-to-ack delay above its own minimum), ms. */
    val queueMs: Int = 0,
    /** Losses the server reported on this link per second, over the engine's last ~5 s diagnostics window. */
    val nakRate: Double = 0.0,
    /** Its in-flight cap (packets) right now. */
    val capPackets: Int = 0,
    /** Packets pushed past its in-flight cap because every link was full, since the stream started. */
    val overCap: Long = 0,
    /** How long since the server last answered on this link, ms; null before it ever did. */
    val proofAgoMs: Long? = null,
    /** Packets this link's socket refused (lost before leaving the phone), since the stream started. */
    val localDrops: Long = 0,
    /** Its role in the bond: "primary", or a standby that only carries what the others can't. */
    val priority: String = "primary",
    /** Bytes sent on this link since the stream started (its data use). */
    val bytesSent: Long = 0,
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
    /**
     * Start in preview: the camera (or the Pocket), the mic, the cards and the encoder all run, but nothing is sent
     * until [StreamEngine.goLive]. The streamer checks the picture and the sound first, as in any camera app.
     */
    val startInPreview: Boolean = false,
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
    /** The phone mic's low cut ([LowCut.AUTO]: 80 Hz on the raw mic, off with the phone's auto level). */
    val lowCut: LowCut = LowCut.AUTO,
    /** The voice leveler on the raw mic (the "Voice" preset); off keeps the level where the fader puts it. */
    val voiceLeveler: Boolean = false,
    /** Fold the phone mic to mono; the stream stays stereo either way. */
    val monoVoice: Boolean = false,
    /** Which mic to record from. */
    val micInput: InputChoice = InputChoice.AUTO,
    /** The level for a USB or wired mic, dB; null: the same level as the phone's own mic. */
    val externalMicGainDb: Float? = null,
    /**
     * The user's own A/V offset, ns (+: the sound later), applied always; clamped to +-[MAX_AV_OFFSET_NS]. The sound
     * can only be held back, so a negative offset only takes effect on top of [autoLipSync].
     */
    val avOffsetNs: Long = 0,
    /**
     * Hold the sound back by the camera's measured delay (the phone camera's capture-to-draw latency, or the
     * external camera's presentation delay). Off by default until the sign and size are confirmed on phones; the
     * measured delay is reported either way ([EngineState.videoDelayMs]).
     */
    val autoLipSync: Boolean = false,
    /** The external camera's own capture-to-arrival delay, ns (from a clap test); 0 until it's calibrated. */
    val externalCaptureDelayNs: Long = 0,
    /** The phone camera's stabilisation, set before the camera opens; it changes the crop, so not mid-stream. */
    val stabilization: StabilizationMode = StabilizationMode.STANDARD,
    /**
     * Draw the camera softer (at 0.75 or 0.5 scale, upscaled) when the bitrate drops low ([DetailRungPolicy]). Off
     * until the downscale has been checked on phones' GPUs.
     */
    val detailRung: Boolean = false,
) {
    /** The user chose a portrait (vertical) stream: [width] < [height]. The live screen is locked to match. */
    val portrait: Boolean get() = height > width

    companion object {
        /** The A/V offset setting's range, ns (300 ms either way). */
        const val MAX_AV_OFFSET_NS = 300_000_000L
        /** The frame rates a stream can run at; 60 only where [app.irlsupreme.engine.video.CameraCapabilities] says so. */
        val FPS_CHOICES = listOf(30, 60)
        /** The audio bitrates on offer, fixed for a stream. */
        val AUDIO_BITRATES = listOf(128_000, 160_000)

        /**
         * The lowest video bitrate Auto quality may go to at a frame size: 1 Mbps for 1080-class (kept there until the
         * detail rung is proven on phones), 600 kbps for 720-class, 400 kbps below that.
         */
        fun minBitrateFor(width: Int, height: Int): Int {
            val short = minOf(width, height)
            return when {
                short >= 1_000 -> 1_000_000
                short >= 700 -> 600_000
                else -> 400_000
            }
        }
    }
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
    /** Running in preview ([StreamConfig.startInPreview]): nothing is sent yet; [StreamEngine.goLive] starts sending. */
    val preview: Boolean = false,
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
    /** Why the bitrate last changed, in the bitrate controller's own words (bonded or direct RTMP). */
    val abrAction: String = "",
    /** The send backlog as time, ms: the bonded links' traffic-weighted queueing, or the RTMP client's queue. */
    val queueMs: Int = 0,
    /** Direct RTMP: bits/s measured leaving the socket (0 for bonded). */
    val throughputBps: Long = 0,
    /** Bonded: link bytes per media byte, smoothed (0 for direct RTMP). */
    val overhead: Double = 0.0,
    /** Bonded: resend requests per packet sent over the last ~2 s. */
    val nakRate: Double = 0.0,
    /** Bonded: what the links have shown they can carry (each link's best recent delivery, summed), bits/s. */
    val capacityBps: Long = 0,
    /** Bonded, since the stream started: resend requests from the server, all links. */
    val naks: Long = 0,
    /** Bonded: packets pushed past every link's in-flight cap (all links were full). */
    val overCapSent: Long = 0,
    /** Bonded: packets with no link to leave on. */
    val droppedNoLink: Long = 0,
    /** Bonded: packets every link's socket refused (lost before leaving the phone). */
    val droppedLocal: Long = 0,
    /** Bonded: sends that failed with an error (a network gone). */
    val sendErrors: Long = 0,
    /** Bonded: frames the SRT client dropped from its own queue (it couldn't send them in time), video and audio. */
    val libraryDroppedFrames: Long = 0,
    /** Bonded: connected links the server has answered on lately (a link that only looks connected isn't counted). */
    val provenLinks: Int = 0,
    /** Bonded: packets from the SRT client the bonding sender couldn't take in (its socket buffer was full). */
    val clientSendDrops: Long = 0,
    /** Bonded: packets dropped while the links were still registering with the server. */
    val droppedDuringHandshake: Long = 0,
    /** Render ticks the thermal ladder dropped on purpose (0 now that it lowers the rate evenly). */
    val thermalSkips: Long = 0,
    /** Unexpected errors caught inside render ticks (each cost at most that tick). */
    val renderErrors: Int = 0,
    /** Android's 10 s thermal forecast (1.0: throttling starts), NaN when the phone doesn't offer one. */
    val thermalHeadroom: Float = Float.NaN,
    /** The phone's battery as the app last reported it ([StreamEngine.setBattery]): percent, -1 when unknown. */
    val phoneBatteryPct: Int = -1,
    /** Battery temperature, degrees C, NaN when unknown. */
    val batteryTempC: Float = Float.NaN,
    val batteryCharging: Boolean = false,
    /** The frame rate the stream runs at, after any fallback. */
    val fps: Int = 30,
    /** Why [fps] isn't the rate asked for, in plain words; empty when it is. */
    val fpsFallback: String = "",
    /** The camera's render scale for the bitrate (1, 0.75 or 0.5; see [StreamConfig.detailRung]). */
    val detailScale: Float = 1f,
    /** The video encoder, e.g. "c2.qti.avc.encoder High@4.1 CBR"; empty before it's up. */
    val encoder: String = "",
    /** Frames per second the encoder put out over the last second. */
    val outputFps: Double = 0.0,
    /** Bytes of the most recent keyframe, and the longest gap between keyframes so far (ms of media time). */
    val lastIdrBytes: Int = 0,
    val maxIdrIntervalMs: Long = 0,
    /**
     * The on-screen camera's measured delay, ms: what automatic lip sync holds the sound back by
     * ([StreamConfig.autoLipSync]). Reported whether or not that's on; 0 while unknown.
     */
    val videoDelayMs: Int = 0,
    /** The delay applied to the sound right now, ms (automatic lip sync plus the user's offset, never below 0). */
    val avDelayMs: Int = 0,
    /** Frame timing: counters since the stream started, timings over the last 5 s window. */
    val cadence: CadenceCounters = CadenceCounters(),
    /** The external camera's clock (anchoring, warm-ups, backlog flushes); null without one. */
    val ingest: IngestHealth? = null,
    /** The mic the recording is really using; null before one opens. */
    val micInput: ActiveInput? = null,
    /** What the phone camera last reported doing (stabilisation, real frame rate, exposure); null when unknown. */
    val captureStats: CaptureStats? = null,
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
    /** Samples of the final mix at full scale: should stay 0 (the limiter covers alert sounds too). */
    val postMixClips: Long = 0,
    /** Chunks whose voice arrived clipped (in the phone or camera, before any gain), and the clipped samples in them. */
    val inputClipChunks: Long = 0,
    val inputClips: Long = 0,
    /** Chunks of silence encoded to close a gap in the mic's timeline. */
    val gapFillChunks: Int = 0,
    /** Chunks timed without an AudioRecord timestamp (extrapolated). */
    val fallbackChunks: Long = 0,
    /** RTMP camera audio frames the decoder dropped or refused. */
    val cameraDecoderDrops: Int = 0,
    /** The RTMP camera's audio sample rate as decoded (0 before any). */
    val cameraRate: Int = 0,
    /** The voice leveler's gain now, dB (0 when it's off). */
    val levelerDb: Float = 0f,
    /** The AAC encoder in use, and the mic the recording uses; empty before they're known. */
    val aacEncoder: String = "",
    val activeInput: String = "",
)

/**
 * The compositor's frame timing for the session log, flattened from its 5 s window: counters run from the start of the
 * stream, timings cover the last window. Latencies are NaN while unknown.
 */
data class CadenceCounters(
    /** Phone camera: ticks that showed the same frame again while live, gaps in its frames, frames replaced unseen. */
    val phoneRepeatTicks: Long = 0,
    val phoneSourceGaps: Long = 0,
    val phoneOverwritten: Long = 0,
    /** External camera: the same three (0 without one). */
    val externalRepeatTicks: Long = 0,
    val externalSourceGaps: Long = 0,
    val externalOverwritten: Long = 0,
    /** External camera frames dropped at the decoder for being more than a frame late. */
    val externalLateDrops: Long = 0,
    /** Render work per tick, ms: median, 99th percentile and worst. */
    val tickP50Ms: Float = 0f,
    val tickP99Ms: Float = 0f,
    val tickMaxMs: Float = 0f,
    /** Ticks whose work took more than 0.8 of a period, and ticks the render thread fell behind on. */
    val lateTicks: Long = 0,
    val skippedTicks: Long = 0,
    /** The rate the compositor runs at now, and whether its clock follows the on-air camera's frames. */
    val outputFps: Float = 0f,
    val phaseLocked: Boolean = false,
    /** Phone camera capture-to-tick latency, ms (median, 95th percentile). */
    val cameraLatencyP50Ms: Float = Float.NaN,
    val cameraLatencyP95Ms: Float = Float.NaN,
)

/**
 * Something the session log should record the moment it happens (not in the next snapshot), from
 * [StreamEngine.events]. [line] is plain words for the log.
 */
sealed interface EngineEvent {
    val line: String

    /** A bonding link came up, went down (with the reason), or the server forgot the group. */
    data class Link(override val line: String) : EngineEvent

    /** The bitrate target changed, with the controller's reason; at most one a second. */
    data class Bitrate(override val line: String) : EngineEvent

    /**
     * The mic in use changed. [message] is for the streamer when it changed kind ("External mic disconnected"),
     * else null.
     */
    data class MicInput(val input: ActiveInput, val message: String?) : EngineEvent {
        override val line: String get() = "mic input ${input.name}" + (message?.let { ": $it" } ?: "")
    }

    /** Anything else worth a line: a frame-rate fallback, a start seed, a resume, a detail or heat change. */
    data class Note(override val line: String) : EngineEvent
}

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
    /** Time spent at each thermal rung (index 0..3), ms; empty if the stream never ran. */
    val thermalRungMs: List<Long> = emptyList(),
    /** The phone's battery when the stream started and ended, percent; -1 when the app didn't report it. */
    val batteryStartPct: Int = -1,
    val batteryEndPct: Int = -1,
    /** How long the battery readings span, ms (0 without two readings). */
    val batterySpanMs: Long = 0,
) {
    val liveDurationMs: Long get() = if (liveSinceMs == 0L) 0 else endedAtMs - liveSinceMs

    /** Battery used per hour, percent; null without two readings at least ten minutes apart. */
    val batteryPctPerHour: Double?
        get() = if (batteryStartPct < 0 || batteryEndPct < 0 || batterySpanMs < 600_000) null
        else (batteryStartPct - batteryEndPct) * 3_600_000.0 / batterySpanMs
}
