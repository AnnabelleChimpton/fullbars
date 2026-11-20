package app.irlsupreme.engine.video

import java.util.concurrent.locks.LockSupport

/**
 * Where [FrameClock] reads the time and how it waits. The real one is System.nanoTime (CLOCK_MONOTONIC) and
 * LockSupport.parkNanos; tests pass a fake that moves time forward instead of sleeping.
 */
interface TickTime {
    fun nanoTime(): Long
    fun parkNanos(ns: Long)

    companion object {
        val SYSTEM: TickTime = object : TickTime {
            override fun nanoTime(): Long = System.nanoTime()
            override fun parkNanos(ns: Long) = LockSupport.parkNanos(ns)
        }
    }
}

/**
 * Fixed-rate clock on System.nanoTime() (CLOCK_MONOTONIC). Not Choreographer/vsync, so it keeps ticking with the
 * screen off. If the render thread falls more than a period behind, ticks are skipped so output timestamps
 * show the real gap instead of bunching up.
 *
 * Two things can move the grid, and neither can make a tick time go backwards:
 *  - [setPeriod] changes the rate from the current tick on (the thermal ladder's even step-down to 20 or 15 fps, and
 *    back). The grid is re-based at the current tick's time, so spacing is constant on both sides of the change.
 *  - [nudge] slides the grid later or earlier by at most [MAX_NUDGE_NS] per call, for [FramePhaseLock]: the next tick
 *    comes a fraction of a millisecond early or late, so encoder timestamps stay monotonic and evenly spaced.
 *
 * [tick] counts output frames (one per tick, plus the skipped ones), whatever the period.
 */
class FrameClock(fps: Int, private val time: TickTime = TickTime.SYSTEM) {
    /** The rate the clock was built for. */
    val nominalPeriodNs = 1_000_000_000L / fps
    var periodNs = nominalPeriodNs
        private set
    /** Time of tick [baseTick]; tick n is at baseNs + (n - baseTick) * periodNs. */
    private var baseNs = time.nanoTime()
    private var baseTick = 0L
    var tick = 0L
        private set

    fun tickTimeNs(): Long = baseNs + (tick - baseTick) * periodNs

    /** Sleeps until the current tick; returns how many ticks were skipped because we were late. */
    fun awaitTick(): Long {
        val target = tickTimeNs()
        while (true) {
            val now = time.nanoTime()
            if (now >= target) break
            time.parkNanos(target - now)
        }
        val late = time.nanoTime() - target
        if (late > periodNs) {
            val skipped = late / periodNs
            tick += skipped
            return skipped
        }
        return 0
    }

    fun advance() { tick++ }

    /**
     * Runs at [newPeriodNs] from the current tick on: the current tick keeps its time and the next one comes
     * [newPeriodNs] after it. Call between [awaitTick] and [advance] (on the render thread).
     */
    fun setPeriod(newPeriodNs: Long) {
        require(newPeriodNs > 0)
        if (newPeriodNs == periodNs) return
        baseNs = tickTimeNs()
        baseTick = tick
        periodNs = newPeriodNs
    }

    /**
     * Moves the grid by [deltaNs] (positive: later), clamped to +-[MAX_NUDGE_NS]. Returns the amount applied. Call
     * after the tick's work, before [advance]: only ticks still to come move.
     */
    fun nudge(deltaNs: Long): Long {
        val d = deltaNs.coerceIn(-MAX_NUDGE_NS, MAX_NUDGE_NS)
        baseNs += d
        return d
    }

    companion object {
        /** A quarter of a millisecond: well inside a 60 fps period, so the next tick can't land before this one. */
        const val MAX_NUDGE_NS = 250_000L
    }
}
