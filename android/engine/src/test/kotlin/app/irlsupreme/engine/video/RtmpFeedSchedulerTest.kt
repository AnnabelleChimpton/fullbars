package app.irlsupreme.engine.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A synthetic stream through [RtmpFeedScheduler] and [OutputPacer]: frames in decode order, a FIFO feeder that waits
 * for each head's feed time, a decoder with a fixed decode time that outputs in presentation order, and the pacer
 * releasing at presentation time.
 */
class RtmpFeedSchedulerTest {
    private val frameMs = 33L
    private val frameNs = frameMs * 1_000_000L
    private val lead = 40_000_000L
    private val decodeNs = 12_000_000L
    private val base = 10_000_000_000L // local time of camera time 0

    private class Frame(val display: Int, val dtsMs: Long, val ptsMs: Long)

    /** IPBB...: decode order I0 P3 B1 B2 P6 B4 B5 ..., PTS = DTS + composition offset (> 0 on the P frames). */
    private fun ipbb(gops: Int): List<Frame> {
        val order = ArrayList<Int>()
        order += 0
        var p = 0
        repeat(gops * 4) { order += p + 3; order += p + 1; order += p + 2; p += 3 }
        return order.mapIndexed { k, d -> Frame(d, k * frameMs, (d + 2) * frameMs) }
    }

    private fun noB(n: Int) = (0 until n).map { Frame(it, it * frameMs, it * frameMs) }

    private fun due(ptsMs: Long) = base + ptsMs * 1_000_000L

    /** Feed times in queue order, with each frame queued 600 ms ahead (the jitter allowance). */
    private fun feedTimes(frames: List<Frame>, byDts: Boolean): LongArray {
        val out = LongArray(frames.size)
        var prev = Long.MIN_VALUE
        for ((k, f) in frames.withIndex()) {
            val want = if (byDts) RtmpFeedScheduler.feedDueNs(due(f.ptsMs), f.ptsMs, f.dtsMs, lead) else due(f.ptsMs) - lead
            val arrived = due(f.ptsMs) - 600_000_000L
            out[k] = maxOf(prev, want, arrived)
            prev = out[k]
        }
        return out
    }

    @Test fun everyBFrameStreamFrameIsFedByItsOwnDeadline() {
        val frames = ipbb(20)
        assertTrue(frames.any { it.ptsMs > it.dtsMs })
        val fed = feedTimes(frames, byDts = true)
        for ((k, f) in frames.withIndex()) assertTrue("frame ${f.display} fed late", fed[k] <= due(f.ptsMs) - lead)
        // The old schedule (PTS minus a fixed lead, in queue order) fed B frames late behind their P frame.
        val old = feedTimes(frames, byDts = false)
        assertTrue(frames.indices.any { old[it] > due(frames[it].ptsMs) - lead })
    }

    @Test fun withNoBFramesTheScheduleIsUnchanged() {
        for (f in noB(30)) {
            assertEquals(due(f.ptsMs) - lead, RtmpFeedScheduler.feedDueNs(due(f.ptsMs), f.ptsMs, f.dtsMs, lead))
        }
    }

    /** Runs the decoder model and the pacer; returns release times in display order and the most buffers held. */
    private fun releases(frames: List<Frame>): Pair<LongArray, Int> {
        val fed = feedTimes(frames, byDts = true)
        val decodedAt = HashMap<Int, Long>()
        for ((k, f) in frames.withIndex()) decodedAt[f.display] = fed[k] + decodeNs
        // Output in display order once it and everything before it are decoded.
        val outputAt = LongArray(frames.size)
        var ready = Long.MIN_VALUE
        for (d in frames.indices) { ready = maxOf(ready, decodedAt.getValue(d)); outputAt[d] = ready }
        val pacer = OutputPacer(3)
        val released = LongArray(frames.size)
        var next = 0 // next output to take from the decoder
        var t = outputAt[0]
        var peak = 0
        val ptsOf = frames.associate { it.display to it.ptsMs }
        while (next < frames.size || pacer.size > 0) {
            while (pacer.size > 0) {
                when (RtmpFeedScheduler.decide(t, pacer.headDueNs, frameNs)) {
                    RtmpFeedScheduler.Action.WAIT -> break
                    RtmpFeedScheduler.Action.RENDER, RtmpFeedScheduler.Action.DROP -> {
                        released[pacer.headIndex] = t
                        pacer.removeHead()
                    }
                }
            }
            if (next < frames.size && !pacer.isFull && outputAt[next] <= t) {
                pacer.add(next, due(ptsOf.getValue(next)))
                next++
                peak = maxOf(peak, pacer.size)
                continue
            }
            // Advance to the next event: a head coming due or the decoder's next output.
            val candidates = ArrayList<Long>()
            if (pacer.size > 0) candidates += pacer.headDueNs
            if (next < frames.size && !pacer.isFull) candidates += outputAt[next]
            if (candidates.isEmpty()) break
            t = maxOf(t + 1, candidates.min())
        }
        return released to peak
    }

    @Test fun releasesAreEvenlySpacedByPts() {
        for (frames in listOf(ipbb(20), noB(80))) {
            val (rel, peak) = releases(frames)
            val gaps = rel.toList().zipWithNext { a, b -> b - a }
            assertTrue("gaps $gaps", gaps.all { it == frameNs })
            assertTrue("held $peak", peak <= 3)
        }
    }

    @Test fun decisions() {
        assertEquals(RtmpFeedScheduler.Action.RENDER, RtmpFeedScheduler.decide(0, RtmpFeedScheduler.NOW, frameNs))
        assertEquals(RtmpFeedScheduler.Action.WAIT, RtmpFeedScheduler.decide(99, 100, frameNs))
        assertEquals(RtmpFeedScheduler.Action.RENDER, RtmpFeedScheduler.decide(100 + frameNs, 100, frameNs))
        assertEquals(RtmpFeedScheduler.Action.DROP, RtmpFeedScheduler.decide(101 + frameNs, 100, frameNs))
        // Late with nothing newer to show instead: shown anyway (a slow decoder mustn't blank the camera).
        assertEquals(RtmpFeedScheduler.Action.RENDER, RtmpFeedScheduler.decide(101 + frameNs, 100, frameNs, newerWaiting = false))
    }

    @Test fun pacerIsAFixedRing() {
        val p = OutputPacer(3)
        p.add(7, 1); p.add(8, 2); p.add(9, 3)
        assertTrue(p.isFull)
        assertEquals(7, p.headIndex)
        p.removeHead()
        p.add(10, 4)
        assertEquals(listOf(8, 9, 10), List(3) { p.headIndex.also { p.removeHead() } })
        assertEquals(0, p.size)
    }
}
