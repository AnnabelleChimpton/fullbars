package app.irlsupreme.engine

import srtlakt.abr.DelayPreset
import kotlin.math.abs

/**
 * The engine's small decisions, pure so they can be tested without a phone. [StreamEngine] calls these from its monitor
 * thread.
 */
internal object EngineRules {
    /** An external camera that published but sent no picture for this long is shown as "connected, no video yet". */
    const val NO_VIDEO_MS = 4_000L

    /** A bonded stream is congested while its links queue past the delay preset's back-off point. */
    fun bondedCongested(queueMs: Int, preset: DelayPreset): Boolean = queueMs > preset.backOffQueueMs

    /**
     * The frame rate to run at and, when it isn't [requested], why (plain words for the session log). 60 fps only for
     * the phone camera, and only when [supported] (the camera and encoder can carry it at this size) says so.
     */
    fun chooseFps(requested: Int, phoneCamera: Boolean, supported: (Int) -> Boolean): Pair<Int, String> = when {
        requested == 30 -> 30 to ""
        requested !in StreamConfig.FPS_CHOICES -> 30 to "$requested fps isn't offered, using 30"
        !phoneCamera -> 30 to "60 fps is for the phone camera only, using 30"
        !supported(requested) -> 30 to "this phone can't film $requested fps at this size, using 30"
        else -> requested to ""
    }

    /**
     * How late the picture on screen is against the sound, ns: the phone camera's capture-to-draw latency while it is
     * drawn, the external camera's capture-to-screen delay while that is, else 0. Unknown counts as 0.
     */
    fun videoDelayNs(activeSource: String, cameraLatencyNs: Long?, externalDelayNs: Long?): Long = when (activeSource) {
        "phone" -> cameraLatencyNs ?: 0L
        "camera" -> externalDelayNs ?: 0L
        else -> 0L
    }.coerceAtLeast(0L)

    fun clampAvOffset(ns: Long): Long = ns.coerceIn(-StreamConfig.MAX_AV_OFFSET_NS, StreamConfig.MAX_AV_OFFSET_NS)

    /** Whether a new bitrate target is worth an encoder parameter call: more than 2% away from the current one. */
    fun worthApplying(next: Int, current: Int): Boolean = abs(next - current) > current / 50

    /**
     * Whether a pre-live delivery measurement should seed the bonded controller: only when it starts higher than the
     * controller would anyway. Before going live the links carry little, so a low figure says nothing about capacity.
     */
    fun worthSeeding(measuredBps: Long, startBps: Int): Boolean = measuredBps > 0 && measuredBps * 3 / 4 > startBps

    /** The external camera's line for the live screen and the log. */
    fun externalCameraStatus(status: String?, width: Int, height: Int, connectedForMs: Long, cameraAudio: Boolean): String? = when {
        status != "connected" -> status
        width > 0 -> "${width}x$height" + if (cameraAudio) " · camera audio" else " · phone mic"
        connectedForMs >= NO_VIDEO_MS -> "connected, no video yet"
        else -> status
    }

    /** What to tell the streamer when the camera sends video the phone can't decode. */
    fun unsupportedVideoStatus(fourcc: String): String = "camera sends ${if (fourcc == "hvc1") "HEVC" else fourcc}; set it to H.264"
}

/** A condition that counts only once it has held for [ticks] readings in a row (one blip isn't worth a Weak pill). */
internal class TickHold(private val ticks: Int = 2) {
    private var run = 0

    fun update(raw: Boolean): Boolean {
        run = if (raw) run + 1 else 0
        return run >= ticks
    }

    fun reset() { run = 0 }
}

/**
 * Notices an output coming back to LIVE after it had been LIVE and dropped (the SRT session restarted): [update]
 * returns true once, on the first LIVE reading after the drop. The first time it goes LIVE isn't a resume.
 */
internal class ResumeWatch {
    private var everLive = false
    private var away = false

    fun update(live: Boolean): Boolean {
        if (!live) {
            if (everLive) away = true
            return false
        }
        everLive = true
        if (!away) return false
        away = false
        return true
    }
}

/**
 * What the bonded links delivered over the last [windowMs] before going live, for the bitrate controller's seed. Gives
 * 0 until the readings span at least [minSpanMs]. Fixed arrays: no allocation per reading.
 */
internal class PreLiveRate(private val windowMs: Long = 3_000, private val minSpanMs: Long = 2_000) {
    private val atMs = LongArray(SLOTS)
    private val bps = LongArray(SLOTS)
    private var count = 0
    private var next = 0

    fun observe(nowMs: Long, deliveredBps: Long) {
        atMs[next] = nowMs
        bps[next] = deliveredBps.coerceAtLeast(0)
        next = (next + 1) % SLOTS
        if (count < SLOTS) count++
    }

    /** The average delivery over the window, bits/s, or 0 when the readings don't cover [minSpanMs] yet. */
    fun averageBps(nowMs: Long): Long {
        var sum = 0L
        var n = 0
        var oldest = Long.MAX_VALUE
        for (i in 0 until count) {
            if (nowMs - atMs[i] > windowMs) continue
            sum += bps[i]
            n++
            if (atMs[i] < oldest) oldest = atMs[i]
        }
        if (n == 0 || nowMs - oldest < minSpanMs) return 0
        return sum / n
    }

    private companion object {
        const val SLOTS = 32
    }
}

/** Lets one thing through per [intervalMs] (the session log's bitrate lines). */
internal class RateLimit(private val intervalMs: Long) {
    private var lastMs = Long.MIN_VALUE

    fun allow(nowMs: Long): Boolean {
        if (lastMs != Long.MIN_VALUE && nowMs - lastMs < intervalMs) return false
        lastMs = nowMs
        return true
    }
}
