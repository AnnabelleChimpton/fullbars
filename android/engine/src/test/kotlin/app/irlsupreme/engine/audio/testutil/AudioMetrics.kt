package app.irlsupreme.engine.audio.testutil

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Test signals, interleaved float (1.0 = full scale), and 16-bit PCM conversion. Every generator is deterministic
 * (seeded), so a failure always reproduces.
 */
object Signals {
    /** [frames] frames of a sine at [hz] and peak [amp], the same on every channel, starting at frame [from]. */
    fun sine(hz: Double, amp: Double, frames: Int, rate: Int = 48_000, channels: Int = 2, from: Long = 0, phase: Double = 0.0): FloatArray {
        val x = FloatArray(frames * channels)
        for (f in 0 until frames) {
            val v = (amp * sin(2 * PI * hz * (from + f) / rate + phase)).toFloat()
            for (ch in 0 until channels) x[f * channels + ch] = v
        }
        return x
    }

    /** A logarithmic sweep from [f0] to [f1] Hz over [frames], peak [amp]. */
    fun logSweep(f0: Double, f1: Double, amp: Double, frames: Int, rate: Int = 48_000, channels: Int = 2): FloatArray {
        val x = FloatArray(frames * channels)
        val t1 = frames.toDouble() / rate
        val k = ln(f1 / f0)
        for (f in 0 until frames) {
            val t = f.toDouble() / rate
            val ph = 2 * PI * f0 * t1 / k * (exp(t / t1 * k) - 1)
            val v = (amp * sin(ph)).toFloat()
            for (ch in 0 until channels) x[f * channels + ch] = v
        }
        return x
    }

    /** Mono pink noise (Paul Kellet's filter on white noise), roughly unit RMS before scaling. */
    fun pink(frames: Int, seed: Long): FloatArray {
        val r = java.util.Random(seed)
        var b0 = 0.0; var b1 = 0.0; var b2 = 0.0; var b3 = 0.0; var b4 = 0.0; var b5 = 0.0; var b6 = 0.0
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            val w = r.nextGaussian()
            b0 = 0.99886 * b0 + w * 0.0555179
            b1 = 0.99332 * b1 + w * 0.0750759
            b2 = 0.96900 * b2 + w * 0.1538520
            b3 = 0.86650 * b3 + w * 0.3104856
            b4 = 0.55000 * b4 + w * 0.5329522
            b5 = -0.7616 * b5 - w * 0.0168980
            out[i] = ((b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362) * 0.11).toFloat()
            b6 = w * 0.115926
        }
        return out
    }

    /**
     * A stand-in for speech: pink noise whose level swings at 4 Hz (syllables) from nothing to full, with a short pause
     * every couple of seconds, scaled to [rmsDb] dBFS RMS over the whole signal. Crest factor ~15 dB, like a voice.
     */
    fun speechLike(rmsDb: Double, frames: Int, rate: Int = 48_000, channels: Int = 2, seed: Long = 1): FloatArray {
        val p = pink(frames, seed)
        val x = FloatArray(frames * channels)
        var sum = 0.0
        for (f in 0 until frames) {
            val t = f.toDouble() / rate
            val syll = 0.5 - 0.5 * cos(2 * PI * 4 * t)
            val pause = if ((t % 2.3) > 2.0) 0.05 else 1.0
            val v = p[f] * syll * syll * pause
            sum += v * v
            for (ch in 0 until channels) x[f * channels + ch] = v.toFloat()
        }
        val g = 10.0.pow(rmsDb / 20) / sqrt(sum / frames)
        for (i in x.indices) x[i] = (x[i] * g).toFloat()
        return x
    }

    /** A handclap: one full-[amp] sample at frame [at], then noise decaying over ~50 ms. */
    fun clap(frames: Int, at: Int, amp: Double, rate: Int = 48_000, channels: Int = 2, seed: Long = 7): FloatArray {
        val r = java.util.Random(seed)
        val x = FloatArray(frames * channels)
        for (f in at until frames) {
            val t = (f - at).toDouble() / rate
            val v = if (f == at) amp else amp * 0.5 * exp(-t / 0.012) * (r.nextDouble() * 2 - 1)
            for (ch in 0 until channels) x[f * channels + ch] = v.toFloat()
        }
        return x
    }

    /** [x] scaled by [db]. */
    fun gain(x: FloatArray, db: Double): FloatArray { val g = 10.0.pow(db / 20).toFloat(); return FloatArray(x.size) { x[it] * g } }

    /** Float to 16-bit little-endian PCM, saturating. */
    fun toPcm(x: FloatArray, from: Int = 0, samples: Int = x.size - from): ByteArray {
        val b = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = (x[from + i] * 32768f).roundToInt().coerceIn(-32768, 32767)
            b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte()
        }
        return b
    }

    /** 16-bit little-endian PCM to float. */
    fun fromPcm(b: ByteArray): FloatArray = FloatArray(b.size / 2) { i ->
        ((b[2 * i].toInt() and 0xff) or (b[2 * i + 1].toInt() shl 8)).toShort() / 32768f
    }
}

