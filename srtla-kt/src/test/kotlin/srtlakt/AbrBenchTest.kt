// SPDX-License-Identifier: Apache-2.0
package srtlakt

import srtlakt.abr.BitrateController
import srtlakt.sim.BondSim
import srtlakt.sim.BondSim.LinkSpec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Closed-loop bench: [BitrateController] driving [BondSim] through a handful of scenarios, 20 seeds each. Writes the
 * numbers (p10/p50/p90 across seeds) to build/bench/abr.json so a change to the controller can be compared with the
 * committed baseline (src/test/resources/abr-baseline.json, recorded from the controller before the delivery-anchored
 * descent, blackout hold, link feed-forward and loss input went in).
 *
 * The model is a bottleneck model, so the absolute numbers are only as good as it is; the comparison is the point.
 */
class AbrBenchTest {
    private val seeds = 1L..20L
    private val results = linkedMapOf<String, Map<String, Double>>()

    private fun controller() = BitrateController(minBps = MIN_BPS, maxBps = MAX_BPS)

    /** 2/2/1.5 Mbit links with different delays: the bond tips into a resend spiral at ~3.3 Mbps of media. */
    private fun steadyBond(seed: Long) = BondSim(
        listOf(LinkSpec(2_000_000, 40), LinkSpec(2_000_000, 70), LinkSpec(1_500_000, 110)),
        controller(), seed, cliff = BondSim.Cliff(onsetMediaBps = 3_000_000),
    )

    private fun pct(xs: List<Double>, p: Double): Double {
        val s = xs.sorted()
        return s[((s.size - 1) * p).toInt()]
    }

    private fun record(name: String, perSeed: List<Double>) {
        results[name] = mapOf("p10" to pct(perSeed, 0.1), "p50" to pct(perSeed, 0.5), "p90" to pct(perSeed, 0.9))
    }

    private data class Steady(val meanRatio: Double, val p10Ratio: Double, val atMinShare: Double, val p95Queue: Double, val cutsPerMin: Double)

    private fun steady(seed: Long): Steady {
        val sim = steadyBond(seed)
        val knee = sim.kneeMediaBps().toDouble()
        sim.run(180_000)
        val s = sim.samples.filter { it.tMs > 30_000 }
        val targets = s.map { it.targetBps.toDouble() }
        val queues = s.map { it.queueMs.toDouble() }
        val cuts = s.count { it.action.startsWith("down") }
        return Steady(
            meanRatio = targets.average() / knee,
            p10Ratio = pct(targets, 0.1) / knee,
            atMinShare = s.count { it.targetBps <= MIN_BPS }.toDouble() / s.size,
            p95Queue = pct(queues, 0.95),
            cutsPerMin = cuts / ((s.last().tMs - s.first().tMs) / 60_000.0),
        )
    }

    /** One 6 Mbit link drops to [toBps] after a minute; returns ms until the target is at or below 0.9x the new rate. */
    private fun stepDown(seed: Long, toBps: Long = 2_500_000): Double {
        val sim = BondSim(listOf(LinkSpec(6_000_000, 50)), controller(), seed)
        sim.run(60_000)
        sim.setRate(0, toBps)
        val at = sim.nowMs
        var reached = -1L
        sim.run(20_000) { if (reached < 0 && it.controller.targetBps <= toBps * 0.9) reached = it.nowMs - at }
        return if (reached < 0) 20_000.0 else reached.toDouble()
    }

    /** One link at 2.5 Mbit for a minute, then 6: ms until the target reaches 85% of the new knee (capped at 120 s). */
    private fun stepUp(seed: Long): Double {
        val sim = BondSim(listOf(LinkSpec(2_500_000, 50)), controller(), seed)
        sim.run(60_000)
        sim.setRate(0, 6_000_000)
        val goal = sim.kneeMediaBps() * 0.85
        val at = sim.nowMs
        var reached = -1L
        sim.run(120_000) { if (reached < 0 && it.controller.targetBps >= goal) reached = it.nowMs - at }
        return if (reached < 0) 120_000.0 else reached.toDouble()
    }

    /** Wi-Fi (3 Mbit) and cellular (2 Mbit); Wi-Fi goes away. Returns ms until target <= 0.9x the survivor's knee. */
    private fun linkLoss(seed: Long): Double {
        val sim = BondSim(listOf(LinkSpec(3_000_000, 30), LinkSpec(2_000_000, 80)), controller(), seed)
        sim.run(60_000)
        sim.setConnected(0, false)
        val goal = sim.kneeMediaBps() * 0.9
        val at = sim.nowMs
        var reached = -1L
        sim.run(10_000) { if (reached < 0 && it.controller.targetBps <= goal) reached = it.nowMs - at }
        return if (reached < 0) 10_000.0 else reached.toDouble()
    }

