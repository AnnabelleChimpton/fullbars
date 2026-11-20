package app.irlsupreme.engine.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The camera's decoded audio converted to the stream's rate and channel count (pure, unit-tested), as one continuous
 * stream across decoded blocks.
 *
 * Why stateful: converting each AAC block on its own (the old way) restarted the interpolation at every block, so
 * whenever the camera wasn't at the stream's rate each block boundary was a small phase jump: at 44.1 kHz that's
 * 43 jumps a second, heard as a buzz of sidebands around every tone. Here the read position carries over from block
 * to block (with the last few input frames kept as history), so block k+1 continues exactly where block k left off.
 *
 * Why a windowed sinc rather than 4-point Hermite: Hermite leaves the images of an 8 kHz tone only ~33 dB down at
 * 44.1 kHz in (they fold back to 4 and 12 kHz), a 32-tap Kaiser-windowed sinc puts them below -80 dB. The same
 * kernel, widened, is the anti-alias low-pass when the camera's rate is higher than the stream's (88.2/96 kHz), so no
 * separate half-band stage is needed. At equal rates (the usual 48 kHz) samples pass through untouched.
 *
 * Decoder thread only. Allocation-free once the buffers have grown to the largest block.
 */
internal class CameraResampler(private val outRate: Int, private val outChannels: Int) {
    var inRate = 0
        private set
    var inChannels = 0
        private set
    /** Input frames per output frame. */
    private var step = 1.0
    /** Kernel half-width, in input frames: taps run from floor(pos) - [halfWidth] + 1 to floor(pos) + [halfWidth]. */
    private var halfWidth = 0
    /** The kernel h(d) at d = k / [OVERSAMPLE] input frames, k = 0 .. halfWidth * OVERSAMPLE (+1 guard). */
    private var table = FloatArray(0)
    private var taps = FloatArray(0)
    /** Frames of history kept between blocks (2 * halfWidth), then the current block; [outChannels] interleaved. */
    private var work = FloatArray(0)
    private var workFrames = 0
    /** The next output frame's position in [work], in input frames (fractional). */
    private var pos = 0.0
    private var started = false
    /** Camera time (ns) the next block should start at, if it continues this one. */
    private var expectNs = 0L

    /** Output of the last [process] call: [outFrames] frames at the start of [out], the first at [outPtsNs]. */
    var out = ShortArray(0)
        private set
    var outFrames = 0
        private set
    var outPtsNs = 0L
        private set
    /** Times the stream started again (a new format, a timestamp jump): for the log. */
    var restarts = 0
        private set

    val converting: Boolean get() = inRate != outRate

    /** Sets the decoder's output format; a change starts the stream again. */
    fun configure(rate: Int, channels: Int) {
        val ch = channels.coerceIn(1, 2)
        if (rate == inRate && ch == inChannels) return
        inRate = rate
        inChannels = ch
        started = false
        if (rate == outRate || rate <= 0) { halfWidth = 0; return }
        step = rate.toDouble() / outRate
        // Up-sampling: cut just below the input's Nyquist. Down-sampling: below the output's, with the kernel
        // stretched to match, so what can't be represented at the output rate is filtered out rather than folded in.
        val scale = minOf(1.0, outRate.toDouble() / rate)
        halfWidth = ceil(HALF_TAPS / scale).toInt()
        val fc = 0.5 * ROLLOFF * scale // cycles per input frame
        val n = halfWidth * OVERSAMPLE
        table = FloatArray(n + 2)
        val i0Beta = besselI0(BETA)
        for (k in 0..n) {
            val d = k.toDouble() / OVERSAMPLE
            val x = 2 * fc * d
            val sinc = if (k == 0) 1.0 else sin(PI * x) / (PI * x)
            val r = d / halfWidth
            val w = besselI0(BETA * sqrt(maxOf(0.0, 1 - r * r))) / i0Beta
            table[k] = (2 * fc * sinc * w).toFloat()
        }
        taps = FloatArray(2 * halfWidth)
    }

    /** Forget the stream (a new session or codec); the format stays. */
    fun reset() { started = false }

