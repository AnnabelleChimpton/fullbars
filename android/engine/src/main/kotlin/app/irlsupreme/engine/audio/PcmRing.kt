package app.irlsupreme.engine.audio

/**
 * The last [seconds] of 16-bit PCM written to it, for playing back what the stream (or the mic check) sounded like.
 * Fixed size, allocated once (15 s of 48 kHz stereo is ~2.9 MB); old audio is overwritten. Thread-safe: the capture
 * thread writes a chunk at a time, and [recent] hands the reader its own copy.
 */
class PcmRing(private val sampleRate: Int, private val channels: Int, seconds: Int) {
    private val buf = ShortArray(sampleRate * channels * seconds)
    private var pos = 0 // next write, in samples
    private var filled = 0

    /** Appends [frames] frames of 16-bit little-endian interleaved PCM from [pcm]. */
    @Synchronized
    fun write(pcm: ByteArray, frames: Int) {
        val n = (frames * channels).coerceAtMost(pcm.size / 2)
        // A chunk bigger than the whole ring keeps only its end.
        val skip = (n - buf.size).coerceAtLeast(0)
        for (k in skip until n) {
            buf[pos] = ((pcm[2 * k].toInt() and 0xff) or (pcm[2 * k + 1].toInt() shl 8)).toShort()
            pos = if (pos + 1 == buf.size) 0 else pos + 1
        }
        filled = (filled + n - skip).coerceAtMost(buf.size)
    }

    /** Forgets everything (a new stream or check starts). */
    @Synchronized
    fun clear() {
        pos = 0; filled = 0
    }

    /** The last [seconds] (or all there is, if less), oldest first; null if nothing has been written. */
    @Synchronized
    fun recent(seconds: Int): PcmClip? {
        if (filled == 0 || seconds <= 0) return null
        val n = minOf(filled, seconds.toLong().times(sampleRate * channels).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val out = ShortArray(n)
        val start = Math.floorMod(pos - n, buf.size)
        val first = minOf(n, buf.size - start)
        System.arraycopy(buf, start, out, 0, first)
        if (first < n) System.arraycopy(buf, 0, out, first, n - first)
        return PcmClip(out, sampleRate, channels)
    }
}
