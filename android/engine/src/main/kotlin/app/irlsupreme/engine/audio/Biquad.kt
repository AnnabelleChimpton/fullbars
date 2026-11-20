package app.irlsupreme.engine.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * One second-order filter section with its own state per channel, Direct Form II transposed. Coefficients and state
 * are doubles: at 48 kHz an 80 Hz high-pass has its poles within 1% of the unit circle, where float state starts to add
 * audible noise and drift. Doubles cost nothing extra on a phone's 64-bit cores. No allocation after construction.
 *
 * Until a set* call it passes audio through unchanged.
 */
internal class Biquad(private val channels: Int) {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private val z1 = DoubleArray(channels)
    private val z2 = DoubleArray(channels)

    /**
     * A 2nd-order Butterworth high-pass at [hz] (RBJ cookbook, Q 0.7071): -3 dB at [hz], 12 dB per octave below it,
     * flat above. Rumble from wind and handling, and any DC offset, is what it takes out.
     */
    fun setHighPass(sampleRate: Int, hz: Double, q: Double = BUTTERWORTH_Q) {
        val w0 = 2 * PI * hz / sampleRate
        val alpha = sin(w0) / (2 * q)
        val c = cos(w0)
        val a0 = 1 + alpha
        set((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
    }

    /** Sets the normalised coefficients (a0 = 1). */
    fun set(b0: Double, b1: Double, b2: Double, a1: Double, a2: Double) {
        this.b0 = b0; this.b1 = b1; this.b2 = b2; this.a1 = a1; this.a2 = a2
    }

    /** Forgets the past (a new source): the next sample starts from rest. */
    fun reset() { z1.fill(0.0); z2.fill(0.0) }

    /** Filters one sample of channel [ch]. */
    fun process(x: Double, ch: Int): Double {
        val y = b0 * x + z1[ch]
        z1[ch] = b1 * x - a1 * y + z2[ch]
        z2[ch] = b2 * x - a2 * y
        return y
    }

    companion object {
        const val BUTTERWORTH_Q = 0.7071067811865476

        /**
         * The two stages of ITU-R BS.1770's K-weighting (the loudness meters' "how loud does this sound" filter) for
         * [sampleRate]: a +4 dB shelf above ~1.7 kHz and a gentle high-pass at ~38 Hz. Worked out from the standard's
         * analogue prototypes, so it is right at any rate, not only 48 kHz.
         */
        fun kWeighting(sampleRate: Int, channels: Int): Pair<Biquad, Biquad> {
            val shelf = Biquad(channels).apply {
                val f0 = 1681.974450955533
                val gainDb = 3.999843853973347
                val q = 0.7071752369554196
                val k = tan(PI * f0 / sampleRate)
                val vh = 10.0.pow(gainDb / 20)
                val vb = vh.pow(0.4996667741545416)
                val a0 = 1 + k / q + k * k
                set((vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0,
                    2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
            }
            val highPass = Biquad(channels).apply {
                val f0 = 38.13547087602444
                val q = 0.5003270373238773
                val k = tan(PI * f0 / sampleRate)
                val a0 = 1 + k / q + k * k
                set(1.0, -2.0, 1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
            }
            return shelf to highPass
        }
    }
}
