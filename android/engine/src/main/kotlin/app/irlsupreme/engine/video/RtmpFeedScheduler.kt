package app.irlsupreme.engine.video

/**
 * When the RTMP camera's compressed frames go into the decoder, and when its decoded frames come out, pure so it can
 * be tested.
 *
 * Input, by decode order: frames arrive in decode (DTS) order, and with B-frames their presentation times (PTS) jump
 * about: I0 P3 B1 B2... Feeding each one a fixed lead before its PTS is due made a P frame (due later) block the B
 * frames queued behind it, which are due sooner. [feedDueNs] subtracts each frame's composition offset (PTS - DTS),
 * which puts it on its DTS on the same clock mapping: rising in queue order, so the head never holds up a frame due
 * earlier. Without B-frames the offset is 0 and nothing changes.
 *
 * Output, by presentation time: a decoder writing into a SurfaceTexture ignores releaseOutputBuffer's timestamp (the
 * consumer takes frames as soon as they're queued), so pacing means holding the decoded buffer and releasing it at
 * its due time ([OutputPacer]).
 */
object RtmpFeedScheduler {
    /** [ptsDueNs]: local time the frame should be shown; [leadNs]: how long ahead the decoder needs it. */
    fun feedDueNs(ptsDueNs: Long, ptsMs: Long, dtsMs: Long, leadNs: Long): Long {
        val ctsMs = (ptsMs - dtsMs).coerceAtLeast(0)
        return ptsDueNs - ctsMs * 1_000_000L - leadNs
    }

    /** What to do with the oldest held output buffer. */
    enum class Action { WAIT, RENDER, DROP }

    /**
     * [dueNs] is the frame's presentation time ([NOW] during the clock's warm-up, when there's no schedule yet).
     * Released on time; dropped unrendered if it's already more than [intervalNs] late and [newerWaiting] (a later
     * decoded frame is held behind it), since showing it would only push that one later still. A frame that's late
     * with nothing behind it is still shown: a decoder that runs consistently slow must not end up showing nothing.
     */
    fun decide(nowNs: Long, dueNs: Long, intervalNs: Long, newerWaiting: Boolean = true): Action = when {
        dueNs == NOW -> Action.RENDER
        nowNs < dueNs -> Action.WAIT
        nowNs - dueNs > intervalNs && newerWaiting -> Action.DROP
        else -> Action.RENDER
    }

    /** "Show as soon as decoded." */
    const val NOW = Long.MIN_VALUE
}

/**
 * Decoded output buffers waiting for their presentation time, oldest first (decoders output in presentation order).
 * A fixed ring of [capacity]: holding more would starve the decoder of buffers. Allocation-free; one thread.
 */
class OutputPacer(val capacity: Int = 3) {
    private val indices = IntArray(capacity)
    private val dues = LongArray(capacity)
    private var first = 0
    var size = 0
        private set

    val isFull: Boolean get() = size == capacity
    val headIndex: Int get() = indices[first]
    val headDueNs: Long get() = dues[first]

    fun add(index: Int, dueNs: Long) {
        check(size < capacity) { "pacer full" }
        val slot = (first + size) % capacity
        indices[slot] = index
        dues[slot] = dueNs
        size++
    }

    fun removeHead() {
        check(size > 0)
        first = (first + 1) % capacity
        size--
    }

    fun clear() {
        first = 0
        size = 0
    }
}
