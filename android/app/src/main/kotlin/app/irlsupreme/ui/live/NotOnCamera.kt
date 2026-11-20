package app.irlsupreme.ui.live

import android.view.RoundedCorner
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import app.irlsupreme.ui.theme.OverVideo

/** The frame's stroke: thick enough to read in peripheral vision while walking, and not a hue. */
private val FRAME = 4.dp

/**
 * The "not on camera" frame: while a chosen card is up, a 4dp Bone border runs round the preview and
 * follows the display's own corner radius, so the screen itself says "viewers don't see your camera" at a glance.
 */
@Composable
internal fun NotOnCameraFrame(modifier: Modifier = Modifier) {
    val view = LocalView.current
    // Per corner, in px: a phone can round its top and bottom corners differently.
    val radii = remember(view.display) {
        val d = view.display
        listOf(
            RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
            RoundedCorner.POSITION_BOTTOM_RIGHT, RoundedCorner.POSITION_BOTTOM_LEFT,
        ).map { (d?.getRoundedCorner(it)?.radius ?: 0).toFloat() }
    }
    Canvas(modifier.fillMaxSize()) {
        val w = FRAME.toPx()
        val i = w / 2
        fun r(px: Float) = CornerRadius((px - i).coerceAtLeast(0f))
        val path = Path().apply {
            addRoundRect(
                RoundRect(
                    left = i, top = i, right = size.width - i, bottom = size.height - i,
                    topLeftCornerRadius = r(radii[0]), topRightCornerRadius = r(radii[1]),
                    bottomRightCornerRadius = r(radii[2]), bottomLeftCornerRadius = r(radii[3]),
                ),
            )
        }
        drawPath(path, OverVideo.text, style = Stroke(w))
    }
}
