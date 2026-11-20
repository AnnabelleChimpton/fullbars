package app.irlsupreme.engine.audio

import app.irlsupreme.engine.audio.testutil.Metrics
import app.irlsupreme.engine.audio.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.sin

class MixStageTest {
    private val rate = 48_000
    private val frames = 1024
    private val chunkNs = frames * 1_000_000_000L / rate
    private val ceilingPcm = (MicDsp.CEILING * 32768).toInt() + 1

    /** A fixed, seeded "voice" for chunk [k]: two tones and some noise, around -12 dBFS. */
    private fun micChunk(k: Int, out: ByteArray) {
        var seed = 0x9E3779B9L xor k.toLong()
        for (f in 0 until frames) {
            val n = k * frames + f
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            val noise = ((seed ushr 33).toInt() % 2000) - 1000
            val v = (6000 * sin(2 * PI * 220.0 * n / rate) + 2500 * sin(2 * PI * 1370.0 * n / rate)).toInt() + noise
            for (ch in 0 until 2) {
                val s = if (ch == 0) v else v / 2
                out[f * 4 + ch * 2] = s.toByte(); out[f * 4 + ch * 2 + 1] = (s shr 8).toByte()
            }
        }
    }

    private fun camChunk(k: Int, out: ByteArray) {
        for (f in 0 until frames) {
            val v = (8000 * sin(2 * PI * 523.25 * (k * frames + f) / rate)).toInt()
            for (ch in 0 until 2) { out[f * 4 + ch * 2] = v.toByte(); out[f * 4 + ch * 2 + 1] = (v shr 8).toByte() }
        }
    }

    /**
     * One fixed script through the stage: gain changes, a camera that comes and goes, two chimes (one scheduled), a
     * mute and a mic reopen. Returns every output chunk, concatenated.
     */
    private fun goldenRun(): ByteArray {
        val dsp = MicDsp(rate, 2).apply { gainDb = 6f }
        val stage = MixStage(rate, 2, dsp)
        stage.start(muted = false)
        stage.micOpened()
        val out = java.io.ByteArrayOutputStream()
        val pcm = ByteArray(frames * 4)
        val cam = ByteArray(frames * 4)
        val bright = AlertSounds.chime(AlertSounds.Style.BRIGHT, rate, true)
        val big = AlertSounds.chime(AlertSounds.Style.BIG, rate, true)
        for (k in 0 until 400) {
            val pts = 1_000_000_000L + k * chunkNs
            if (k == 30) stage.playEffect(bright, pts + 7_000_000L)
            if (k == 200) stage.playEffect(big, 0L)
            if (k == 350) stage.micOpened()
            stage.lowCutHz = if (k >= 100) 80 else 0
            micChunk(k, pcm)
            val attached = k >= 50
            val covered = k in 60 until 250
            if (attached) { if (covered) camChunk(k, cam) else cam.fill(0) }
            stage.process(pcm, frames, pts, if (attached) cam else null, covered, if (k >= 100) 20f else null, k in 300 until 320, micPresent = true)
            out.write(pcm)
        }
        return out.toByteArray()
    }

    @Test fun `golden checksum of the scripted mix`() {
        // Guards the mix against unintended change. If a change to the mix is meant, check the new output by ear or
        // with the bench (AudioQualityBenchTest), then update this.
        val crc = CRC32().apply { update(goldenRun()) }
        assertEquals(GOLDEN_CRC, crc.value)
    }

    /** Drives a [MixStage] chunk by chunk with float signals; returns each output chunk as float. */
    private class Rig(rate: Int, val frames: Int = 1024, gainDb: Float = 0f) {
        val dsp = MicDsp(rate, 2).apply { this.gainDb = gainDb }
        val stage = MixStage(rate, 2, dsp).apply { start(false) }
        private val chunkNs = frames * 1_000_000_000L / rate
        var k = 0
        val pts: Long get() = 1_000_000_000L + k * chunkNs
        var last = MicLevel.SILENT

        fun chunk(mic: FloatArray?, cam: FloatArray? = null, covered: Boolean = cam != null, micGainDb: Float? = null, muted: Boolean = false): FloatArray {
            val pcm = if (mic != null) Signals.toPcm(mic) else ByteArray(frames * 4)
            val camPcm = cam?.let { Signals.toPcm(it) }
            last = stage.process(pcm, frames, pts, camPcm, covered, micGainDb, muted, micPresent = mic != null)
            k++
            return Signals.fromPcm(pcm)
        }
    }

