// SPDX-License-Identifier: Apache-2.0
package srtlakt.abr

import srtlakt.LinkPhase
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
    ;

    companion object {
        /**
         * The preset for the SRT latency the stream really runs with, so the thresholds always match it: up to 1.5 s
         * is [FAST], up to 3 s [BALANCED], anything longer [ROCK_SOLID].
         */
        fun forLatency(latencyMs: Int): DelayPreset = when {
            latencyMs <= 1_500 -> FAST
            latencyMs <= 3_000 -> BALANCED
            else -> ROCK_SOLID
        }
    }
}

/**
 * Delay-based bitrate control for a bonded stream. Pure and clock-free: call [update] every ~250 ms with
 * the sender's stats and the media bitrate actually produced, and feed [targetBps] to the encoder.
 *
 * Signals (seen by the sender before anything is lost):
 *  - queue delay: how far each link's send->ack delay sits above its own minimum, weighted by traffic. Cellular
 *    buffers are deep, so loss arrives seconds too late; delay rises first;
 *  - overhead: bytes on the links / media bytes. Normally ~1.1-1.25 (SRT headers, ordinary resends). Across links
 *    with different delays, packets arrive reordered and the SRT receiver asks for resends of packets that weren't
 *    lost; past a point that feeds itself (a retransmission spiral), and overhead jumps to 1.6-2.5x at once, with queueing;
 *  - delivery: what the links' receivers confirmed per second. Once every link is full, that is what the bond can
 *    carry, so a collapse is cut to it directly instead of in x0.75 steps a second apart;
 *  - link changes: a link that leaves takes its share of the traffic with it, so the target drops by that share at
 *    once instead of waiting for the queue to show it;
 *  - silence: when no link has heard anything for a while (a tunnel), ACKs are overdue because nothing gets through,
 *    not because of congestion; the controller holds rather than cutting on it.
 * NAKs: a stock receiver NAKs every gap, and on bonded links most gaps are reordering, so they mean nothing there. A
 * receiver that waits out reordering before it NAKs ([receiverReorderTolerant], like the stay-live server) still NAKs
 * when a gap outlasts its wait (very different link delays), so even then a high NAK rate only stops the ramp; cuts
 * come from loss the receiver could not recover ([update]'s unrecoveredLossPerSec). Packets over the links' in-flight
 * caps aren't a signal either: every keyframe briefly overflows them, and a sustained overflow shows up as queue delay.
 *
 * Policy, AIMD with memory:
 *  - congested -> x[DECREASE_FACTOR], and the level where the episode started becomes [knownLimitBps]. Within the
 *    episode, cut again (every [DESCENT_INTERVAL_MS]) only while the links deliver less than is already asked of
 *    them, to min(x0.75, what they deliver less a margin); while they deliver more, the queue is draining and the
 *    cut is working. A queue past a third of the SRT latency is an emergency: x0.5 at once;
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
    /** The receiver waits out reordering before it NAKs (the stay-live server does; stock srtla_rec/SLS don't). */
    val receiverReorderTolerant: Boolean = false,
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

    /**
     * Cold start from a measurement: [capacityBps] is what the links showed they carry before going live (their
     * aggregate delivery rate over a few seconds). Starts at 75% of it and remembers it as the known limit, so the
     * ramp is fast up to there. Call before the first [update]; zero or less is ignored.
     */
    fun seed(capacityBps: Long) {
        if (capacityBps <= 0) return
        targetBps = min(maxBps.toLong(), (capacityBps * SEED_START_SHARE).toLong()).toInt().coerceIn(minBps, maxBps)
        knownLimitBps = capacityBps.coerceIn(minBps.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * The SRT session restarted (the output went down and came back): the links' delay and delivery figures start
     * from scratch. The next [update] resumes at no more than 60% of the known limit and holds a while before ramping.
     */
    fun onResume() { resumePending = true }

    /** Why the last change happened, for logs and the stats line. */
    var lastAction: String = "start"
        private set
    /** Link bytes per media byte, smoothed. */
    var overhead: Double = 1.0
        private set
    /** NAKed packets per packet sent over the last ~2 s (0 before there are enough packets to tell). */
    var nakRate: Double = 0.0
        private set

    private var lastUpdateMs = Long.MIN_VALUE
    private var lastDecreaseMs = Long.MIN_VALUE
    private var queueAtLastCut = 0
    private var episodeStartMs = Long.MIN_VALUE
    /** Where the last episode began; probing well past it means the cliff has moved. */
    private var episodeLimitBps = 0
    private var congestedSinceMs = Long.MIN_VALUE
    private var silentSinceMs = Long.MIN_VALUE
    private var silenceEndedMs = Long.MIN_VALUE
    private var noLinksSinceMs = Long.MIN_VALUE
    private var resumePending = false

    // Delivery over the last second (the link stats update it every 500 ms; ticks are ~250 ms).
    private val deliveredRing = LongArray(DELIVERED_TICKS)
    private var deliveredIdx = 0

    // Each connected link's share of what was sent, as of the last tick. Fixed arrays: no allocation per tick.
    private val shareIds = IntArray(MAX_TRACKED_LINKS)
    private val shares = DoubleArray(MAX_TRACKED_LINKS)
    private var shareCount = 0

    // NAK and packet deltas per tick over ~2 s.
    private val nakDeltas = LongArray(LOSS_TICKS)
    private val packetDeltas = LongArray(LOSS_TICKS)
    private var lossIdx = 0
    private var lastNaks = -1L
    private var lastPackets = -1L

    // Unrecovered loss in 1 s buckets; bit i of lossHistory = the bucket i seconds ago was bad.
    private var bucketStartMs = Long.MIN_VALUE
    private var bucketLost = 0.0
    private var bucketPackets = 0L
    private var lossHistory = 0

    /**
     * [mediaBps]: what the encoder actually produced recently (CBR encoders undershoot on simple scenes).
     * [unrecoveredLossPerSec]: packets per second the receiver lost for good (reported by the server, 0 when it
     * doesn't): at least [LOSS_MIN_PACKETS] and [LOSS_MIN_SHARE] of what was sent in 2 of 3 seconds is a cut.
     */
    fun update(nowMs: Long, stats: SenderStats, mediaBps: Long = targetBps.toLong(), unrecoveredLossPerSec: Double = 0.0): Int {
        val first = lastUpdateMs == Long.MIN_VALUE
        val dtMs = if (first) 0L else nowMs - lastUpdateMs
        lastUpdateMs = nowMs
        trackLoss(nowMs, stats, dtMs, unrecoveredLossPerSec)
        var connected = 0
        for (i in stats.links.indices) if (stats.links[i].phase == LinkPhase.CONNECTED) connected++
        if (first) {
            rememberShares(stats)
            lastAction = "start"
            return targetBps
        }
        if (connected == 0) { // a tunnel: nothing to measure, keep the target
            if (noLinksSinceMs == Long.MIN_VALUE) noLinksSinceMs = nowMs
            shareCount = 0
            lastAction = "hold (no links)"
            return targetBps
        }
        val queue = stats.queueDelayMs
        // Back from a long gap with no links, or a restarted SRT session: the fresh links' delay and delivery figures
        // need a moment to become valid, and the old target may be far above what they carry.
        val gapEnded = noLinksSinceMs != Long.MIN_VALUE && nowMs - noLinksSinceMs > RESUME_AFTER_NO_LINKS_MS
        noLinksSinceMs = Long.MIN_VALUE
        if (gapEnded || resumePending) {
            resumePending = false
            targetBps = min(targetBps, (knownLimitBps * RESUME_SHARE).roundToInt()).coerceIn(minBps, maxBps)
            lastDecreaseMs = nowMs
            queueAtLastCut = 0
            congestedSinceMs = Long.MIN_VALUE
            deliveredRing.fill(0)
            rememberShares(stats)
            lastAction = "hold (resumed at ${targetBps / 1000} kbps)"
            return targetBps
        }
        // A link that carried a real share left: take its share off at once.
        val lost = lostShare(stats)
        rememberShares(stats)
        if (lost > LINK_LOSS_MIN_SHARE) {
            val old = targetBps
            targetBps = max(minBps.toDouble(), targetBps * (1 - min(lost, LINK_LOSS_MAX_SHARE)) * LINK_LOSS_MARGIN)
                .roundToInt().coerceIn(minBps, maxBps)
            startEpisode(nowMs, old)
            lastDecreaseMs = nowMs
            queueAtLastCut = queue
            congestedSinceMs = nowMs
            deliveredRing.fill(0) // the old figures include the link that left
            lastAction = "down (link lost, carried ${(lost * 100).roundToInt()}%)"
            return targetBps
        }
        recordDelivered(stats)
        // Every link silent: overdue ACKs say nothing gets through, not that the bond is congested. Hold everything.
        if (allQuiet(stats, SILENT_MIN_MS, 3)) {
            if (silentSinceMs == Long.MIN_VALUE) silentSinceMs = nowMs
            lastAction = "hold (all links silent)"
            return targetBps
        }
        if (silentSinceMs != Long.MIN_VALUE) {
            silentSinceMs = Long.MIN_VALUE
            silenceEndedMs = nowMs
        }
        if (mediaBps > 0 && stats.totalTxBitsPerSec > 0) {
            val r = stats.totalTxBitsPerSec.toDouble() / mediaBps
            overhead = overhead * 0.5 + r * 0.5
        }
        val sinceDecrease = if (lastDecreaseMs == Long.MIN_VALUE) Long.MAX_VALUE else nowMs - lastDecreaseMs
        // A real resend storm always comes with queueing; overhead alone can spike for a second because the link
        // rate is measured over 1 s while the target moves every 250 ms.
        val storm = overhead > STORM_OVERHEAD && queue > preset.rampQueueMs

        if (queue > preset.backOffQueueMs || storm) {
            if (congestedSinceMs == Long.MIN_VALUE) congestedSinceMs = nowMs
            congested(nowMs, stats, queue, mediaBps, sinceDecrease)
        } else if (lossHistory.countOneBits() >= LOSS_BAD_SECONDS) {
            congestedSinceMs = Long.MIN_VALUE
            if (sinceDecrease >= DECREASE_INTERVAL_MS) {
                if (sinceDecrease >= EPISODE_GAP_MS) startEpisode(nowMs, targetBps)
                targetBps = (targetBps * DECREASE_FACTOR).roundToInt().coerceIn(minBps, maxBps)
                lastDecreaseMs = nowMs
                queueAtLastCut = queue
                lossHistory = 0
                lastAction = "down (unrecovered loss)"
            } else {
                lastAction = "hold (loss)"
            }
        } else {
            congestedSinceMs = Long.MIN_VALUE
            clear(nowMs, stats, queue, dtMs, sinceDecrease)
        }
        return targetBps
    }

    private fun congested(nowMs: Long, stats: SenderStats, queue: Int, mediaBps: Long, sinceDecrease: Long) {
        val emergency = queue > preset.srtLatencyMs / 3
        val newEpisode = sinceDecrease >= EPISODE_GAP_MS
        val delivered = deliveredRing.max()
        val clampedOverhead = overhead.coerceIn(OVERHEAD_CLAMP_MIN, OVERHEAD_CLAMP_MAX)
        val deliveredMedia = delivered / clampedOverhead
        // Compared with what's already asked of the encoder, not only what it still produces: after a cut it keeps
        // sending at the old rate for ~0.5 s, and that lag mustn't be read as the bond falling further behind.
        val asked = min(mediaBps, targetBps.toLong()).toDouble()
        val shortfall = delivered > 0 && deliveredMedia < asked * SHORTFALL_SHARE
        // What the bond can carry, once congestion has lasted a delivery window and every link is full (a link with
        // room left could carry more, so its delivery says nothing about capacity).
        val anchor = if (delivered > 0 && nowMs - congestedSinceMs >= DELIVERY_WINDOW_MS && allFull(stats)) {
            (deliveredMedia * ANCHOR_MARGIN).roundToInt()
        } else Int.MAX_VALUE
        val afterSilence = silenceEndedMs != Long.MIN_VALUE && nowMs - silenceEndedMs < SILENCE_GRACE_MS
        val factor = if (emergency) EMERGENCY_FACTOR else DECREASE_FACTOR
        when {
            // Nothing at all has come back for a few hundred ms: overdue ACKs, not congestion (with traffic flowing,
            // the receiver's ACKs arrive every ~10 ms). Wait to see whether it becomes a silence.
            allQuiet(stats, QUIET_MIN_MS, 2) -> lastAction = "hold (links quiet)"
            // The backlog a silence left behind drains by itself once the links are back, unless they carry less now.
            afterSilence && !shortfall -> lastAction = "hold (draining after silence)"
            newEpisode -> {
                // The first cut of an episode never anchors to delivery: before congestion the links were carrying
                // only what the encoder gave them, which says nothing about what they could carry.
                startEpisode(nowMs, targetBps)
                cut(nowMs, queue, if (emergency) min((targetBps * factor).roundToInt(), anchor) else (targetBps * factor).roundToInt(), "down")
            }
            else -> {
                // Within an episode: with a delivery figure, cut again while the bond carries less than is asked of
                // it; without one, while the queue keeps growing.
                val again = if (delivered > 0) shortfall else queue > queueAtLastCut
                val interval = if (delivered > 0 || emergency) DESCENT_INTERVAL_MS else DECREASE_INTERVAL_MS
                if (sinceDecrease >= interval && (again || (emergency && queue > queueAtLastCut))) {
                    cut(nowMs, queue, min((targetBps * factor).roundToInt(), anchor), "down")
                } else {
                    lastAction = "hold (draining)"
                }
            }
        }
    }

    private fun cut(nowMs: Long, queue: Int, to: Int, verb: String) {
        targetBps = to.coerceIn(minBps, maxBps)
        lastDecreaseMs = nowMs
        queueAtLastCut = queue
        lastAction = "$verb (queue ${queue}ms, overhead ${"%.2f".format(overhead)})"
    }

    /** Only an episode's first cut marks where the cliff is. */
    private fun startEpisode(nowMs: Long, levelBps: Int) {
        knownLimitBps = levelBps
        episodeLimitBps = levelBps
        episodeStartMs = nowMs
    }

    private fun clear(nowMs: Long, stats: SenderStats, queue: Int, dtMs: Long, sinceDecrease: Long) {
        if (stats.totalTxBitsPerSec < targetBps * APP_LIMITED_SHARE) {
            // The links aren't carrying anything like the target (still connecting, or the encoder undershoots a
            // simple scene): no delay samples, so a short queue proves nothing. Never ramp blind (in testing, ramping
            // while the stream hadn't started yet put the target over the cliff before the first packet).
            lastAction = "hold (not sending at target yet)"
            return
        }
        if ((receiverReorderTolerant && nakRate > NAK_HOLD_RATE) || (lossHistory and 1) != 0) {
            lastAction = "hold (loss)" // loss isn't a cut by itself, but it is no time to ask for more
            return
        }
        if (queue >= preset.rampQueueMs || sinceDecrease < HOLD_AFTER_DECREASE_MS) {
            lastAction = "hold (queue ${queue}ms)"
            return
        }
        // Probed well past where the last episode began without trouble: that cliff has moved (the links got better),
        // so climb like before any congestion instead of 1% at a time.
        if (episodeStartMs != Long.MIN_VALUE && targetBps > episodeLimitBps * REPROBE_ABOVE_LIMIT) episodeStartMs = Long.MIN_VALUE
        val fast = targetBps < knownLimitBps * NEAR_LIMIT
        // Past a bond's cliff, storms start at random (the bond is bistable); every probe risks one. So after an
        // episode, sit just under the cliff for a while before probing again.
        val mayProbe = episodeStartMs == Long.MIN_VALUE || nowMs - episodeStartMs >= SETTLE_MS
        if (!fast && !mayProbe) {
            lastAction = "hold (settled under ${knownLimitBps / 1000} kbps)"
            return
        }
        val startup = episodeStartMs == Long.MIN_VALUE
        // No cliff found yet: climb fast to full quality, but only while queues are clearly short. The delay
        // signal is still settling in the first seconds, and a startup storm is the costliest kind.
        if (!fast && startup && queue >= preset.rampQueueMs / 2) {
            lastAction = "hold (queue ${queue}ms)"
            return
        }
        // Clean links (barely any queue, ordinary overhead) before any congestion: climb faster still.
        val clean = startup && overhead < CLEAN_OVERHEAD && queue < preset.rampQueueMs / 4
        val rate = when {
            clean -> RAMP_STARTUP_CLEAN_PER_S
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
    }

    private fun recordDelivered(stats: SenderStats) {
        var d = 0L
        for (i in stats.links.indices) {
            val l = stats.links[i]
            if (l.phase == LinkPhase.CONNECTED) d += l.deliveredBps
        }
        deliveredRing[deliveredIdx] = d
        deliveredIdx = (deliveredIdx + 1) % deliveredRing.size
    }

    /** Every connected link has heard nothing for max([minMs], [rtts] x its base RTT). */
    private fun allQuiet(stats: SenderStats, minMs: Long, rtts: Int): Boolean {
        var any = false
        for (i in stats.links.indices) {
            val l = stats.links[i]
            if (l.phase != LinkPhase.CONNECTED) continue
            val ago = l.lastReceivedAgoMs ?: return false
            if (ago <= max(minMs, rtts.toLong() * (l.minRttMs ?: 0))) return false
            any = true
        }
        return any
    }

    /** Every connected link is at its in-flight cap or queueing past the back-off threshold (its ACKs overdue). */
    private fun allFull(stats: SenderStats): Boolean {
        for (i in stats.links.indices) {
            val l = stats.links[i]
            if (l.phase != LinkPhase.CONNECTED) continue
            if (l.ownInFlight < l.capPackets && l.queueDelayMs <= preset.backOffQueueMs) return false
        }
        return true
    }

    private fun rememberShares(stats: SenderStats) {
        var total = 0L
        for (i in stats.links.indices) {
            val l = stats.links[i]
            if (l.phase == LinkPhase.CONNECTED) total += l.txBitsPerSec
        }
        shareCount = 0
        if (total <= 0) return
        for (i in stats.links.indices) {
            val l = stats.links[i]
            if (l.phase != LinkPhase.CONNECTED || shareCount == MAX_TRACKED_LINKS) continue
            shareIds[shareCount] = l.id
            shares[shareCount] = l.txBitsPerSec.toDouble() / total
            shareCount++
        }
    }

    /** The summed share (as of the last tick) of links that were connected then and aren't now. */
    private fun lostShare(stats: SenderStats): Double {
        var lost = 0.0
        for (k in 0 until shareCount) {
            var still = false
            for (i in stats.links.indices) {
                val l = stats.links[i]
                if (l.id == shareIds[k] && l.phase == LinkPhase.CONNECTED) { still = true; break }
            }
            if (!still) lost += shares[k]
        }
        return lost
    }

    private fun trackLoss(nowMs: Long, stats: SenderStats, dtMs: Long, unrecoveredLossPerSec: Double) {
        var naks = 0L
        var packets = 0L
        for (i in stats.links.indices) {
            naks += stats.links[i].naks
            packets += stats.links[i].packetsSent
        }
        // Counters restart with a new session (or a link leaving takes its counts along): a drop is a restart, not
        // negative traffic.
        val dNaks = if (lastNaks < 0 || naks < lastNaks) 0L else naks - lastNaks
        val dPackets = if (lastPackets < 0 || packets < lastPackets) 0L else packets - lastPackets
        lastNaks = naks
        lastPackets = packets
        nakDeltas[lossIdx] = dNaks
        packetDeltas[lossIdx] = dPackets
        lossIdx = (lossIdx + 1) % LOSS_TICKS
        val sentRecently = packetDeltas.sum()
        nakRate = if (sentRecently >= NAK_MIN_PACKETS) nakDeltas.sum().toDouble() / sentRecently else 0.0

        if (bucketStartMs == Long.MIN_VALUE) bucketStartMs = nowMs
        bucketLost += max(0.0, unrecoveredLossPerSec) * dtMs / 1000.0
        bucketPackets += dPackets
        if (nowMs - bucketStartMs >= LOSS_BUCKET_MS) {
            val bad = bucketLost >= LOSS_MIN_PACKETS && bucketPackets > 0 && bucketLost / bucketPackets >= LOSS_MIN_SHARE
            lossHistory = ((lossHistory shl 1) or (if (bad) 1 else 0)) and LOSS_WINDOW_MASK
            bucketStartMs = nowMs
            bucketLost = 0.0
            bucketPackets = 0
        }
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
        /** A queue past a third of the SRT latency is close to packets arriving too late: halve at once. */
        const val EMERGENCY_FACTOR = 0.5
        /** Within an episode, how often a cut may follow while the bond delivers less than is asked of it. */
        const val DESCENT_INTERVAL_MS = 500L
        /** LinkState's delivery window: a delivery figure needs this long to describe the congested state. */
        const val DELIVERY_WINDOW_MS = 500L
        /** Delivered (in media terms) below this share of what's asked = the bond is falling behind. */
        const val SHORTFALL_SHARE = 0.9
        /** A delivery-anchored cut lands this far under what the links delivered, so the queue drains. */
        const val ANCHOR_MARGIN = 0.85
        /** Overhead used to turn delivered link bytes into media, clamped so a storm's spike doesn't distort it. */
        const val OVERHEAD_CLAMP_MIN = 1.1
        const val OVERHEAD_CLAMP_MAX = 1.3
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
        /**
         * Before the first congestion, on clean links (overhead under [CLEAN_OVERHEAD], queue under a quarter of the
         * ramp threshold). A measured start ([seed]) needs about this to get from 75% to past the measured capacity
         * within 5 s.
         */
        const val RAMP_STARTUP_CLEAN_PER_S = 0.10
        const val CLEAN_OVERHEAD = 1.3
        /** Ramp only while the links carry at least this share of the target. */
        const val APP_LIMITED_SHARE = 0.5
        const val SETTLE_MS = 30_000L
        /** Probing this far past where the last episode began, without trouble, means that cliff has moved. */
        const val REPROBE_ABOVE_LIMIT = 1.1
        /** Before any congestion, the "known limit" sits this far above the start bitrate. */
        const val START_LIMIT_FACTOR = 1.2
        const val MIN_STEP_BPS_PER_S = 50_000.0
        /** A measured start ([seed]) begins at this share of the measured capacity. */
        const val SEED_START_SHARE = 0.75
        /** A link silent this long (or 3x its base RTT, if longer) has heard nothing at all. */
        const val SILENT_MIN_MS = 1_000L
        /** All links quiet this long (or 2x their base RTT) defers a congestion cut: it may be the start of a silence. */
        const val QUIET_MIN_MS = 300L
        /** After a silence ends, its leftover backlog is given this long to drain before it can count as congestion. */
        const val SILENCE_GRACE_MS = 3_000L
        /** A gap with no connected links longer than this, or an SRT restart, resumes at [RESUME_SHARE] of the limit. */
        const val RESUME_AFTER_NO_LINKS_MS = 2_000L
        const val RESUME_SHARE = 0.6
        /** Links that left carrying more than this share of the traffic cut the target by that share at once. */
        const val LINK_LOSS_MIN_SHARE = 0.2
        /**
         * The share taken off is capped: shares follow the scheduler, not capacity, so a survivor that carried little
         * may well carry much more. The ramp and the queue take it from there.
         */
        const val LINK_LOSS_MAX_SHARE = 0.7
        const val LINK_LOSS_MARGIN = 0.9
        private const val MAX_TRACKED_LINKS = 16
        private const val DELIVERED_TICKS = 4
        /** NAK rate above this (with a reorder-tolerant receiver) stops the ramp; it never cuts by itself. */
        const val NAK_HOLD_RATE = 0.015
        private const val NAK_MIN_PACKETS = 50L
        private const val LOSS_TICKS = 8
        private const val LOSS_BUCKET_MS = 1_000L
        /** Unrecovered loss counts in a second with at least this many lost packets and this share of those sent. */
        const val LOSS_MIN_PACKETS = 3.0
        const val LOSS_MIN_SHARE = 0.005
        /** Bad seconds out of the last three that make a cut (the server's own Weak rule). */
        const val LOSS_BAD_SECONDS = 2
        private const val LOSS_WINDOW_MASK = 0b111
    }
}
