package app.irlsupreme.engine.audio

/** What [feedDecoder] needs from a decoder's input side: a MediaCodec in the app, a fake in tests. */
internal interface DecoderInput {
    /** A free input buffer's index, or negative when none came free within [timeoutUs]. */
    fun dequeueInputBuffer(timeoutUs: Long): Int
    /** Copies [data] into input buffer [index] and queues it at [ptsUs]. */
    fun queue(index: Int, data: ByteArray, ptsUs: Long)
}

/**
 * Compressed camera audio frames waiting for the decoder (pure, unit-tested). The RTMP read thread [offer]s and moves
 * straight on; one feeder thread takes them in order. Bounded: when the decoder falls a second behind, the oldest frame
 * goes and [drops] counts it, so decoder backpressure can be told apart from Wi-Fi loss (which shows as gap fills).
 */
internal class AacFrameQueue(private val capacity: Int = CAPACITY) {
    private val frames = arrayOfNulls<ByteArray>(capacity)
    private val pts = LongArray(capacity)
    private var head = 0
    private var size = 0
    private val lock = Object()

    @Volatile var drops = 0
        private set
    /** The [poll]ed frame's timestamp (ms). Single consumer: only the feeder thread polls. */
    var polledPtsMs = 0L
        private set

    val queued: Int get() = synchronized(lock) { size }

    /** Never waits on the decoder: at worst a brief lock against the feeder's poll. */
    fun offer(aac: ByteArray, ptsMs: Long): Unit = synchronized(lock) {
        if (size == capacity) { // a second behind: lose the oldest, not the newest
            frames[head] = null
            head = (head + 1) % capacity
            size--
            drops++
        }
        val at = (head + size) % capacity
        frames[at] = aac
        pts[at] = ptsMs
        size++
        lock.notifyAll()
    }

    /** The oldest frame, waiting up to [timeoutMs] for one; null if none came. Its timestamp is in [polledPtsMs]. */
    fun poll(timeoutMs: Long): ByteArray? = synchronized(lock) {
        if (size == 0 && timeoutMs > 0) lock.wait(timeoutMs) // wait(0) would wait forever
        if (size == 0) return null
        val f = frames[head]
        polledPtsMs = pts[head]
        frames[head] = null
        head = (head + 1) % capacity
        size--
        f
    }

    fun clear(): Unit = synchronized(lock) {
        for (i in 0 until capacity) frames[i] = null
        head = 0
        size = 0
    }

    companion object {
        /** ~1 s of 1024-sample AAC frames at 48 kHz. */
        const val CAPACITY = 50
    }
}

/**
 * Feeder thread body: hands queued frames to [input] in order while [running], waiting as long as it takes for an
 * input buffer rather than dropping a frame (the queue's bound is the only place frames are lost to backpressure).
 *
 * A decoder that throws on one frame (a transient codec error) loses only that frame: [onError] hears of it and the
 * feeder carries on with the next, so the camera's audio doesn't stop for the rest of the codec's life. At most one
 * error per frame, so a decoder that keeps failing can't spin the thread. Exits once [running] says so.
 */
internal fun feedDecoder(
    queue: AacFrameQueue,
    input: DecoderInput,
    running: () -> Boolean,
    onError: (Exception) -> Unit = {},
) {
    while (running()) {
        val aac = queue.poll(POLL_MS) ?: continue
        val ptsUs = queue.polledPtsMs * 1000
        try {
            while (running()) {
                val idx = input.dequeueInputBuffer(INPUT_WAIT_US)
                if (idx >= 0) { input.queue(idx, aac, ptsUs); break }
            }
        } catch (e: Exception) {
            if (!running()) return // stopped under us: the codec's errors mean nothing now
            onError(e)
        }
    }
}

private const val POLL_MS = 20L
private const val INPUT_WAIT_US = 10_000L
