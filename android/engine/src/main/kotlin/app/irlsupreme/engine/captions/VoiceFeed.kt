package app.irlsupreme.engine.captions

import app.irlsupreme.engine.audio.Biquad
import java.util.concurrent.atomic.AtomicLong

/**
 * The voice as viewers hear it, handed over by the capture thread once per chunk, before alert sounds, card music and
 * the mute (see MixStage). [audible] is false while viewers can't hear it (muted, or music that turns the mic off), so
 * nothing they didn't hear is ever captioned. Must not block or allocate.
 */
fun interface VoiceTap {
    fun onVoice(voice: FloatArray, frames: Int, channels: Int, audible: Boolean)
}

/**
 * A single-producer, single-consumer ring of 16-bit samples: the capture thread writes, the recogniser's feeding
 * thread reads. When full, new samples are dropped (the reader has fallen behind; captions skip rather than lag).
 */
internal class ShortRing(capacity: Int) {
    private val buf = ShortArray(capacity)
    private val written = AtomicLong(0)
    private val read = AtomicLong(0)

    val available: Int get() = (written.get() - read.get()).toInt()

    /** Producer. False when the ring was full and [s] was dropped. */
    fun write(s: Short): Boolean {
        val w = written.get()
        if (w - read.get() >= buf.size) return false
        buf[(w % buf.size).toInt()] = s
        written.set(w + 1)
        return true
    }

    /** Consumer: up to [max] samples into [dst], returns how many. */
    fun read(dst: ShortArray, max: Int = dst.size): Int {
        val r = read.get()
        val n = minOf(max, (written.get() - r).toInt())
        for (i in 0 until n) dst[i] = buf[((r + i) % buf.size).toInt()]
        read.set(r + n)
        return n
    }

    /** Consumer: forgets all but the newest [keep] samples (a backlog that would only make captions late). */
    fun skipTo(keep: Int) {
        val w = written.get()
        if (w - read.get() > keep) read.set(w - keep)
    }
}

/**
 * Folds the voice to mono, low-passes it at 7 kHz (two Butterworth sections, so nothing above the new Nyquist folds
 * back as hiss) and resamples it to [OUT_RATE] Hz 16-bit, the format the speech recogniser takes, into [ring].
 * Capture thread; no allocation per chunk.
 */
internal class VoiceDownsampler(inRate: Int, private val ring: ShortRing) : VoiceTap {
    private val lp1 = Biquad(1).apply { setLowPass(inRate, CUTOFF_HZ) }
    private val lp2 = Biquad(1).apply { setLowPass(inRate, CUTOFF_HZ) }
    private val step = inRate.toDouble() / OUT_RATE
    /** Where the next output sample falls, in input samples after the previous input sample (0, 1] when due. */
    private var next = 1.0
    private var prev = 0.0
    /** Samples dropped because the ring was full. */
    @Volatile var dropped = 0L
        private set

    override fun onVoice(voice: FloatArray, frames: Int, channels: Int, audible: Boolean) {
        for (f in 0 until frames) {
            var x = 0.0
            if (audible) {
                for (c in 0 until channels) x += voice[f * channels + c]
                x /= channels
            }
            val y = lp2.process(lp1.process(x, 0), 0)
            while (next <= 1.0) {
                val v = prev + (y - prev) * next
                if (!ring.write((v * 32767.0).coerceIn(-32768.0, 32767.0).toInt().toShort())) dropped++
                next += step
            }
            next -= 1.0
            prev = y
        }
    }

    companion object {
        const val OUT_RATE = 16_000
        private const val CUTOFF_HZ = 7_000.0
    }
}
