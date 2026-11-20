package app.irlsupreme.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Where a layer sits on the stream. */
enum class Corner(val title: String) {
    TOP_LEFT("Top left"), TOP_RIGHT("Top right"), BOTTOM_LEFT("Bottom left"), BOTTOM_RIGHT("Bottom right");

    val bottom: Boolean get() = this == BOTTOM_LEFT || this == BOTTOM_RIGHT
    val right: Boolean get() = this == TOP_RIGHT || this == BOTTOM_RIGHT
}

/** The image's long side, as a share of the stream's short side. */
enum class ImageSize(val title: String, val fraction: Float) { SMALL("Small", 0.14f), MEDIUM("Medium", 0.22f), LARGE("Large", 0.32f) }

/**
 * What's drawn over the stream: Twitch chat, a line of text, an image (a logo). Drawn on the phone
 * into the stream itself, so viewers see them without OBS. Shown over Live; over the BRB, Starting soon and Ending
 * cards only if [onCards]; never over Privacy or the Weak signal screen.
 */
data class OverlaySettings(
    val chat: Boolean = false,
    val chatCorner: Corner = Corner.BOTTOM_LEFT,
    val textOn: Boolean = false,
    val text: String = "",
    val textCorner: Corner = Corner.TOP_LEFT,
    val imageOn: Boolean = false,
    val imageCorner: Corner = Corner.TOP_RIGHT,
    val imageSize: ImageSize = ImageSize.SMALL,
    val onCards: Boolean = false,
    /** A web page drawn into the stream (the URL itself is a secret: [OverlayStore.browserUrl]). */
    val browserOn: Boolean = false,
    /** Twitch alerts (follows, subs, gifts, raids, cheers) drawn on the stream, with a chime mixed in if [alertSound]. */
    val alertsOn: Boolean = true,
    val alertSound: Boolean = true,
)

object OverlayStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("overlays", Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0)
    /** Bumped on every change, so a live stream redraws its overlay. */
    val changes: StateFlow<Int> get() = _changes

    fun load(ctx: Context): OverlaySettings {
        val p = prefs(ctx)
        fun <E : Enum<E>> e(key: String, def: E, all: Array<E>) = all.firstOrNull { it.name == p.getString(key, null) } ?: def
        return OverlaySettings(
            chat = p.getBoolean("chat", false),
            chatCorner = e("chat_corner", Corner.BOTTOM_LEFT, Corner.entries.toTypedArray()),
            textOn = p.getBoolean("text_on", false),
            text = p.getString("text", "") ?: "",
            textCorner = e("text_corner", Corner.TOP_LEFT, Corner.entries.toTypedArray()),
            imageOn = p.getBoolean("image_on", false) && imageFile(ctx).exists(),
            imageCorner = e("image_corner", Corner.TOP_RIGHT, Corner.entries.toTypedArray()),
            imageSize = e("image_size", ImageSize.SMALL, ImageSize.entries.toTypedArray()),
            onCards = p.getBoolean("on_cards", false),
            browserOn = p.getBoolean("browser_on", false),
            alertsOn = p.getBoolean("alerts_on", true),
            alertSound = p.getBoolean("alert_sound", true),
        )
    }

    fun save(ctx: Context, s: OverlaySettings) {
        prefs(ctx).edit()
            .putBoolean("chat", s.chat).putString("chat_corner", s.chatCorner.name)
            .putBoolean("text_on", s.textOn).putString("text", s.text).putString("text_corner", s.textCorner.name)
            .putBoolean("image_on", s.imageOn).putString("image_corner", s.imageCorner.name).putString("image_size", s.imageSize.name)
            .putBoolean("on_cards", s.onCards)
            .putBoolean("browser_on", s.browserOn)
            .putBoolean("alerts_on", s.alertsOn).putBoolean("alert_sound", s.alertSound)
            .apply()
        _changes.value++
    }

    /**
     * The browser overlay's URL (StreamElements / Streamlabs: it carries the account's token, so it lives in the
     * encrypted [SecretStore]). Only https.
     */
    fun browserUrl(ctx: Context): String? = SecretStore(ctx).get("overlay_url")?.takeIf { validBrowserUrl(it) }
    fun setBrowserUrl(ctx: Context, url: String) {
        SecretStore(ctx).put("overlay_url", url.trim().ifEmpty { null })
        _changes.value++
    }
    fun validBrowserUrl(url: String) = url.trim().let { it.startsWith("https://") && it.length > 12 && ' ' !in it }

    /** The chosen image, kept as a small PNG in the app's own files (no lasting access to the photo library). */
    fun imageFile(ctx: Context) = File(ctx.filesDir, "overlay-image.png")

    /** Copies a picked image in, at most 512 px on its long side. Blocking: call off the main thread. */
    fun setImage(ctx: Context, uri: Uri): Boolean = runCatching {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { d, info, _ ->
            val long = maxOf(info.size.width, info.size.height)
            if (long > MAX_IMAGE) d.setTargetSampleSize((long + MAX_IMAGE - 1) / MAX_IMAGE)
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val tmp = File(ctx.filesDir, "overlay-image.tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        tmp.renameTo(imageFile(ctx))
    }.getOrDefault(false).also { if (it) _changes.value++ }

    fun clearImage(ctx: Context) {
        imageFile(ctx).delete()
        _changes.value++
    }

    fun loadImage(ctx: Context): Bitmap? = imageFile(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    private const val MAX_IMAGE = 512
}

/** The overlay's layout rules, kept free of Android graphics so they can be unit-tested. */
object OverlayLayout {
    data class Box(val x: Float, val y: Float, val w: Float, val h: Float)

    /** The stream's drawing unit, u: 1% of the short side, so graphics read the same in portrait and landscape. */
    fun unit(width: Int, height: Int) = minOf(width, height) / 100f

    /** Distance from the frame's edges: 4% of the short side (inside common "title safe" areas). */
    fun margin(width: Int, height: Int) = minOf(width, height) * 0.04f

    /**
     * The corners a layer of [s] actually sits in: chat when it's on, the text when there's some, the image when one
     * is chosen. The alert reads this to keep out of their way.
     */
    fun occupied(s: OverlaySettings): Set<Corner> = buildSet {
        if (s.chat) add(s.chatCorner)
        if (s.textOn && s.text.isNotBlank()) add(s.textCorner)
        if (s.imageOn) add(s.imageCorner)
    }

    /**
     * Where a [w] x [h] alert goes: left at the title-safe margin with its top edge at 58% of the height on
     * camera, or 30% over a full-frame card ([onCard]) so it clears the card's headline. It mirrors to the right edge
     * when a layer [occupied] the lower-left and the lower-right is free. It never rises into the top 20%, where the
     * streamer's own pill covers the preview, and it's never centred; a tall alert moves up to stay title-safe.
     */
    fun alertBox(width: Int, height: Int, onCard: Boolean, occupied: Set<Corner>, w: Float, h: Float): Box {
        val m = margin(width, height)
        val right = Corner.BOTTOM_LEFT in occupied && Corner.BOTTOM_RIGHT !in occupied
        val x = if (right) width - m - w else m
        val wanted = height * (if (onCard) ALERT_TOP_ON_CARD else ALERT_TOP_ON_CAMERA)
        val y = minOf(wanted, height - m - h).coerceAtLeast(height * ALERT_MIN_TOP)
        return Box(x, y, w, h)
    }

    /** The largest an alert is drawn, as shares of the frame: it never covers more than a corner of the picture. */
    const val ALERT_MAX_W = 0.62f
    const val ALERT_MAX_H = 0.26f
    const val ALERT_TOP_ON_CAMERA = 0.58f
    const val ALERT_TOP_ON_CARD = 0.30f
    const val ALERT_MIN_TOP = 0.20f

    /**
     * Where a [w] x [h] layer goes in [corner], after [used] px already taken there by layers stacked before it
     * (stacked downward from a top corner, upward from a bottom one), with [gap] between layers.
     */
    fun place(corner: Corner, w: Float, h: Float, width: Int, height: Int, used: Float = 0f, gap: Float = 0f): Box {
        val m = margin(width, height)
        val x = if (corner.right) width - m - w else m
        val offset = if (used > 0f) used + gap else 0f
        val y = if (corner.bottom) height - m - offset - h else m + offset
        return Box(x, y, w, h)
    }

    /** The chat lines to show: newest last, no mod commands, nothing older than [maxAgeMs], at most [max]. */
    fun visibleChat(lines: List<ChatLine>, nowMs: Long, maxAgeMs: Long = CHAT_MAX_AGE_MS, max: Int = CHAT_MAX_LINES): List<ChatLine> =
        lines.filter { !it.isCommand && nowMs - it.atMs <= maxAgeMs }.takeLast(max)

    const val CHAT_MAX_AGE_MS = 60_000L
    const val CHAT_MAX_LINES = 6
}
