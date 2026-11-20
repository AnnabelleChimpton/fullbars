package app.irlsupreme.engine.output

import kotlin.math.max
import kotlin.math.min

/*
 * The decisions behind RtmpOutput, kept free of Android and RootEncoder so they can be tested with a fake clock:
 * how long the send backlog is, which frames to forward, when a live connection has stalled, and when to reconnect.
 * Not thread-safe; RtmpOutput serialises the calls.
 */

/**
 * Turns RootEncoder's counters into a backlog time and a throughput. Call [update] every 250-500 ms while live.
 *
 * Throughput: while bytes are waiting, what left is what the link carries, so the latest reading is taken as it is
 * (a capacity drop shows at once); with an empty queue it only says what was offered, so it is smoothed.
 * Backlog: the queued bytes divided by the throughput, i.e. how long the newest frame waits. When the link carries
 * under half of what goes in (a stall, or close to one) that would read a two-second stall as minutes, so then the
 * queued bytes are divided by the input rate instead: how much media is waiting, which stays finite.
 * Without a byte count it falls back to queued items over the frame rate plus AAC's ~47 frames/s.
 */
class RtmpMeter(private val fps: Int) {
    /** Bits/s that left the socket recently (RTMP chunk headers included). */
    var throughputBps = 0L
        private set
    /** Bits/s handed to the client recently. */
    var inputBps = 0L
        private set
    var queueDelayMs = 0
        private set

    private var lastMs = Long.MIN_VALUE
    private var lastSent = -1L
    private var lastIn = -1L

    /** New connection: the counters may restart and the old rates mean nothing. */
    fun reset() {
        lastMs = Long.MIN_VALUE; lastSent = -1; lastIn = -1
        throughputBps = 0; inputBps = 0; queueDelayMs = 0
    }

    /**
     * [bytesSent]: the client's running count of bytes written. [bytesIn]: our running count of bytes handed to it.
     * [queueBytes]: bytes waiting in its queue (negative if unknown). [itemsInCache]: frames waiting in its queue.
     */
    fun update(nowMs: Long, bytesSent: Long, bytesIn: Long, queueBytes: Long, itemsInCache: Int) {
        if (lastMs != Long.MIN_VALUE && nowMs > lastMs) {
            val dt = (nowMs - lastMs) / 1000.0
            // A count that went down restarted (a new connection): no traffic to infer from that step.
            val sent = if (lastSent < 0 || bytesSent < lastSent) 0L else bytesSent - lastSent
            val fed = if (lastIn < 0 || bytesIn < lastIn) 0L else bytesIn - lastIn
            val sample = (sent * 8 / dt).toLong()
            inputBps = smooth(inputBps, (fed * 8 / dt).toLong())
            val backlogged = queueBytes > inputBps / 8 * BACKLOGGED_MS / 1000
            throughputBps = if (backlogged) sample else smooth(throughputBps, sample)
        }
        lastMs = nowMs
        lastSent = bytesSent
        lastIn = bytesIn
        val rate = if (throughputBps * 2 >= inputBps) throughputBps else inputBps
        queueDelayMs = when {
            queueBytes == 0L -> 0
            queueBytes > 0 && rate > 0 -> min(MAX_QUEUE_MS, queueBytes * 8 * 1000 / rate).toInt()
            else -> (max(0, itemsInCache) * 1000L / (fps + AAC_FRAMES_PER_S)).toInt()
        }
    }

    private fun smooth(old: Long, sample: Long) = if (old == 0L) sample else (old + sample) / 2

    companion object {
        /** AAC frames per second at 48 kHz (1024 samples each). */
        const val AAC_FRAMES_PER_S = 47
        private const val MAX_QUEUE_MS = 60_000L
        /** More than this much media queued means the link, not the encoder, set the pace. */
        private const val BACKLOGGED_MS = 50L
    }
}

