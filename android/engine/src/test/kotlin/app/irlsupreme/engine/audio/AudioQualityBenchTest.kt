package app.irlsupreme.engine.audio

import app.irlsupreme.engine.audio.testutil.Metrics
import app.irlsupreme.engine.audio.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The real mix chain ([MixStage] with its [MicDsp]) on a bench: test signals at the levels a phone really sends, with
 * the events a stream really has (mute, the camera's audio coming and going, the mic reopening, the fader moving,
 * alert chimes), measured the way an engineer would. The two sanity checks at the end prove the bench can fail: a hard
 * switch between sources, and chimes added after the limiter (how it used to be), both do.
 */
class AudioQualityBenchTest {
    private val rate = 48_000
    private val frames = 1024
    private val ceiling = MicDsp.CEILING.toDouble()

    private fun db(x: Double) = 10.0.pow(x / 20)

    /** One scripted event per chunk index. */
    private class Script(
        val chunks: Int,
        val mutedAt: (Int) -> Boolean = { false },
        val camAttached: (Int) -> Boolean = { false },
        val camCovered: (Int) -> Boolean = { false },
        /** Chunks where the mic is closed (reopening); the chunk before fades out, the one after fades in. */
        val micGone: (Int) -> Boolean = { false },
        val gainAt: (Int) -> Float = { 0f },
        val chimes: Map<Int, Pair<AlertSounds.Style, Int>> = emptyMap(),
    )

    private class Variant(val switchFadeFrames: Int = MixStage.SWITCH_FADE_FRAMES, val chimesAfterLimiter: Boolean = false)

    /**
     * Runs [mic] (and [cam], when the script attaches the camera) through a [MixStage] following [script]; returns the
     * whole output, interleaved float.
     */
    private fun run(mic: FloatArray, cam: FloatArray?, script: Script, variant: Variant = Variant(), lowCutHz: Int = 0, leveler: Boolean = false): FloatArray {
        val dsp = MicDsp(rate, 2)
        val stage = MixStage(rate, 2, dsp, variant.switchFadeFrames)
        stage.start(false)
        stage.micOpened()
        stage.lowCutHz = lowCutHz
        stage.leveler = leveler
        val out = FloatArray(script.chunks * frames * 2)
        val chunkNs = frames * 1_000_000_000L / rate
        val pcm = ByteArray(frames * 4)
        // For the old path: chimes queued here and mixed after the limiter, saturating, as the capture loop used to.
        var oldChime: ShortArray? = null
        var oldPos = 0
        for (k in 0 until script.chunks) {
            val pts = 1_000_000_000L + k * chunkNs
            script.chimes[k]?.let { (style, at) ->
                val c = AlertSounds.chime(style, rate, true)
                if (variant.chimesAfterLimiter) { oldChime = c; oldPos = 0 } else stage.playEffect(c, pts + at * 1_000_000_000L / rate)
            }
            val gone = script.micGone(k)
            if (!gone && k > 0 && script.micGone(k - 1)) stage.micOpened()
            if (gone) pcm.fill(0) else Signals.toPcm(mic, k * frames * 2, frames * 2).copyInto(pcm)
            val camPcm = if (cam != null && script.camAttached(k)) Signals.toPcm(cam, k * frames * 2, frames * 2) else null
            stage.process(
                pcm, frames, pts, camPcm, script.camCovered(k), script.gainAt(k), script.mutedAt(k),
                micPresent = !gone, micFadeOut = !gone && script.micGone(k + 1),
            )
            oldChime?.let { c ->
                oldPos = AlertSounds.mixInto(pcm, c, oldPos)
                if (oldPos >= c.size) oldChime = null
            }
            Signals.fromPcm(pcm).copyInto(out, k * frames * 2)
        }
        return out
    }

    /** A random but fixed script of mutes, camera stretches, mic reopens, fader moves and chimes. */
    private fun eventScript(seed: Long, chunks: Int, baseGain: Float): Script {
        val r = java.util.Random(seed)
        val mutes = (0 until 4).map { r.nextInt(chunks - 40).let { s -> s until s + 5 + r.nextInt(20) } }
        val camFrom = 20 + r.nextInt(40)
        val camTo = camFrom + MixStage.CAM_SWITCH_ON + 40 + r.nextInt(60)
        val reopen = camTo + 30 + r.nextInt(40)
        val sweepAt = r.nextInt(chunks)
        val chimes = mapOf(
            r.nextInt(chunks / 2) to (AlertSounds.Style.BRIGHT to r.nextInt(frames)),
            chunks / 2 + r.nextInt(chunks / 2 - 80) to (AlertSounds.Style.BIG to r.nextInt(frames)),
        )
        return Script(
            chunks,
            mutedAt = { k -> mutes.any { k in it } },
            camAttached = { k -> k >= camFrom },
            camCovered = { k -> k in camFrom until camTo },
            micGone = { k -> k in reopen until reopen + 3 },
            gainAt = { k -> if (k < sweepAt) baseGain else baseGain + 6f },
            chimes = chimes,
        )
    }

    /** Test signals, [chunks] long, for the mic. */
    private fun signals(chunks: Int): Map<String, FloatArray> {
        val n = chunks * frames
        return mapOf(
            "100 Hz" to Signals.sine(100.0, 0.25, n, rate),
            "1 kHz" to Signals.sine(1000.0, 0.25, n, rate),
            "8 kHz" to Signals.sine(8000.0, 0.25, n, rate),
            "sweep" to Signals.logSweep(40.0, 16000.0, 0.25, n, rate),
            "speech" to Signals.speechLike(-24.0, n, rate),
            "clap" to Signals.clap(n, n / 3, 1.0, rate),
        )
    }