/** Measurements on interleaved float audio (1.0 = full scale). */
object Metrics {
    fun db(linear: Double): Double = if (linear <= 0) -200.0 else 20 * log10(linear)

    /** The largest |sample| of channel [ch] (or all channels when -1) in frames [from] until [to]. */
    fun peak(x: FloatArray, channels: Int = 2, from: Int = 0, to: Int = x.size / channels, ch: Int = -1): Double {
        var p = 0.0
        for (f in from until to) for (c in 0 until channels) if (ch < 0 || c == ch) p = maxOf(p, abs(x[f * channels + c].toDouble()))
        return p
    }

    fun rmsDb(x: FloatArray, channels: Int = 2, from: Int = 0, to: Int = x.size / channels): Double {
        var s = 0.0
        for (i in from * channels until to * channels) s += x[i].toDouble() * x[i]
        return db(sqrt(s / ((to - from) * channels).coerceAtLeast(1)))
    }

    /** Channel [ch] as its own array. */
    fun channel(x: FloatArray, channels: Int = 2, ch: Int = 0): FloatArray = FloatArray(x.size / channels) { x[it * channels + ch] }

    /**
     * The largest second difference |x[n] - 2x[n-1] + x[n-2]| of a mono signal from [from]: a click or a step in gain
     * shows up as a kink far bigger than any smooth wave's ((2 pi f / rate)^2 times its amplitude).
     */
    fun maxKink(x: FloatArray, from: Int = 2): Double {
        var m = 0.0
        for (i in maxOf(from, 2) until x.size) m = maxOf(m, abs(x[i] - 2.0 * x[i - 1] + x[i - 2]))
        return m
    }

    /**
     * THD+N of a mono [x] against a tone at [hz] (a ratio, 0.01 = 1%): everything that isn't a least-squares fit of the
     * tone, relative to it. Use a whole number of cycles for an exact fit.
     */
    fun thdN(x: FloatArray, hz: Double, rate: Int = 48_000): Double {
        var ss = 0.0; var cc = 0.0; var sc = 0.0; var xs = 0.0; var xc = 0.0; var mean = 0.0
        for (v in x) mean += v
        mean /= x.size
        for (i in x.indices) {
            val s = sin(2 * PI * hz * i / rate); val c = cos(2 * PI * hz * i / rate)
            ss += s * s; cc += c * c; sc += s * c; xs += (x[i] - mean) * s; xc += (x[i] - mean) * c
        }
        val det = ss * cc - sc * sc
        val a = (xs * cc - xc * sc) / det
        val b = (xc * ss - xs * sc) / det
        var res = 0.0; var fund = 0.0
        for (i in x.indices) {
            val f = a * sin(2 * PI * hz * i / rate) + b * cos(2 * PI * hz * i / rate)
            fund += f * f
            val r = x[i] - mean - f
            res += r * r
        }
        return sqrt(res / fund)
    }

    /** The amplitude of the [hz] component of mono [x], from a least-squares fit. */
    fun toneAmp(x: FloatArray, hz: Double, rate: Int = 48_000, from: Int = 0, to: Int = x.size): Double {
        var ss = 0.0; var cc = 0.0; var sc = 0.0; var xs = 0.0; var xc = 0.0
        for (i in from until to) {
            val s = sin(2 * PI * hz * i / rate); val c = cos(2 * PI * hz * i / rate)
            ss += s * s; cc += c * c; sc += s * c; xs += x[i] * s; xc += x[i] * c
        }
        val det = ss * cc - sc * sc
        val a = (xs * cc - xc * sc) / det
        val b = (xc * ss - xs * sc) / det
        return sqrt(a * a + b * b)
    }

