package app.irlsupreme.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import app.irlsupreme.twitch.TwitchAlert
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** The alert kinds a streamer can make their own. */
enum class AlertKind(val title: String, val defaultLabel: String) {
    FOLLOW("Follow", "FOLLOW"),
    SUB("Sub", "SUB"),
    GIFT("Gift subs", "GIFT SUBS"),
    BITS("Bits", "CHEER"),
    RAID("Raid", "RAID"),
    TIP("Tip", "TIP"),
    ;

    companion object {
        fun of(a: TwitchAlert): AlertKind = when (a) {
            is TwitchAlert.Follow -> FOLLOW
            is TwitchAlert.Sub -> SUB
            is TwitchAlert.GiftSubs -> GIFT
            is TwitchAlert.Cheer -> BITS
            is TwitchAlert.Raid -> RAID
            is TwitchAlert.Tip -> TIP
        }
    }
}

/** What the streamer set for one kind; empty text keeps the default. */
data class AlertStyleText(val label: String = "", val message: String = "", val hasPicture: Boolean = false) {
    val isCustom: Boolean get() = label.isNotBlank() || message.isNotBlank() || hasPicture
}

/** One kind's look as the card drawing uses it: null means the default. */
data class AlertLook(val label: String? = null, val message: String? = null, val picture: Bitmap? = null)

/**
 * The streamer's own words and picture for each alert kind (Settings > Overlays > Customize alerts). Words live with
 * the other settings; pictures are small PNGs in the app's files. [look] is what [AlertText.card] reads, so the stream,
 * the previews and the Test an alert row all show the same thing.
 */
object AlertStyles {
    const val MAX_LABEL = 20
    const val MAX_MESSAGE = 60
    private const val MAX_PICTURE = 256

    private var flow: MutableStateFlow<Map<AlertKind, AlertStyleText>>? = null
    /** Loaded pictures, swapped whole so the overlay thread never sees half an update. */
    @Volatile private var pictures: Map<AlertKind, Bitmap> = emptyMap()
    private val _version = MutableStateFlow(0)
    /** Bumps on every change, so previews redraw. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("overlays", Context.MODE_PRIVATE)
    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "alerts").apply { mkdirs() }
    private fun pictureFile(ctx: Context, k: AlertKind) = File(dir(ctx), "${k.name.lowercase()}.png")
    private fun key(k: AlertKind, field: String) = "alert_${k.name.lowercase()}_$field"

    @Synchronized
    fun state(ctx: Context): StateFlow<Map<AlertKind, AlertStyleText>> =
        (flow ?: MutableStateFlow(load(ctx)).also { flow = it }).asStateFlow()

    fun text(ctx: Context, k: AlertKind): AlertStyleText = state(ctx).value[k] ?: AlertStyleText()

    /** The look [AlertText.card] applies for [k]; defaults until [apply] has run once. */
    fun look(k: AlertKind): AlertLook {
        val t = flow?.value?.get(k)
        return AlertLook(t?.label?.trim()?.ifEmpty { null }, t?.message?.trim()?.ifEmpty { null }, pictures[k])
    }

    private fun load(ctx: Context): Map<AlertKind, AlertStyleText> = AlertKind.entries.associateWith { k ->
        val p = prefs(ctx)
        AlertStyleText(p.getString(key(k, "label"), "").orEmpty(), p.getString(key(k, "message"), "").orEmpty(), pictureFile(ctx, k).exists())
    }

    fun setText(ctx: Context, k: AlertKind, label: String, message: String) {
        val l = label.take(MAX_LABEL)
        val m = message.take(MAX_MESSAGE)
        prefs(ctx).edit().putString(key(k, "label"), l).putString(key(k, "message"), m).apply()
        update(ctx, k) { it.copy(label = l, message = m) }
    }

    /** Copies a picked picture in, at most 256 px on its long side. Blocking: call off the main thread. */
    fun setPicture(ctx: Context, k: AlertKind, uri: Uri): Boolean = runCatching {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
            val long = maxOf(info.size.width, info.size.height)
            if (long > MAX_PICTURE) d.setTargetSampleSize((long + MAX_PICTURE - 1) / MAX_PICTURE)
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val tmp = File(dir(ctx), "${k.name.lowercase()}.tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        tmp.renameTo(pictureFile(ctx, k))
    }.getOrDefault(false).also { ok -> if (ok) { update(ctx, k) { it.copy(hasPicture = true) }; apply(ctx) } }

    fun clearPicture(ctx: Context, k: AlertKind) {
        pictureFile(ctx, k).delete()
        update(ctx, k) { it.copy(hasPicture = false) }
        apply(ctx)
    }

    fun reset(ctx: Context, k: AlertKind) {
        prefs(ctx).edit().remove(key(k, "label")).remove(key(k, "message")).apply()
        pictureFile(ctx, k).delete()
        update(ctx, k) { AlertStyleText() }
        apply(ctx)
    }

    /** Loads the pictures (small PNGs; call off the main thread). Idempotent. */
    fun apply(ctx: Context) {
        state(ctx)
        pictures = AlertKind.entries.mapNotNull { k ->
            val f = pictureFile(ctx, k)
            if (f.exists()) runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull()?.let { k to it } else null
        }.toMap()
        _version.value++
    }

    private fun update(ctx: Context, k: AlertKind, change: (AlertStyleText) -> AlertStyleText) {
        state(ctx)
        val f = flow ?: return
        f.value = f.value + (k to change(f.value[k] ?: AlertStyleText()))
        _version.value++
    }
}
