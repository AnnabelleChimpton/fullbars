package app.irlsupreme.service

import app.irlsupreme.engine.EngineEvent
import app.irlsupreme.engine.EngineState
import app.irlsupreme.engine.LinkInfo
import app.irlsupreme.engine.OutputTarget
import app.irlsupreme.engine.SessionSummary
import app.irlsupreme.engine.StreamConfig
import app.irlsupreme.engine.audio.MicLevel
import java.io.File
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * The phone's own record of one stream: plain text, one timestamped line per event, in
 * `files/logs/session-<yyyyMMdd-HHmmss>.log` (local time). Read it with
 *
 *     adb shell run-as app.irlsupreme ls files/logs
 *     adb shell run-as app.irlsupreme cat files/logs/session-20261119-162503.log
 *
 * Why it exists: logcat is a small ring buffer the whole phone shares. After the first real 68-minute stream it had
 * rotated before anyone looked, and the phone's side of two Twitch interruptions was gone. This file outlives that, a
 * crash (every line is flushed as it's written) and the next few streams (the newest [KEEP] are kept, each capped at
 * about [MAX_BYTES]).
 *
 * It never holds a secret. Callers pass only non-secret facts, and every line also goes through [SessionLogText.scrub],
 * which cuts URLs down to scheme and host and blanks anything shaped like a stream key or token.
 *
 * Lines are written in order on the log's own thread, so any thread (the main one included) can log without touching
 * the disk. A log that can't write (disk full, no permission) reports through [onError] once and stops trying; the
 * stream never notices.
 */
