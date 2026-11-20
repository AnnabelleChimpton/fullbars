package app.irlsupreme.engine.video

/**
 * Rate over a true sliding window: a small ring of (time, bytes) samples, the oldest dropped as the window moves, so
 * the value follows every sample instead of jumping once per window.
 *
 * The rate is worked out between samples: the bytes of every sample after the oldest one kept, divided by the time
 * from that oldest sample to the newest. At a steady 30 fps the window then holds exactly 30 frame intervals however
 * the window edge falls between frames, so a constant input reads constant (a plain bytes/window sum jumps by one
 * frame's worth, ~3%, as frames cross the edge).
 *
 * Reads 0 until one full window has passed since the first sample, the same "no measurement yet" the callers already
 * handle. Time going backwards starts it over. Single writer, no allocation after construction.
 */
class RateWindow(private val windowNs: Long = 1_000_000_000L, capacity: Int = 256) {
    init {
        require(windowNs > 0 && capacity >= 2)
    }

    private val times = LongArray(capacity)
    private val sizes = IntArray(capacity)
    private var head = 0 // oldest sample
    private var count = 0
    private var bytes = 0L // sum of sizes in the ring
    private var firstNs = 0L
    private var started = false

    fun add(timeNs: Long, size: Int) {
        if (count > 0 && timeNs < newestNs()) reset()
        if (!started) { firstNs = timeNs; started = true }
        if (count == times.size) dropOldest() // more samples per window than the ring holds: the window just shortens
        val i = (head + count) % times.size
        times[i] = timeNs
        sizes[i] = size
        count++
        bytes += size
        val start = timeNs - windowNs
        while (count > 1 && times[head] < start) dropOldest()
    }

    /** Bits per second over the last window; 0 until a full window has been seen. */
    fun bitsPerSecond(): Long {
        if (!warm()) return 0
        val span = newestNs() - times[head]
        return if (dense(span)) {
            (bytes - sizes[head]) * 8 * 1_000_000_000L / span
        } else {
            // Sparse samples (output resumed after a stall): fall back to what arrived inside the window.
            bytes * 8 * 1_000_000_000L / windowNs
        }
    }

    /** Samples per second over the last window (frames per second for an encoder); 0 until a full window. */
    fun perSecond(): Double {
        if (!warm()) return 0.0
        val span = newestNs() - times[head]
        return if (dense(span)) (count - 1) * 1e9 / span else count * 1e9 / windowNs
    }

    fun reset() {
        head = 0; count = 0; bytes = 0; started = false
    }

    /** Enough samples spread over the window to rate between them; a full ring is dense by definition. */
    private fun dense(span: Long) = count >= 2 && span > 0 && (span >= windowNs / 2 || count == times.size)

    private fun warm() = count > 0 && newestNs() - firstNs >= windowNs

    private fun newestNs() = times[(head + count - 1) % times.size]

    private fun dropOldest() {
        bytes -= sizes[head]
        head = (head + 1) % times.size
        count--
    }
}

/**
 * Live encoder output numbers, fed from the drain thread for every frame: bitrate and frame rate over a 1 s sliding
 * window, the size of the last IDR, and the longest IDR-to-IDR interval seen. Timed by the frames' presentation
 * timestamps, which the compositor spaces evenly, so drain-thread jitter doesn't show up as rate noise.
 *
 * 1 s matches the links' own rate window (LinkState's RATE_WINDOW_MS), so the ABR's overhead ratio (link bytes over
 * media bytes) compares the same span of time on both sides.
 *
 * One writer (the drain thread); the published values are volatile so the engine can read them from any thread.
 */
class EncoderMeter(windowNs: Long = 1_000_000_000L) {
    private val window = RateWindow(windowNs)
    private var lastIdrUs = NONE

    /** Encoder output, bits per second, over the window. 0 until the first full window. */
    @Volatile var bitsPerSecond = 0L
        private set

    /** Output frames per second over the window. 0 until the first full window. */
    @Volatile var framesPerSecond = 0.0
        private set

    /** Bytes of the most recent IDR (0 before the first). Big IDRs are the bursts the links have to absorb. */
    @Volatile var lastIdrBytes = 0
        private set

    /**
     * Longest time from one IDR to the next, in ms of media time; the gap still open counts too, so an encoder that
     * stops producing IDRs shows up before the next one arrives. The server's slate splice and Twitch both wait for an
     * IDR, so this is how long a viewer can be stuck after a join or a dropout.
     */
    @Volatile var maxIdrIntervalMs = 0L
        private set

    @Volatile var frames = 0L
        private set

    fun onFrame(ptsUs: Long, bytes: Int, key: Boolean) {
        window.add(ptsUs * 1_000, bytes)
        bitsPerSecond = window.bitsPerSecond()
        framesPerSecond = window.perSecond()
        if (lastIdrUs != NONE && ptsUs >= lastIdrUs) {
            val gapMs = (ptsUs - lastIdrUs) / 1_000
            if (gapMs > maxIdrIntervalMs) maxIdrIntervalMs = gapMs
        }
        if (key) {
            lastIdrUs = ptsUs
            lastIdrBytes = bytes
        }
        frames++
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
