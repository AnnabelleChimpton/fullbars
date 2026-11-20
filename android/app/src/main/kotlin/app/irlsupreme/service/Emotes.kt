package app.irlsupreme.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Twitch emote images for chat: fetched once from Twitch's emote CDN, kept on disk (the app's cache folder, which
 * Android may clear) and in memory. Static images only: an animated emote shows its first frame. A chat line asks for
 * its emotes as it arrives; [version] ticks whenever one more image is ready, so the chat views and the stream's chat
 * overlay redraw with it. Until an image is ready, the emote shows as its name.
 */
object EmoteCache {
    private const val TAG = "Emotes"
    /** Twitch's static 2x image (56 px tall): sharp at chat size on any phone, small to fetch. */
    fun url(id: String) = "https://static-cdn.jtvnw.net/emoticons/v2/$id/static/dark/2.0"

    private val mem = LruCache<String, Bitmap>(300)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    /** Ids Twitch had no image for, so a typo'd or removed emote isn't fetched again on every message. */
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy { OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build() }
    @Volatile private var dir: File? = null

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun init(ctx: Context) {
        if (dir == null) dir = File(ctx.applicationContext.cacheDir, "emotes").apply { mkdirs() }
    }

    /** The image for [id] if it's ready; null (show the name) otherwise. */
    fun get(id: String): Bitmap? = mem.get(id)

    /** Makes sure [id]'s image is on its way. Cheap to call for every emote of every message. */
    fun request(id: String) {
        if (!VALID.matches(id) || mem.get(id) != null || id in missing || !inFlight.add(id)) return
        scope.launch {
            try {
                load(id)?.let { mem.put(id, it); _version.value++ }
            } catch (t: Throwable) {
                Log.w(TAG, "emote $id: ${t.message}")
            } finally {
                inFlight.remove(id)
            }
        }
    }

    private fun load(id: String): Bitmap? {
        val f = dir?.let { File(it, "$id.png") }
        if (f != null && f.exists()) BitmapFactory.decodeFile(f.path)?.let { return it }
        http.newCall(Request.Builder().url(url(id)).build()).execute().use { r ->
            if (r.code == 404) { missing += id; return null }
            if (!r.isSuccessful) return null
            val bytes = r.body.bytes()
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            if (f != null) runCatching { val tmp = File(f.path + ".tmp"); tmp.writeBytes(bytes); tmp.renameTo(f) }
            return bmp
        }
    }

    private val VALID = Regex("[A-Za-z0-9_]{1,64}")
}