    /** Pearson correlation of two equal-length mono signals. */
    fun correlation(a: FloatArray, b: FloatArray): Double {
        var ma = 0.0; var mb = 0.0
        for (i in a.indices) { ma += a[i]; mb += b[i] }
        ma /= a.size; mb /= b.size
        var sab = 0.0; var saa = 0.0; var sbb = 0.0
        for (i in a.indices) { val da = a[i] - ma; val dbv = b[i] - mb; sab += da * dbv; saa += da * da; sbb += dbv * dbv }
        return if (saa == 0.0 || sbb == 0.0) 0.0 else sab / sqrt(saa * sbb)
    }

    /**
     * True peak (BS.1770-style): the largest |sample| after 4x oversampling with a 64-tap windowed-sinc interpolator,
     * so peaks that fall between samples (which a DAC or an AAC decoder will reproduce) count. Linear, all channels.
     */
    fun truePeak4x(x: FloatArray, channels: Int = 2): Double {
        val taps = 16 // per phase, either side: 64 in all
        val kernel = Array(4) { phase ->
            DoubleArray(2 * taps) { k ->
                val t = (k - taps + 1) - phase / 4.0
                val sinc = if (t == 0.0) 1.0 else sin(PI * t) / (PI * t)
                val w = 0.5 + 0.5 * cos(PI * t / taps) // Hann over the span
                sinc * w
            }
        }
        val frames = x.size / channels
        var p = 0.0
        for (c in 0 until channels) for (n in 0 until frames) for (phase in 0 until 4) {
            var acc = 0.0
            val kk = kernel[phase]
            for (k in 0 until 2 * taps) {
                val idx = n + k - taps + 1
                if (idx in 0 until frames) acc += kk[k] * x[idx * channels + c]
            }
            p = maxOf(p, abs(acc))
        }
        return p
    }

    /** BS.1770 K-weighted mean square per 100 ms step, at 48 kHz: the building block for momentary/short-term/integrated loudness. */
    private fun kWeightedPower(x: FloatArray, channels: Int): DoubleArray {
        // The standard's coefficients for 48 kHz (written out independently of the app's own filter design).
        val sb = doubleArrayOf(1.53512485958697, -2.69169618940638, 1.19839281085285)
        val sa = doubleArrayOf(-1.69065929318241, 0.73248077421585)
        val hb = doubleArrayOf(1.0, -2.0, 1.0)
        val ha = doubleArrayOf(-1.99004745483398, 0.99007225036621)
        val frames = x.size / channels
        val y2 = DoubleArray(frames)
        for (c in 0 until channels) {
            var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var yy2 = 0.0
            var u1 = 0.0; var u2 = 0.0; var z1 = 0.0; var z2 = 0.0
            for (n in 0 until frames) {
                val xin = x[n * channels + c].toDouble()
                val y = sb[0] * xin + sb[1] * x1 + sb[2] * x2 - sa[0] * y1 - sa[1] * yy2
                x2 = x1; x1 = xin; yy2 = y1; y1 = y
                val z = hb[0] * y + hb[1] * u1 + hb[2] * u2 - ha[0] * z1 - ha[1] * z2
                u2 = u1; u1 = y; z2 = z1; z1 = z
                y2[n] += z * z
            }
        }
        return y2
    }

    private fun lufs(ms: Double) = if (ms <= 0) -200.0 else -0.691 + 10 * log10(ms)

    /** Short-term loudness (LUFS-S, a 3 s window) ending at each 100 ms step, from the first full window on. */
    fun shortTermLufs(x: FloatArray, channels: Int = 2, rate: Int = 48_000): DoubleArray {
        val p = kWeightedPower(x, channels)
        val win = 3 * rate
        val step = rate / 10
        val out = ArrayList<Double>()
        var end = win
        while (end <= p.size) {
            var s = 0.0
            for (i in end - win until end) s += p[i]
            out.add(lufs(s / win))
            end += step
        }
        return out.toDoubleArray()
    }

    /** Integrated loudness with BS.1770's -70 LUFS absolute and -10 LU relative gates (400 ms blocks, 75% overlap). */
    fun integratedLufs(x: FloatArray, channels: Int = 2, rate: Int = 48_000): Double {
        val p = kWeightedPower(x, channels)
        val win = (0.4 * rate).toInt()
        val step = win / 4
        val blocks = ArrayList<Double>()
        var start = 0
        while (start + win <= p.size) {
            var s = 0.0
            for (i in start until start + win) s += p[i]
            blocks.add(s / win)
            start += step
        }
        val abs = blocks.filter { lufs(it) > -70 }
        if (abs.isEmpty()) return -200.0
        val rel = lufs(abs.average()) - 10
        val gated = abs.filter { lufs(it) > rel }
        return lufs(gated.average())
    }
}