    private data class Tunnel(val downsDuring: Double, val recoveredShare: Double)

    /** Two links, both blackholed for 4 s (they stay connected): cuts during it, and target 3 s after vs before. */
    private fun tunnel(seed: Long): Tunnel {
        val sim = BondSim(listOf(LinkSpec(3_000_000, 40), LinkSpec(3_000_000, 70)), controller(), seed)
        sim.run(60_000)
        val before = sim.controller.targetBps
        sim.setRate(0, 0); sim.setRate(1, 0)
        val from = sim.samples.size
        sim.run(4_000)
        val downs = sim.samples.drop(from).count { it.action.startsWith("down") }
        sim.setRate(0, 3_000_000); sim.setRate(1, 3_000_000)
        sim.run(3_000)
        return Tunnel(downs.toDouble(), sim.controller.targetBps.toDouble() / before)
    }

    @Test
    fun `closed-loop bench`() {
        val st = seeds.map { steady(it) }
        record("steady.meanTargetOverKnee", st.map { it.meanRatio })
        record("steady.p10TargetOverKnee", st.map { it.p10Ratio })
        record("steady.shareAtMinBps", st.map { it.atMinShare })
        record("steady.p95QueueMs", st.map { it.p95Queue })
        record("steady.cutsPerMin", st.map { it.cutsPerMin })
        record("stepDown.msToSafe", seeds.map { stepDown(it) })
        record("stepUp.msTo85PctKnee", seeds.map { stepUp(it) })
        record("linkLoss.msToSafe", seeds.map { linkLoss(it) })
        val tu = seeds.map { tunnel(it) }
        record("tunnel.downsDuringBlackout", tu.map { it.downsDuring })
        record("tunnel.targetAfter3sOverBefore", tu.map { it.recoveredShare })
        write()
        println(json())

        val r = results
        fun v(k: String, p: String) = r.getValue(k).getValue(p)
        val failures = mutableListOf<String>()
        fun check(ok: Boolean, what: String) { if (!ok) failures += what }
        check(v("steady.meanTargetOverKnee", "p50") >= 0.75, "steady: mean target >= 0.75x knee")
        check(v("steady.p10TargetOverKnee", "p50") >= 0.65, "steady: p10 target >= 0.65x knee")
        check(v("steady.shareAtMinBps", "p90") == 0.0, "steady: no time at minBps")
        check(v("steady.p95QueueMs", "p50") <= 200.0, "steady: p95 queue <= backOffQueueMs")
        check(v("steady.cutsPerMin", "p50") <= 2.0, "steady: at most 2 cuts/min")
        check(v("stepDown.msToSafe", "p90") <= 1_500.0, "step 6 -> 2.5: safe within 1.5 s")
        check(v("stepUp.msTo85PctKnee", "p90") <= 60_000.0, "step 2.5 -> 6: 85% of the knee within 60 s")
        check(v("tunnel.downsDuringBlackout", "p90") == 0.0, "tunnel: no cuts while all links are silent")
        check(v("tunnel.targetAfter3sOverBefore", "p10") >= 0.85, "tunnel: >= 85% of the pre-tunnel target 3 s after")
        // No regression against the committed baseline on the low tail of the steady state.
        val base = baseline("steady.p10TargetOverKnee", "p10")
        if (base != null) check(v("steady.p10TargetOverKnee", "p10") >= base - 0.02, "steady p10 no worse than the baseline ($base)")
        assertTrue(failures.isEmpty(), "bench: ${failures.joinToString("; ")}\n${json()}")
    }

    private fun baseline(key: String, p: String): Double? {
        val text = javaClass.getResource("/abr-baseline.json")?.readText() ?: return null
        val obj = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\\{([^}]*)}").find(text) ?: return null
        return Regex("\"$p\"\\s*:\\s*([0-9.eE+-]+)").find(obj.groupValues[1])?.groupValues?.get(1)?.toDouble()
    }

    private fun json(): String = results.entries.joinToString(",\n", "{\n", "\n}\n") { (k, m) ->
        "  \"$k\": {" + m.entries.joinToString(", ") { (p, x) -> "\"$p\": ${"%.4f".format(java.util.Locale.ROOT, x)}" } + "}"
    }

    private fun write() {
        val dir = File("build/bench").apply { mkdirs() }
        File(dir, "abr.json").writeText(json())
    }

    private companion object {
        const val MIN_BPS = 1_000_000
        const val MAX_BPS = 6_000_000
    }
}