class SessionLog internal constructor(
    private val dir: File,
    /** When the stream started (wall clock); names the file. */
    val startMs: Long,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val maxBytes: Long = MAX_BYTES,
    private val keep: Int = KEEP,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onError: (Throwable) -> Unit = {},
) {
    private val io: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "session-log").apply { isDaemon = true } }

    // Log thread only.
    private var out: Writer? = null
    private var written = 0L
    private var full = false
    private var failed = false

    /** The file this stream writes to, once it's open (null if it couldn't be). */
    @Volatile var file: File? = null
        private set
    @Volatile private var closed = false

    init {
        submit { open() }
    }

    /** One line. Dropped once the file reaches its cap. */
    fun line(text: String) = write(text, essential = false)

    /** A line that matters for reading the rest (the start and the end): still written into a full file. */
    fun essential(text: String) = write(text, essential = true)

    /** Writes what's queued, then closes the file. Later lines are ignored. */
    fun close() {
        if (closed) return
        closed = true
        submit {
            runCatching { out?.close() }
            out = null
        }
        io.shutdown()
    }

    /** Waits until everything queued so far is on disk (tests). */
    internal fun awaitWritten(timeoutMs: Long = 5_000) {
        if (closed) io.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS) else runCatching { io.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    private fun write(text: String, essential: Boolean) {
        if (closed) return
        // Stamped now, not when the log thread gets to it, so the times are the events' own.
        val stamped = SessionLogText.timestamp(clock(), zone) + " " + SessionLogText.scrub(text) + "\n"
        submit { append(stamped, essential) }
    }

    private fun submit(work: () -> Unit) {
        try {
            io.execute(work)
        } catch (_: RejectedExecutionException) {
            // Closed meanwhile: the line is simply not written.
        }
    }

    private fun open() {
        try {
            dir.mkdirs()
            prune(dir, keep - 1)
            val f = uniqueFile(dir, SessionLogText.fileName(startMs, zone))
            out = f.outputStream().bufferedWriter(Charsets.UTF_8)
            file = f
        } catch (t: Throwable) {
            fail(t)
            return
        }
        append(SessionLogText.timestamp(clock(), zone) + " " + SessionLogText.header(startMs, zone) + "\n", essential = true)
    }

    private fun append(line: String, essential: Boolean) {
        val w = out ?: return
        val bytes = line.toByteArray(Charsets.UTF_8).size.toLong()
        if (!full && written + bytes > maxBytes - ESSENTIAL_RESERVE) {
            full = true
            val note = SessionLogText.timestamp(clock(), zone) +
                " log full (${maxBytes / 1_000_000} MB): snapshots stop here, the end is still written\n"
            if (!put(w, note)) return
        }
        if (full && !essential) return
        if (written + bytes > maxBytes) return // even the reserve is used up
        put(w, line)
    }

    /** Writes and flushes [s] so a crash a moment later keeps it. */
    private fun put(w: Writer, s: String): Boolean = try {
        w.write(s)
        w.flush()
        written += s.toByteArray(Charsets.UTF_8).size
        true
    } catch (t: Throwable) {
        fail(t)
        false
    }

    private fun fail(t: Throwable) {
        runCatching { out?.close() }
        out = null
        if (!failed) {
            failed = true
            onError(t)
        }
    }

    companion object {
        /** Under the app's files dir: `files/logs`. */
        const val DIR = "logs"
        const val KEEP = 5
        const val MAX_BYTES = 5_000_000L
        /** Kept free for the end of the stream once snapshots have filled the file. */
        private const val ESSENTIAL_RESERVE = 64_000L
        private val NAME = Regex("""session-\d{8}-\d{6}(-\d+)?\.log""")

        /** The folder every session log goes in, for [android.content.Context.getFilesDir]. */
        fun dir(filesDir: File) = File(filesDir, DIR)

        /** Deletes the oldest session logs in [dir] until at most [keep] are left (names sort by start time). */
        internal fun prune(dir: File, keep: Int) {
            val logs = dir.listFiles { f -> f.isFile && NAME.matches(f.name) }?.sortedByDescending { it.name } ?: return
            logs.drop(keep.coerceAtLeast(0)).forEach { it.delete() }
        }

        /** [name] in [dir], or name-2, name-3… when two streams started within the same second. */
        private fun uniqueFile(dir: File, name: String): File {
            var f = File(dir, name)
            var n = 2
            while (f.exists()) f = File(dir, name.removeSuffix(".log") + "-${n++}.log")
            return f
        }

        /**
         * Adds a line to the log of the stream that started at [startMs], if that file is still there: for what's only
         * known later, such as Android's reason for ending the app mid-stream (read on the next launch). Blocking.
         */
        fun appendLater(filesDir: File, startMs: Long, text: String, zone: ZoneId = ZoneId.systemDefault()) {
            val f = File(dir(filesDir), SessionLogText.fileName(startMs, zone))
            if (!f.isFile || f.length() > MAX_BYTES) return
            runCatching {
                f.appendText(SessionLogText.timestamp(System.currentTimeMillis(), zone) + " " + SessionLogText.scrub(text) + "\n")
            }
        }
    }
}

/** What the stream was set up as, for the log's first lines. Only what's safe to write down: no keys, no URLs. */
data class SessionStart(
    val config: StreamConfig,
    val test: Boolean,
    val cameraKind: String,
    /** The Pocket's network and bitrate, or null for other cameras. */
    val pocket: String?,
    val startFacing: String,
    val mic: MicPrefs,
    val app: String,
    val device: String,
    /** The frame rate the streamer asked for (the config holds what the engine is asked to run). */
    val requestedFps: Int = config.fps,
)

