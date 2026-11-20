package app.irlsupreme.engine.ingest

import app.irlsupreme.engine.audio.CameraPlayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The video side of [IngestClock] against synthetic arrival traces: 30 fps frames with whole-ms camera timestamps,
 * delayed by a function of camera time and delivered in order (TCP), on a fake clock.
 */
class IngestClockTest {
    private val frameNs = 1_000_000_000L / 30
    private val jitterNs = IngestClock.DEFAULT_JITTER_MS * 1_000_000L

    private class Frame(val camMs: Long, val arrivalNs: Long, val dueNs: Long, val anchored: Boolean, val provisionalNs: Long?)

    /**
     * Feeds [seconds] of frames whose network delay at camera time t (s) is [delayS]; the camera's first frame is
     * sent at local time 10 s. The provisional mapping is read before every frame, as the audio would.
     */
    private fun run(clock: IngestClock, seconds: Double, delayS: (Double) -> Double): List<Frame> {
        val out = ArrayList<Frame>()
        var latest = Long.MIN_VALUE
        var k = 0
        while (k / 30.0 < seconds) {
            val t = k / 30.0
            val camMs = (t * 1000).roundToLong()
            latest = maxOf(latest, 10_000_000_000L + ((t + delayS(t)) * 1e9).roundToLong()) // in order, however late
            val prov = clock.toLocalNsProvisional(0)
            val due = clock.onFrame(camMs, latest)
            out += Frame(camMs, latest, due, clock.isAnchored, prov)
            k++
        }
        return out
    }

    /**
     * When each frame reaches the screen: the decoder is fed in arrival order, a frame no earlier than it's due and no
     * earlier than it arrived, and never before the one ahead of it.
     */
    private fun presented(frames: List<Frame>): LongArray {
        val p = LongArray(frames.size)
        for (i in frames.indices) {
            p[i] = maxOf(frames[i].dueNs, frames[i].arrivalNs, if (i > 0) p[i - 1] else Long.MIN_VALUE)
        }
        return p
    }

    /** The real Pocket 3 pattern: 2 s of start-up delay rising by 1 s over 6 s, then 2.5 s caught up in a burst. */
    private fun pocket(t: Double): Double = when {
        t < 6.0 -> 2.0 + t / 6.0
        else -> maxOf(0.5, 3.0 - 0.9 * (t - 6.0)) // catch-up: frames arrive ~10x faster than real time
    }

    @Test fun `a Pocket start-up anchors with no freeze and no re-anchor`() {
        val clock = IngestClock()
        val frames = run(clock, 40.0, ::pocket)
        assertEquals(0, clock.reanchors)
        assertTrue(clock.isAnchored)
        val p = presented(frames)
        val anchorAt = frames.indexOfFirst { it.anchored }
        assertTrue(anchorAt > 0)
        var worst = 0L
        for (i in 1 until p.size) worst = maxOf(worst, p[i] - p[i - 1])
        assertTrue("worst gap ${worst / 1_000_000} ms", worst <= frameNs * 3 / 2)
        // Warm-up frames were held by the jitter allowance, not shown on arrival.
        assertEquals(frames[anchorAt - 1].arrivalNs + jitterNs, frames[anchorAt - 1].dueNs)
        // After anchoring, frames are paced by their camera timestamps: steady (whole-ms rounding, and the clock's
        // 5 ms steps down towards its target spare), whatever the arrivals did.
        for (i in anchorAt + 60 until p.size) assertTrue(abs(p[i] - p[i - 1] - frameNs) <= 6_000_000)
    }

    @Test fun `the provisional mapping hands over to the anchored one within the audio's resync bound`() {
        val clock = IngestClock()
        val frames = run(clock, 20.0, ::pocket)
        assertNull("nothing before the first frame", frames[0].provisionalNs)
        // From the first frame: its delay (2 s from camera time 0, sent at local 10 s) plus the allowance and the
        // warm-up headroom, so the 1 s rise that follows never outruns the audio.
        assertEquals(12_000_000_000L + jitterNs + IngestClock.WARMUP_AUDIO_HEADROOM_NS, frames[1].provisionalNs)
        val anchorAt = frames.indexOfFirst { it.anchored }
        val before = frames[anchorAt].provisionalNs!! // read just before the anchoring frame
        val after = clock.toLocalNs(0)!!
        assertTrue("moved ${(after - before) / 1_000_000} ms", abs(after - before) <= CameraPlayout.RESYNC_NS)
        // During the rise (delay going up 1 s) the audio's mapping holds still: no chasing the Wi-Fi.
        val rise = frames.filter { it.arrivalNs < 10_000_000_000L + 7_500_000_000L }.mapNotNull { it.provisionalNs }
        assertEquals(1, rise.distinct().size)
        // Through the catch-up burst it moves once, after the burst, not with every frame.
        val all = frames.take(anchorAt + 1).mapNotNull { it.provisionalNs }
        assertEquals(2, all.distinct().size)
        // Once anchored, the provisional mapping is the anchored one.
        assertEquals(clock.toLocalNs(12_345), clock.toLocalNsProvisional(12_345))
    }

