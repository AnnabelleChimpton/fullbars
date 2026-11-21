package app.irlsupreme.engine.audio

import app.irlsupreme.engine.audio.testutil.Metrics
import app.irlsupreme.engine.audio.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Card music and the streamer's own alert sounds in the mix: under the limiter, through mutes, never stacking. */
class MixStageMusicTest {
    private val rate = 48_000
    private val frames = 1024
    private val chunkNs = frames * 1_000_000_000L / rate
    private val ceiling = MicDsp.CEILING.toDouble() + 1.0 / 32768

    /** Music as the mixer sees it: a steady tone at [amp], playing while [on]. */
    private class ToneMusic(val hz: Double, val amp: Double) : MusicSource {
        var on = true
        var off = false
        private var n = 0L
        override val engaged: Boolean get() = on
        override val micOff: Boolean get() = off
        override fun addInto(fx: FloatArray, frames: Int, channels: Int) {
            for (f in 0 until frames) {
                val v = (amp * sin(2 * PI * hz * (n + f) / 48_000)).toFloat()
                for (c in 0 until channels) fx[f * channels + c] += v
            }
            n += frames
        }
    }

    private inner class Rig(gainDb: Float = 0f, music: MusicSource? = null) {
        val dsp = MicDsp(rate, 2).apply { this.gainDb = gainDb }
        val stage = MixStage(rate, 2, dsp).apply { start(false); this.music = music }
        var k = 0
        val pts: Long get() = 1_000_000_000L + k * chunkNs

        fun chunk(mic: FloatArray, muted: Boolean = false, micGainDb: Float? = null): FloatArray {
            val pcm = Signals.toPcm(mic)
            stage.process(pcm, frames, pts, null, false, micGainDb, muted, micPresent = true)
            k++
            return Signals.fromPcm(pcm)
        }
    }

    private fun voice(k: Int, db: Double = -12.0) = Signals.sine(300.0, Math.pow(10.0, db / 20), frames, rate, from = k.toLong() * frames)

    private fun maxDiff(a: FloatArray, b: FloatArray): Double {
        var m = 0.0
        for (i in a.indices) m = maxOf(m, Math.abs((a[i] - b[i]).toDouble()))
        return m
    }

    /** The 300 Hz voice's and the 1 kHz music's amplitudes in one output chunk. */
    private fun amps(out: FloatArray): Pair<Double, Double> {
        val l = Metrics.channel(out)
        return Metrics.toneAmp(l, 300.0) to Metrics.toneAmp(l, 1000.0)
    }

    @Test fun `music over a loud mic stays under the ceiling`() {
        for (musicAmp in listOf(0.3, 0.9, 1.0)) {
            val r = Rig(music = ToneMusic(1000.0, musicAmp))
            val speech = Signals.speechLike(-6.0, frames * 200, rate)
            var peak = 0.0
            for (k in 0 until 200) {
                val out = r.chunk(speech.copyOfRange(k * frames * 2, (k + 1) * frames * 2), micGainDb = 20f)
                peak = maxOf(peak, Metrics.peak(out))
            }
            assertTrue("music at $musicAmp: peak ${Metrics.db(peak)} dBFS", peak <= ceiling)
            assertEquals(0L, r.stage.postMixClips)
        }
    }

    @Test fun `muted with music on - the voice is gone, the music plays on`() {
        val music = ToneMusic(1000.0, 0.1)
        val r = Rig(music = music)
        // The same music with no voice at all, for comparison.
        val ref = Rig(music = ToneMusic(1000.0, 0.1))
        repeat(5) { r.chunk(voice(it)); ref.chunk(FloatArray(frames * 2)) }
        // Privacy: muted from here, music still on.
        var out = FloatArray(0)
        for (k in 5 until 15) {
            out = r.chunk(voice(k), muted = true)
            val alone = ref.chunk(FloatArray(frames * 2), muted = true)
            // A chunk after the mute: exactly the music alone, the voice nowhere.
            if (k >= 6) assertEquals(0.0, maxDiff(out, alone), 1.5 / 32768)
        }
        assertEquals(0.1, amps(out).second, 0.005)
        // The music stops (its own fade done) while still muted: everything goes quiet.
        music.on = false
        repeat(3) { out = r.chunk(voice(15 + it), muted = true) }
        assertEquals(0.0, Metrics.peak(out), 0.0)
        // Unmuted: the voice is back.
        repeat(3) { out = r.chunk(voice(18 + it)) }
        assertTrue(amps(out).first > 0.2)
    }

