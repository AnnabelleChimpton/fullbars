package app.irlsupreme.engine.audio

import android.util.Log
import java.util.concurrent.locks.LockSupport
import kotlin.math.pow

/**
 * Music to play under a scene card: the file (already copied into the app's storage), the part to loop (frames at
 * 48 kHz, silence trimmed: [MusicInfo]), its level in the mix (the loudness gain plus the streamer's volume), and
 * whether the mic is off while it plays.
 */
data class MusicTrack(
    val path: String,
    val startFrame: Long,
    val endFrame: Long,
    val gainDb: Float,
    val micOff: Boolean = false,
) {
    /** The same music, whatever its level or mic setting: changing those mustn't fade it out and back in. */
    fun sameMusic(o: MusicTrack?): Boolean = o != null && o.path == path && o.startFrame == startFrame && o.endFrame == endFrame
}

/** What [MixStage] needs from the music: whether it's sounding, whether the mic should be off, and its next chunk. */
internal interface MusicSource {
    /** Playing, fading or about to start. Capture thread. */
    val engaged: Boolean
    /** The music wanted now asks for the mic off. Capture thread. */
    val micOff: Boolean
    /** Adds the next [frames] frames into [fx] (interleaved, [channels]). Never blocks. Capture thread. */
    fun addInto(fx: FloatArray, frames: Int, channels: Int)
}

/**
 * A file's sound, played round and round without a seam: each pass runs from [start] to [end] (frames, end exclusive;
 * 0 or less: to the file's end), and the last [xfadeFrames] of a pass are crossfaded, equal power, into the first of
 * the next, which then carries on from just after them. So the wrap is sample-continuous, a fast fade with no gap and
 * no click, whatever the file's ends look like.
 *
 * Pure (tests feed it a synthetic [PcmSource]). Holds back [xfadeFrames] frames, so the tail is known before it's
 * played. No allocation after construction.
 */
internal class LoopStitcher(
    private val src: PcmSource,
    private val start: Long,
    private val end: Long,
    xfadeFrames: Int = XFADE_FRAMES,
) {
    private val n = if (end > start) minOf(xfadeFrames.toLong(), (end - start) / 4).toInt().coerceAtLeast(0) else xfadeFrames
    private val head = FloatArray(n * 2)
    private var headFilled = 0
    /** The last [n] frames read, oldest at [fifoPos]. */
    private val fifo = FloatArray(n * 2)
    private var fifoPos = 0
    private var fifoCount = 0
    private val seam = FloatArray(n * 2)
    private var seamPos = 0
    private var seamLeft = 0
    private var passPos = 0L
    private var skipUntil = start
    private var firstPass = true
    private var passEnded = false
    private var emptyPasses = 0
    private val scratch = FloatArray(BLOCK * 2)
    private var scratchPos = 0
    private var scratchLen = 0

    /** True once the source has given nothing for a whole pass: it can't be played. */
    var broken = false
        private set

    /** Fills [out] with up to [maxFrames] frames of the loop; fewer only when [broken]. */
    fun next(out: FloatArray, maxFrames: Int): Int {
        var w = 0
        while (w < maxFrames && !broken) {
            if (seamLeft > 0) {
                val k = minOf(seamLeft, maxFrames - w)
                System.arraycopy(seam, seamPos * 2, out, w * 2, k * 2)
                seamPos += k; seamLeft -= k; w += k
                continue
            }
            if (scratchPos >= scratchLen) {
                if (passEnded) { endPass(); continue }
                val got = src.read(scratch, BLOCK)
                if (got < 0) { passEnded = true; continue }
                scratchPos = 0; scratchLen = got
                continue
            }
            // One frame of the pass.
            val idx = passPos++
            val s = scratchPos * 2
            scratchPos++
            if (idx < skipUntil) continue
            if (end > start && idx >= end) { passEnded = true; scratchPos = scratchLen; continue }
            val l = scratch[s]; val r = scratch[s + 1]
            if (firstPass && idx - start < n) { head[headFilled * 2] = l; head[headFilled * 2 + 1] = r; headFilled++ }
            if (n == 0) { out[w * 2] = l; out[w * 2 + 1] = r; w++; continue }
            if (fifoCount == n) {
                // The oldest goes out, the newest takes its place.
                out[w * 2] = fifo[fifoPos * 2]; out[w * 2 + 1] = fifo[fifoPos * 2 + 1]; w++
                fifo[fifoPos * 2] = l; fifo[fifoPos * 2 + 1] = r
                fifoPos = if (fifoPos + 1 == n) 0 else fifoPos + 1
            } else {
                val at = (fifoPos + fifoCount) % n
                fifo[at * 2] = l; fifo[at * 2 + 1] = r
                fifoCount++
            }
        }
        return w
    }

    /** The pass is over: what's held back is its tail; crossfade it into the head and start the next pass after it. */
    private fun endPass() {
        passEnded = false
        val produced = passPos > skipUntil
        if (!produced) { if (++emptyPasses >= 2) broken = true } else emptyPasses = 0
        val m = minOf(fifoCount, headFilled)
        for (i in 0 until m) {
            val t = (fifoPos + i) % n
            val gOut = ImportMath.seamOut(i, m)
            val gIn = ImportMath.seamIn(i, m)
            seam[i * 2] = fifo[t * 2] * gOut + head[i * 2] * gIn
            seam[i * 2 + 1] = fifo[t * 2 + 1] * gOut + head[i * 2 + 1] * gIn
        }
        // A tail longer than the head (a sound too short for a full crossfade) can't happen: both are n when the
        // pass is at least 2n long, which [n] guarantees for a known [end].
        seamPos = 0
        seamLeft = m
        fifoPos = 0
        fifoCount = 0
        firstPass = false
        skipUntil = start + m
        passPos = 0
        scratchPos = 0; scratchLen = 0
        src.rewind()
    }

    companion object {
        /** 25 ms: long enough that any wrap is smooth, short enough not to be heard as a fade. */
        const val XFADE_FRAMES = 1200
        private const val BLOCK = 1024
    }
}