    @Test fun `nothing ever goes over the ceiling, chimes included, at any gain`() {
        val chunks = 420
        val cam = Signals.sine(523.25, db(-12.0), chunks * frames, rate)
        for ((name, x) in signals(chunks)) for (gain in listOf(0f, 20f, 30f)) {
            val out = run(x, cam, eventScript(name.hashCode().toLong() + gain.toLong(), chunks, gain), lowCutHz = 80)
            val peak = Metrics.peak(out)
            assertTrue("$name at +$gain dB: sample peak ${Metrics.db(peak)} dBFS", peak <= ceiling + 1.0 / 32768)
            // True peak, held to about 0 dBTP for now. The limiter reads sample peaks; -1.5 dBFS leaves room for the
            // peaks between samples of anything but strong high tones: the worst here is an 8 kHz tone driven 20 dB
            // into the limiter with a chime on top, ~+0.1 dBTP. A true-peak sidechain would close that gap.
            val tp = Metrics.truePeak4x(out)
            assertTrue("$name at +$gain dB: true peak ${Metrics.db(tp)} dBTP", tp <= TRUE_PEAK_LIMIT)
        }
    }

    /** The largest kink in channel 0 over the whole run, relative to the output's own peak. */
    private fun normalisedKink(out: FloatArray): Double {
        val l = Metrics.channel(out)
        return Metrics.maxKink(l) / Metrics.peak(out).coerceAtLeast(1e-6)
    }

    /** Smooth tones (so any kink is the mix's doing) through every kind of event. */
    private fun kinkRun(seed: Long, gain: Float, variant: Variant = Variant()): Double {
        val chunks = 420
        val mic = Signals.sine(200.0, db(-12.0), chunks * frames, rate)
        val cam = Signals.sine(330.0, db(-12.0), chunks * frames, rate, phase = 1.3)
        val script = eventScript(seed, chunks, gain).let { s ->
            Script(s.chunks, s.mutedAt, s.camAttached, s.camCovered, s.micGone, s.gainAt) // no chimes: they have kinks of their own
        }
        return normalisedKink(run(mic, cam, script, variant))
    }

    @Test fun `no clicks through mutes, source switches, reopens and fader moves`() {
        // A 330 Hz tone's own kink is ~0.002 of its peak; a click (a step) is ~1. The fades leave corners ~0.01.
        for (seed in 1L..6L) for (gain in listOf(0f, 20f)) {
            val k = kinkRun(seed, gain)
            assertTrue("seed $seed +$gain dB: kink $k", k < KINK_BOUND)
        }
    }

    @Test fun `latency is exactly the lookahead`() {
        val dsp = MicDsp(rate, 2)
        val stage = MixStage(rate, 2, dsp).apply { start(false) }
        val x = FloatArray(frames * 2)
        x[2 * 100] = 0.5f; x[2 * 100 + 1] = 0.5f
        val pcm = Signals.toPcm(x)
        stage.process(pcm, frames, 0L, null, false, null, false, micPresent = true)
        val l = Metrics.channel(Signals.fromPcm(pcm))
        assertEquals(100 + dsp.latencyFrames, l.indices.first { l[it] != 0f })
    }

    @Test fun `a clean tone stays clean when nothing is limited`() {
        val chunks = 60
        val x = Signals.sine(1031.25, db(-6.0), chunks * frames, rate) // whole cycles per chunk: an exact fit
        val out = run(x, null, Script(chunks), lowCutHz = 80)
        val l = Metrics.channel(out)
        val d = Metrics.thdN(l.copyOfRange(40 * frames, 60 * frames), 1031.25, rate)
        assertTrue("THD+N ${d * 100}%", d < 0.0005)
    }

    @Test fun `a voice at default raw settings lands at a broadcast level`() {
        // The raw source: ~28 dB under the camcorder one, +20 dB on the fader, the low cut and the leveler on.
        val chunks = 16 * rate / frames
        for (inDb in listOf(-46.0, -40.0, -34.0)) {
            val x = Signals.speechLike(inDb, chunks * frames, rate)
            val out = run(x, null, Script(chunks, gainAt = { 20f }), lowCutHz = lowCutHz(LowCut.AUTO, raw = true), leveler = true)
            val st = Metrics.shortTermLufs(out).takeLast(40).sorted()
            val median = st[st.size / 2]
            assertTrue("input $inDb dBFS: $median LUFS-S", median in -20.0..-14.0)
        }
    }

    @Test fun `sanity - a hard switch between sources fails the bench`() {
        val worst = (1L..6L).maxOf { kinkRun(it, 0f, Variant(switchFadeFrames = 0)) }
        assertTrue("hard switch kink $worst", worst >= KINK_BOUND)
    }

    @Test fun `sanity - chimes added after the limiter, as they used to be, fail the bench`() {
        val chunks = 420
        val x = Signals.sine(300.0, 0.25, chunks * frames, rate)
        val failed = (1L..4L).any { seed ->
            val out = run(x, null, eventScript(seed, chunks, 20f).let { s -> Script(s.chunks, chimes = s.chimes, gainAt = { 20f }) },
                Variant(chimesAfterLimiter = true))
            Metrics.peak(out) > ceiling + 1.0 / 32768
        }
        assertTrue(failed)
    }

    private companion object {
        const val KINK_BOUND = 0.05
        /** +0.25 dBTP. */
        val TRUE_PEAK_LIMIT = 10.0.pow(0.25 / 20)
    }
}
