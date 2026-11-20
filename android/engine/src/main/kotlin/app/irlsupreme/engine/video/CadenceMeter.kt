package app.irlsupreme.engine.video

import java.util.concurrent.atomic.AtomicLong

/**
 * Durations (ns) in fixed-width buckets, so recording costs an array increment and never allocates. Percentiles come
 * back as the upper edge of their bucket (at most [bucketNs] high), or the largest value seen if they fall past the
 * last bucket. One writer thread; read it on that thread too.
 */
class DurationHistogram(private val bucketNs: Long, buckets: Int) {
    private val counts = IntArray(buckets + 1) // the last one holds everything past the range
    var count = 0L
        private set
    var maxNs = 0L
        private set

    fun record(ns: Long) {
        val v = if (ns < 0) 0 else ns
        val i = (v / bucketNs).coerceAtMost((counts.size - 1).toLong()).toInt()
        counts[i]++
        count++
        if (v > maxNs) maxNs = v
    }

    /** The [q] quantile (0..1); 0 when nothing was recorded. */
    fun percentileNs(q: Double): Long {
        if (count == 0L) return 0
        val rank = kotlin.math.ceil(q.coerceIn(0.0, 1.0) * count).toLong().coerceAtLeast(1)
        var seen = 0L
        for (i in counts.indices) {
            seen += counts[i]
            if (seen >= rank) return if (i == counts.size - 1) maxNs else minOf((i + 1) * bucketNs, maxNs)
        }
        return maxNs
    }

    fun reset() {
        counts.fill(0)
        count = 0
        maxNs = 0
    }
}

/**
 * How smoothly one video source (the phone camera, or the RTMP camera) reaches the stream. Allocation-free.
 *
 *  - [arrivals]: frames the source handed over (counted in its frame-available listener, on its own thread).
 *  - [latched]: frames the render thread actually took. arrivals - latched = frames overwritten before a tick
 *    took them (dropped).
 *  - [repeatTicksWhileLive]: ticks where this source was on air and LIVE, no switch was under way, and no new frame
 *    had come: the stream repeated the previous picture.
 *  - [sourceGaps]: latched frames more than 1.5 expected intervals after the one before, by the source's own
 *    timestamps: a hole in what the source delivered, or a frame lost on the way.
 *  - latched age: tick time minus the moment the latched frame arrived, i.e. how long a frame waited for a tick.
 *    A rising value means a queue is building.
 *
 * Read [arrivals] and [lastArrivalNs] from any thread; everything else belongs to the render thread.
 */
class CadenceMeter(nominalIntervalNs: Long) {
    private val arrivalCount = AtomicLong()
    private val lastArrival = AtomicLong()

    var latched = 0L
        private set
    var repeatTicksWhileLive = 0L
        private set
    var sourceGaps = 0L
        private set
    /** The interval the source's frames should be latched at; a gap is 1.5 of these. Render thread sets it. */
    var expectedIntervalNs = nominalIntervalNs
    /** Window of latched ages, reset by whoever publishes it ([resetWindow]). */
    val latchedAge = DurationHistogram(AGE_BUCKET_NS, AGE_BUCKETS)
    private var lastSourceTsNs = Long.MIN_VALUE

    val arrivals: Long get() = arrivalCount.get()
    /** System.nanoTime of the newest arrival (0 before the first). */
    val lastArrivalNs: Long get() = lastArrival.get()

    /** Listener thread: a frame arrived at [nowNs]. The time is written first, so a reader that sees the count sees it. */
    fun onArrival(nowNs: Long) {
        lastArrival.set(nowNs)
        arrivalCount.incrementAndGet()
    }

    /**
     * Render thread: a frame was latched on the tick at [tickNs]. [sourceTsNs] is the source's own timestamp for it
     * (any time base: only differences are used); [arrivalNs] is when it arrived ([lastArrivalNs] at latch time).
     */
    fun onLatch(tickNs: Long, sourceTsNs: Long, arrivalNs: Long) {
        latched++
        if (lastSourceTsNs != Long.MIN_VALUE && sourceTsNs - lastSourceTsNs > expectedIntervalNs * 3 / 2) sourceGaps++
        lastSourceTsNs = sourceTsNs
        if (arrivalNs != 0L) latchedAge.record(tickNs - arrivalNs)
    }

    /** Render thread, each tick this source is on air, LIVE and not switching: [gotFrame] is what update() returned. */
    fun onLiveTick(gotFrame: Boolean) {
        if (!gotFrame) repeatTicksWhileLive++
    }

    /** After a flip, reopen or reconnect: the next frame starts a new run, so the pause before it isn't a gap. */
    fun restartRun() { lastSourceTsNs = Long.MIN_VALUE }

    fun resetWindow() = latchedAge.reset()

    fun snapshot(): SourceCadence = SourceCadence(
        arrivals = arrivals,
        latched = latched,
        repeatTicksWhileLive = repeatTicksWhileLive,
        sourceGaps = sourceGaps,
        latchedAgeP50Ms = latchedAge.percentileNs(0.5) / 1e6f,
        latchedAgeP95Ms = latchedAge.percentileNs(0.95) / 1e6f,
    )

    private companion object {
        const val AGE_BUCKET_NS = 500_000L
        const val AGE_BUCKETS = 200 // 0-100 ms
    }
}

/** One source's [CadenceMeter] as published: counters since the stream started, ages over the last window. */
data class SourceCadence(
    val arrivals: Long,
    val latched: Long,
    val repeatTicksWhileLive: Long,
    val sourceGaps: Long,
    val latchedAgeP50Ms: Float,
    val latchedAgeP95Ms: Float,
) {
    /** Frames that arrived but were replaced by a newer one before a tick took them. */
    val overwritten: Long get() = (arrivals - latched).coerceAtLeast(0)
}

/**
 * The compositor's frame timing as published every [Compositor.CADENCE_WINDOW_NS] (see [Compositor.cadenceSnapshot]).
 * Counters run from the start of the stream; percentiles cover the last window only.
 */
data class CadenceSnapshot(
    val phone: SourceCadence,
    /** Null without an external camera. */
    val external: SourceCadence?,
    /** Render work per tick (not counting the wait for the tick), this window. */
    val tickWorkP50Ms: Float,
    val tickWorkP99Ms: Float,
    val tickWorkMaxMs: Float,
    /** Ticks whose work took more than 0.8 of a period (late even if none was skipped). */
    val lateTicks: Long,
    /** Ticks the render thread fell behind on (missing from the stream). */
    val skippedTicks: Long,
    /** Ticks the thermal ladder dropped on purpose (zero now that it lowers the rate evenly; kept for old logs). */
    val thermalSkips: Long,
    /** The rate the compositor is running at now. */
    val outputFps: Float,
    /** The output clock is following the on-air source's frames ([FramePhaseLock]). */
    val phaseLocked: Boolean,
    /** Phone camera capture-to-tick latency (median and p95, ms), null when unknown. */
    val cameraLatencyP50Ms: Float?,
    val cameraLatencyP95Ms: Float?,
    /** RTMP camera frames dropped at the decoder output for being more than a frame late. */
    val externalLateDrops: Long,
)