    @Test fun `muting while music plays never dips the music`() {
        val r = Rig(music = ToneMusic(1000.0, 0.1))
        repeat(10) { r.chunk(FloatArray(frames * 2)) }
        // Through the mute, window by window, the music holds its level.
        for (k in 10 until 16) {
            val l = Metrics.channel(r.chunk(FloatArray(frames * 2), muted = true))
            for (w in 0 until 4) {
                val m = Metrics.toneAmp(l, 1000.0, from = w * 256, to = (w + 1) * 256)
                assertTrue("chunk $k window $w: music $m", m > 0.095)
            }
        }
    }

    @Test fun `music that turns the mic off takes the voice down, and leaving brings it back`() {
        val music = ToneMusic(1000.0, 0.05).apply { off = true }
        val r = Rig(music = music)
        val ref = Rig(music = ToneMusic(1000.0, 0.05))
        var out = FloatArray(0)
        var alone = FloatArray(0)
        repeat(8) { out = r.chunk(voice(it)); alone = ref.chunk(FloatArray(frames * 2)) }
        assertEquals(0.0, maxDiff(out, alone), 1.5 / 32768)
        music.on = false
        repeat(8) { out = r.chunk(voice(8 + it)) }
        assertEquals(0.25, amps(out).first, 0.01)
    }

    @Test fun `with no music, mutes behave exactly as before`() {
        val r = Rig(music = ToneMusic(1000.0, 0.1).apply { on = false })
        repeat(3) { r.chunk(voice(it)) }
        val fading = r.chunk(voice(3), muted = true)
        assertEquals(0.0, Metrics.peak(fading, from = MixStage.MUTE_RAMP_FRAMES + 1), 0.0)
    }

    @Test fun `music asked for through the real mixer reaches the stream, and leaves it`() {
        val ring = MusicRing(rate * 2)
        val mixer = MusicMixer(ring, rate)
        val r = Rig(music = mixer)
        var gen = 0
        val block = FloatArray(4096) { 0.25f }
        fun chunk(): FloatArray {
            val out = r.chunk(FloatArray(frames * 2))
            // The decoder's part: follow the request, keep the ring full.
            val req = mixer.request
            if (req.gen != gen) { gen = req.gen; ring.startGen(gen) }
            if (req.track != null) while (ring.free() > 0) ring.write(block, minOf(2048, ring.free()))
            return out
        }
        repeat(5) { assertEquals(0.0, Metrics.peak(chunk()), 0.0) }
        mixer.wanted = MusicTrack("/m", 0, 1000, 0f)
        var out = FloatArray(0)
        repeat(60) { out = chunk() }
        assertEquals(0.25, Metrics.peak(out), 1e-3)
        mixer.wanted = null
        repeat(60) { out = chunk() }
        assertEquals(0.0, Metrics.peak(out), 0.0)
        assertTrue(!mixer.engaged)
    }

    /** A steady level for [seconds], as a clip in the stream's layout. */
    private fun level(v: Float, seconds: Double) = ShortArray((rate * seconds).toInt() * 2) { (v * 32768).toInt().toShort() }

    @Test fun `a new alert sound replaces the one still playing instead of stacking`() {
        val r = Rig()
        r.stage.playEffect(level(0.2f, 3.0), 0L, 1f, replaces = true)
        repeat(5) { r.chunk(FloatArray(frames * 2)) }
        val second = r.pts + 100 * 1_000_000_000L / rate
        r.stage.playEffect(level(0.1f, 3.0), second, 1f, replaces = true)
        val out = ArrayList<Float>()
        repeat(4) { out.addAll(Metrics.channel(r.chunk(FloatArray(frames * 2))).toList()) }
        val l = out.toFloatArray()
        val la = r.dsp.latencyFrames
        // The first plays on until the second starts...
        assertEquals(0.2f, l[la + 50], 1e-3f)
        // ...fades out quickly as the second comes in...
        val fade = MixStage.REPLACE_FADE_FRAMES
        assertEquals(0.1f + 0.2f * 0.5f, l[la + 100 + fade / 2], 2e-3f)
        // ...and after that, only the second.
        assertEquals(0.1f, l[la + 100 + MixStage.REPLACE_FADE_FRAMES + 10], 1e-3f)
        assertEquals(0.1f, l.last(), 1e-3f)
    }

    @Test fun `alert sound volume is applied in the mix, and chimes still queue`() {
        val r = Rig()
        r.stage.playEffect(level(0.2f, 1.0), 0L, 0.5f, replaces = true)
        val out = Metrics.channel(r.chunk(FloatArray(frames * 2)))
        assertEquals(0.1f, out[frames - 1], 1e-3f)

        val q = Rig()
        q.stage.playEffect(level(0.1f, 0.05), 0L)
        q.stage.playEffect(level(0.2f, 0.05), 0L) // a chime waits for the one before it
        val first = Metrics.channel(q.chunk(FloatArray(frames * 2)))
        assertEquals(0.1f, first[frames - 1], 1e-3f)
    }
}