    @Test fun `a steady camera keeps the warm-up headroom until the anchoring takes it away`() {
        val clock = IngestClock()
        val frames = run(clock, 12.0) { 0.2 }
        val anchorAt = frames.indexOfFirst { it.anchored }
        val warm = frames.take(anchorAt + 1).mapNotNull { it.provisionalNs }
        // Held through the warm-up (only the whole-ms rounding of camera timestamps can nudge it).
        assertTrue("warm-up mapping moved", warm.max() - warm.min() <= 1_000_000)
        val moved = warm.last() - clock.toLocalNs(0)!!
        // Anchoring moves the audio once, earlier by the headroom (to within the whole-ms timestamps).
        assertTrue("moved ${moved / 1_000_000} ms", abs(moved - IngestClock.WARMUP_AUDIO_HEADROOM_NS) <= 1_000_000)
    }

    @Test fun `the video backlog bound is camera time, whatever the frame rate`() {
        val clock = IngestClock()
        // The Pocket's warm-up at its worst: the allowance plus a 2.5 s catch-up queued, ~92 frames at 30 fps and
        // ~184 at 60. Neither is hopeless.
        assertFalse(clock.backlogHopeless(0, 600 + 2_500))
        // Seconds beyond that is.
        assertTrue(clock.backlogHopeless(0, 600 + IngestClock.MAX_BACKLOG_BEYOND_ALLOWANCE_MS + 1))
        assertFalse(clock.backlogHopeless(1_000, 1_000)) // one frame
    }

    @Test fun `normal Wi-Fi jitter never re-anchors`() {
        for (seed in 1L..5L) {
            val rnd = Random(seed)
            val clock = IngestClock()
            run(clock, 120.0) { 0.2 + (rnd.nextDouble() - 0.5) * 0.1 } // ±50 ms
            assertEquals("seed $seed", 0, clock.reanchors)
        }
    }

    @Test fun `a burst over a second late re-anchors once`() {
        val clock = IngestClock()
        // Everything sent from camera time 30 s to 31.7 s is stuck until 31.8 s, then arrives at once.
        run(clock, 60.0) { t -> if (t in 30.0..31.7) 31.8 - t + 0.1 else 0.1 }
        assertEquals(1, clock.reanchors)
        assertTrue(clock.lastReanchorSlackMs < -1000)
    }

    @Test fun `health counters and the presentation spare`() {
        val clock = IngestClock()
        assertFalse(clock.inWarmup)
        assertEquals(0L, clock.presentationSpareNs)
        assertNull(clock.toLocalNsProvisional(0))

        clock.onFrame(0, 1_000_000_000L)
        assertTrue(clock.inWarmup)
        assertEquals(jitterNs, clock.presentationSpareNs)
        assertEquals(2_000_000_000L, clock.warmupElapsedNs(3_000_000_000L))
        assertNotNull(clock.toLocalNsProvisional(0))

        // Steady 100 ms delay from local 0: anchors at 8 s with exactly the allowance to spare.
        val c2 = IngestClock()
        val frames = ArrayList<Long>()
        var k = 0
        fun feed(untilS: Double, delayNs: Long) {
            while (k / 30.0 < untilS) {
                val camMs = (k * 1000.0 / 30).roundToLong()
                frames += c2.onFrame(camMs, camMs * 1_000_000L + delayNs)
                k++
            }
        }
        feed(8.2, 100_000_000L)
        assertTrue(c2.isAnchored)
        assertEquals(1, c2.warmups)
        assertTrue(c2.warmupTotalNs in 8_000_000_000L..8_100_000_000L)
        assertEquals(jitterNs, c2.presentationSpareNs)
        assertTrue(c2.lastAnchorNs > 0)
        c2.captureToArrivalNs = 70_000_000L
        assertEquals(jitterNs + 70_000_000L, c2.micAlignDelayNs)

        // Over a minute the clock walks the delay down towards its 100-300 ms target; the spare follows.
        feed(80.0, 100_000_000L)
        val spare = c2.presentationSpareNs
        assertTrue("spare ${spare / 1_000_000} ms", spare in 250_000_000L until jitterNs)
        assertEquals(spare, c2.toLocalNs(0)!! - 100_000_000L) // mapping minus the baseline delay

        c2.noteBacklogFlush()
        val h = c2.health(c2.lastAnchorNs + 5_000_000_000L)
        assertTrue(h.anchored)
        assertEquals(5_000L, h.sinceAnchorMs)
        assertEquals(1, h.backlogFlushes)
        assertEquals(spare / 1_000_000, h.spareMs)
        assertEquals(0, h.reanchors)

        // A new session forgets the mapping but keeps the counters.
        c2.reset()
        assertFalse(c2.isAnchored)
        assertFalse(c2.inWarmup)
        assertNull(c2.toLocalNsProvisional(0))
        assertEquals(0L, c2.presentationSpareNs)
        assertEquals(1, c2.warmups)
        assertEquals(1, c2.backlogFlushes)
    }

    @Test fun `after a late re-anchor the spare shows the extra delay once the normal baseline returns`() {
        val clock = IngestClock()
        val frames = run(clock, 60.0) { t -> if (t in 30.0..31.7) 31.8 - t + 0.1 else 0.1 }
        assertEquals(1, clock.reanchors)
        // Re-anchored ~1.7 s late; the baseline (0.1 s) is back, so ~1.7 s more than the allowance is held, less
        // the few 5 ms steps the clock has walked down since.
        val spare = clock.presentationSpareNs
        assertTrue("spare ${spare / 1_000_000} ms", spare in 2_000_000_000L..2_400_000_000L)
        assertTrue(frames.last().anchored)
    }
}