/**
 * The decoded music between its own thread and the capture thread: single producer, single consumer, lock-free.
 * Interleaved stereo float. Each change of music is a new generation; the consumer skips anything older.
 */
internal class MusicRing(private val capacityFrames: Int) {
    private val buf = FloatArray(capacityFrames * 2)
    @Volatile private var writePos = 0L
    @Volatile private var readPos = 0L
    @Volatile private var genStart = 0L
    /** The generation whose frames follow [genStart]. Written after it. */
    @Volatile var gen = 0
        private set
    /** A generation that couldn't be played (the file wouldn't open). */
    @Volatile var failedGen = -1

    // Producer.
    fun free(): Int = (capacityFrames - (writePos - readPos)).toInt()

    fun startGen(g: Int) { genStart = writePos; gen = g }

    fun write(src: FloatArray, frames: Int): Int {
        val n = minOf(frames, free())
        var w = writePos
        for (f in 0 until n) {
            val at = (w % capacityFrames).toInt() * 2
            buf[at] = src[f * 2]; buf[at + 1] = src[f * 2 + 1]
            w++
        }
        writePos = w
        return n
    }

    // Consumer.
    private var syncedGen = -1

    /** Whether the frames now readable are generation [g]'s (skipping older ones the first time). */
    fun sync(g: Int): Boolean {
        if (syncedGen == g) return true
        if (gen != g) return false
        readPos = maxOf(readPos, genStart)
        syncedGen = g
        return true
    }

    fun available(): Int = (writePos - readPos).toInt()

    /** Up to [frames] frames into [dst]; returns how many. */
    fun read(dst: FloatArray, frames: Int): Int {
        val n = minOf(frames, available())
        var r = readPos
        for (f in 0 until n) {
            val at = (r % capacityFrames).toInt() * 2
            dst[f * 2] = buf[at]; dst[f * 2 + 1] = buf[at + 1]
            r++
        }
        readPos = r
        return n
    }
}

/**
 * The capture thread's side of card music: follows [wanted], fades in over [fadeFrames] when music starts and out
 * over the same when it stops or changes (a change fades the old one out fully, then the new one in), glides the
 * level when only the volume changes, and mixes the ring's frames in. A ring that runs dry plays silence rather than
 * waiting. Never blocks, never allocates.
 */
