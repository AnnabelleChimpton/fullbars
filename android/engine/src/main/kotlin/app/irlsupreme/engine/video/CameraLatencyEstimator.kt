package app.irlsupreme.engine.video

/**
 * The clock a camera's frame timestamps are on, so they can be compared with System.nanoTime (CLOCK_MONOTONIC).
 *
 *  - [MONOTONIC]: already on it. Android 13+ gets this by asking for it (OutputConfiguration.TIMESTAMP_BASE_MONOTONIC).
 *  - [REALTIME]: CLOCK_BOOTTIME (SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME on Android 12), which runs ahead of the
 *    monotonic clock by the time the phone has spent asleep; converted with a back-to-back sample of both clocks.
 *  - [UNKNOWN]: SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN on Android 12. It's usually monotonic, but nothing promises it,
 *    so no latency is claimed from it.
 */
enum class CaptureTimeBase { MONOTONIC, REALTIME, UNKNOWN }

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

    /** Median latency (ns), or null before the first full window or while the camera's clock is unknown. */
    @Volatile var medianNs: Long? = null
        private set
    @Volatile var p95Ns: Long? = null
        private set
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
        medianNs = null
        p95Ns = null
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
        medianNs = if (n % 2 == 1) scratch[n / 2] else (scratch[n / 2 - 1] + scratch[n / 2]) / 2
        p95Ns = scratch[(kotlin.math.ceil(0.95 * n).toInt() - 1).coerceIn(0, n - 1)]
    }

    companion object {
        /** Past this a "latency" is a clock mismatch (or a stuck frame), not the camera pipeline. */
        const val MAX_LATENCY_NS = 300_000_000L
    }
}