    private fun sineChunk(hz: Double, db: Double, k: Int) = Signals.sine(hz, Math.pow(10.0, db / 20), frames, rate, from = k.toLong() * frames)

    @Test fun `the camera taking over from a boosted mic never gets the mic's gain`() {
        val r = Rig(rate)
        // A quiet raw mic at +20 dB, the camera's audio on hand from the start: the switch comes after CAM_SWITCH_ON.
        val switchAt = MixStage.CAM_SWITCH_ON - 1
        var switched = -1
        for (k in 0 until switchAt + 30) {
            val out = r.chunk(sineChunk(300.0, -40.0, k), sineChunk(1000.0, -12.0, k), micGainDb = 20f)
            if (r.stage.usingCamera && switched < 0) switched = k
            if (switched in 0 until k) { // every chunk after the switch
                val peakDb = Metrics.db(Metrics.peak(out))
                assertEquals("chunk $k peak", -12.0, peakDb, 0.5)
                assertEquals("chunk $k reduction", 0f, r.last.reductionDb, 0f)
            }
        }
        assertEquals(switchAt, switched)
    }

    @Test fun `going back to the mic never boosts the camera's fading tail`() {
        val r = Rig(rate)
        var k = 0
        repeat(MixStage.CAM_SWITCH_ON + 20) { r.chunk(sineChunk(300.0, -40.0, k), sineChunk(1000.0, -12.0, k), micGainDb = 20f); k++ }
        assertTrue(r.stage.usingCamera)
        // The camera stops delivering (its playout still hands over what it last had); the mic takes back over.
        repeat(MixStage.CAM_SWITCH_OFF + 20) {
            val out = r.chunk(sineChunk(300.0, -40.0, k), sineChunk(1000.0, -12.0, k), covered = false, micGainDb = 20f)
            assertTrue("chunk $k peak ${Metrics.db(Metrics.peak(out))}", Metrics.db(Metrics.peak(out)) <= -12.0 + 0.1)
            assertEquals(0f, r.last.reductionDb, 0f)
            k++
        }
        assertTrue(!r.stage.usingCamera)
    }

    /**
     * A voice held at the ceiling in bursts (250 ms on, 250 ms off), with BRIGHT and BIG chimes at random offsets. Runs
     * the same script with no voice too: that output is the chimes alone, for the correlation check. Returns (mix, chimes).
     */
    private fun chimesOverLoudVoice(seed: Long): Pair<FloatArray, FloatArray> {
        val chunks = 160
        val rnd = java.util.Random(seed)
        val starts = listOf(10 + rnd.nextInt(20), 60 + rnd.nextInt(20))
        val offsets = starts.map { rnd.nextInt(frames) }
        fun run(voice: Boolean): FloatArray {
            val r = Rig(rate)
            val out = FloatArray(chunks * frames * 2)
            val bright = AlertSounds.chime(AlertSounds.Style.BRIGHT, rate, true)
            val big = AlertSounds.chime(AlertSounds.Style.BIG, rate, true)
            for (k in 0 until chunks) {
                starts.forEachIndexed { i, s ->
                    if (k == s) r.stage.playEffect(if (i == 0) bright else big, r.pts + offsets[i] * 1_000_000_000L / rate)
                }
                val mic = if (voice) {
                    val x = sineChunk(300.0, -1.5, k)
                    for (f in 0 until frames) {
                        val t = ((k * frames + f) % (rate / 2)).toDouble() / rate // 0..0.5 s
                        val on = if (t < 0.25) minOf(1.0, minOf(t, 0.25 - t) / 0.005) else 0.0
                        x[2 * f] = (x[2 * f] * on).toFloat(); x[2 * f + 1] = (x[2 * f + 1] * on).toFloat()
                    }
                    x
                } else FloatArray(frames * 2)
                r.chunk(mic).copyInto(out, k * frames * 2)
            }
            return out
        }
        return run(true) to run(false)
    }