/** The log's words, kept apart from the file handling so they can be tested. */
internal object SessionLogText {
    private val STAMP = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.ROOT)
    private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
    private val FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx", Locale.ROOT)

    private val URL = Regex("""(?i)\b([a-z][a-z0-9+.-]*)://([^\s/?#]*)(\S*)""")
    private val BEARER = Regex("""(?i)\bbearer\s+\S+""")
    private val TWITCH_KEY = Regex("""\blive_\d+_\w+""")
    private val LONG_TOKEN = Regex("""[A-Za-z0-9_+/=-]{32,}""")

    /** Logcat-style local time, so lines line up with a logcat or the server's log by eye. */
    fun timestamp(ms: Long, zone: ZoneId): String = STAMP.format(Instant.ofEpochMilli(ms).atZone(zone))

    fun fileName(startMs: Long, zone: ZoneId): String = "session-" + FILE_STAMP.format(Instant.ofEpochMilli(startMs).atZone(zone)) + ".log"

    /**
     * The last guard against a secret reaching the file: URLs keep only their scheme and host (an RTMP URL's path is the
     * stream key, a pairing link's query is the token), and bearer tokens, Twitch keys and any long key-like run of
     * characters are blanked. Also keeps every entry on one line.
     */
    fun scrub(s: String): String {
        var t = URL.replace(s) { m ->
            val host = m.groupValues[2].substringAfterLast('@') // no user:password@
            m.groupValues[1] + "://" + host + if (m.groupValues[3].isNotEmpty()) "/<redacted>" else ""
        }
        t = BEARER.replace(t, "Bearer <redacted>")
        t = TWITCH_KEY.replace(t, "<key>")
        t = LONG_TOKEN.replace(t, "<redacted>")
        return t.replace('\n', ' ').replace('\r', ' ')
    }

    fun header(startMs: Long, zone: ZoneId): String =
        "Fullbars session log, started ${FULL.format(Instant.ofEpochMilli(startMs).atZone(zone))} ($zone)"

    fun startLines(s: SessionStart): List<String> {
        val c = s.config
        val target = when (val t = c.target) {
            is OutputTarget.Bonded -> "Combine connections to your BRB server (port ${t.port}), SRT latency ${t.latencyMs} ms, " +
                "encrypted ${yes(t.passphrase != null)}, helper phones ${onOff(t.helpers != null)}"
            is OutputTarget.Rtmp -> "direct RTMP to ${rtmpHost(t.url)}"
        }
        val shape = "${c.width}x${c.height} ${if (c.portrait) "portrait" else "landscape"} ${c.fps} fps, " +
            "quality ${kb(c.videoBitrate.toLong())} (floor ${kb(c.minVideoBitrate.toLong())}), keyframe ${c.keyframeIntervalSec} s, " +
            "audio ${c.audioSampleRate} Hz ${if (c.audioStereo) "stereo" else "mono"} ${kb(c.audioBitrate.toLong())}"
        val mic = if (s.mic.raw) "phone auto level off (raw), level ${gainText(s.mic.rawGainDb)}" else "phone auto level on, level ${gainText(s.mic.gainDb)}"
        val fpsAsked = if (s.requestedFps != c.fps) " (asked for ${s.requestedFps}, not offered here)" else ""
        return listOf(
            "start: ${if (s.test) "TEST stream (bandwidth test, nobody watching)" else "LIVE stream"}",
            "target: $target",
            "shape: $shape",
            "camera: ${s.cameraKind}${s.pocket?.let { ", $it" } ?: ""}, phone camera starts ${s.startFacing}",
            "mic: $mic (other mode ${gainText(if (s.mic.raw) s.mic.gainDb else s.mic.rawGainDb)})",
            "mic options: input ${c.micInput.name.lowercase()}, plugged-in mic level ${c.externalMicGainDb?.let { gainText(it.toInt()) } ?: "same as phone"}, " +
                "low cut ${c.lowCut.name.lowercase()}, leveler ${onOff(c.voiceLeveler)}, mono voice ${onOff(c.monoVoice)}",
            "frame rate: ${c.fps} fps$fpsAsked; lip sync: offset ${signedMs(c.avOffsetNs / 1_000_000)}, automatic ${onOff(c.autoLipSync)}",
            "weak signal screen: ${onOff(c.autoWeakSignal)}",
            "app ${s.app}, ${s.device}",
        )
    }

    /** Only the host of an RTMP URL: its path holds the stream key. */
    private fun rtmpHost(url: String): String = runCatching { java.net.URI(url).host }.getOrNull() ?: "an RTMP server"

    /** The every-5-seconds line: everything the live screen knows, plus the mic over the last window. */
    fun snapshot(st: EngineState, mic: String?, alerts: Int, screenOn: Boolean? = null, pocketScreen: Boolean = false): String = buildString {
        append("snap health=").append(st.health)
        append(" conn=").append(st.connection)
        if (st.reconnectAttempt > 0) append("#").append(st.reconnectAttempt)
        append(" scene=").append(st.scene)
        append(" weak=").append(yes(st.weakSignal))
        append(" target=").append(kb(st.targetVideoBitrate.toLong()))
        append(" ceiling=").append(kb(st.qualityCeiling.toLong()))
        append(" encoded=").append(kb(st.measuredVideoBitrate))
        append(" sent=").append(kb(st.sentBitrate))
        append(" congested=").append(yes(st.congested))
        append(" dropped=").append(st.droppedFrames)
        append(" encRecoveries=").append(st.encoderRecoveries)
        append(" heat=").append(st.thermalStatus).append("/rung").append(st.thermalRung)
        if (st.previewPaused) append(" previewPaused")
        append(" cam=").append(st.camera).append('/').append(st.cameraFacing)
        if (st.zoomRatio != 1f) append(" zoom=").append(String.format(Locale.ROOT, "%.1f", st.zoomRatio))
        append(" onScreen=").append(st.activeSource)
        st.externalCamera?.let { append(" ext=\"").append(it).append('"') }
        append(" audio=").append(st.audioInput)
        if (st.micMuted) append(" micMuted")
        mic?.let { append(" ").append(it) }
        append(" alerts=").append(alerts)
        // Next to the links' RTT: Wi-Fi power save with the screen off shows up there first.
        screenOn?.let { append(" screen=").append(if (it) "on" else "off") }
        if (pocketScreen) append(" pocketScreen")
        append(" links=").append(if (st.links.isEmpty()) "none" else st.links.joinToString("; ", "[", "]") { link(it) })
        // Everything else the engine reports, by name; a field left out is at its default (0, off, empty or unknown),
        // which keeps a long stream's log well inside its cap.
        extras(st, KNOWN_STATE_FIELDS, skipDefaults = true).forEach { (k, v) -> append(' ').append(k).append('=').append(v) }
        // The audio glitch counters (mic overruns, camera underruns, limiter load...): the first thing to read when a
        // stream sounded bad.
        extras(st.audio, emptySet(), skipDefaults = true).forEach { (k, v) -> append(" audio.").append(k).append('=').append(v) }
        // Frame timing, the external camera's clock, the mic in use and what the camera says it does.
        extras(st.cadence, emptySet(), skipDefaults = true).forEach { (k, v) -> append(" cadence.").append(k).append('=').append(v) }
        st.ingest?.let { i -> extras(i, emptySet(), skipDefaults = true).forEach { (k, v) -> append(" ingest.").append(k).append('=').append(v) } }
        st.micInput?.let { append(" micInput=\"").append(it.name).append("\"/").append(it.inputClass) }
        st.captureStats?.let { c -> extras(c, emptySet(), skipDefaults = true).forEach { (k, v) -> append(" capture.").append(k).append('=').append(v) } }
        st.lastError?.let { append(" error=\"").append(it).append('"') }
    }

    private fun link(l: LinkInfo): String = buildString {
        append(l.kind).append(" \"").append(l.name).append("\" ")
        if (!l.up) { append("down"); return@buildString }
        append("up ").append(kb(l.kbps * 1000)).append(' ').append(l.rttMs?.let { "${it}ms" } ?: "rtt?")
        l.batteryPercent?.let { append(" battery ").append(it).append('%') }
        if (l.hot) append(" hot")
        // Its queue, the server's resend requests per second, its in-flight cap and what went past it.
        append(" q ").append(l.queueMs).append("ms")
        append(" nak ").append(String.format(Locale.ROOT, "%.1f", l.nakRate)).append("/s")
        append(" cap ").append(l.capPackets)
        if (l.overCap > 0) append(" over ").append(l.overCap)
        // When the server last answered on it, its role if it's a standby, socket drops and its data use so far.
        append(" proof ").append(l.proofAgoMs?.let { "${it}ms" } ?: "never")
        if (l.priority != "primary") append(' ').append(l.priority)
        if (l.localDrops > 0) append(" drops ").append(l.localDrops)
        if (l.bytesSent > 0) append(" sent ").append(String.format(Locale.ROOT, "%.1f", l.bytesSent / 1_000_000.0)).append("MB")
    }

    /**
     * What changed between two engine states, one line each: health, connection, scene, the Weak signal screen, the
     * link set, the camera, what's on screen, the audio input and errors. Logged as they happen (the state updates
     * twice a second), so a short drop between two snapshots still shows.
     */
    fun changes(prev: EngineState?, now: EngineState): List<String> {
        val p = prev ?: EngineState()
        val out = mutableListOf<String>()
        fun <T> diff(label: String, a: T, b: T, more: String = "") { if (a != b) out += "$label $a -> $b$more" }
        diff("health", p.health, now.health)
        diff("connection", p.connection, now.connection, if (now.reconnectAttempt > 0) " (attempt ${now.reconnectAttempt})" else "")
        diff("scene", p.scene, now.scene)
        if (p.weakSignal != now.weakSignal) out += "weak signal screen ${onOff(now.weakSignal)}"
        val links = { s: EngineState -> s.links.map { Triple(it.kind, it.name, it.up) } }
        if (links(p) != links(now)) {
            out += "links " + if (now.links.isEmpty()) "none" else now.links.joinToString(", ") { "${it.kind} \"${it.name}\" ${if (it.up) "up" else "down"}" }
        }
        diff("camera", p.camera, now.camera)
        diff("phone camera", p.cameraFacing, now.cameraFacing)
        diff("on screen", p.activeSource, now.activeSource)
        if (p.externalCamera != now.externalCamera) out += "external camera ${now.externalCamera ?: "none"}"
        diff("audio input", p.audioInput, now.audioInput)
        if (p.micMuted != now.micMuted) out += if (now.micMuted) "mic muted" else "mic unmuted"
        diff("heat rung", p.thermalRung, now.thermalRung, " (thermal status ${now.thermalStatus})")
        if (now.encoderRecoveries > p.encoderRecoveries) out += "video encoder recovered (${now.encoderRecoveries} so far)"
        // Known a frame or two in: once each, then whenever they change (an encoder restart can pick another).
        if (now.encoder.isNotEmpty() && now.encoder != p.encoder) out += "video encoder ${now.encoder}"
        if (now.audio.aacEncoder.isNotEmpty() && now.audio.aacEncoder != p.audio.aacEncoder) out += "audio encoder ${now.audio.aacEncoder}"
        if (now.fpsFallback.isNotEmpty() && now.fpsFallback != p.fpsFallback) out += "frame rate ${now.fps}: ${now.fpsFallback}"
        if (p.lastError != now.lastError) out += now.lastError?.let { "error: $it" } ?: "error cleared"
        return out
    }

    fun endLines(
        reason: String,
        summary: SessionSummary?,
        alerts: Int,
        zone: ZoneId,
        screenOffMs: Long = 0,
        pocketScreenMs: Long = 0,
    ): List<String> {
        val lines = mutableListOf("end: $reason")
        if (summary == null) {
            lines += "summary: none (the engine wasn't running)"
        } else {
            val live = if (summary.liveSinceMs == 0L) "never reached the server" else
                "live ${duration(summary.liveDurationMs)} from ${timestamp(summary.liveSinceMs, zone)}"
            lines += "summary: $live, running ${duration(summary.endedAtMs - summary.startedAtMs)}, " +
                "drops recovered ${summary.recoveredDrops}, encoder recoveries ${summary.encoderRecoveries}, " +
                "sent ${String.format(Locale.ROOT, "%.2f", summary.bytesSent / 1e9)} GB, peak thermal status ${summary.peakThermalStatus}, " +
                "lowest bitrate ${kb(summary.lowestBitrate.toLong())}, alerts shown $alerts" +
                (summary.lastError?.let { ", last error \"$it\"" } ?: "")
            lines += recap(summary, screenOffMs, pocketScreenMs)
        }
        return lines
    }

    /** The heat, battery and screen side of the end: minutes at each thermal rung, battery per hour, screen time. */
    fun recap(s: SessionSummary, screenOffMs: Long, pocketScreenMs: Long): String = buildString {
        append("recap: heat ")
        append(
            if (s.thermalRungMs.isEmpty()) "not measured"
            else s.thermalRungMs.withIndex().joinToString(", ") { (i, ms) -> String.format(Locale.ROOT, "rung %d %.1f min", i, ms / 60_000.0) },
        )
        append("; battery ")
        when {
            s.batteryStartPct < 0 -> append("not read")
            else -> {
                append(s.batteryStartPct).append("% -> ").append(s.batteryEndPct).append('%')
                append(s.batteryPctPerHour?.let { String.format(Locale.ROOT, ", %.1f%%/h", it) } ?: ", too short for a rate")
            }
        }
        append("; screen off ").append(duration(screenOffMs))
        append(", pocket screen ").append(duration(pocketScreenMs))
    }

    /**
     * What the engine reported as it happened, as a log line: link events with their reasons, bitrate changes (with the
     * controller's reason), mic input changes and notes. The engine keeps the bitrate lines to one a second.
     */
    fun event(e: EngineEvent): String = when (e) {
        is EngineEvent.Link -> e.line
        is EngineEvent.Bitrate -> e.line
        is EngineEvent.MicInput -> e.line
        is EngineEvent.Note -> e.line
    }

    /**
     * The fields of [obj] this log doesn't know by name yet, as name=value: what another part of the app adds to
     * [EngineState] later (audio glitch counters, say) shows in the snapshots without this file changing first. Only
     * plain values (numbers, booleans, text, enums), and text is cut short. The app isn't minified, so names stay
     * readable. [skipDefaults] leaves out zeros, false, empty text and NaN (unknown).
     */
    fun extras(obj: Any, known: Set<String>, skipDefaults: Boolean = false): List<Pair<String, String>> = runCatching {
        obj.javaClass.declaredFields
            .filter { f -> !java.lang.reflect.Modifier.isStatic(f.modifiers) && !f.isSynthetic && f.name !in known }
            .sortedBy { it.name }
            .mapNotNull { f ->
                f.isAccessible = true
                when (val v = f.get(obj)) {
                    is Float -> f.name to String.format(Locale.ROOT, "%.1f", v)
                    // Doubles carry ratios (overhead, resend rate) where the third decimal matters.
                    is Double -> f.name to String.format(Locale.ROOT, "%.3f", v)
                    is Number, is Boolean, is Enum<*> -> f.name to v.toString()
                    is CharSequence -> f.name to "\"" + v.toString().take(80) + "\""
                    else -> null
                }?.takeUnless { (_, v) -> skipDefaults && v in DEFAULT_VALUES }
            }
    }.getOrDefault(emptyList())

    /** How [extras] prints a value at its default. */
    private val DEFAULT_VALUES = setOf("0", "0.0", "0.000", "false", "\"\"", "NaN")

    /** [EngineState]'s fields the snapshot already writes by name. */
    val KNOWN_STATE_FIELDS = setOf(
        "running", "portrait", "connection", "reconnectAttempt", "camera", "scene", "weakSignal", "audioInput",
        "thermalRung", "previewPaused", "cameraFacing", "zoomRatio", "micMuted", "liveSinceMs", "targetVideoBitrate",
        "qualityCeiling", "measuredVideoBitrate", "sentBitrate", "congested", "thermalStatus", "encoderRecoveries",
        "droppedFrames", "lastError", "activeSource", "externalCamera", "links",
    )

    /** Bits per second as whole kb/s ("5980k"): exact enough to compare with the server's log. */
    fun kb(bps: Long): String = "${bps / 1000}k"

    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.ROOT, "%dh %02dm %02ds", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.ROOT, "%dm %02ds", s / 60, s % 60)
    }

    /** "+40 ms", "-20 ms", "0 ms". */
    fun signedMs(ms: Long): String = if (ms > 0) "+$ms ms" else "$ms ms"

    private fun yes(b: Boolean) = if (b) "yes" else "no"
    private fun onOff(b: Boolean) = if (b) "on" else "off"
}

