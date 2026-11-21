package app.irlsupreme.service

import android.content.Context
import android.net.Uri
import app.irlsupreme.engine.audio.ImportFailure
import java.io.File

/** Picking a sound file (an alert sound, a card's music): copying it in, and what to say when it doesn't work. */
object AudioPick {
    /** The biggest file taken, alert sound or music. */
    const val MAX_BYTES = 60L * 1024 * 1024

    /** What the system picker is asked to show. */
    val MIME_TYPES = arrayOf("audio/*")

    enum class Copy { OK, TOO_BIG, UNREADABLE }

    /**
     * Copies the picked [uri] to [dest], stopping at [MAX_BYTES]. Blocking: call off the main thread. On anything but
     * [Copy.OK], [dest] is gone.
     */
    fun copy(ctx: Context, uri: Uri, dest: File): Copy {
        val r = runCatching {
            dest.parentFile?.mkdirs()
            val input = ctx.contentResolver.openInputStream(uri) ?: return@runCatching Copy.UNREADABLE
            input.use { inp ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) return@runCatching Copy.TOO_BIG
                        out.write(buf, 0, n)
                    }
                    if (total == 0L) Copy.UNREADABLE else Copy.OK
                }
            }
        }.getOrDefault(Copy.UNREADABLE)
        if (r != Copy.OK) dest.delete()
        return r
    }

    /** The words for a failed import. Pure. */
    fun failureText(why: ImportFailure): String = when (why) {
        ImportFailure.UNREADABLE -> "That file couldn't be played. Try an mp3, m4a, ogg, wav or flac."
        ImportFailure.SILENT -> "That file is silent."
        ImportFailure.TOO_LONG -> "Music can be up to 15 minutes."
        ImportFailure.TOO_SHORT -> "That's too short to loop. Pick something at least a second long."
    }

    fun copyFailureText(c: Copy): String? = when (c) {
        Copy.OK -> null
        Copy.TOO_BIG -> "That file is too big. Up to 60 MB."
        Copy.UNREADABLE -> "That file couldn't be opened. Try another."
    }
}
