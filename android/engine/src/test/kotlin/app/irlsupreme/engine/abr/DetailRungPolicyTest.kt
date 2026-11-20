package app.irlsupreme.engine.abr

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailRungPolicyTest {
    private var now = 0L

    /** Feeds [bps] every 250 ms for [ms]; returns the scale at the end. */
    private fun DetailRungPolicy.hold(bps: Int, ms: Long): Float {
        var s = scale
        repeat((ms / 250).toInt()) { now += 250; s = update(now, bps) }
        return s
    }

    @Test fun stepsDownAfterTwoSecondsAndUpAfterFive() {
        val p = DetailRungPolicy(DetailRungPolicy.WidthClass.P1080)
        assertEquals(1.0f, p.hold(5_000_000, 10_000))
        assertEquals("not yet", 1.0f, p.hold(3_000_000, 1_750))
        assertEquals(0.75f, p.hold(3_000_000, 500))
        assertEquals("not yet", 0.75f, p.hold(4_000_000, 4_750))
        assertEquals(1.0f, p.hold(4_000_000, 500))
    }

    @Test fun aDipShorterThanTheHoldChangesNothing() {
        val p = DetailRungPolicy(DetailRungPolicy.WidthClass.P1080)
        p.hold(5_000_000, 5_000)
        p.hold(1_500_000, 1_500)
        assertEquals(1.0f, p.hold(5_000_000, 10_000))
    }

    @Test fun oneStepPerEpisode() {
        val p = DetailRungPolicy(DetailRungPolicy.WidthClass.P1080)
        p.hold(5_000_000, 5_000)
        // One episode: the target falls 5 -> 1.5 Mbps in steps a second apart, then sits there.
        var s = 1.0f
        for (bps in listOf(4_000_000, 3_000_000, 2_200_000, 1_500_000)) { s = p.hold(bps, 1_000) }
        assertEquals(0.75f, s)
        assertEquals("still one rung while the episode lasts", 0.75f, p.hold(1_500_000, 3_500))
        // The episode is over once the target hasn't fallen for 5 s: the next rung follows.
        assertEquals(0.5f, p.hold(1_500_000, 2_000))
    }

    @Test fun climbsOneRungAtATime() {
        val p = DetailRungPolicy(DetailRungPolicy.WidthClass.P1080)
        p.hold(1_500_000, 2_250)
        p.hold(1_500_000, 7_000)
        assertEquals(0.5f, p.scale)
        assertEquals(0.5f, p.hold(5_000_000, 4_750))
        assertEquals(0.75f, p.hold(5_000_000, 500))
        assertEquals(0.75f, p.hold(5_000_000, 4_500))
        assertEquals(1.0f, p.hold(5_000_000, 1_000))
    }

    @Test fun sevenTwentyNeverGoesBelowThreeQuarters() {
        val p = DetailRungPolicy(DetailRungPolicy.WidthClass.P720)
        assertEquals(1.0f, p.hold(2_500_000, 5_000))
        assertEquals(0.75f, p.hold(1_900_000, 2_250))
        assertEquals(0.75f, p.hold(500_000, 30_000))
        assertEquals(1.0f, p.hold(2_000_000, 5_250))
    }

    @Test fun frameSizePicksTheLadder() {
        assertEquals(DetailRungPolicy.WidthClass.P1080, DetailRungPolicy.forFrame(1920, 1080).widthClass)
        assertEquals(DetailRungPolicy.WidthClass.P1080, DetailRungPolicy.forFrame(1080, 1920).widthClass)
        assertEquals(DetailRungPolicy.WidthClass.P720, DetailRungPolicy.forFrame(1280, 720).widthClass)
    }
}
