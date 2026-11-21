package app.irlsupreme.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import app.irlsupreme.service.StreamService

/**
 * Debug builds only (src/debug). Works with the screen off/locked, unlike activity intents:
 *   adb shell am broadcast -a app.irlsupreme.debug.CONTROL -p app.irlsupreme --es cmd stop
 *   adb shell am broadcast -a app.irlsupreme.debug.CONTROL -p app.irlsupreme --es cmd scene --es value BRB
 *   adb shell am broadcast -a app.irlsupreme.debug.CONTROL -p app.irlsupreme --es cmd mic --es value off
 *   adb shell am broadcast -a app.irlsupreme.debug.CONTROL -p app.irlsupreme --es cmd lipsync --es value on
 * (lipsync: automatic lip sync for the next stream, unproven on phones, so only reachable here.)
 * The manifest requires the DUMP permission from the sender: adb's shell has it, other apps can't, so nothing else on
 * the phone can end the stream, unmute or leave Privacy. Starting a stream goes through [DebugActivity]
 * (camera/mic foreground services must start from the foreground).
 */
class DebugControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val value = intent.getStringExtra("value")
        Log.i("EngineDebug", "debug control: $cmd ${value ?: ""}")
        if (cmd == "lipsync") {
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit { putBoolean(app.irlsupreme.service.StreamSettings.KEY_AUTO_LIP_SYNC, value == "on") }
            return
        }
        val svc = Intent(context, StreamService::class.java)
        when (cmd) {
            "stop" -> svc.setAction(StreamService.ACTION_STOP)
            "scene" -> svc.setAction(StreamService.ACTION_SCENE).putExtra(StreamService.EXTRA_SCENE, value)
            "mic" -> svc.setAction(StreamService.ACTION_MIC).putExtra(StreamService.EXTRA_MUTED, value == "off")
            else -> return
        }
        context.startService(svc)
    }
}