internal class MusicMixer(
    private val ring: MusicRing,
    private val sampleRate: Int,
    private val fadeFrames: Int = (sampleRate * FADE_S).toInt(),
    /** Frames the ring must hold before a fade-in starts, so it never starts on an underrun. */
    private val prefillFrames: Int = sampleRate / 5,
) : MusicSource {
    /** The music the scene on air wants; null for none. Set from any thread. */
    @Volatile var wanted: MusicTrack? = null

    /** What the decoder should be playing: [gen] bumps on every change. Read by the decoder thread. */
    class Request(val gen: Int, val track: MusicTrack?)
    @Volatile var request = Request(0, null)
        private set

    private var current: MusicTrack? = null
    private var playing = false
    /** Fade position in frames, 0..[fadeFrames]; the gain is [ImportMath.fadeCurve] of its fraction. */
    private var level = 0
    private val fadeLen = fadeFrames.coerceAtLeast(1)
    private var volume = 0f
    private var scratch = FloatArray(0)

    /** Chunks the ring ran dry while playing (for the log). */
    @Volatile var underruns = 0
        private set

    override val engaged: Boolean get() = current != null || level > 0
    override val micOff: Boolean get() = wanted?.micOff == true

    override fun addInto(fx: FloatArray, frames: Int, channels: Int) {
        val w = wanted
        var fadingOut = false
        if (current?.sameMusic(w) == true) {
            current = w // the same music: just take its new level and mic setting
        } else if (current == null || level == 0) {
            switchTo(w)
        } else {
            fadingOut = true
        }
        val c = current ?: return
        if (!playing) {
            if (ring.failedGen == request.gen) return
            if (!ring.sync(request.gen) || ring.available() < prefillFrames) return
            playing = true
            volume = linear(c.gainDb)
        }
        if (scratch.size < frames * 2) scratch = FloatArray(frames * 2)
        val got = ring.read(scratch, frames)
        if (got < frames) { underruns++; scratch.fill(0f, got * 2, frames * 2) }
        val v0 = volume
        val v1 = linear(c.gainDb)
        for (f in 0 until frames) {
            level = if (fadingOut) maxOf(0, level - 1) else minOf(fadeLen, level + 1)
            val g = ImportMath.fadeCurve(level.toFloat() / fadeLen) * (v0 + (v1 - v0) * (f + 1) / frames)
            val l = scratch[f * 2] * g
            val r = scratch[f * 2 + 1] * g
            if (channels == 2) { fx[f * 2] += l; fx[f * 2 + 1] += r } else fx[f] += (l + r) * 0.5f
        }
        volume = v1
    }

    private fun switchTo(t: MusicTrack?) {
        current = t
        playing = false
        level = 0
        request = Request(request.gen + 1, t)
    }

    private fun linear(db: Float) = 10f.pow(db / 20f)

    companion object {
        /** In and out over 0.8 s. */
        const val FADE_S = 0.8f
    }
}

/**
 * Card music for one stream: a decoder thread that keeps [MusicRing] (about two seconds) topped up from the file,
 * looped by [LoopStitcher], and the [MusicMixer] the capture thread pulls from. Only one file is open at a time, and
 * only a few seconds of audio are ever in memory, however long the music is.
 */
class CardMusicPlayer(sampleRate: Int) {
    private val ring = MusicRing(sampleRate * 2)
    internal val mixer = MusicMixer(ring, sampleRate)
    @Volatile private var running = false
    private var thread: Thread? = null

    /** The music the scene on air wants; null for none. Fades in and out by itself. Any thread. */
    @Synchronized
    fun play(track: MusicTrack?) {
        mixer.wanted = track
        if (track != null && thread == null) {
            running = true
            thread = Thread(::loop, "card-music").apply { isDaemon = true; start() }
        }
    }

    /** Chunks the music ran dry (the phone couldn't decode it fast enough). */
    val underruns: Int get() = mixer.underruns

    @Synchronized
    fun stop() {
        mixer.wanted = null
        running = false
        thread?.let { it.interrupt(); runCatching { it.join(500) } }
        thread = null
    }

    private fun loop() {
        var gen = 0
        var decoder: AudioFileDecoder? = null
        var stitcher: LoopStitcher? = null
        val block = FloatArray(BLOCK * 2)
        try {
            while (running) {
                val req = mixer.request
                if (req.gen != gen) {
                    decoder?.close(); decoder = null; stitcher = null
                    gen = req.gen
                    ring.startGen(gen)
                    val t = req.track
                    if (t != null) {
                        try {
                            val d = AudioFileDecoder(t.path).also { decoder = it }
                            d.open()
                            stitcher = LoopStitcher(d, t.startFrame, t.endFrame)
                        } catch (e: Exception) {
                            Log.w(TAG, "card music wouldn't open: ${e.javaClass.simpleName}")
                            decoder?.close(); decoder = null
                            ring.failedGen = gen
                        }
                    }
                }
                val s = stitcher
                if (s == null || ring.free() < BLOCK) { LockSupport.parkNanos(if (s == null) 20_000_000L else 5_000_000L); continue }
                val n = try { s.next(block, BLOCK) } catch (e: Exception) {
                    Log.w(TAG, "card music stopped: ${e.javaClass.simpleName}")
                    ring.failedGen = gen; stitcher = null; 0
                }
                if (s.broken) { ring.failedGen = gen; stitcher = null }
                if (n > 0) ring.write(block, n)
            }
        } finally {
            decoder?.close()
        }
    }

    private companion object {
        const val TAG = "CardMusic"
        const val BLOCK = 2048
    }
}