    @Test fun `chimes over a voice at the ceiling never clip and stay clean where the voice pauses`() {
        for (seed in 1L..4L) {
            val (mix, chimes) = chimesOverLoudVoice(seed)
            val peak = (Metrics.peak(mix) * 32768).toInt()
            assertTrue("seed $seed peak $peak", peak <= ceilingPcm)
            // In the voice's pauses (allowing the 5 ms lookahead and the limiter's release), the mix is the chime.
            val l = Metrics.channel(mix); val c = Metrics.channel(chimes)
            var checked = 0
            for (pause in 0 until l.size / (rate / 2)) {
                val from = pause * rate / 2 + (0.30 * rate).toInt()
                val to = pause * rate / 2 + (0.49 * rate).toInt()
                if (to > l.size || Metrics.peak(c, 1, from, to) < 0.01) continue
                val corr = Metrics.correlation(l.copyOfRange(from, to), c.copyOfRange(from, to))
                assertTrue("seed $seed pause $pause corr $corr", corr > 0.9)
                checked++
            }
            assertTrue(checked >= 2)
        }
    }

    @Test fun `muting during a chime fades it out and drops what was queued`() {
        val r = Rig(rate)
        val chime = AlertSounds.chime(AlertSounds.Style.BIG, rate, true)
        r.stage.playEffect(chime, 0L)
        r.stage.playEffect(chime, 0L) // queued behind the first
        repeat(10) { r.chunk(FloatArray(frames * 2)) }
        val fading = r.chunk(FloatArray(frames * 2), muted = true)
        assertTrue(Metrics.peak(fading, from = 0, to = 100) > 0.01) // still sounding as the ramp starts
        assertEquals(0.0, Metrics.peak(fading, from = MixStage.MUTE_RAMP_FRAMES + 1), 0.0)
        repeat(3) { assertEquals(0.0, Metrics.peak(r.chunk(FloatArray(frames * 2), muted = true)), 0.0) }
        // Unmuted: nothing comes back, neither the rest of the first chime nor the queued one.
        repeat(80) { assertEquals(0.0, Metrics.peak(r.chunk(FloatArray(frames * 2))), 0.0) }
    }

    @Test fun `a chime is scheduled on the input clock, so it comes out on its own sample`() {
        val r = Rig(rate)
        val click = ShortArray(2) { 16_000 }
        val startFrame = 300
        r.stage.playEffect(click, r.pts + startFrame * 1_000_000_000L / rate + 1)
        val out = r.chunk(FloatArray(frames * 2))
        // Out of the limiter a lookahead later, on the output timeline at exactly its start time.
        val l = Metrics.channel(out)
        val at = l.indices.first { l[it] != 0f }
        assertEquals(startFrame + r.dsp.latencyFrames, at)
    }

    @Test fun `with the camera on, the mic is still levelled every chunk so switching back doesn't jump`() {
        val r = Rig(rate)
        var k = 0
        repeat(MixStage.CAM_SWITCH_ON + 5) { r.chunk(sineChunk(300.0, -30.0, k), sineChunk(1000.0, -12.0, k), micGainDb = 10f); k++ }
        // Change the mic's level while the camera is on: by the time the mic is back, its glide has long settled.
        repeat(20) { r.chunk(sineChunk(300.0, -30.0, k), sineChunk(1000.0, -12.0, k), micGainDb = 16f); k++ }
        repeat(MixStage.CAM_SWITCH_OFF + 5) { r.chunk(sineChunk(300.0, -30.0, k), FloatArray(frames * 2), covered = false, micGainDb = 16f); k++ }
        val out = r.chunk(sineChunk(300.0, -30.0, k), FloatArray(frames * 2), covered = false, micGainDb = 16f)
        assertEquals(-14.0, Metrics.db(Metrics.peak(out)), 0.3)
    }

    @Test fun `input clipping is counted for the source viewers hear`() {
        val r = Rig(rate)
        val clipped = Signals.sine(1000.0, 1.3, frames, rate).also { x -> for (i in x.indices) x[i] = x[i].coerceIn(-1f, 32767f / 32768) }
        r.chunk(clipped, micGainDb = -10f)
        assertTrue(r.last.inClipped > 0)
        assertEquals(1L, r.stage.inputClipChunks)
        assertEquals(0L, r.stage.postMixClips)
    }

    private companion object {
        const val GOLDEN_CRC = 4022891213L
    }
}
