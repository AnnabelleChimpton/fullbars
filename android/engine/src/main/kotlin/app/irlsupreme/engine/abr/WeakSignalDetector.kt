package app.irlsupreme.engine.abr

/**
 * Decides when viewers should see the Weak signal screen instead of a stream that can't get through. A still
 * card needs a fraction of the bits of a moving picture, so it arrives while the camera wouldn't; the mic keeps going.
 *
 *  - **On** once Auto quality is already at its floor and the send queue has stayed backed up for [enterMs].
 *  - **Off** once the queue has stayed clear for the exit hold. The card itself clears the queue, so a clear queue
 *    doesn't prove the connection recovered: if it goes bad again within [flapWindowMs] of coming back, the next exit
 *    hold doubles (from [exitMs] up to [maxExitMs]) so viewers don't see it flicker.
 *
 * Pure and clock-driven, for tests. Not thread-safe: one caller (the engine's bitrate tick).
 */
class WeakSignalDetector(
    private val enterMs: Long = 4_000,
    private val exitMs: Long = 5_000,
    private val maxExitMs: Long = 40_000,
    private val flapWindowMs: Long = 60_000,
) {
    var active = false
        private set
    /** How many times it switched on (for the end-of-stream recap). */
    var episodes = 0
        private set

    private var badSinceMs = -1L
    private var clearSinceMs = -1L
    private var exitHoldMs = exitMs
    private var lastExitMs = Long.MIN_VALUE

    /**
     * [atFloor]: the bitrate can't go any lower. [backedUp]: the send queue is past the point where Auto quality cuts.
     * [clear]: the queue is low enough that Auto quality would raise quality. Returns [active].
     */
    fun update(nowMs: Long, atFloor: Boolean, backedUp: Boolean, clear: Boolean): Boolean {
        if (!active) {
            if (atFloor && backedUp) {
                if (badSinceMs < 0) badSinceMs = nowMs
                if (nowMs - badSinceMs >= enterMs) {
                    active = true
                    episodes++
                    clearSinceMs = -1
                    exitHoldMs = if (lastExitMs != Long.MIN_VALUE && nowMs - lastExitMs < flapWindowMs) {
                        minOf(exitHoldMs * 2, maxExitMs)
                    } else exitMs
                }
            } else badSinceMs = -1
        } else {
            if (clear) {
                if (clearSinceMs < 0) clearSinceMs = nowMs
                if (nowMs - clearSinceMs >= exitHoldMs) {
                    active = false
                    badSinceMs = -1
                    lastExitMs = nowMs
                }
            } else clearSinceMs = -1
        }
        return active
    }

    /** Not live (connecting, all links down): nothing to judge; start over when it's live again. */
    fun reset() {
        active = false
        badSinceMs = -1
        clearSinceMs = -1
    }

    /** The current exit hold, for logs. */
    val exitHold: Long get() = exitHoldMs
}
