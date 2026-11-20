package app.irlsupreme.engine.audio

import app.irlsupreme.engine.audio.testutil.Metrics
import app.irlsupreme.engine.audio.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLevelerTest {
    private val rate = 48_000
    private val frames = 1024

    /** Runs [x] through a mic stage at [gainDb] with the leveler on or off; returns the output and the final leveler gain. */
    private fun run(x: FloatArray, gainDb: Float, leveler: Boolean = true): Pair<FloatArray, Float> {
        val dsp = MicDsp(rate, 2).apply { this.gainDb = gainDb }
        val out = FloatArray(x.size)
        var level = MicLevel.SILENT
        for (c in 0 until x.size / (frames * 2)) {
            val pcm = Signals.toPcm(x, c * frames * 2, frames * 2)
            level = dsp.process(pcm, frames, lowCutHz = 80, leveler = leveler)
            Signals.fromPcm(pcm).copyInto(out, c * frames * 2)
        }
        return out to level.levelerDb
    }

    /** Mean short-term loudness over the last [seconds] of [x]. */
    private fun endLoudness(x: FloatArray, seconds: Int = 4): Double {
        val st = Metrics.shortTermLufs(x)
        return st.takeLast(seconds * 10).average()
    }

    @Test fun `a voice at very different levels comes out about as loud`() {
        // +12 dB on the fader puts the middle level near the target; the leveler covers 10 dB either side of it.
        val n = 16 * rate
        val outs = listOf(-40.0, -30.0, -20.0).map { db -> endLoudness(run(Signals.speechLike(db, n, rate), 12f).first) }
        val flat = listOf(-40.0, -30.0, -20.0).map { db -> endLoudness(run(Signals.speechLike(db, n, rate), 12f, leveler = false).first) }
        val spread = outs.max() - outs.min()
        assertTrue("levelled $outs (flat $flat), spread $spread LU", spread <= 4.0)
        assertTrue("flat spread ${flat.max() - flat.min()}", flat.max() - flat.min() > 12.0) // the problem it solves
        for (l in outs) assertEquals(VoiceLeveler.TARGET_LUFS.toDouble(), l, 3.0)
    }

    @Test fun `steady quiet noise is not turned up`() {
        val n = 10 * rate
        val p = Signals.pink(n, seed = 3)
        val x = FloatArray(n * 2)
        val g = Math.pow(10.0, -55.0 / 20) / Math.sqrt(p.sumOf { it.toDouble() * it } / n)
        for (f in 0 until n) { x[2 * f] = (p[f] * g).toFloat(); x[2 * f + 1] = x[2 * f] }
        val (_, gainDb) = run(x, 0f)
        assertTrue("leveler gain $gainDb dB", gainDb < 3f)
    }

    @Test fun `a pause between sentences holds the gain instead of raising the room`() {
        // Talk over a room's steady noise (15 dB under the voice), then 4 s of just the room: the gain mustn't climb
        // during the pause. Between words the room is what's left, which is how the leveler knows it for the floor.
        fun room(seconds: Int, seed: Long) = Signals.gain(Signals.pink(seconds * rate, seed).let { p -> FloatArray(p.size * 2) { p[it / 2] } }, -50.0)
        val talk = Signals.speechLike(-35.0, 6 * rate, rate).also { t -> val r = room(6, 8); for (i in t.indices) t[i] += r[i] }
        val room = room(4, 9)
        val dsp = MicDsp(rate, 2).apply { gainDb = 12f }
        fun feed(x: FloatArray): Float {
            var l = MicLevel.SILENT
            for (c in 0 until x.size / (frames * 2)) l = dsp.process(Signals.toPcm(x, c * frames * 2, frames * 2), frames, lowCutHz = 80, leveler = true)
            return l.levelerDb
        }
        val afterTalk = feed(talk)
        val afterPause = feed(room)
        assertTrue("talk $afterTalk dB then pause $afterPause dB", afterPause <= afterTalk + 1.5f)
    }

    @Test fun `the levelled voice never goes over the ceiling`() {
        val (out, _) = run(Signals.speechLike(-14.0, 8 * rate, rate), 20f)
        assertTrue(Metrics.peak(out) * 32768 <= MicDsp.CEILING * 32768 + 1)
    }

    @Test fun `switched off, it glides back to 0 dB rather than jumping`() {
        val lev = VoiceLeveler(rate, 2)
        val x = Signals.speechLike(-40.0, 6 * rate, rate)
        val buf = FloatArray(frames * 2)
        for (c in 0 until x.size / (frames * 2)) { x.copyInto(buf, 0, c * frames * 2, (c + 1) * frames * 2); lev.process(buf, frames, true) }
        val up = lev.gainDb
        assertTrue("gain $up", up > 6f)
        lev.process(buf, frames, false)
        assertTrue(lev.gainDb < up && lev.gainDb > up - 0.2f)
        repeat(200) { lev.process(buf, frames, false) }
        assertEquals(0f, lev.gainDb, 0f)
    }

    @Test fun `a reopened mic starts from 0 dB, so auto level coming back on gets no leftover boost`() {
        // A quiet raw mic builds the gain up...
        val dsp = MicDsp(rate, 2)
        val quiet = Signals.speechLike(-40.0, 6 * rate, rate)
        var l = MicLevel.SILENT
        for (c in 0 until quiet.size / (frames * 2)) l = dsp.process(Signals.toPcm(quiet, c * frames * 2, frames * 2), frames, leveler = true)
        assertTrue("built up ${l.levelerDb} dB", l.levelerDb > 6f)
        // ...then the phone's auto level goes back on: the mic reopens ~28 dB hotter, leveler off.
        dsp.micReopened()
        val hot = Signals.sine(1000.0, Math.pow(10.0, -10.0 / 20), frames)
        l = dsp.process(Signals.toPcm(hot), frames, leveler = false)
        assertEquals(0f, l.levelerDb, 0f)
        assertEquals(0f, l.reductionDb, 0f)
    }

    /** Room noise 15 dB under a -35 dB voice, as in the pause test. */
    private fun talkOverRoom(seconds: Int): FloatArray {
        val r = Signals.gain(Signals.pink(seconds * rate, 8).let { p -> FloatArray(p.size * 2) { p[it / 2] } }, -50.0)
        return Signals.speechLike(-35.0, seconds * rate, rate).also { t -> for (i in t.indices) t[i] += r[i] }
    }

    private fun VoiceLeveler.feed(x: FloatArray, enabled: Boolean = true) {
        val buf = FloatArray(frames * 2)
        for (c in 0 until x.size / (frames * 2)) { x.copyInto(buf, 0, c * frames * 2, (c + 1) * frames * 2); process(buf, frames, enabled) }
    }

    @Test fun `gap-fill silence neither moves the gain nor drags the noise floor down`() {
        val lev = VoiceLeveler(rate, 2)
        lev.feed(Signals.gain(talkOverRoom(6), 12.0))
        val floor = lev.floorDb
        val gain = lev.gainDb
        // Three of the longest gaps the timeline fills, close together: counted, they'd be the floor (10th percentile).
        lev.feed(FloatArray(30 * frames * 2))
        // The filters' tail makes the first chunk of a gap not quite silent; the rest is.
        assertEquals(floor, lev.floorDb, 1.5f)
        assertEquals(gain, lev.gainDb, 0.1f)
        assertTrue(lev.frozen)
    }

    @Test fun `switched off and on again, it measures fresh audio rather than what it heard before`() {
        val lev = VoiceLeveler(rate, 2)
        lev.feed(Signals.speechLike(-20.0, 4 * rate, rate))
        lev.feed(FloatArray(2 * frames * 2), enabled = false)
        // Back on with steady quiet noise: the first chunk's 400 ms loudness is that noise alone, not the old voice.
        val p = Signals.pink(frames, 4)
        val noise = Signals.gain(FloatArray(frames * 2) { p[it / 2] }, -55.0)
        lev.process(noise, frames, true)
        assertTrue("loudness ${lev.loudnessLufs}", lev.loudnessLufs < -45f && lev.loudnessLufs > VoiceLeveler.DIGITAL_SILENCE_LUFS)
        assertTrue(lev.frozen)
    }
}
