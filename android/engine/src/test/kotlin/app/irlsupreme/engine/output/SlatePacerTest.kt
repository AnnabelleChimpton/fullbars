package app.irlsupreme.engine.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlatePacerTest {
    /** Feeds [frames] through the pacer on a fake clock; returns (handover time ns, bytes) per frame. */
    private fun pace(frames: List<Int>, capBps: Int, lateNs: (Int) -> Long = { 0 }): List<Pair<Long, Int>> {
        val p = SlatePacer()
        var now = 0L
        return frames.mapIndexed { i, bytes ->
            now += p.waitNs(now) + lateNs(i)
            p.sent(now, bytes, capBps)
            now to bytes
        }
    }

    /** A 2 s GOP at 6 Mbps: a big keyframe, then P-frames, ~1.5 MB in all. */
    private val gop = listOf(150_000) + List(59) { 22_900 }

    @Test fun `a 1_5 MB slate at 1 Mbps takes about 12 s`() {
        val sent = pace(gop, 1_000_000)
        val total = gop.sum()
        assertTrue("slate is ${total / 1000} KB", total in 1_450_000..1_550_000)
        // The last frame goes once everything before it has had its time; it then takes its own share.
        val doneNs = sent.last().first + sent.last().second * 8L * 1_000_000_000L / 1_000_000
        assertEquals(12.0, doneNs / 1e9, 0.5)
    }

    @Test fun `the rate never exceeds the cap by more than one frame, even after oversleeping`() {
        val cap = 1_000_000
        for (late in listOf<(Int) -> Long>({ 0 }, { i -> if (i % 7 == 0) 300_000_000L else 0 })) {
            val sent = pace(gop, cap, late)
            for (i in sent.indices) for (j in i + 1 until sent.size) {
                val bits = sent.subList(i, j).sumOf { it.second.toLong() } * 8
                val allowed = cap * (sent[j].first - sent[i].first) / 1_000_000_000L + sent[i].second * 8L
                assertTrue("frames $i..$j: $bits bits, allowed $allowed", bits <= allowed)
            }
        }
    }

    @Test fun `the cap follows the live rate with a floor`() {
        assertEquals(500_000, SlatePacer.capFor(0))
        assertEquals(500_000, SlatePacer.capFor(2_000_000))
        assertEquals(1_080_000, SlatePacer.capFor(6_000_000))
        // A cap read below the floor still paces at the floor.
        val sent = pace(listOf(62_500, 62_500), capBps = 100_000)
        assertEquals(1_000_000_000L, sent[1].first)
    }

    @Test fun `the upload waits for a steady live rate, or 25 s`() {
        val g = SlateStartGate()
        var t = 0L
        fun run(seconds: Int, bps: (Long) -> Long): Long? {
            repeat(seconds * 2) {
                t += 500
                if (g.update(t, bps(t))) return t
            }
            return null
        }
        // Ramping up for 8 s: never steady.
        assertEquals(null, run(8) { 2_000_000 + it * 400 })
        // Then steady, with keyframe bumps every 2 s: ready ~10 s later.
        val ready = run(14) { 5_000_000L + if (it % 2_000 == 0L) 1_500_000 else 0 }
        assertTrue("ready at $ready", ready != null && ready in 17_000L..20_000L)

        val never = SlateStartGate()
        var u = 0L
        var fired = false
        repeat(60) {
            u += 500
            if (!fired) fired = never.update(u, 1_000_000 + u * 150) // still climbing 150 kbps a second
            if (u < 25_000) assertFalse("fired at $u", fired)
        }
        assertTrue("a rate that never settles still uploads at 25 s", fired)
    }
}
