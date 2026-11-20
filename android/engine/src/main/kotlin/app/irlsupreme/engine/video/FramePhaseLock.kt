package app.irlsupreme.engine.video

import kotlin.math.abs

/**
 * Keeps the output clock's ticks half a period after the on-air source's frames arrive, so arrival jitter of a few
 * milliseconds can't turn into a repeated frame followed by a dropped one (judder). A slow, bounded phase-locked loop:
 *
 *  - On each tick that saw a new arrival, the arrival's phase on the tick grid, ((arrival - tick) mod period), is
 *    compared with the target, half a period. The error is smoothed (EWMA), unwrapped against the current estimate
 *    so arrivals that sit right on a tick boundary don't average out to "no error".
 *  - The grid is then moved by a fraction of the smoothed error, at most [FrameClock.MAX_NUDGE_NS] per tick. Tick
 *    spacing therefore stays within a quarter millisecond of the period, so PTS stay monotonic and even.
 *  - It only steers while [update] is told it may (the source is on air, LIVE, not flipping, no card, no thermal
 *    reduced rate) and the source's measured interval is within [LOCK_TOLERANCE] of the period. Otherwise the clock
 *    free-runs exactly as it always did, and the estimate starts over next time.
 *
 * A source a little off the nominal rate (a 29.97 fps camera, or a camera crystal 100 ppm out) is simply followed:
 * the grid drifts with it by a few microseconds a tick, far inside the nudge limit. [slavedPeriodNs] can also set the
 * period itself to the measured interval when that's within [SLAVE_TOLERANCE] (the RTMP camera), which removes the
 * small steady phase error the loop would otherwise carry.
 *
 * Pure (no Android, no clock of its own) and allocation-free; render thread only.
 */
class FramePhaseLock(
    /** Share of the smoothed error corrected per tick. */
    private val gain: Double = 0.2,
    /** EWMA weight of each new phase sample. */
    private val alpha: Double = 0.1,
) {
    private var lastCount = 0L
    private var lastArrivalNs = 0L
    private var errNs = 0.0
    private var hasErr = false
    private var intervalNs = 0.0
    private var intervalSamples = 0

    /** Steering this tick (allowed, interval within tolerance, error estimate small). */
    var locked = false
        private set

    /** The smoothed interval between the source's frames (ns), 0 before it's known. */
    val measuredIntervalNs: Double get() = intervalNs

    /** The smoothed phase error (ns): positive when frames arrive later than half a period after a tick. */
    val phaseErrorNs: Double get() = errNs

    /**
     * Render thread, once per tick, after the tick's work. [tickNs] and [periodNs] are this tick's time and the
     * clock's period; [arrivals] and [lastArrivalNs] come from the source's [CadenceMeter]. Returns how far to
     * [FrameClock.nudge] the grid (0 when free-running).
     */
    fun update(tickNs: Long, periodNs: Long, arrivals: Long, lastArrivalNs: Long, allowed: Boolean): Long {
        if (!allowed) {
            reset(arrivals)
            return 0
        }
        val n = arrivals - lastCount
        if (n > 0 && lastArrivalNs != 0L) {
            if (this.lastArrivalNs != 0L && n <= MAX_COUNTED_GAP) {
                val sample = (lastArrivalNs - this.lastArrivalNs).toDouble() / n
                if (sample > 0) {
                    intervalNs = if (intervalSamples == 0) sample else intervalNs + INTERVAL_ALPHA * (sample - intervalNs)
                    intervalSamples++
                }
            }
            this.lastArrivalNs = lastArrivalNs
            val half = periodNs / 2
            var e = Math.floorMod(lastArrivalNs - tickNs, periodNs) - half.toDouble()
            if (hasErr) e = errNs + wrap(e - errNs, periodNs)
            errNs = if (hasErr) errNs + alpha * (e - errNs) else e
            hasErr = true
            if (abs(errNs) > half) errNs = wrap(errNs, periodNs)
        }
        lastCount = arrivals
        val rateOk = intervalSamples >= MIN_INTERVAL_SAMPLES && abs(intervalNs - periodNs) <= periodNs * LOCK_TOLERANCE
        if (!hasErr || !rateOk) {
            locked = false
            return 0
        }
        val want = (gain * errNs).toLong()
        val nudge = want.coerceIn(-FrameClock.MAX_NUDGE_NS, FrameClock.MAX_NUDGE_NS)
        errNs -= nudge // the grid moved; so did every arrival's phase on it
        locked = abs(errNs) < LOCKED_ERROR_NS
        return nudge
    }

    /**
     * The period to run at for a source whose rate may be slightly off nominal: the measured interval when it's
     * within [SLAVE_TOLERANCE] of [nominalNs] and well measured, else [nominalNs]. Rounded to whole microseconds so the
     * clock isn't re-based for noise.
     */
    fun slavedPeriodNs(nominalNs: Long): Long {
        if (intervalSamples < MIN_SLAVE_SAMPLES) return nominalNs
        if (abs(intervalNs - nominalNs) > nominalNs * SLAVE_TOLERANCE) return nominalNs
        return (intervalNs / 1_000).toLong() * 1_000
    }

    /** Forget everything (source switch, flip, card). [arrivals]: the source's count now, so old frames aren't samples. */
    fun reset(arrivals: Long = lastCount) {
        lastCount = arrivals
        lastArrivalNs = 0L
        errNs = 0.0
        hasErr = false
        intervalNs = 0.0
        intervalSamples = 0
        locked = false
    }

    private fun wrap(v: Double, periodNs: Long): Double {
        val p = periodNs.toDouble()
        var r = v % p
        if (r >= p / 2) r -= p
        if (r < -p / 2) r += p
        return r
    }

    companion object {
        /** Steer only for sources within 2% of the output rate: a 25 fps camera into 30 fps needs a matching rate, not this. */
        const val LOCK_TOLERANCE = 0.02
        /** The RTMP camera's period follows it only this close to nominal (29.97 vs 30 is 0.1%). */
        const val SLAVE_TOLERANCE = 0.005
        private const val INTERVAL_ALPHA = 0.05
        private const val MIN_INTERVAL_SAMPLES = 8
        private const val MIN_SLAVE_SAMPLES = 90
        /** More frames than this between two ticks isn't a rate sample (a stall, or the listener was held up). */
        private const val MAX_COUNTED_GAP = 3
        private const val LOCKED_ERROR_NS = 3_000_000.0
    }
}
