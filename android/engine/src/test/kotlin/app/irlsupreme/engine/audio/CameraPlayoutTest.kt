package app.irlsupreme.engine.audio

import app.irlsupreme.engine.ingest.IngestClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * The camera's audio over a simulated Wi-Fi RTMP feed: AAC-sized blocks of a sine with whole-millisecond timestamps,
 * delivered late, in bursts, with jitter and clock drift; video frames on the same feed drive the real [IngestClock];
 * the playout is pulled one 1024-frame chunk per mic chunk, as [AudioPipeline] does.
 */
class CameraPlayoutTest {
    private val rate = 48_000
    private val block = 1024
    private val chunkNs = block * 1_000_000_000L / rate

    private class Result(
        val out: ShortArray,
        /** Camera time (ns) each output frame was due at, per the clock; null before it anchored. */
        val dueCamNs: LongArray,
        val firstPlayed: Int,
        val playout: CameraPlayout,
    )

    /**
     * Runs [seconds] of camera time. [arrivalDelayNs] gives each packet's network delay from its camera time (s);
     * [driftPpm]: the camera's clock runs fast by this much. [dropBlock] loses an audio block; [camShiftMs] adds a jump
     * to the camera's timestamps from a camera time on.
     */
    private fun run(
        seconds: Double,
        hz: Double = 440.0,
        amp: Double = 0.5,
        driftPpm: Double = 0.0,
        dropBlock: (Int) -> Boolean = { false },
        camShiftMs: (Double) -> Long = { 0L },
        arrivalDelayNs: (Double) -> Long,
    ): Result {
        class Ev(val atNs: Long, val video: Boolean, val camMs: Long, val pcm: ShortArray?)
        val evs = ArrayList<Ev>()
        val toLocal = { camS: Double -> (camS / (1 + driftPpm / 1e6) * 1e9).roundToLong() + arrivalDelayNs(camS) }
        var b = 0
        while (b * block.toDouble() / rate < seconds) {
            val camS = b * block.toDouble() / rate
            if (!dropBlock(b)) {
                val pcm = ShortArray(block) { k -> (amp * 32767 * sin(2 * PI * hz * (b * block + k) / rate)).toInt().toShort() }
                evs += Ev(toLocal(camS), false, (camS * 1000).roundToLong() + camShiftMs(camS), pcm)
            }
            b++
        }
        var v = 0
        while (v / 30.0 < seconds) {
            val camS = v / 30.0
            evs += Ev(toLocal(camS), true, (camS * 1000).roundToLong() + camShiftMs(camS), null)
            v++
        }
        // TCP: the feed arrives in the order it was sent, however late.
        evs.sortBy { it.camMs * 2 + if (it.video) 1 else 0 }
        var latest = Long.MIN_VALUE
        val ordered = evs.map { e -> latest = maxOf(latest, e.atNs); Ev(latest, e.video, e.camMs, e.pcm) }
        evs.clear(); evs.addAll(ordered)

        val clock = IngestClock()
        val playout = CameraPlayout(rate, 1)
        val chunks = ((seconds - 1.5) * rate / block).toInt() // the feed ends; stop before the playout runs dry
        val out = ShortArray(chunks * block)
        val due = LongArray(chunks * block) { Long.MIN_VALUE }
        var next = 0
        var firstPlayed = -1
        val tmp = ShortArray(block)
        for (c in 0 until chunks) {
            val pts = 1_000_000_000L + c * chunkNs // the mic chunk's first sample
            val now = pts + chunkNs + 3_000_000L // read just after it's complete
            while (next < evs.size && evs[next].atNs <= now) {
                val e = evs[next++]
                if (e.video) clock.onFrame(e.camMs, e.atNs) else playout.push(e.camMs * 1_000_000L, e.pcm!!)
            }
            val target = clock.toLocalNs(0)?.let { pts - it }
            val n = playout.pull(tmp, block, target)
            if (n > 0 && firstPlayed < 0) firstPlayed = c * block
            tmp.copyInto(out, c * block)
            if (target != null) for (m in 0 until block) due[c * block + m] = target + m * 1_000_000_000L / rate
        }
        assertTrue("the camera never played", firstPlayed >= 0)
        return Result(out, due, firstPlayed, playout)
    }

