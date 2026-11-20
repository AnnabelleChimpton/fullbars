package app.irlsupreme.ui.live

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.core.graphics.createBitmap
import app.irlsupreme.engine.Scene
import app.irlsupreme.engine.video.SlateAudio
import app.irlsupreme.engine.video.Slates

/*
 * Thumbnails for the Scenes sheet: the real card each row would put on stream, and the last camera frame for Live, so
 * the choice shows what viewers will see rather than an icon standing for it.
 */

/**
 * The card [scene] puts on stream, drawn by the engine at [width]×[height] (null for Live), with the audio footer
 * viewers would see. The channel is left off: at thumbnail size it's unreadable.
 */
internal fun sceneCardBitmap(scene: Scene, muted: Boolean, width: Int, height: Int): Bitmap? {
    val card = Slates.Card.of(scene) ?: return null
    val audio = if (muted || scene == Scene.PRIVACY) SlateAudio.MUTED else SlateAudio.ON
    return Slates.bitmap(width, height, card, channel = "", audio = audio)
}

/**
 * Copies what the preview [view] shows into a [width]×[height] bitmap (scaled by the copy), for the Live row's
 * thumbnail. Calls [onResult] on the main thread with null when the surface isn't ready.
 */
internal fun captureSurface(view: SurfaceView, width: Int, height: Int, onResult: (Bitmap?) -> Unit) {
    val holder = view.holder
    if (!holder.surface.isValid || width <= 0 || height <= 0) {
        onResult(null)
        return
    }
    val bmp = createBitmap(width, height)
    runCatching {
        PixelCopy.request(view, bmp, { result -> onResult(if (result == PixelCopy.SUCCESS) bmp else null) }, Handler(Looper.getMainLooper()))
    }.onFailure { onResult(null) }
}
