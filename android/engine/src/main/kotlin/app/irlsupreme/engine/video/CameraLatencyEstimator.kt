package app.irlsupreme.engine.video

/**
 * The clock a camera's frame timestamps are on, so they can be compared with System.nanoTime (CLOCK_MONOTONIC).
 *
 *  - [MONOTONIC]: already on it.
 *  - [REALTIME]: CLOCK_BOOTTIME (SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME), which runs ahead of the monotonic clock by
 *    the time the phone has spent asleep; converted with a back-to-back sample of both clocks.
 *  - [UNKNOWN]: SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN. It's usually monotonic, but nothing promises it, so it's worked
 *    out from the frames themselves ([TimeBaseVote]) before any latency is claimed.
 *
 * Android 13+ could ask for monotonic timestamps outright (OutputConfiguration.TIMESTAMP_BASE_MONOTONIC), but for a
 * SurfaceTexture output only the default base lets the camera framework re-space frame delivery to cut jitter, and a
 * smooth picture matters more than a tidy clock. So the default base stays, and the clock is read as above.
 */
enum class CaptureTimeBase { MONOTONIC, REALTIME, UNKNOWN }

/**
 * Works out which clock a camera's timestamps are really on, from the frames. Each latched frame's timestamp is read
 * on both clocks, and the one that puts its capture 0..[CameraLatencyEstimator.MAX_LATENCY_NS] in the past gets a
 * vote. The answer changes only after [needed] votes in a row for the other clock, so one odd frame can't flip it.
 *
 * Seeded with what the camera reports ([reset]): REALTIME is believed until the frames say otherwise (a HAL that
 * mislabels its clock would otherwise show no latency at all); UNKNOWN starts with no answer. When the two clocks
 * agree to within [agreeNs] (a phone that hasn't slept since boot) either reading is right, and it votes monotonic;
 * when both fit but disagree, the frame tells us nothing and doesn't vote.
 *
 * Allocation-free; one thread (the render thread).
 */
class TimeBaseVote(private val needed: Int = 10, private val agreeNs: Long = 2_000_000L) {
    /** The clock in use, or null while it isn't known yet. */
    var base: CaptureTimeBase? = null
        private set
    private var challenger: CaptureTimeBase? = null
    private var streak = 0

    /** Starts over for a (re)opened camera that reports [reported]. */
    fun reset(reported: CaptureTimeBase) {
        base = if (reported == CaptureTimeBase.UNKNOWN) null else reported
        challenger = null
        streak = 0
    }

    /**
     * One latched frame: its timestamp [sensorTsNs], the tick it was latched on [nowMonoNs] (System.nanoTime clock),
     * and elapsedRealtimeNanos - nanoTime [bootMinusMonoNs]. Returns the clock to read it on, or null.
     */
    fun observe(sensorTsNs: Long, nowMonoNs: Long, bootMinusMonoNs: Long): CaptureTimeBase? {
        val max = CameraLatencyEstimator.MAX_LATENCY_NS
        val asMono = nowMonoNs - sensorTsNs
        val asBoot = asMono + bootMinusMonoNs
        val monoFits = asMono in 0..max
        val bootFits = asBoot in 0..max
        val vote = when {
            monoFits && bootFits -> if (kotlin.math.abs(bootMinusMonoNs) <= agreeNs) CaptureTimeBase.MONOTONIC else null
            monoFits -> CaptureTimeBase.MONOTONIC
            bootFits -> CaptureTimeBase.REALTIME
            else -> null
        }
        if (vote == null) return base // tells us nothing
        if (vote == base) {
            streak = 0 // the run for the other clock is broken
        } else {
            if (vote == challenger) streak++ else { challenger = vote; streak = 1 }
            if (streak >= needed) { base = vote; challenger = null; streak = 0 }
        }
        return base
    }
}

object CaptureClock {
    /**
     * A frame's capture time [sensorTsNs] on the System.nanoTime clock, or null when its clock is [CaptureTimeBase.UNKNOWN].
     * [bootMinusMonoNs]: elapsedRealtimeNanos() - nanoTime() sampled together (only used for REALTIME).
     */
    fun toMonotonicNs(sensorTsNs: Long, base: CaptureTimeBase, bootMinusMonoNs: Long): Long? = when (base) {
        CaptureTimeBase.MONOTONIC -> sensorTsNs
        CaptureTimeBase.REALTIME -> sensorTsNs - bootMinusMonoNs
        CaptureTimeBase.UNKNOWN -> null
    }

