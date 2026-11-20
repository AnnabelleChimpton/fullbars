package app.irlsupreme.engine.audio

/**
 * Delays the stream's audio against its picture, for lip sync (pure, unit-tested). The picture is stamped when it's
 * drawn, the audio when it's captured; a camera that takes ~100 ms to deliver a frame puts the sound that much ahead
 * of the lips. Shifting the audio's timestamps later by the same amount lines them up again, without touching the
 * video's (which the encoder pacing, the alert cards and the slate all depend on).
 *
 * Each chunk is stamped its capture PTS plus the delay in use. The delay moves towards the one asked for by at most
 * [slewNsPerChunk] per chunk, so PTS stay strictly increasing and the AAC timeline never jumps: a sudden change would
 * be a hole or an overlap in the audio. Whole chunks of the delay are also held back here (floor(delay / chunk)), so
 * each chunk still leaves at about the time its new stamp says, and the stream's audio and video packets stay
 * interleaved by time as the output expects.
 *
 * With no delay (the default) a chunk goes straight through with no copy. Not thread-safe; the capture thread owns it.
 * Arrays for held chunks are allocated once, the first time a delay needs them.
 */
internal class AvDelayLine(
    private val chunkNs: Long,
    private val chunkBytes: Int,
    private val maxDelayNs: Long = MAX_DELAY_NS,
    private val slewNsPerChunk: Long = SLEW_NS_PER_CHUNK,
) {
    /** The delay applied to the last chunk, ns. */
    var appliedNs = 0L
        private set

    private val capacity = (maxDelayNs / chunkNs).toInt() + 2
    private var bufs: Array<ByteArray>? = null
    private val pts = LongArray(capacity)
    private var head = 0
    private var count = 0

    /** Chunks held back right now. */
    val held: Int get() = count

    /** Gets each chunk that's due, with its final PTS. */
    fun interface Emit { fun emit(pcm: ByteArray, ptsNs: Long) }

    /**
     * Takes one mixed chunk ([pcm], stamped [ptsNs] by capture) and the delay wanted now ([targetNs], clamped to
     * 0..[maxDelayNs]); hands every chunk that's due to [out], oldest first, at its delayed PTS. [pcm] may be reused
     * by the caller as soon as this returns.
     */
    fun push(pcm: ByteArray, ptsNs: Long, targetNs: Long, out: Emit) {
        val target = targetNs.coerceIn(0L, maxDelayNs)
        appliedNs += (target - appliedNs).coerceIn(-slewNsPerChunk, slewNsPerChunk)
        val hold = (appliedNs / chunkNs).toInt().coerceAtMost(capacity - 1)
        if (hold == 0 && count == 0) { out.emit(pcm, ptsNs + appliedNs); return }
        val b = bufs ?: Array(capacity) { ByteArray(chunkBytes) }.also { bufs = it }
        val at = (head + count) % capacity
        pcm.copyInto(b[at], 0, 0, minOf(pcm.size, chunkBytes))
        pts[at] = ptsNs
        count++
        while (count > hold) {
            out.emit(b[head], pts[head] + appliedNs)
            head = (head + 1) % capacity
            count--
        }
    }

    companion object {
        /** The most audio can be held back: beyond this a lip-sync problem isn't the camera's latency. */
        const val MAX_DELAY_NS = 400_000_000L
        /**
         * 0.5 ms per ~21 ms chunk: a 120 ms change takes ~5 s, unnoticeable, and the AAC frames never overlap or part by
         * more than half a millisecond (the server drops audio that overlaps by more than 1 ms).
         */
        const val SLEW_NS_PER_CHUNK = 500_000L
    }
}
