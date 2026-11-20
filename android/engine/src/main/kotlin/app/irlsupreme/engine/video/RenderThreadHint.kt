package app.irlsupreme.engine.video

import android.content.Context
import android.os.PerformanceHintManager
import android.os.Process
import android.util.Log

/**
 * An Android performance hint session (ADPF) for the render thread: tells the CPU governor how long each tick's work
 * took against a target a little under the frame period, so it can raise the clock before a tick misses its deadline
 * (and lower it when there's room, which saves heat). Only the tick's own work is reported, never the wait for the
 * next tick.
 *
 * Optional everywhere: a phone without the service, or one that refuses a session, just gets no hint, and every call
 * is guarded. Create, use and close it on the render thread (the session is for the calling thread's id).
 */
class RenderThreadHint private constructor(private val session: PerformanceHintManager.Session) {
    private var failed = false

    /** Render thread: one tick's work, in ns. */
    fun report(workNs: Long) {
        if (failed || workNs <= 0) return
        try {
            session.reportActualWorkDuration(workNs)
        } catch (t: Throwable) {
            failed = true // don't log every tick
            Log.w(TAG, "performance hint stopped working; carrying on without it", t)
        }
    }

    /** The frame period changed (the thermal ladder's lower rate, and back). */
    fun setTarget(periodNs: Long) {
        if (failed) return
        runCatching { session.updateTargetWorkDuration(targetFor(periodNs)) }
    }

    fun close() {
        runCatching { session.close() }
    }

    companion object {
        private const val TAG = "Engine"
        /** Headroom left under the period: the encoder copy and the preview blit come after the reported work. */
        private const val MARGIN_NS = 2_000_000L

        fun targetFor(periodNs: Long): Long = (periodNs - MARGIN_NS).coerceAtLeast(periodNs / 2)

        /** A session for the calling (render) thread, or null when the phone offers none. */
        fun create(ctx: Context, periodNs: Long): RenderThreadHint? = try {
            val manager = ctx.getSystemService(PerformanceHintManager::class.java)
            val session = manager?.createHintSession(intArrayOf(Process.myTid()), targetFor(periodNs))
            if (session == null) null else RenderThreadHint(session)
        } catch (t: Throwable) {
            Log.w(TAG, "no performance hint session on this phone", t)
            null
        }
    }
}