    /**
     * elapsedRealtimeNanos() - nanoTime(), from the tightest of [tries] back-to-back samples (the boot clock read
     * between two monotonic reads, against their midpoint), so a preemption mid-sample doesn't skew it.
     */
    inline fun bootMinusMono(tries: Int = 3, mono: () -> Long, boot: () -> Long): Long {
        var best = Long.MAX_VALUE
        var offset = 0L
        repeat(tries) {
            val a = mono()
            val b = boot()
            val c = mono()
            if (c - a < best) {
                best = c - a
                offset = b - (a + (c - a) / 2)
            }
        }
        return offset
    }
}

/**
 * How long the phone camera's picture takes from the sensor to the stream: tick time minus capture time, per
 * latched frame. Audio is stamped at capture, video at the tick, so this is how far the picture lags the sound;
 * the audio side can delay itself by it ([medianNs]).
 *
 * Keeps the last [windowNs] of samples and publishes their median and 95th percentile every few samples. A sample
 * outside 0..[MAX_LATENCY_NS] is a clock mix-up, not a latency, and is dropped. [reset] (a flip, a reopen, a
 * stabilisation change: each can change the pipeline's delay) empties the window but keeps the published values
 * until [minSamples] new ones are in, so a consumer never sees the number vanish or jump to a half-measured one.
 *
 * Allocation-free after construction. Write from one thread (the render thread); [medianNs] and [p95Ns] can be
 * read from any.
 */
class CameraLatencyEstimator(
    private val windowNs: Long = 2_000_000_000L,
    capacity: Int = 256,
    private val minSamples: Int = 20,
    private val publishEvery: Int = 8,
) {
    private val times = LongArray(capacity)
    private val values = LongArray(capacity)
    private val scratch = LongArray(capacity)
    private var head = 0 // next slot to write
    private var size = 0
    private var sinceReset = 0
    private var sincePublish = 0

    // Published as plain longs (-1: none) so the render thread never boxes; a reader gets a Long? when it asks.
    @Volatile private var median = NONE
    @Volatile private var p95 = NONE

    /** Median latency (ns), or null before the first full window or while the camera's clock is unknown. */
    val medianNs: Long? get() = median.takeIf { it != NONE }
    val p95Ns: Long? get() = p95.takeIf { it != NONE }
    /** A value is published (the same as medianNs != null, without boxing). */
    val hasValue: Boolean get() = median != NONE
    /** Samples refused for being out of range (diagnostics). */
    @Volatile var rejected = 0L
        private set

    /** Adds one frame's latency, measured at [nowNs]. Returns false if it was out of range and dropped. */
    fun record(nowNs: Long, latencyNs: Long): Boolean {
        if (latencyNs < 0 || latencyNs > MAX_LATENCY_NS) {
            rejected++
            return false
        }
        times[head] = nowNs
        values[head] = latencyNs
        head = (head + 1) % times.size
        if (size < times.size) size++
        sinceReset++
        if (++sincePublish >= publishEvery && sinceReset >= minSamples) {
            sincePublish = 0
            publish(nowNs)
        }
        return true
    }

    /** The pipeline changed: measure afresh, holding the last values until the new ones are ready. */
    fun reset() {
        size = 0
        sinceReset = 0
        sincePublish = 0
    }

    /** The camera's clock can't be trusted (or the camera is gone for good): no latency is claimed. */
    fun clear() {
        reset()
        median = NONE
        p95 = NONE
    }

    private fun publish(nowNs: Long) {
        var n = 0
        for (i in 0 until size) {
            val slot = (head - 1 - i + times.size) % times.size
            if (nowNs - times[slot] > windowNs) break // older ones are older still
            scratch[n++] = values[slot]
        }
        if (n == 0) return
        java.util.Arrays.sort(scratch, 0, n)
        median = if (n % 2 == 1) scratch[n / 2] else (scratch[n / 2 - 1] + scratch[n / 2]) / 2
        p95 = scratch[(kotlin.math.ceil(0.95 * n).toInt() - 1).coerceIn(0, n - 1)]
    }

    companion object {
        /** Past this a "latency" is a clock mismatch (or a stuck frame), not the camera pipeline. */
        const val MAX_LATENCY_NS = 300_000_000L
        private const val NONE = -1L
    }
}