    /** The biggest second difference: a skipped, repeated or zeroed sample shows up here, a smooth fade doesn't. */
    private fun maxKink(x: ShortArray, from: Int): Int {
        var m = 0
        for (i in maxOf(from, 2) until x.size) m = maxOf(m, abs(x[i] - 2 * x[i - 1] + x[i - 2]))
        return m
    }

    /** Longest run of exact zeros (a sine at 440 Hz has single-sample zero crossings at most). */
    private fun longestSilence(x: ShortArray, from: Int): Int {
        var best = 0; var run = 0
        for (i in from until x.size) { if (x[i].toInt() == 0) { run++; best = maxOf(best, run) } else run = 0 }
        return best
    }

    /** How far (ms) the output lags the camera time it was due at, from a least-squares fit at [hz] over a window. */
    private fun lagMs(r: Result, hz: Double, from: Int, to: Int): Double {
        var s = 0.0; var c = 0.0
        for (i in from until to) {
            val ph = 2 * PI * hz * r.dueCamNs[i] / 1e9
            s += r.out[i] * sin(ph); c += r.out[i] * cos(ph)
        }
        // out ≈ A·sin(ph - lag·w) = A·(sin ph·cos lw - cos ph·sin lw)
        return atan2(-c, s) / (2 * PI * hz) * 1000
    }

    // Bursty Wi-Fi: 80 ms base, everything in the first 300 ms of each 600 ms held to its end, plus 0-40 ms jitter.
    private fun bursty(seed: Long): (Double) -> Long {
        val rnd = Random(seed)
        return { camS ->
            val inCycle = camS % 0.6
            val hold = if (inCycle < 0.3) (0.3 - inCycle) else 0.0
            ((0.08 + hold) * 1e9).toLong() + (rnd.nextDouble() * 40e6).toLong()
        }
    }

    @Test fun `bursty jittery Wi-Fi plays continuously without clicks`() {
        val r = run(60.0, arrivalDelayNs = bursty(1))
        val p = r.playout
        assertEquals(0, p.underruns)
        assertEquals(0, p.resyncs)
        assertEquals(0, p.gapFills)
        // A sine's own second difference here is ~54; the fade-in adds ~70. A skipped or held sample is hundreds.
        assertTrue("kink ${maxKink(r.out, r.firstPlayed)}", maxKink(r.out, r.firstPlayed) < 200)
        assertTrue("silence ${longestSilence(r.out, r.firstPlayed)}", longestSilence(r.out, r.firstPlayed) <= 2)
        assertTrue("depth ${p.depthNs / 1_000_000} ms", p.depthNs in 1..1_000_000_000L)
    }

    @Test fun `old per-block placement would have clicked on this feed`() {
        // Guards the test itself: whole-ms timestamps against 21.33 ms blocks overlap or leave holes every block, so
        // splicing blocks by timestamp (the old CameraAudio) produces kinks far above the threshold used above.
        val camMs = LongArray(50) { b -> (b * block * 1000.0 / rate).roundToLong() }
        val placed = ShortArray(50 * block)
        for (b in 0 until 50) {
            val start = (camMs[b] * rate / 1000).toInt()
            for (k in 0 until block) if (start + k < placed.size) placed[start + k] = (0.5 * 32767 * sin(2 * PI * 440 * (b * block + k) / rate)).toInt().toShort()
        }
        assertTrue(maxKink(placed, 0) > 200)
    }

    @Test fun `stays in sync with the picture through jitter`() {
        val r = run(60.0, hz = 50.0, arrivalDelayNs = bursty(2))
        val end = r.out.size
        val lag = lagMs(r, 50.0, end - 5 * rate, end)
        assertTrue("lag $lag ms", abs(lag) < 3.0)
        assertTrue(abs(r.playout.syncErrorNs) < 3_000_000)
    }

