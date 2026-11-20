// SPDX-License-Identifier: Apache-2.0
package srtlakt.abr

import srtlakt.SenderStats
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The app's "Delay" setting: how much queueing the stream tolerates before backing off, paired with the SRT
 * latency it runs with. More latency buys more room for queues and retransmissions.
 */
enum class DelayPreset(val srtLatencyMs: Int, val backOffQueueMs: Int, val rampQueueMs: Int) {
    FAST(srtLatencyMs = 1_000, backOffQueueMs = 150, rampQueueMs = 60),
    BALANCED(srtLatencyMs = 2_000, backOffQueueMs = 200, rampQueueMs = 80),
    ROCK_SOLID(srtLatencyMs = 4_000, backOffQueueMs = 500, rampQueueMs = 200),
}

/**
 * Delay-based bitrate control for a bonded stream. Pure and clock-free: call [update] every ~250 ms with
 * the sender's stats and the media bitrate actually produced, and feed [targetBps] to the encoder.
 *
 * Signals (both seen by the sender before anything is lost):
 *  - queue delay: how far each link's send->ack delay sits above its own minimum, weighted by traffic. Cellular
 *    buffers are deep, so loss arrives seconds too late; delay rises first;
 *  - overhead: bytes on the links / media bytes. Normally ~1.1-1.25 (SRT headers, ordinary resends). Across links
 *    with different delays, packets arrive reordered and the SRT receiver asks for resends of packets that weren't
 *    lost; past a point that feeds itself (a retransmission spiral), and overhead jumps to 1.6-2.5x at once, with queueing.
 * Not signals: NAKs (mostly reordering on bonded links) and packets over the links' in-flight caps (every keyframe
 * briefly overflows them; a sustained overflow shows up as queue delay).
 *
 * Policy, AIMD with memory:
 *  - congested -> x[DECREASE_FACTOR]; within one episode, again only while the queue keeps growing (at most every
 *    [DECREASE_INTERVAL_MS]); the level where the episode started becomes [knownLimitBps];
 *  - clear (queue < preset.rampQueueMs) for [HOLD_AFTER_DECREASE_MS] -> ramp fast up to 85% of the known limit; above
 *    that, probe slowly, and only once [SETTLE_MS] have passed since the last episode began (each probe past the cliff
 *    risks a storm). Before any congestion, probing is faster ([RAMP_STARTUP_PER_S]) so good links reach full
 *    quality in seconds rather than a minute;
 *  - otherwise hold.
 */
