package app.irlsupreme.engine.output

/**
 * Notices the moment the BRB server can hear this phone again after a gap, so a keyframe can be asked for right then
 * (pure, unit-tested; polled every 100-200 ms).
 *
 * Why: during a dead zone of 1.5 s or more the server goes to its slate, and it can only splice the live picture back
 * in at a keyframe. Without asking, viewers wait for the encoder's next scheduled one, up to a whole GOP (2 s) more of
 * "Signal lost". SRT itself never reconnects for gaps under ~10 s, and links stay CONNECTED for up to 4 s, so neither
 * the SRT state nor link up/down events see these gaps.
 *
 * The input is how long it has been since any link last heard from the server (`silentMs`). Past [armAfterMs] (just
 * under the server's 1.5 s stall) the detector arms; when `silentMs` drops back under [fireBelowMs] it fires and
 * disarms. A link coming up while none were connected ([onLinkUpAfterOutage]) also fires. Fires at most once per
 * [minIntervalMs]; one that comes too soon is held and delivered when the interval is up, so the last return of a
 * flapping link still gets its keyframe.
 */
internal class ResumeDetector(
    private val armAfterMs: Long = 1_000,
    private val fireBelowMs: Long = 200,
    private val minIntervalMs: Long = 1_000,
) {
    private var armed = false
    private var pending = false
    private var lastFireMs = Long.MIN_VALUE

    /** A link registered while no other link was connected: the server hears us again. */
    @Synchronized
    fun onLinkUpAfterOutage() {
        pending = true
    }

    /** [silentMs]: time since any link last heard from the server, or null when that isn't known (no links). */
    @Synchronized
    fun update(nowMs: Long, silentMs: Long?): Boolean {
        if (silentMs != null) {
            if (silentMs > armAfterMs) armed = true
            else if (armed && silentMs < fireBelowMs) {
                armed = false
                pending = true
            }
        }
        if (!pending) return false
        if (lastFireMs != Long.MIN_VALUE && nowMs - lastFireMs < minIntervalMs) return false
        pending = false
        armed = false // one return, one keyframe, even when a link-up and the silence ending both report it
        lastFireMs = nowMs
        return true
    }
}
