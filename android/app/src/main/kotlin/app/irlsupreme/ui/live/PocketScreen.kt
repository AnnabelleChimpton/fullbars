package app.irlsupreme.ui.live

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import app.irlsupreme.engine.Health
import app.irlsupreme.ui.theme.FbLiveType
import app.irlsupreme.ui.theme.OverVideo
import kotlinx.coroutines.delay

/** The screen's brightness while the Pocket screen is on: as dark as Android goes without turning it off. */
private const val POCKET_BRIGHTNESS = 0.01f

/**
 * The Pocket screen: an opaque black layer over the whole live screen, the window dimmed to [POCKET_BRIGHTNESS] (put
 * back when it goes, however it goes), and one small dim line with the pill's headline that moves every minute so it
 * never marks the screen. Trouble the streamer must notice (Reconnecting, Camera lost, the stream ending) shows at full
 * brightness. The screen stays on (the live screen keeps it on), haptics and TalkBack carry on as usual, and the
 * engine stops drawing the preview meanwhile (set by the live screen). A double tap leaves it: a single touch in a
 * pocket shouldn't light the screen up again.
 */
@Composable
internal fun PocketScreenOverlay(pill: PillModel, health: Health, ending: Boolean, onLeave: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val before = window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = POCKET_BRIGHTNESS } }
        onDispose { window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = before } } }
    }
    var minute by remember { mutableLongStateOf(System.currentTimeMillis() / 60_000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            minute = System.currentTimeMillis() / 60_000
        }
    }
    val alarm = pocketAlarm(health, ending)
    val headline = if (health == Health.OFF && !ending) "Ended" else pill.headline
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onLeave() }) }
            .semantics {
                contentDescription = "Pocket screen. ${pill.spoken}"
                liveRegion = LiveRegionMode.Polite
                onClick("Leave the pocket screen") { onLeave(); true }
            },
    ) {
        val (fx, fy) = pocketSpot(minute)
        val lineW = 220.dp
        Column(
            Modifier
                .offset(x = (maxWidth - lineW).coerceAtLeast(0.dp) * fx, y = (maxHeight - 56.dp).coerceAtLeast(0.dp) * fy)
                .widthIn(max = lineW)
                .clearAndSetSemantics {},
        ) {
            Text(headline, style = FbLiveType.L2, color = if (alarm) OverVideo.text else OverVideo.text.copy(alpha = 0.35f), maxLines = 1)
            Text("Double tap to leave", style = FbLiveType.L3, color = OverVideo.text2.copy(alpha = if (alarm) 0.8f else 0.3f), maxLines = 1)
        }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