    @Test fun `camera clock drift is absorbed by resampling, not by dropping samples`() {
        for (drift in listOf(400.0, -400.0)) {
            val rnd = Random(3)
            val r = run(120.0, hz = 50.0, driftPpm = drift) { (0.1e9 + rnd.nextDouble() * 30e6).toLong() }
            val p = r.playout
            assertEquals("drift $drift", 0, p.underruns)
            assertEquals("drift $drift", 0, p.resyncs)
            assertTrue("drift $drift kink ${maxKink(r.out, r.firstPlayed)}", maxKink(r.out, r.firstPlayed) < 200)
            assertTrue("drift $drift silence", longestSilence(r.out, r.firstPlayed + rate) <= 2)
            val end = r.out.size
            val lag = lagMs(r, 50.0, end - 5 * rate, end)
            // The clock steps its mapping by 5-20 ms to follow the drift; the playout follows within a few ms.
            assertTrue("drift $drift lag $lag ms", abs(lag) < 25.0)
            assertTrue("drift $drift rate ${p.ratePpm}", abs(p.ratePpm) <= 6_000)
        }
    }

    @Test fun `a lost AAC frame becomes faded silence of the same length and sync holds`() {
        val lost = 1500 // ~32 s in, well after the clock anchored
        val r = run(60.0, hz = 440.0, dropBlock = { it == lost }) { 100_000_000L }
        val p = r.playout
        assertEquals(1, p.gapFills)
        assertEquals(0, p.underruns)
        assertTrue("kink ${maxKink(r.out, r.firstPlayed)}", maxKink(r.out, r.firstPlayed) < 200)
        // The gap is the lost block's length, as far as whole-ms timestamps and a 0.6% resample can say, not more.
        val gap = longestSilence(r.out, r.firstPlayed)
        assertTrue("gap $gap", gap in block - 60..block + 60)
    }

    @Test fun `running dry fades out, then comes back aligned, without clicks`() {
        // Everything from camera time 30 s to 31 s is held until 31 s: longer than the clock's spare.
        val r = run(60.0, hz = 440.0) { camS -> 100_000_000L + if (camS in 30.0..31.0) ((31.0 - camS) * 1e9).toLong() else 0L }
        val p = r.playout
        assertTrue("underruns ${p.underruns}", p.underruns >= 1)
        assertTrue("kink ${maxKink(r.out, r.firstPlayed)}", maxKink(r.out, r.firstPlayed) < 200)
        // Plays again afterwards: the last few seconds are all camera audio.
        assertTrue(longestSilence(r.out, r.out.size - 5 * rate) <= 2)
    }

    @Test fun `a jump in the camera's timeline aligns again without a click`() {
        val r = run(60.0, hz = 440.0, camShiftMs = { camS -> if (camS >= 30.0) 10_000L else 0L }) { 100_000_000L }
        val p = r.playout
        assertTrue("resyncs ${p.resyncs}", p.resyncs + p.underruns >= 1)
        assertTrue("kink ${maxKink(r.out, r.firstPlayed)}", maxKink(r.out, r.firstPlayed) < 200)
        assertTrue(longestSilence(r.out, r.out.size - 5 * rate) <= 2)
    }

    @Test fun `nothing plays before the clock anchors, and memory stays bounded`() {
        val p = CameraPlayout(rate, 2, capacityFrames = rate)
        val pcm = ShortArray(block * 2) { 1000 }
        repeat(500) { p.push(it * 21_333_333L, pcm) } // ~10 s, never pulled with a clock
        val out = ShortArray(block * 2)
        assertEquals(0, p.pull(out, block, null))
        assertTrue(out.all { it.toInt() == 0 })
        assertTrue(p.depthNs <= 1_000_000_000L)
    }
}