class BitrateController(
    val minBps: Int,
    val maxBps: Int,
    startBps: Int = min(maxBps, max(minBps, DEFAULT_START_BPS)),
    val preset: DelayPreset = DelayPreset.BALANCED,
) {
    var targetBps: Int = startBps.coerceIn(minBps, maxBps)
        private set
    /**
     * Bitrate at which the bond last congested; ramping slows down near it. Until the first congestion it sits just
     * above the start, so the controller probes upward gently instead of racing into a cliff it hasn't found yet.
     */
    var knownLimitBps: Int = (startBps.coerceIn(minBps, maxBps) * START_LIMIT_FACTOR).roundToInt()
        private set
    /**
     * An outside cap (the phone's heat): pulls the target down to what's really sent, so the next congestion cut starts
     * from there rather than from an uncapped number above it that the encoder never reaches. Never raises anything.
     */
    fun capAt(capBps: Int) {
        val c = capBps.coerceIn(minBps, maxBps)
        if (targetBps > c) targetBps = c
    }

    /** Why the last change happened, for logs and the stats line. */
    var lastAction: String = "start"
        private set
    /** Link bytes per media byte, smoothed. */
    var overhead: Double = 1.0
        private set

    private var lastUpdateMs = Long.MIN_VALUE
    private var lastDecreaseMs = Long.MIN_VALUE
    private var queueAtLastCut = 0
    private var episodeStartMs = Long.MIN_VALUE

    /** [mediaBps]: what the encoder actually produced recently (CBR encoders undershoot on simple scenes). */
    fun update(nowMs: Long, stats: SenderStats, mediaBps: Long = targetBps.toLong()): Int {
        val first = lastUpdateMs == Long.MIN_VALUE
        val dtMs = if (first) 0L else nowMs - lastUpdateMs
        lastUpdateMs = nowMs
        if (first || stats.connectedLinks == 0) {
            lastAction = if (first) "start" else "hold (no links)" // a tunnel: nothing to measure, keep the target
            return targetBps
        }
        if (mediaBps > 0 && stats.totalTxBitsPerSec > 0) {
            val r = stats.totalTxBitsPerSec.toDouble() / mediaBps
            overhead = overhead * 0.5 + r * 0.5
        }
        val queue = stats.queueDelayMs
        val sinceDecrease = if (lastDecreaseMs == Long.MIN_VALUE) Long.MAX_VALUE else nowMs - lastDecreaseMs
        // A real resend storm always comes with queueing; overhead alone can spike for a second because the link
        // rate is measured over 1 s while the target moves every 250 ms.
        val storm = overhead > STORM_OVERHEAD && queue > preset.rampQueueMs

        if (queue > preset.backOffQueueMs || storm) {
            val newEpisode = sinceDecrease >= EPISODE_GAP_MS
            // After a cut the SRT sender keeps resending its backlog for a few seconds, so queue and overhead stay
            // high although the source is no longer the cause. Within an episode, cut again only while the queue is
            // still growing; otherwise the last cut is working and the queue just needs time to drain.
            val worsening = queue > queueAtLastCut
            if (sinceDecrease >= DECREASE_INTERVAL_MS && (newEpisode || worsening)) {
                if (newEpisode) { // only an episode's first cut marks where the cliff is
                    knownLimitBps = targetBps
                    episodeStartMs = nowMs
                }
                targetBps = (targetBps * DECREASE_FACTOR).roundToInt().coerceIn(minBps, maxBps)
                lastDecreaseMs = nowMs
                queueAtLastCut = queue
                lastAction = "down (queue ${queue}ms, overhead ${"%.2f".format(overhead)})"
            } else {
                lastAction = "hold (draining)"
            }
        } else if (stats.totalTxBitsPerSec < targetBps * APP_LIMITED_SHARE) {
            // The links aren't carrying anything like the target (still connecting, or the encoder undershoots a
            // simple scene): no delay samples, so a short queue proves nothing. Never ramp blind (in testing, ramping
            // while the stream hadn't started yet put the target over the cliff before the first packet).
            lastAction = "hold (not sending at target yet)"
        } else if (queue < preset.rampQueueMs && sinceDecrease >= HOLD_AFTER_DECREASE_MS) {
            val fast = targetBps < knownLimitBps * NEAR_LIMIT
            // Past a bond's cliff, storms start at random (the bond is bistable); every probe risks one. So after an
            // episode, sit just under the cliff for a while before probing again.
            val mayProbe = episodeStartMs == Long.MIN_VALUE || nowMs - episodeStartMs >= SETTLE_MS
            if (!fast && !mayProbe) {
                lastAction = "hold (settled under ${knownLimitBps / 1000} kbps)"
                return targetBps
            }
            val startup = episodeStartMs == Long.MIN_VALUE
            // No cliff found yet: climb fast to full quality, but only while queues are clearly short. The delay
            // signal is still settling in the first seconds, and a startup storm is the costliest kind.
            if (!fast && startup && queue >= preset.rampQueueMs / 2) {
                lastAction = "hold (queue ${queue}ms)"
                return targetBps
            }
            val rate = when {
                fast -> RAMP_FAST_PER_S
                startup -> RAMP_STARTUP_PER_S
                else -> RAMP_PROBE_PER_S
            }
            val step = max(MIN_STEP_BPS_PER_S, targetBps * rate) * dtMs / 1000.0
            targetBps = (targetBps + step).roundToInt().coerceIn(minBps, maxBps)
            if (targetBps > knownLimitBps) knownLimitBps = targetBps // probed past it without trouble
            lastAction = when {
                targetBps == maxBps -> "at ceiling"
                fast -> "up"
                else -> "probe"
            }
        } else {
            lastAction = "hold (queue ${queue}ms)"
        }
        return targetBps
    }

    companion object {
        /**
         * Cold start: no delay or capacity measurements yet, so start with margin. The startup probe gets good links
         * to full quality in seconds. (In testing, starting at 3 Mbps on a 5.5 Mbps capped bond, which tips at
         * ~3.3, occasionally lost a couple of packets in the first second.)
         */
        const val DEFAULT_START_BPS = 2_500_000
        const val DECREASE_FACTOR = 0.75
        const val DECREASE_INTERVAL_MS = 1_000L
        const val HOLD_AFTER_DECREASE_MS = 2_000L
        /** Link bytes / media bytes above this = resend storm. */
        const val STORM_OVERHEAD = 1.6
        /** Cuts closer together than this belong to the same congestion episode. */
        const val EPISODE_GAP_MS = 5_000L
        const val NEAR_LIMIT = 0.85
        const val RAMP_FAST_PER_S = 0.08
        const val RAMP_PROBE_PER_S = 0.01
        /** Probing before the first congestion: ~3 -> 6 Mbps in ~15 s on links with headroom. */
        const val RAMP_STARTUP_PER_S = 0.05
        /** Ramp only while the links carry at least this share of the target. */
        const val APP_LIMITED_SHARE = 0.5
        const val SETTLE_MS = 30_000L
        /** Before any congestion, the "known limit" sits this far above the start bitrate. */
        const val START_LIMIT_FACTOR = 1.2
        const val MIN_STEP_BPS_PER_S = 50_000.0
    }
}