/**
 * Which frames RtmpOutput forwards to the client, so a full queue never throws away audio or reference frames at
 * random (RootEncoder's own drop, when its queue is full, takes whatever arrives next of either kind).
 *
 *  - After every (re)connect no video goes out until a keyframe: frames before it would only decode as smear.
 *    Some encoders ignore a keyframe request, so it is repeated every [rerequestMs] until one arrives.
 *  - When the backlog passes [enterMs], non-key video is dropped (counted in [selfDropped]) until a keyframe arrives
 *    with the backlog back under [exitMs]; once it is under, a keyframe is requested. Dropping video drains the queue
 *    fast, and the picture resumes cleanly at a keyframe instead of smearing until the next GOP. Audio keeps flowing.
 *  - Past [hardMs] the backlog is beyond saving: [updateBacklog] returns [Action.FLUSH] (clear the client's queue and
 *    request a keyframe), and audio is dropped while it stays that deep, as one run rather than scattered clicks.
 */
class RtmpFramePolicy(
    val enterMs: Int = 1_500,
    val exitMs: Int = 1_000,
    val hardMs: Int = 3_000,
    val rerequestMs: Long = 1_000,
    val flushGapMs: Long = 2_000,
) {
    enum class Action { NONE, FLUSH }

    /** No video forwarded since the last (re)connect: waiting for its first keyframe. */
    var awaitingKey = true
        private set
    /** Backlog too deep: dropping non-key video until a keyframe can go out. */
    var waitingForIdr = false
        private set
    /** Frames this policy dropped (gated after a connect, dropped for backlog), video and audio. */
    var selfDropped = 0L
        private set
    var backlogMs = 0
        private set

    private var lastKeyRequestMs = Long.MIN_VALUE
    private var lastFlushMs = Long.MIN_VALUE
    private var keyWanted = false

    /** A (re)connect: hold video for a keyframe (one was just requested along with the connect callback). */
    fun onConnected(nowMs: Long) {
        awaitingKey = true
        waitingForIdr = false
        backlogMs = 0
        lastKeyRequestMs = nowMs
        keyWanted = false
    }

    /** The latest backlog reading. */
    fun updateBacklog(nowMs: Long, backlog: Int): Action {
        backlogMs = backlog
        if (backlog > hardMs && (lastFlushMs == Long.MIN_VALUE || nowMs - lastFlushMs >= flushGapMs)) {
            lastFlushMs = nowMs
            backlogMs = 0 // the caller clears the queue
            waitingForIdr = true
            keyWanted = true
            lastKeyRequestMs = nowMs
            return Action.FLUSH
        }
        if (backlog >= enterMs && !awaitingKey) waitingForIdr = true
        if (waitingForIdr && backlog < exitMs) wantKey(nowMs)
        return Action.NONE
    }

    /** Whether to forward a video frame. */
    fun onVideo(nowMs: Long, isKeyFrame: Boolean): Boolean {
        if (awaitingKey) {
            if (isKeyFrame) { awaitingKey = false; return true }
            selfDropped++
            wantKey(nowMs)
            return false
        }
        if (waitingForIdr) {
            if (isKeyFrame && backlogMs < exitMs) { waitingForIdr = false; return true }
            selfDropped++
            if (backlogMs < exitMs) wantKey(nowMs)
            return false
        }
        return true
    }

    /** Whether to forward an audio frame. */
    fun onAudio(): Boolean {
        if (backlogMs > hardMs) { selfDropped++; return false }
        return true
    }

    /** True once per needed keyframe request (rate-limited to one per [rerequestMs]); the caller requests it. */
    fun takeKeyframeRequest(): Boolean {
        val w = keyWanted
        keyWanted = false
        return w
    }

    private fun wantKey(nowMs: Long) {
        if (lastKeyRequestMs == Long.MIN_VALUE || nowMs - lastKeyRequestMs >= rerequestMs) {
            lastKeyRequestMs = nowMs
            keyWanted = true
        }
    }
}

/**
 * Notices a live connection that has died without closing: no bytes leave for [stallMs] although the encoder is
 * producing, past a [graceMs] after connecting. TCP recovers short blips by itself, so the threshold is several
 * seconds. Fires once per connection.
 */
class StallWatchdog(val graceMs: Long = 5_000, val stallMs: Long = 7_000) {
    private var connectedAtMs = Long.MIN_VALUE
    private var lastProgressMs = Long.MIN_VALUE
    private var lastBytes = Long.MIN_VALUE
    private var fired = false

    fun onConnected(nowMs: Long) {
        connectedAtMs = nowMs
        lastProgressMs = nowMs
        lastBytes = Long.MIN_VALUE
        fired = false
    }

