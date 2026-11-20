package app.irlsupreme.engine.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.sin

class MixStageTest {
    private val rate = 48_000
    private val frames = 1024
    private val chunkNs = frames * 1_000_000_000L / rate

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
     * One fixed script through a stage: gain changes, a camera that comes and goes, two chimes (one scheduled), a mute
     * and a mic reopen. Returns every output chunk, concatenated.
     */
    private fun script(process: (k: Int, pcm: ByteArray, ptsNs: Long, cam: ByteArray?, covered: Boolean, gain: Float?, muted: Boolean) -> Unit,
                       onEffect: (ShortArray, Long) -> Unit, onReopen: () -> Unit): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val pcm = ByteArray(frames * 4)
        val cam = ByteArray(frames * 4)
        val bright = AlertSounds.chime(AlertSounds.Style.BRIGHT, rate, true)
        val big = AlertSounds.chime(AlertSounds.Style.BIG, rate, true)
        for (k in 0 until 400) {
            val pts = 1_000_000_000L + k * chunkNs
            if (k == 30) onEffect(bright, pts + 7_000_000L)
            if (k == 200) onEffect(big, 0L)
            if (k == 350) onReopen()
            micChunk(k, pcm)
            val attached = k >= 50
            val covered = k in 60 until 250
            if (attached) { if (covered) camChunk(k, cam) else cam.fill(0) }
            process(k, pcm, pts, if (attached) cam else null, covered, if (k >= 100) 20f else null, k in 300 until 320)
            out.write(pcm)
        }
        return out.toByteArray()
    }

    private fun runStage(): ByteArray {
        val dsp = MicDsp(rate, 2).apply { gainDb = 6f }
        val stage = MixStage(rate, 2, dsp)
        stage.start(muted = false)
        stage.micOpened()
        return script(
            { _, pcm, pts, cam, covered, gain, muted -> stage.process(pcm, frames, pts, cam, covered, gain, muted, micPresent = true) },
            { s, at -> stage.playEffect(s, at) },
            { stage.micOpened() },
        )
    }

    @Test fun `the extracted mix stage matches the old capture loop byte for byte`() {
        val legacy = LegacyMix(rate, 2).apply { dsp.gainDb = 6f }
        val expected = script(
            { _, pcm, pts, cam, covered, gain, muted -> legacy.chunk(pcm, frames, pts, cam, covered, gain, muted) },
            { s, at -> legacy.playEffect(s, at) },
            { legacy.micFadeIn = true },
        )
        assertArrayEquals(expected, runStage())
    }

    @Test fun `golden checksum of the scripted mix`() {
        val crc = CRC32().apply { update(runStage()) }
        assertEquals(GOLDEN_CRC, crc.value)
    }

    private companion object {
        const val GOLDEN_CRC = 2084123681L
    }
}

/** The capture loop's per-chunk body as it was before [MixStage], transcribed, to prove the extraction changed nothing. */
private class LegacyMix(private val sampleRate: Int, private val channels: Int) {
    val dsp = MicDsp(sampleRate, channels)
    private var muteGain = 1f
    var micFadeIn = true
    private val effects = java.util.ArrayDeque<Pair<ShortArray, Long>>()
    private var effect: ShortArray? = null
    private var effectPos = 0
    private var usingCamera = false
    private var camHits = 0
    private var camMisses = 0

    fun playEffect(s: ShortArray, at: Long) { if (effects.size < 8) effects.add(s to at) }

    fun chunk(pcm: ByteArray, chunkFrames: Int, ptsNs: Long, camScratch: ByteArray?, covered: Boolean, gain: Float?, muted: Boolean) {
        val bytesPerFrame = 2 * channels
        val dspLatencyNs = dsp.latencyFrames * 1_000_000_000L / sampleRate
        if (micFadeIn) { crossfade(null, pcm, pcm, chunkFrames, channels, 240); micFadeIn = false }
        val wasCamera = usingCamera
        if (camScratch != null) {
            if (covered) { camHits++; camMisses = 0 } else { camMisses++; camHits = 0 }
            if (!usingCamera && camHits >= 94) usingCamera = true
            else if (usingCamera && camMisses >= 10) usingCamera = false
        } else usingCamera = false
        when {
            usingCamera && wasCamera -> camScratch!!.copyInto(pcm)
            usingCamera -> crossfade(pcm, camScratch!!, pcm, chunkFrames, channels, 240)
            wasCamera -> crossfade(camScratch, pcm, pcm, chunkFrames, channels, 240)
        }
        dsp.process(pcm, chunkFrames, if (usingCamera) 0f else gain)
        val outPtsNs = ptsNs - dspLatencyNs
        if (muted && muteGain == 0f) { effects.clear(); effect = null }
        var pcmFrom = 0
        if (effect == null && !muted) effects.peek()?.let { next ->
            val frame = AlertSounds.startFrame(outPtsNs, chunkFrames, sampleRate, next.second)
            if (frame >= 0) { effects.poll(); effect = next.first; effectPos = 0; pcmFrom = frame * bytesPerFrame }
        }
        effect?.let { e ->
            effectPos = AlertSounds.mixInto(pcm, e, effectPos, pcmFrom)
            if (effectPos >= e.size) effect = null
        }
        muteGain = applyRamp(pcm, chunkFrames, channels, muteGain, if (muted) 0f else 1f, 480)
    }
}
