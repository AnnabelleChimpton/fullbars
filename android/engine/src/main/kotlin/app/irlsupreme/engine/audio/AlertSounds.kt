package app.irlsupreme.engine.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Alert chimes, synthesised: a few bell-like notes with soft attacks and long decays, no recorded audio to
 * license. Mixed into the stream only (not the phone's speaker, which would echo into the mic). Pure, for tests.
 */
object AlertSounds {
    enum class Style(internal val notes: List<Double>, internal val step: Double) {
        /** Two notes, short: a follow. */
        SOFT(listOf(659.25, 987.77), 0.12),
        /** A rising major arpeggio: subs, gifts, cheers. */
        BRIGHT(listOf(523.25, 659.25, 783.99, 1046.5), 0.11),
        /** Lower and longer: a raid. */
        BIG(listOf(392.0, 523.25, 659.25, 783.99, 1046.5), 0.14),
    }

    /**
     * Milliseconds from the first sample of [style]'s chime to the peak of its first note's attack: the moment the
     * ear hears it "land". The engine starts the chime this long before an alert is fully revealed, so the sound and
     * the first complete frame of the alert arrive together.
     */
    fun attackMs(style: Style): Int = (ATTACK_S * 1000).roundToInt()

    /** Each note's linear fade-in: long enough not to click, short enough to read as a strike. */
    private const val ATTACK_S = 0.008

    /** 16-bit PCM, interleaved when [stereo], peak around -10 dBFS so it sits under a voice. */
    fun chime(style: Style, sampleRate: Int, stereo: Boolean): ShortArray {
        val channels = if (stereo) 2 else 1
        val tail = 1.1 // seconds each note rings
        val totalSec = style.step * (style.notes.size - 1) + tail
        val frames = (totalSec * sampleRate).roundToInt()
        val out = ShortArray(frames * channels)
        val peak = 0.32 * Short.MAX_VALUE
        for (f in 0 until frames) {
            val t = f.toDouble() / sampleRate
            var v = 0.0
            for ((i, freq) in style.notes.withIndex()) {
                val nt = t - i * style.step
                if (nt < 0 || nt > tail) continue
                val attack = minOf(1.0, nt / ATTACK_S) // no click
                val decay = exp(-nt * 4.5)
                // A bell: the note plus a quieter octave.
                v += attack * decay * (sin(2 * PI * freq * nt) + 0.35 * sin(4 * PI * freq * nt))
            }
            val s = (v / style.notes.size.coerceAtMost(2) * peak).coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()
            for (c in 0 until channels) out[f * channels + c] = s
        }
        return out
    }

    /**
     * Where a sound scheduled for [startAtNs] begins inside the chunk of [chunkFrames] frames whose first frame is at
     * [chunkStartNs] (both on the System.nanoTime clock): the frame index, 0 when it's due already (or unscheduled),
     * or -1 when it belongs to a later chunk. Sample-accurate, so the chime's attack can land on a given video frame.
     */
    fun startFrame(chunkStartNs: Long, chunkFrames: Int, sampleRate: Int, startAtNs: Long): Int {
        if (startAtNs <= chunkStartNs) return 0
        val frame = (startAtNs - chunkStartNs) * sampleRate / 1_000_000_000L
        return if (frame >= chunkFrames) -1 else frame.toInt()
    }

    /**
     * Adds [effect] (from sample [from]) into [dst] (float, 1.0 = full scale, same channel layout) starting at sample
     * [dstFrom], and returns where the effect got to; >= effect.size means it's finished. This is how the stream mixes
     * chimes: into the voice before the limiter, so a chime over a loud voice is turned down with it, never clipped.
     */
    fun addInto(dst: FloatArray, dstSamples: Int, effect: ShortArray, from: Int, dstFrom: Int = 0): Int {
        var i = from
        var d = dstFrom.coerceAtLeast(0)
        while (d < dstSamples && i < effect.size) {
            dst[d] += effect[i] / 32768f
            d++
            i++
        }
        return i
    }

    /**
     * Mixes [effect] (from [from]) into [pcm] (16-bit little-endian, same channel layout), starting at byte [pcmFrom]
     * of [pcm], and returns where the effect got to; >= effect.size means it's finished. The sum saturates at full
     * scale instead of wrapping round (a wrap is a full-scale crack). That's a last-resort guard: the stream adds
     * chimes before its limiter ([addInto]), where nothing clips.
     */
    fun mixInto(pcm: ByteArray, effect: ShortArray, from: Int, pcmFrom: Int = 0): Int {
        var i = from
        var b = pcmFrom.coerceAtLeast(0) and 1.inv() // whole samples only
        while (b + 1 < pcm.size && i < effect.size) {
            val mic = (pcm[b].toInt() and 0xff) or (pcm[b + 1].toInt() shl 8)
            val mixed = (mic + effect[i]).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm[b] = mixed.toByte()
            pcm[b + 1] = (mixed shr 8).toByte()
            b += 2
            i++
        }
        return i
    }
}
