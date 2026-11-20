package app.irlsupreme.engine.abr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Bitrate control for direct RTMP(S), driven by the send backlog and the measured throughput. Pure and clock-free:
 * call [update] every 250 ms while live (500 ms works, but reacts later: a 6 -> 4 Mbps drop then peaks at ~1.3 s of
 * backlog instead of ~0.8 s) and feed [targetBps] to the encoder.
 *
 * RTMP runs over one TCP connection, so the only signals are how much is waiting in the client's send queue (as time:
 * [update]'s queueDelayMs) and how fast it leaves (throughputBps). Unlike a bonded SRT stream there is no resend cliff
 * to fear: going a little over capacity just grows the backlog, and the backlog says by how much.
 *
 * Policy, in episodes:
 *  - a backlog over [BACKLOG_MS] on two updates in a row (one keyframe burst isn't congestion) starts an episode:
 *    remember the target as [knownLimitBps] and cut once to 85% of what actually leaves (or by 25% when nothing
 *    leaves at all: a stall says nothing about capacity);
 *  - then wait: the encoder takes up to a second to follow, and the backlog needs backlog / (throughput - target) to
 *    drain. Cut again only if, after that, the backlog is above where it was at the cut and still growing. A backlog
 *    that is high but shrinking means the cut is working; cutting again on every tick is how the old loop fell to the
 *    floor in three seconds;
 *  - once the backlog has stayed under [CLEAR_MS] for [CLEAR_HOLD_MS], ramp +10%/s up to 85% of the known limit,
 *    then probe +5% every [PROBE_EVERY_MS] toward the ceiling, stepping back if a probe grows the backlog within
 *    [PROBE_WATCH_MS]. The known limit is forgotten after [KNOWN_LIMIT_TTL_MS] of clear backlog, so a link that got
 *    better is found again in seconds rather than minutes. Each probe that fails (a step back, or congestion soon
 *    after it) doubles both waits, up to [MAX_PROBE_BACKOFF] times, so a steady bottleneck isn't re-tested every few
 *    seconds; a probe that holds until the next one resets them;
 *  - a backlog over [FLUSH_MS] is past saving: [flushNow] asks the output to drop it and restart from a keyframe.
 */
class RtmpRateController(
    val minBps: Int,
    val maxBps: Int,
    startBps: Int = maxBps,
) {
    var targetBps: Int = startBps.coerceIn(minBps, maxBps)
        private set
    /** The target when the last episode began; the fast ramp stops short of it. */
    var knownLimitBps: Int = maxBps
        private set
    /** Why the last change happened, for logs. */
    var lastAction: String = "start"
        private set
    /** The backlog is over [FLUSH_MS]: the output should clear its queue and request a keyframe. */
    var flushNow: Boolean = false
        private set

    private var lastUpdateMs = Long.MIN_VALUE
    private var overTicks = 0
    private var clearSinceMs = Long.MIN_VALUE
    private var inEpisode = false
    private var holdUntilMs = Long.MIN_VALUE
    private var queueAtLastCut = 0
    private var lastProbeMs = Long.MIN_VALUE
    private var probeFromBps = 0
    private var queueAtProbe = 0
    private var lastQueue = 0
    /** Multiplies the probe interval and the known limit's lifetime; doubles on each failed probe. */
    private var probeBackoff = 1
    private var probeFailed = false
    private var lastProbeBaseBps = 0

    /**
     * [queueDelayMs]: how long the client's send backlog takes to leave. [throughputBps]: what actually left recently
     * (bits/s, audio included). [ceilingBps]: the most the target may be right now (the chosen bitrate under the
     * phone's heat cap). Returns the new target; [lastAction] and [flushNow] say why and what else to do.
     */
    fun update(nowMs: Long, queueDelayMs: Int, throughputBps: Long, ceilingBps: Int = maxBps): Int {
        val dtMs = if (lastUpdateMs == Long.MIN_VALUE) 0L else (nowMs - lastUpdateMs).coerceIn(0L, MAX_DT_MS)
        lastUpdateMs = nowMs
        val ceiling = ceilingBps.coerceIn(minBps, maxBps)
        if (targetBps > ceiling) targetBps = ceiling
        val queue = max(0, queueDelayMs)
        val previous = lastQueue
        lastQueue = queue
        flushNow = queue > FLUSH_MS
        overTicks = if (queue > BACKLOG_MS) overTicks + 1 else 0
        // What has lasted two readings: a keyframe burst lifts one reading, and must neither end a clear stretch nor
        // undo a probe.
        val sustained = min(queue, previous)
        if (sustained < CLEAR_MS) {
            if (clearSinceMs == Long.MIN_VALUE) clearSinceMs = nowMs
        } else {
            clearSinceMs = Long.MIN_VALUE
        }

        // A probe that grew the backlog: back to where it was clear, and remember where it wasn't.
        if (lastProbeMs != Long.MIN_VALUE && nowMs - lastProbeMs <= PROBE_WATCH_MS && probeFromBps > 0 &&
            sustained > STEP_BACK_MS && sustained > queueAtProbe
        ) {
            knownLimitBps = targetBps
            targetBps = probeFromBps.coerceIn(minBps, ceiling)
            probeFromBps = 0
            failProbe()
            lastAction = "step back (queue ${queue}ms)"
            return targetBps
        }

        if (overTicks >= CONGESTED_TICKS) {
            probeFromBps = 0
            if (!inEpisode) {
                inEpisode = true
                // Congestion soon after a probe, above where it started: that probe found the limit the hard way.
                if (lastProbeMs != Long.MIN_VALUE && nowMs - lastProbeMs < PROBE_FAIL_WINDOW_MS && targetBps > lastProbeBaseBps) {
                    failProbe()
                }
                knownLimitBps = targetBps
                cut(nowMs, queue, throughputBps, ceiling, "down")
            } else if (nowMs >= holdUntilMs && queue > queueAtLastCut && queue > previous) {
                cut(nowMs, queue, throughputBps, ceiling, "down again")
            } else {
                lastAction = "hold (draining, queue ${queue}ms)"
            }
            return targetBps
        }

        val clearFor = if (clearSinceMs == Long.MIN_VALUE) 0L else nowMs - clearSinceMs
        if (clearFor < CLEAR_HOLD_MS) {
            lastAction = "hold (queue ${queue}ms)"
            return targetBps
        }
        inEpisode = false
        // Forget an old limit (the link may have got better), later after probes that failed.
        if (clearFor >= KNOWN_LIMIT_TTL_MS * probeBackoff && knownLimitBps < ceiling) knownLimitBps = ceiling
        val fastTop = (knownLimitBps * NEAR_LIMIT).roundToInt()
        when {
            targetBps >= ceiling -> lastAction = "at ceiling"
            targetBps < fastTop -> {
                val step = max(MIN_STEP_BPS_PER_S, targetBps * RAMP_FAST_PER_S) * dtMs / 1000.0
                targetBps = min((targetBps + step).roundToInt(), min(fastTop, ceiling)).coerceIn(minBps, ceiling)
                lastAction = "up"
            }
            lastProbeMs == Long.MIN_VALUE || nowMs - lastProbeMs >= PROBE_EVERY_MS * probeBackoff -> {
                if (lastProbeMs != Long.MIN_VALUE && !probeFailed) probeBackoff = 1 // the last probe held
                probeFailed = false
                lastProbeBaseBps = targetBps
                probeFromBps = targetBps
                queueAtProbe = queue
                lastProbeMs = nowMs
                targetBps = min((targetBps * (1 + PROBE_STEP)).roundToInt(), ceiling).coerceIn(minBps, ceiling)
                if (targetBps > knownLimitBps) knownLimitBps = targetBps
                lastAction = if (targetBps >= ceiling) "at ceiling" else "probe"
            }
            else -> lastAction = "hold (probing)"
        }
        return targetBps
    }

    private fun failProbe() {
        if (probeFailed) return
        probeFailed = true
        probeBackoff = min(probeBackoff * 2, MAX_PROBE_BACKOFF)
    }

    /** Current multiplier on the probe interval and the known limit's lifetime (1 until a probe fails). */
    val backoff: Int get() = probeBackoff

    private fun cut(nowMs: Long, queue: Int, throughputBps: Long, ceiling: Int, verb: String) {
        val stalled = throughputBps < targetBps * STALL_SHARE
        val to = if (stalled) {
            targetBps * STALL_FACTOR // nothing leaves: no capacity figure to anchor to
        } else {
            min(targetBps * CUT_FACTOR, throughputBps * CUT_FACTOR)
        }
        targetBps = max(minBps.toDouble(), to).roundToInt().coerceIn(minBps, ceiling)
        // Wait for the encoder to follow, then for the backlog to drain at the surplus the cut leaves.
        val backlogBits = queue / 1000.0 * throughputBps
        val surplus = throughputBps - targetBps
        val drainMs = if (stalled || surplus <= 0) MAX_DRAIN_WAIT_MS else min(MAX_DRAIN_WAIT_MS, (backlogBits / surplus * 1000).toLong())
        holdUntilMs = nowMs + ENCODER_LAG_MS + drainMs
        queueAtLastCut = queue
        lastAction = "$verb (queue ${queue}ms, ${throughputBps / 1000} kbps out)"
    }

    companion object {
        /** A backlog over this on [CONGESTED_TICKS] updates in a row is congestion. */
        const val BACKLOG_MS = 400
        const val CONGESTED_TICKS = 2
        /** Under this the backlog counts as empty. */
        const val CLEAR_MS = 100
        /** How long the backlog must stay empty before ramping. */
        const val CLEAR_HOLD_MS = 2_000L
        /** Past this the backlog is beyond saving: flush it. */
        const val FLUSH_MS = 3_000
        const val CUT_FACTOR = 0.85
        /** Throughput under this share of the target counts as a stall. */
        const val STALL_SHARE = 0.1
        const val STALL_FACTOR = 0.75
        /** MediaCodec takes up to about this long to apply a new bitrate, and frames already queued went at the old one. */
        const val ENCODER_LAG_MS = 1_000L
        /** The longest the drain estimate may hold off a second cut. */
        const val MAX_DRAIN_WAIT_MS = 5_000L
        const val NEAR_LIMIT = 0.85
        const val RAMP_FAST_PER_S = 0.10
        const val MIN_STEP_BPS_PER_S = 50_000.0
        const val PROBE_STEP = 0.05
        const val PROBE_EVERY_MS = 5_000L
        /** A probe that grows the backlog past [STEP_BACK_MS] within this long is undone. */
        const val PROBE_WATCH_MS = 3_000L
        const val STEP_BACK_MS = 200
        /** Clear backlog for this long and the known limit is forgotten (the link may have got better). */
        const val KNOWN_LIMIT_TTL_MS = 8_000L
        /** Congestion within this long of a probe, above the level it started from, counts as that probe failing. */
        const val PROBE_FAIL_WINDOW_MS = 10_000L
        /** Failed probes stretch the probe interval and the known limit's lifetime up to this many times. */
        const val MAX_PROBE_BACKOFF = 8
        private const val MAX_DT_MS = 2_000L
    }
}