/**
 * The engine's events as log lines. The engine already sends at most one bitrate line a second; [abrGapMs] is only a
 * safety net well under that, so delivery jitter never drops a line the engine let through. Everything else is
 * written as it comes.
 */
internal class EventLines(private val abrGapMs: Long = 500) {
    private var lastAbrMs = Long.MIN_VALUE / 2

    @Synchronized
    fun line(nowMs: Long, e: EngineEvent): String? {
        if (e is EngineEvent.Bitrate) {
            if (nowMs - lastAbrMs < abrGapMs) return null
            lastAbrMs = nowMs
        }
        return SessionLogText.event(e)
    }
}

/**
 * The mic over one snapshot window: its loudest peak, its average level (as power, so a loud second isn't averaged
 * away by quiet ones), the average while there was a voice, how often the limiter was pulling it down, and what came
 * in clipped before any gain. A single reading every 5 s would miss what the first stream needed to know (a hot mic
 * clamped most of the time sounds harsh), so every level the engine publishes goes through here. Thread-safe.
 */
internal class MicWindow {
    private var count = 0
    private var limiting = 0
    private var peak = MicLevel.FLOOR_DB
    private var powerSum = 0.0
    private var voiced = 0
    private var voicedPowerSum = 0.0
    private var maxReduction = 0f
    private var inPeak = MicLevel.FLOOR_DB
    private var clipped = 0L
    private var leveler = 0f

