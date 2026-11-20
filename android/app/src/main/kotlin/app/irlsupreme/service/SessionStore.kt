package app.irlsupreme.service

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import app.irlsupreme.engine.SessionSummary

/** Why a stream ended without the user ending it, in plain words: what happened, and the fix. */
data class UnexpectedEnd(
    val atMs: Long,
    val title: String,
    val body: String,
    /** Show a button to the app's system settings (the "Allow background activity" fix, or a revoked permission). */
    val offerBackgroundFix: Boolean,
    /** That button's label. */
    val fixLabel: String = "Fix in settings",
)

/**
 * Remembers that a stream is supposed to be running ("live intent"), and the last session's recap.
 * If the process dies mid-stream nothing else runs, so the intent is still set on next launch; we then read
 * ApplicationExitInfo to explain what happened (OxygenOS's background killer ends streams ~5 min after screen-off).
 */
class SessionStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    /** Returns the start time it stored, which also names the stream's [SessionLog]. */
    fun markLive(): Long {
        val now = System.currentTimeMillis()
        prefs.edit().putBoolean(LIVE, true).putLong(STARTED, now).apply()
        return now
    }

    fun markEnded(summary: SessionSummary?) {
        val e = prefs.edit().putBoolean(LIVE, false)
        if (summary != null) {
            e.putLong("s_start", summary.startedAtMs).putLong("s_end", summary.endedAtMs)
                .putLong("s_live", summary.liveSinceMs).putInt("s_drops", summary.recoveredDrops)
                .putInt("s_enc", summary.encoderRecoveries).putLong("s_bytes", summary.bytesSent)
                .putInt("s_heat", summary.peakThermalStatus).putInt("s_low", summary.lowestBitrate)
                .putString("s_err", summary.lastError).putBoolean("s_seen", false)
        }
        e.apply()
    }

    /** The last session's recap, until the user dismisses it. */
    fun unseenSummary(): SessionSummary? {
        if (!prefs.contains("s_end") || prefs.getBoolean("s_seen", true)) return null
        return SessionSummary(
            startedAtMs = prefs.getLong("s_start", 0), endedAtMs = prefs.getLong("s_end", 0),
            liveSinceMs = prefs.getLong("s_live", 0), recoveredDrops = prefs.getInt("s_drops", 0),
            encoderRecoveries = prefs.getInt("s_enc", 0), bytesSent = prefs.getLong("s_bytes", 0),
            peakThermalStatus = prefs.getInt("s_heat", 0), lowestBitrate = prefs.getInt("s_low", 0),
            lastError = prefs.getString("s_err", null),
        )
    }

    fun dismissSummary() = prefs.edit().putBoolean("s_seen", true).apply()

    /**
     * If a stream was supposed to be running but the app isn't streaming, explain why (once). Call when the
     * engine is known not to be running.
     */
    fun consumeUnexpectedEnd(): UnexpectedEnd? {
        if (!prefs.getBoolean(LIVE, false)) return null
        val started = prefs.getLong(STARTED, 0)
        // The stored recap belongs to an earlier stream; don't show it next to this one's explanation.
        prefs.edit().putBoolean(LIVE, false).putBoolean("s_seen", true).apply()
        val am = context.getSystemService(ActivityManager::class.java)
        val exit = am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
            .firstOrNull { it.timestamp >= started }
        val at = exit?.timestamp ?: started
        // The stream's own log stops wherever the process died; Android's reason is only known now, so it goes there too.
        if (exit != null) SessionLog.appendLater(
            context.filesDir, started,
            "after the stream: Android ended the app at ${SessionLogText.timestamp(exit.timestamp, java.time.ZoneId.systemDefault())}, " +
                "reason ${exit.reason}, status ${exit.status}, importance ${exit.importance}" +
                (exit.description?.let { ", \"$it\"" } ?: ""),
        )
        return when (exit?.reason) {
            // REASON_SIGNALED: OEM killers (OxygenOS's included) end the process with a signal, not a battery reason.
            ApplicationExitInfo.REASON_OTHER, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_FREEZER, ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_SIGNALED,
            -> UnexpectedEnd(
                at, "Your phone stopped the stream",
                "Android's battery manager closed the app while you were live" +
                    (if (exit.reason == ApplicationExitInfo.REASON_LOW_MEMORY) " (low memory)." else ".") +
                    " Turn on “Allow background activity” so it can't happen again.",
                offerBackgroundFix = true,
            )
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR,
            -> UnexpectedEnd(
                at, "The app crashed and your stream ended",
                "Sorry about that. If it keeps happening, please report it.",
                offerBackgroundFix = false,
            )
            ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED,
            -> UnexpectedEnd(
                at, "The app was closed while you were live",
                "Swiping the app away (or force-stopping it) ends the stream on this phone. " +
                    "Use the hold-to-end button to finish, or just lock the screen — the stream keeps going.",
                offerBackgroundFix = false,
            )
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> UnexpectedEnd(
                at, "A permission Fullbars needs was turned off",
                "Android closes the app when camera, microphone or another permission it uses is taken away. " +
                    "Turn it back on in the app's settings, then go live again.",
                offerBackgroundFix = true,
                fixLabel = "Open app settings",
            )
            null -> null // no record: most likely still finishing up cleanly
            // The numeric reason means nothing to a streamer; it stays in ApplicationExitInfo for bug reports.
            else -> UnexpectedEnd(
                at, "Your stream ended unexpectedly",
                "Android closed the app. If it keeps happening, check the battery settings.",
                offerBackgroundFix = true,
            )
        }
    }

    private companion object {
        const val LIVE = "live_intent"
        const val STARTED = "started_at"
    }
}