    /**
     * Converts [frames] frames of [pcm] (interleaved at the configured input format) whose first frame is at camera
     * time [ptsNs]. The result is in [out]/[outFrames]/[outPtsNs]: with conversion, it ends a few input frames short
     * of the block (they need the next block's samples) and starts that much before it.
     */
    fun process(pcm: ShortArray, frames: Int, ptsNs: Long) {
        outFrames = 0
        outPtsNs = ptsNs
        if (frames <= 0 || inRate <= 0) return
        val blockNs = frames * 1_000_000_000L / inRate
        val continues = started && abs(ptsNs - expectNs) <= CameraPlayout.JOIN_NS
        expectNs = ptsNs + blockNs
        if (halfWidth == 0) { passThrough(pcm, frames); started = true; return }

        val hist = 2 * halfWidth
        if (!continues) {
            // A new stream: history is the first frame held (no step from silence), reading from the block's start.
            if (started) restarts++
            ensureWork(hist + frames)
            for (f in 0 until hist) readFrame(pcm, 0, work, f)
            workFrames = hist
            pos = hist.toDouble()
            started = true
        }
        val blockStart = workFrames
        ensureWork(workFrames + frames)
        for (f in 0 until frames) readFrame(pcm, f, work, workFrames + f)
        workFrames += frames

        // Output every position whose taps all have samples.
        val firstPos = pos
        val maxOut = ((workFrames - halfWidth - pos) / step).toInt() + 2
        if (out.size < maxOut * outChannels) out = ShortArray(maxOut * outChannels)
        var n = 0
        while (floor(pos).toInt() + halfWidth <= workFrames - 1) {
            interpolate(pos, n)
            n++
            pos += step
        }
        outFrames = n
        outPtsNs = ptsNs + ((firstPos - blockStart) * 1e9 / inRate).toLong()

        // Keep the last [hist] frames as the next block's history and rebase the read position onto them.
        val drop = workFrames - hist
        System.arraycopy(work, drop * outChannels, work, 0, hist * outChannels)
        workFrames = hist
        pos -= drop
    }

    private fun interpolate(p: Double, at: Int) {
        val i = floor(p).toInt()
        val frac = p - i
        val w = halfWidth
        var sum = 0f
        // Taps at and before i are frac, 1 + frac, ...; after it 1 - frac, 2 - frac, ... input frames away.
        for (j in 0 until w) {
            val a = kernel(j + frac); taps[w - 1 - j] = a; sum += a
            val b = kernel(j + 1 - frac); taps[w + j] = b; sum += b
        }
        val norm = if (sum != 0f) 1f / sum else 1f // exact unity gain at every phase
        val base = (i - w + 1) * outChannels
        for (ch in 0 until outChannels) {
            var acc = 0f
            var k = base + ch
            for (t in 0 until 2 * w) { acc += taps[t] * work[k]; k += outChannels }
            out[at * outChannels + ch] = (acc * norm * 32768f).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** The kernel at distance [d] (0 <= d <= halfWidth) input frames, interpolated from [table]. */
    private fun kernel(d: Double): Float {
        val x = d * OVERSAMPLE
        val k = x.toInt()
        val t = (x - k).toFloat()
        return table[k] + (table[k + 1] - table[k]) * t
    }

    private fun passThrough(pcm: ShortArray, frames: Int) {
        if (out.size < frames * outChannels) out = ShortArray(frames * outChannels)
        if (inChannels == outChannels) {
            System.arraycopy(pcm, 0, out, 0, frames * outChannels)
        } else {
            for (f in 0 until frames) {
                if (inChannels == 1) {
                    for (ch in 0 until outChannels) out[f * outChannels + ch] = pcm[f]
                } else {
                    out[f] = ((pcm[2 * f] + pcm[2 * f + 1]) / 2).toShort()
                }
            }
        }
        outFrames = frames
    }

    /** Frame [f] of [pcm] in the stream's channel layout, as floats, into frame [to] of [dst]. */
    private fun readFrame(pcm: ShortArray, f: Int, dst: FloatArray, to: Int) {
        val d = to * outChannels
        if (inChannels == outChannels) {
            for (ch in 0 until outChannels) dst[d + ch] = pcm[f * outChannels + ch] / 32768f
        } else if (inChannels == 1) {
            val v = pcm[f] / 32768f
            for (ch in 0 until outChannels) dst[d + ch] = v
        } else {
            dst[d] = (pcm[2 * f] + pcm[2 * f + 1]) / 65536f
        }
    }

    private fun ensureWork(frames: Int) {
        if (work.size >= frames * outChannels) return
        work = work.copyOf(frames * outChannels)
    }

    companion object {
        /** Kernel half-width in input frames when up-sampling (32 taps). */
        const val HALF_TAPS = 16
        /** Pass band as a fraction of the lower Nyquist: 19.8 kHz flat at 44.1 kHz in. */
        const val ROLLOFF = 0.9
        /** Kaiser shape: ~80 dB stop band. */
        const val BETA = 8.0
        /** Kernel table steps per input frame (linear interpolation between them is far below the stop band). */
        const val OVERSAMPLE = 512

        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val q = x * x / 4
            var k = 1
            while (term > 1e-12 * sum) { term *= q / (k.toDouble() * k); sum += term; k++ }
            return sum
        }
    }
}
