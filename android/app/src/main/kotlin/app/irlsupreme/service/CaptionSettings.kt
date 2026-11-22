package app.irlsupreme.service

import android.content.Context
import androidx.core.content.edit
import app.irlsupreme.engine.CaptionOptions
import app.irlsupreme.engine.CaptionPosition
import app.irlsupreme.engine.CaptionSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Live captions (Settings › Captions, and the switch in the live Scenes sheet). Off by default. [language] null: the
 * phone's own language.
 */
data class CaptionPrefs(
    val on: Boolean = false,
    val language: String? = null,
    val size: CaptionSize = CaptionSize.MEDIUM,
    val position: CaptionPosition = CaptionPosition.BOTTOM,
    val maskOffensive: Boolean = true,
) {
    /** The language in use: the chosen one, or the phone's. */
    val languageTag: String get() = language ?: Locale.getDefault().toLanguageTag()

    /** What a stream gets: null while captions are off. */
    fun options(): CaptionOptions? = if (on) CaptionOptions(languageTag, size, position, maskOffensive) else null
}

/** The caption settings, shared by Settings, the live screen and the stream's config. */
object CaptionSettings {
    private const val KEY_ON = "captions_on"
    private const val KEY_LANG = "captions_language"
    private const val KEY_SIZE = "captions_size"
    private const val KEY_POS = "captions_position"
    private const val KEY_MASK = "captions_mask"
    private var flow: MutableStateFlow<CaptionPrefs>? = null

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Synchronized
    fun state(ctx: Context): StateFlow<CaptionPrefs> = (flow ?: MutableStateFlow(load(ctx)).also { flow = it }).asStateFlow()

    fun current(ctx: Context): CaptionPrefs = state(ctx).value

    fun setOn(ctx: Context, on: Boolean) = save(ctx, current(ctx).copy(on = on))
    fun setLanguage(ctx: Context, tag: String?) = save(ctx, current(ctx).copy(language = tag))
    fun setSize(ctx: Context, s: CaptionSize) = save(ctx, current(ctx).copy(size = s))
    fun setPosition(ctx: Context, p: CaptionPosition) = save(ctx, current(ctx).copy(position = p))
    fun setMask(ctx: Context, on: Boolean) = save(ctx, current(ctx).copy(maskOffensive = on))

    private fun load(ctx: Context): CaptionPrefs = prefs(ctx).let { p ->
        CaptionPrefs(
            on = p.getBoolean(KEY_ON, false),
            language = p.getString(KEY_LANG, null),
            size = CaptionSize.entries.firstOrNull { it.name == p.getString(KEY_SIZE, null) } ?: CaptionSize.MEDIUM,
            position = CaptionPosition.entries.firstOrNull { it.name == p.getString(KEY_POS, null) } ?: CaptionPosition.BOTTOM,
            maskOffensive = p.getBoolean(KEY_MASK, true),
        )
    }

    @Synchronized
    private fun save(ctx: Context, c: CaptionPrefs) {
        prefs(ctx).edit {
            putBoolean(KEY_ON, c.on)
            if (c.language == null) remove(KEY_LANG) else putString(KEY_LANG, c.language)
            putString(KEY_SIZE, c.size.name)
            putString(KEY_POS, c.position.name)
            putBoolean(KEY_MASK, c.maskOffensive)
        }
        (flow ?: MutableStateFlow(c).also { flow = it }).value = c
    }
}