    /**
     * Call every ~1 s while live with the client's running byte count. Returns how long nothing has left (ms) when
     * the connection should be treated as failed, else -1.
     */
    fun check(nowMs: Long, encoderProducing: Boolean, bytesSent: Long): Long {
        if (connectedAtMs == Long.MIN_VALUE) return -1
        if (lastBytes == Long.MIN_VALUE) {
            lastBytes = bytesSent // the first reading after a connect is the baseline, not progress
        } else if (bytesSent != lastBytes) {
            lastBytes = bytesSent
            lastProgressMs = nowMs
        }
        // Nothing to send isn't the link's fault.
        if (!encoderProducing) lastProgressMs = nowMs
        if (fired || nowMs - connectedAtMs < graceMs) return -1
        val idle = nowMs - lastProgressMs
        if (idle < stallMs) return -1
        fired = true
        return idle
    }
}

/**
 * Retry timing for a dropped connection: 0.5, 1, 2 s, capped at [fastCapMs] for the first [fastWindowMs] of an outage
 * (a connect while offline fails at once, so trying often costs nothing and catches the network's return), then
 * growing to [slowCapMs].
 */
class ReconnectPolicy(
    val baseMs: Long = 500,
    val fastCapMs: Long = 2_000,
    val slowCapMs: Long = 8_000,
    val fastWindowMs: Long = 30_000,
) {
    /** Failed tries since the last success. */
    var attempt = 0
        private set
    private var outageStartMs = Long.MIN_VALUE

    /** A try failed: returns how long to wait before the next. */
    fun onFailure(nowMs: Long): Long {
        attempt++
        if (outageStartMs == Long.MIN_VALUE) outageStartMs = nowMs
        val cap = if (nowMs - outageStartMs < fastWindowMs) fastCapMs else slowCapMs
        return min(baseMs shl min(attempt - 1, 4), cap)
    }

    fun onSuccess() {
        attempt = 0
        outageStartMs = Long.MIN_VALUE
    }

    /** A network came up: start the backoff over (the outage itself goes on). */
    fun resetBackoff() { attempt = 0 }
}

/**
 * Decides when a change of the default network should cut a reconnect's wait short: a network newly available, or one
 * that just became validated, while the output is (re)connecting. Repeated capability updates for the same network
 * (signal strength changes arrive constantly) do nothing, and at most one network-triggered connect starts per
 * [minGapMs]; one that comes sooner is deferred to the end of the gap rather than lost.
 */
class NetworkTrigger(val minGapMs: Long = 2_000) {
    private var availableNet = NONE
    private var validatedNet = NONE
    private var lastTriggerMs = Long.MIN_VALUE
    private var lastTriggerNet = NONE

    /** Returns the delay (ms) before a reconnect should start, or -1 for none. */
    fun onAvailable(net: Long, nowMs: Long, reconnecting: Boolean): Long {
        if (net == availableNet) return -1
        availableNet = net
        if (validatedNet != net) validatedNet = NONE
        return trigger(net, nowMs, reconnecting)
    }

    fun onCapabilities(net: Long, validated: Boolean, nowMs: Long, reconnecting: Boolean): Long {
        if (!validated) {
            if (validatedNet == net) validatedNet = NONE
            return -1
        }
        if (validatedNet == net) return -1
        validatedNet = net
        availableNet = net
        // A new default network usually arrives already validated, right after onAvailable: one connect covers both.
        if (lastTriggerNet == net && nowMs - lastTriggerMs < minGapMs) return -1
        return trigger(net, nowMs, reconnecting)
    }

    fun onLost(net: Long) {
        if (availableNet == net) availableNet = NONE
        if (validatedNet == net) validatedNet = NONE
    }

    private fun trigger(net: Long, nowMs: Long, reconnecting: Boolean): Long {
        if (!reconnecting) return -1
        val wait = if (lastTriggerMs == Long.MIN_VALUE) 0L else max(0L, lastTriggerMs + minGapMs - nowMs)
        lastTriggerMs = nowMs + wait
        lastTriggerNet = net
        return wait
    }

    private companion object { const val NONE = Long.MIN_VALUE }
}
