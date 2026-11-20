package app.irlsupreme.service

import android.content.Context
import app.irlsupreme.engine.Facing

/**
 * Which phone camera a stream starts on (Settings > Camera, "Start on"). The live screen applies it when a stream
 * starts on the phone camera; flipping mid-stream doesn't change it.
 */
object CameraPrefs {
    private const val KEY = "start_facing"
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun startFacing(ctx: Context): Facing =
        Facing.entries.firstOrNull { it.name == prefs(ctx).getString(KEY, null) } ?: Facing.BACK

    fun setStartFacing(ctx: Context, f: Facing) = prefs(ctx).edit().putString(KEY, f.name).apply()
}