    @Synchronized
    fun add(l: MicLevel) {
        count++
        peak = maxOf(peak, l.peakDb)
        val power = Math.pow(10.0, l.rmsDb / 10.0)
        powerSum += power
        if (l.rmsDb > VOICE_DB) { voiced++; voicedPowerSum += power }
        if (l.reductionDb > LIMITING_DB) limiting++
        maxReduction = maxOf(maxReduction, l.reductionDb)
        inPeak = maxOf(inPeak, l.inPeakDb)
        clipped += l.inClipped
        leveler = l.levelerDb
    }

    /**
     * "mic peak -1.0 rms -18.4 voice -18.4 limiting 40% max 6.0dB in-peak -3.0 clip 0 leveler 0.0" for the window so
     * far ("voice none" when nobody spoke), then starts a new one; null without levels.
     */
    @Synchronized
    fun take(): String? {
        if (count == 0) return null
        val voice = if (voiced == 0) "none" else String.format(Locale.ROOT, "%.1f", db(voicedPowerSum / voiced))
        val s = String.format(
            Locale.ROOT, "mic peak %.1f rms %.1f voice %s limiting %d%% max %.1fdB in-peak %.1f clip %d leveler %.1f",
            peak, db(powerSum / count), voice, limiting * 100 / count, maxReduction, inPeak, clipped, leveler,
        )
        count = 0; limiting = 0; peak = MicLevel.FLOOR_DB; powerSum = 0.0; voiced = 0; voicedPowerSum = 0.0
        maxReduction = 0f; inPeak = MicLevel.FLOOR_DB; clipped = 0
        return s
    }

    private fun db(power: Double): Double = if (power <= 0.0) MicLevel.FLOOR_DB.toDouble() else 10 * Math.log10(power)

    private companion object {
        /** Less than this is the limiter's release tail, not it holding the voice down. */
        const val LIMITING_DB = 0.5f
        /** Louder than this counts as a voice (the mic controls' own line). */
        const val VOICE_DB = -45f
    }
}

/** How long something has been on in total (the screen off, the Pocket screen), from changes with their times. Thread-safe. */
internal class TimeOn {
    private var on = false
    private var sinceMs = 0L
    private var totalMs = 0L

    /** Starts counting afresh at [nowMs], [on] or off. */
    @Synchronized
    fun reset(nowMs: Long, on: Boolean) {
        totalMs = 0
        this.on = on
        sinceMs = nowMs
    }

    @Synchronized
    fun set(nowMs: Long, on: Boolean) {
        if (on == this.on) return
        if (this.on) totalMs += (nowMs - sinceMs).coerceAtLeast(0)
        this.on = on
        sinceMs = nowMs
    }

    @Synchronized
    fun totalMs(nowMs: Long): Long = totalMs + if (on) (nowMs - sinceMs).coerceAtLeast(0) else 0
}
