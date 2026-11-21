package app.irlsupreme.service

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.core.graphics.get
import androidx.core.graphics.scale
import app.irlsupreme.engine.Scene
import app.irlsupreme.engine.video.CardFont
import app.irlsupreme.engine.video.CardLayout
import app.irlsupreme.engine.video.CardStyle
import app.irlsupreme.engine.video.CardTextSize
import app.irlsupreme.engine.video.Slates
import app.irlsupreme.engine.video.StreamPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * CONTRACT (custom cards): live elements the overlay draws over a phone-drawn card while it's up. [countdownMinutes]
 * counts down from when the card appears ("Starting in 4:32", "Back in 2:10"; 0 = off); [showTimeAway] counts up how
 * long the card has been up; [showChat] shows the chat layer on this card even if chat isn't otherwise on cards.
 */
data class CardElements(val countdownMinutes: Int = 0, val showTimeAway: Boolean = false, val showChat: Boolean = false)

/** What kind of background a card has; the file's extension says which (bg_brb.jpg / .png / .gif). */
enum class CardBackground { NONE, STILL, GIF }

/**
 * Everything the streamer set for one card (Settings › Scene cards). Empty text means the card's own words; [webUrl]
 * is kept as typed so the field never loses what you wrote, and only a valid https address ([cardWebUrl]) is used.
 * [name] and [created] only mean something on the streamer's own scenes ([Slates.Card.isCustom]): the scene exists
 * once made, and its name is kept as typed (shown cleaned up, see [SceneNames.clean]).
 */
data class CardText(
    val headline: String = "",
    val sub: String = "",
    val hasPicture: Boolean = false,
    val background: CardBackground = CardBackground.NONE,
    /** How much Ink lies over the background, in percent (one of [CardRules.DIM_STEPS]). */
    val dimPercent: Int = CardRules.DEFAULT_DIM,
    val layout: CardLayout = CardLayout.CLASSIC,
    /** A preset from [Slates.ACCENTS] or any opaque colour, or null for the brand's own colours. */
    val accent: Int? = null,
    val elements: CardElements = CardElements(),
    val webUrl: String = "",
    /** The words' colour, or null for the card's own (Bone on Ink, Ink on Bone). */
    val textColor: Int? = null,
    val textSize: CardTextSize = CardTextSize.NORMAL,
    val font: CardFont = CardFont.FULLBARS,
    val name: String = "",
    val created: Boolean = false,
) {
    /** The card looks other than its own. Dim alone isn't custom: with no background it changes nothing viewers see. */
    val isCustom: Boolean
        get() = headline.isNotBlank() || sub.isNotBlank() || hasPicture || background != CardBackground.NONE ||
            layout != CardLayout.CLASSIC || accent != null || elements != CardElements() || cardWebUrl(webUrl) != null ||
            textColor != null || textSize != CardTextSize.NORMAL || font != CardFont.FULLBARS
}

/** How picking a background went; anything but [OK] leaves the old background as it was. */
enum class BackgroundResult { OK, TOO_BIG, TOO_LARGE, UNREADABLE }

/**
 * Which card can do what, and the limits. Pure, so the page and the stream agree and the rules are unit-tested.
 *
 * Signal lost and Weak signal are also encoded once and looped by the BRB server (SlateEncoder), so they can only ever
 * be still: no looping GIF, no web page, no live elements. Weak signal is also shown precisely because the connection
 * can't carry a moving picture, so it stays still on the phone too.
 */
object CardRules {
    /** The dim steps offered, in percent. 80 is the engine's ceiling (CardStyle.dim 0..0.8). */
    val DIM_STEPS = listOf(0, 20, 35, 50, 65, 80)
    const val DEFAULT_DIM = 35

    /** Countdown lengths offered, in minutes; 0 is Off. */
    val COUNTDOWN_CHOICES = listOf(0, 1, 2, 3, 5, 10, 15)
    private const val MAX_COUNTDOWN = 60

    const val MAX_SOCIALS = 40
    const val MAX_WEB_URL = 2000

    /** A still background is stored at most this long on its long side and this many pixels (one 1080p frame). */
    const val MAX_BACKGROUND_LONG = 1920
    const val MAX_BACKGROUND_PIXELS = 1920 * 1080

    /**
     * GIFs are copied as they are, so the cap is on the file and its size: 8 MB and 1280 px on the long side (at most
     * 1280×720 worth of pixels). The compositor decodes one frame at a time, so a frame buffer is under 4 MB.
     */
    const val MAX_GIF_BYTES = 8 * 1024 * 1024
    const val MAX_GIF_LONG = 1280
    const val MAX_GIF_PIXELS = 1280 * 720

    /** The cards the BRB server loops as a still picture. */
    fun serverLooped(card: Slates.Card): Boolean = card == Slates.Card.SIGNAL_LOST || card == Slates.Card.WEAK_SIGNAL

    /** A GIF background moves only where the phone draws the card every frame; elsewhere its first frame shows. */
    fun loopsGif(card: Slates.Card): Boolean = !serverLooped(card)

    /** Timers, chat and socials are drawn by the phone's overlay, so only on the cards the phone draws. */
    fun hasLiveElements(card: Slates.Card): Boolean = !serverLooped(card)

    /** A countdown only where there's something to count down to: the start, or coming back (your own scenes too). */
    fun hasCountdown(card: Slates.Card): Boolean =
        card == Slates.Card.STARTING_SOON || card == Slates.Card.BRB || card == Slates.Card.PRIVACY || card.isCustom

    fun canUseWebPage(card: Slates.Card): Boolean = !serverLooped(card)

    /** The countdown's words on [card], for the help line ("Starting in 4:32"). */
    fun countdownExample(card: Slates.Card): String =
        if (card == Slates.Card.STARTING_SOON) "Starting in 4:32" else "Back in 2:10"

    /** [card]'s elements as the stream may use them: nothing on the server's cards, no countdown where it means nothing. */
    fun effective(card: Slates.Card, e: CardElements): CardElements = when {
        !hasLiveElements(card) -> CardElements()
        !hasCountdown(card) -> e.copy(countdownMinutes = 0)
        else -> e.copy(countdownMinutes = e.countdownMinutes.coerceIn(0, MAX_COUNTDOWN))
    }

    /** The nearest offered dim step to [percent], so an odd stored value still shows as a chosen key. */
    fun snapDim(percent: Int): Int = DIM_STEPS.minBy { kotlin.math.abs(it - percent) }

    /**
     * The curated accent nearest to [argb], or null when there are none. A preset accent is stored as a preset and
     * snapped on load, so if the curated set is retuned a streamer's pick moves to its nearest neighbour. A custom
     * colour is stored apart ([CardPrefs]) and kept exactly as picked.
     */
    fun snapAccent(argb: Int, accents: List<Int>): Int? = accents.minByOrNull { a ->
        val dr = ((a shr 16) and 0xFF) - ((argb shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((argb shr 8) and 0xFF)
        val db = (a and 0xFF) - (argb and 0xFF)
        dr * dr + dg * dg + db * db
    }

    /**
     * A plain name for an accent colour, for TalkBack and the label beside the swatches. Worked out from the hue, so
     * it stays right if the curated values are tuned.
     */
    fun accentName(argb: Int): String {
        // The curated palette carries its own names; the hue rule below is only for anything else.
        val i = Slates.ACCENTS.indexOf(argb)
        if (i >= 0) Slates.ACCENT_NAMES.getOrNull(i)?.let { return it }
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val sat = if (max == 0f) 0f else d / max
        if (sat < 0.12f) return "Grey"
        val hue = when (max) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return when {
            hue < 15f || hue >= 345f -> "Rose"
            hue < 45f -> if (sat < 0.4f) "Sand" else "Peach"
            hue < 70f -> "Butter"
            hue < 150f -> "Lime"
            hue < 185f -> "Mint"
            hue < 225f -> "Sky"
            hue < 275f -> "Lilac"
            hue < 315f -> "Orchid"
            else -> "Pink"
        }
    }

    /** Below this contrast (WCAG, for large type) the page says the words may be hard to read. */
    const val MIN_TEXT_CONTRAST = 3.0

    /** What a background is taken to be before its picture is known: a mid grey. */
    private const val UNKNOWN_TONE = 0xFF808080.toInt()

    /**
     * The colour [card]'s words are drawn in with [t]'s settings: the streamer's, else the card's own (Bone on Ink, Ink
     * on a Bone bookend, Bone on the plate and over any picture or background), as the stream's card drawing does it.
     */
    fun wordsColor(card: Slates.Card, t: CardText): Int {
        t.textColor?.let { return it or OPAQUE }
        val plate = t.layout == CardLayout.FULL_PICTURE
        val imagery = t.background != CardBackground.NONE || (plate && t.hasPicture)
        return if (!plate && !imagery && card.family == Slates.Family.BOOKEND) StreamPalette.INK else StreamPalette.BONE
    }

    /**
     * The colour behind [card]'s words, as near as can be told without drawing it: the Ink plate on Full picture, Ink
     * at the dim over the background ([backgroundTone]: its average colour, when known), or the card's own field.
     */
    fun wordsBackdrop(card: Slates.Card, t: CardText, backgroundTone: Int?): Int = when {
        t.layout == CardLayout.FULL_PICTURE -> StreamPalette.INK
        t.background != CardBackground.NONE -> blend(StreamPalette.INK, snapDim(t.dimPercent) / 100f, backgroundTone ?: UNKNOWN_TONE)
        card.family == Slates.Family.BOOKEND -> StreamPalette.BONE
        else -> StreamPalette.INK
    }

    /**
     * The page's warning when [card]'s words would be hard to read with [t]'s settings, in plain words, or null. Not on
     * a card that shows a web page instead.
     */
    fun contrastProblem(card: Slates.Card, t: CardText, backgroundTone: Int?): String? {
        if (canUseWebPage(card) && cardWebUrl(t.webUrl) != null) return null
        val ratio = ColorMath.contrast(wordsColor(card, t), wordsBackdrop(card, t, backgroundTone))
        if (ratio >= MIN_TEXT_CONTRAST) return null
        return if (t.background != CardBackground.NONE && t.layout != CardLayout.FULL_PICTURE) {
            "Your words may be hard to read on this background. Darken it more, or pick another text colour."
        } else {
            "Your words may be hard to read: they're close to the colour behind them. Pick another text colour."
        }
    }

    private const val OPAQUE = 0xFF000000.toInt()

    /** [top] at [alpha] over [bottom], as Canvas lays a colour over a picture. */
    private fun blend(top: Int, alpha: Float, bottom: Int): Int {
        val a = alpha.coerceIn(0f, 1f)
        fun ch(shift: Int) = (((top shr shift) and 0xFF) * a + ((bottom shr shift) and 0xFF) * (1 - a)).roundToInt().coerceIn(0, 255)
        return OPAQUE or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** What's wrong with [raw] as a card's web page, in plain words, or null when it's fine or empty. */
    fun webUrlProblem(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.length > MAX_WEB_URL) return "That address is too long."
        if (s.any { it.isWhitespace() }) return "A web address can't have spaces."
        val lower = s.lowercase(java.util.Locale.ROOT)
        if (lower.startsWith("http://")) return "Use an https:// address. Plain http pages aren't shown."
        if (!lower.startsWith("https://")) return "Start the address with https://"
        val uri = runCatching { URI(s) }.getOrNull() ?: return "That doesn't look like a web address."
        val host = uri.host
        if (host.isNullOrEmpty() || '.' !in host || host.startsWith('.') || host.endsWith('.')) {
            return "That doesn't look like a web address."
        }
        if (uri.rawUserInfo != null) return "Leave out the name and password part of the address."
        return null
    }

    /** The host alone ("example.com"), for the preview's placeholder: never the path or query, which can carry keys. */
    fun webHost(url: String): String = runCatching { URI(url.trim()).host }.getOrNull()?.removePrefix("www.").orEmpty()

    /**
     * The size a still [w]x[h] background is stored at: shrunk to fit [MAX_BACKGROUND_LONG] and
     * [MAX_BACKGROUND_PIXELS], never enlarged. Cropped to fill either a 16:9 or a 9:16 frame, it stays sharp enough
     * under the dim, and it costs at most 8.3 MB once decoded.
     */
    fun backgroundSize(w: Int, h: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return w to h
        val long = maxOf(w, h).toDouble()
        val s = minOf(1.0, MAX_BACKGROUND_LONG / long, sqrt(MAX_BACKGROUND_PIXELS.toDouble() / (w.toDouble() * h)))
        return maxOf(1, (w * s).roundToInt()).coerceAtMost(MAX_BACKGROUND_LONG) to
            maxOf(1, (h * s).roundToInt()).coerceAtMost(MAX_BACKGROUND_LONG)
    }

    /** Whether a GIF of [bytes] length and [info] can be a background, or why not. */
    fun gifCheck(bytes: Long, info: GifInfo?): BackgroundResult = when {
        bytes > MAX_GIF_BYTES -> BackgroundResult.TOO_BIG
        info == null || info.width <= 0 || info.height <= 0 || info.frames < 1 -> BackgroundResult.UNREADABLE
        maxOf(info.width, info.height) > MAX_GIF_LONG || info.width.toLong() * info.height > MAX_GIF_PIXELS -> BackgroundResult.TOO_LARGE
        else -> BackgroundResult.OK
    }

    /** The page's words for a failed pick, or null for [BackgroundResult.OK]. */
    fun backgroundError(r: BackgroundResult): String? = when (r) {
        BackgroundResult.OK -> null
        BackgroundResult.TOO_BIG -> "That GIF is over 8 MB. Try a shorter or smaller one."
        BackgroundResult.TOO_LARGE -> "That GIF is over $MAX_GIF_LONG pixels on its long side. Try a smaller one."
        BackgroundResult.UNREADABLE -> "That picture couldn't be opened. Try another."
    }
}

/** A valid https address for a card's web page, trimmed, or null. */
fun cardWebUrl(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() && CardRules.webUrlProblem(it) == null }

/** A GIF's logical size and how many frames it has (counted up to the first damaged block). */
data class GifInfo(val width: Int, val height: Int, val frames: Int)

/**
 * Reads a GIF's structure without decoding any pixels: the header, the logical screen size and the frame count. Pure,
 * so a picked file is checked (and a one-frame GIF treated as a still) before anything big is allocated.
 */
object GifScan {
    /** [bytes]' GIF info, or null when it isn't a GIF or has no whole frame. A damaged tail still counts the frames before it. */
    fun inspect(bytes: ByteArray): GifInfo? {
        if (!isGif(bytes) || bytes.size < 13) return null
        val width = u16(bytes, 6)
        val height = u16(bytes, 8)
        var i = 13 + colorTable(bytes[10].toInt())
        var frames = 0
        while (i < bytes.size) {
            when (bytes[i].toInt() and 0xFF) {
                0x21 -> { // extension: label, then sub-blocks
                    i = skipSubBlocks(bytes, i + 2) ?: break
                }
                0x2C -> { // image descriptor: 9 bytes, an optional local colour table, the LZW code size, sub-blocks
                    if (i + 10 > bytes.size) break
                    val next = skipSubBlocks(bytes, i + 10 + colorTable(bytes[i + 9].toInt()) + 1) ?: break
                    frames++
                    i = next
                }
                0x3B -> break // trailer
                else -> break // damaged: keep what we counted
            }
        }
        return if (frames == 0) null else GifInfo(width, height, frames)
    }

    fun isGif(bytes: ByteArray): Boolean = bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte() && (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte()) &&
        bytes[5] == 'a'.code.toByte()

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    /** The colour table's length in bytes from a packed field (0 when its flag is off). */
    private fun colorTable(packed: Int): Int = if (packed and 0x80 != 0) 3 * (1 shl ((packed and 0x07) + 1)) else 0

    /** The index just past a run of sub-blocks starting at [start], or null when they run off the end. */
    private fun skipSubBlocks(b: ByteArray, start: Int): Int? {
        var i = start
        while (true) {
            if (i >= b.size) return null
            val n = b[i].toInt() and 0xFF
            i += 1
            if (n == 0) return i
            i += n
        }
    }
}

/**
 * How one card's settings sit in SharedPreferences, as a plain map so it round-trips in unit tests. A null value means
 * "remove the key" (the default), so an untouched card leaves nothing behind.
 */
object CardPrefs {
    const val SOCIALS = "cards_socials"

    fun key(card: Slates.Card, field: String) = "card_${card.name.lowercase()}_$field"

    /**
     * The fields of a card's look that [CardText] keeps in settings. The picture and the background are files, not
     * keys. A preset accent is kept under "accent" (snapped to the presets on load, as it always was); a custom colour
     * under "accent_custom", exactly. Keys added later are simply missing on an older install, which reads as today's
     * look.
     */
    val FIELDS = listOf(
        "headline", "sub", "dim", "layout", "accent", "accent_custom", "countdown", "time_away", "chat", "web",
        "text_color", "text_size", "font",
    )

    /** What makes one of the streamer's own scenes: its name, and that it exists. Kept when its look is reset. */
    val SCENE_FIELDS = listOf("name", "on")

    fun write(card: Slates.Card, t: CardText, accents: List<Int> = Slates.ACCENTS): Map<String, Any?> = mapOf(
        key(card, "headline") to t.headline.ifEmpty { null },
        key(card, "sub") to t.sub.ifEmpty { null },
        key(card, "dim") to t.dimPercent.takeIf { it != CardRules.DEFAULT_DIM },
        key(card, "layout") to t.layout.takeIf { it != CardLayout.CLASSIC }?.name,
        key(card, "accent") to t.accent?.takeIf { it in accents },
        key(card, "accent_custom") to t.accent?.takeIf { it !in accents },
        key(card, "countdown") to t.elements.countdownMinutes.takeIf { it > 0 },
        key(card, "time_away") to t.elements.showTimeAway.takeIf { it },
        key(card, "chat") to t.elements.showChat.takeIf { it },
        key(card, "web") to t.webUrl.ifEmpty { null },
        key(card, "text_color") to t.textColor,
        key(card, "text_size") to t.textSize.takeIf { it != CardTextSize.NORMAL }?.name,
        key(card, "font") to t.font.takeIf { it != CardFont.FULLBARS }?.name,
        key(card, "name") to t.name.takeIf { card.isCustom }?.ifEmpty { null },
        key(card, "on") to t.created.takeIf { card.isCustom && it },
    )

    /** [card]'s settings from [get]; values of the wrong type (an older build, a hand edit) read as the default. */
    fun read(
        card: Slates.Card, get: (String) -> Any?, hasPicture: Boolean, background: CardBackground, accents: List<Int>,
    ): CardText {
        fun str(f: String) = (get(key(card, f)) as? String).orEmpty()
        fun int(f: String) = get(key(card, f)) as? Int
        fun bool(f: String) = get(key(card, f)) as? Boolean ?: false
        return CardText(
            headline = str("headline").take(CardStore.MAX_HEADLINE),
            sub = str("sub").take(CardStore.MAX_SUB),
            hasPicture = hasPicture,
            background = background,
            dimPercent = CardRules.snapDim(int("dim") ?: CardRules.DEFAULT_DIM),
            layout = CardLayout.entries.firstOrNull { it.name == str("layout") } ?: CardLayout.CLASSIC,
            accent = int("accent_custom")?.let { it or OPAQUE } ?: int("accent")?.let { CardRules.snapAccent(it, accents) },
            elements = CardElements(int("countdown")?.coerceIn(0, 60) ?: 0, bool("time_away"), bool("chat")),
            webUrl = str("web").take(CardRules.MAX_WEB_URL),
            textColor = int("text_color")?.let { it or OPAQUE },
            textSize = CardTextSize.parse(str("text_size")),
            font = CardFont.parse(str("font")),
            name = if (card.isCustom) str("name").take(SceneNames.MAX_NAME) else "",
            created = card.isCustom && bool("on"),
        )
    }

    private const val OPAQUE = 0xFF000000.toInt()

    /**
     * The engine's style for [card] from its settings and its loaded art. The server's cards never get a looping GIF
     * or a web page (their first frame / their own card shows instead); a web page needs a valid https address.
     */
    fun style(card: Slates.Card, t: CardText, picture: Bitmap?, background: Bitmap?, gif: File?): CardStyle {
        val base = CardStyle.of(t.headline, t.sub, picture)
        return base.copy(
            background = background,
            backgroundGif = gif?.takeIf { CardRules.loopsGif(card) },
            dim = CardRules.snapDim(t.dimPercent) / 100f,
            layout = t.layout,
            accent = t.accent,
            webUrl = if (CardRules.canUseWebPage(card)) cardWebUrl(t.webUrl) else null,
            textColor = t.textColor,
            textSize = t.textSize,
            font = t.font,
            title = if (card.isCustom) SceneNames.clean(t.name).ifEmpty { null } else null,
        )
    }
}

/**
 * The scene cards' custom words, pictures, backgrounds, layouts, accents, live elements and web pages (Settings › Scene
 * cards), for every card including the automatic ones. Settings live with the other settings ([CardPrefs]); each
 * picture and background is a file in the app's own files (no lasting access to the photo library). [apply] hands
 * everything to the engine's card drawing ([Slates.setStyles]), which the stream, the Scenes sheet thumbnails, the
 * previews and the slates sent to the BRB server all use.
 *
 * Memory: a picture is at most 512 px (1 MB decoded), a still background or a GIF's first frame at most one 1080p frame
 * (8.3 MB). Identical files share one bitmap, so one background used on every card costs 8.3 MB, and seven different
 * ones at most 58 MB. Bitmaps are only read, never recycled: the render thread may still be drawing an old one.
 */
object CardStore {
    const val MAX_HEADLINE = 40
    const val MAX_SUB = 90
    private const val MAX_PICTURE = 512
    private const val TAG = "CardStore"

    private var flow: MutableStateFlow<Map<Slates.Card, CardText>>? = null
    private var socialsFlow: MutableStateFlow<String>? = null

    /** One card's loaded pictures, and its background's average colour (for the page's contrast warning). */
    private class Art(val picture: Bitmap?, val background: Bitmap?, val gif: File?, val tone: Int? = null)

    /** The decoded bitmaps by content (length + CRC), so identical files share one and an unchanged file isn't re-decoded. */
    @Volatile private var decoded: Map<String, Bitmap> = emptyMap()
    @Volatile private var art: Map<Slates.Card, Art> = emptyMap()
    /** Bumped whenever [art] changes, so an [apply] that read the files before a removal doesn't put it back. */
    @Volatile private var artGen = 0
    /** Serialises the style hand-off, so a quick text edit can't land an older picture over a newer one. */
    private val lock = Any()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "cards").apply { mkdirs() }
    fun pictureFile(ctx: Context, card: Slates.Card) = File(dir(ctx), "${card.name.lowercase()}.png")

    private val BG_EXTENSIONS = setOf("jpg", "png", "gif")
    private fun bgPrefix(card: Slates.Card) = "bg_${card.name.lowercase()}-"

    /**
     * [card]'s background files ("bg_brb-<stamp>.gif"). Each pick gets a new name, so a replaced GIF is a new path:
     * whatever plays the old one sees the style change, and nothing is overwritten under a reader.
     */
    private fun backgroundFiles(ctx: Context, card: Slates.Card): List<File> =
        dir(ctx).listFiles { f -> f.name.startsWith(bgPrefix(card)) && f.extension in BG_EXTENSIONS }?.toList().orEmpty()

    /** [card]'s background file, if it has one (the newest, should an interrupted pick have left two). */
    fun backgroundFile(ctx: Context, card: Slates.Card): File? = backgroundFiles(ctx, card).maxByOrNull { it.lastModified() }

    private fun kindOf(f: File?) = when (f?.extension) {
        null -> CardBackground.NONE
        "gif" -> CardBackground.GIF
        else -> CardBackground.STILL
    }

    @Synchronized
    fun state(ctx: Context): StateFlow<Map<Slates.Card, CardText>> =
        (flow ?: MutableStateFlow(load(ctx)).also { flow = it; SceneNames.created = SceneNames.created(it.value) }).asStateFlow()

    /** The streamer's own scenes that exist, with their names, in slot order. */
    fun createdScenes(ctx: Context): Map<Scene, String> = SceneNames.created(state(ctx).value)

    /** Your socials line, as typed. */
    @Synchronized
    fun socialsState(ctx: Context): StateFlow<String> =
        (socialsFlow ?: MutableStateFlow(prefs(ctx).getString(CardPrefs.SOCIALS, "").orEmpty()).also { socialsFlow = it }).asStateFlow()

    fun text(ctx: Context, card: Slates.Card): CardText = state(ctx).value[card] ?: CardText()

    private fun load(ctx: Context): Map<Slates.Card, CardText> {
        val all = prefs(ctx).all
        return Slates.Card.entries.associateWith { c ->
            CardPrefs.read(c, all::get, pictureFile(ctx, c).exists(), kindOf(backgroundFile(ctx, c)), Slates.ACCENTS)
        }
    }

    /**
     * CONTRACT (custom cards): [card]'s live elements as the stream may draw them (none on Signal lost and Weak signal,
     * no countdown where there's nothing to count down to). Cheap: reads memory after the first call.
     */
    fun elements(ctx: Context, card: Slates.Card): CardElements = CardRules.effective(card, text(ctx, card).elements)

    /** CONTRACT (custom cards): your socials line ("@you everywhere"), drawn along phone-drawn cards; "" = none. */
    fun socials(ctx: Context): String = socialsState(ctx).value.trim()

    fun setSocials(ctx: Context, text: String) {
        val s = text.take(CardRules.MAX_SOCIALS)
        prefs(ctx).edit { if (s.isEmpty()) remove(CardPrefs.SOCIALS) else putString(CardPrefs.SOCIALS, s) }
        socialsState(ctx)
        socialsFlow?.value = s
    }

    /** Saves [card]'s words, cut to the limits, and redraws. Cheap: no picture is reloaded. */
    fun setText(ctx: Context, card: Slates.Card, headline: String, sub: String) =
        change(ctx, card) { it.copy(headline = headline.take(MAX_HEADLINE), sub = sub.take(MAX_SUB)) }

    fun setDim(ctx: Context, card: Slates.Card, percent: Int) = change(ctx, card) { it.copy(dimPercent = CardRules.snapDim(percent)) }

    fun setLayout(ctx: Context, card: Slates.Card, layout: CardLayout) = change(ctx, card) { it.copy(layout = layout) }

    /** A preset or any colour (made opaque); null for the card's own. */
    fun setAccent(ctx: Context, card: Slates.Card, accent: Int?) =
        change(ctx, card) { it.copy(accent = accent?.let { a -> a or 0xFF000000.toInt() }) }

    /** The words' colour (made opaque); null for the card's own. */
    fun setTextColor(ctx: Context, card: Slates.Card, color: Int?) =
        change(ctx, card) { it.copy(textColor = color?.let { c -> c or 0xFF000000.toInt() }) }

    fun setTextSize(ctx: Context, card: Slates.Card, size: CardTextSize) = change(ctx, card) { it.copy(textSize = size) }

    fun setFont(ctx: Context, card: Slates.Card, font: CardFont) = change(ctx, card) { it.copy(font = font) }

    /**
     * Makes a new scene of your own in the first free slot, its look the plain chosen card's, and returns its card;
     * null when all [Scene.CUSTOM] slots are taken.
     */
    fun createScene(ctx: Context): Slates.Card? {
        val texts = state(ctx).value
        val card = Scene.CUSTOM.mapNotNull { Slates.Card.of(it) }.firstOrNull { texts[it]?.created != true } ?: return null
        change(ctx, card) { CardText(created = true) }
        return card
    }

    /** Keeps [raw] as typed (cut to [SceneNames.MAX_NAME]); everywhere else shows it cleaned up ([SceneNames.clean]). */
    fun setSceneName(ctx: Context, card: Slates.Card, raw: String) {
        if (!card.isCustom) return
        change(ctx, card) { it.copy(name = raw.take(SceneNames.MAX_NAME)) }
    }

    /** Deletes one of your own scenes: its name, its look, its picture and background. The slot is free again. */
    fun deleteScene(ctx: Context, card: Slates.Card) {
        if (!card.isCustom) return
        prefs(ctx).edit { (CardPrefs.FIELDS + CardPrefs.SCENE_FIELDS).forEach { remove(CardPrefs.key(card, it)) } }
        pictureFile(ctx, card).delete()
        backgroundFiles(ctx, card).forEach { it.delete() }
        update(ctx, card) { CardText() }
        dropArt(ctx, card, picture = true, background = true)
    }

    /** The average colour of [card]'s background as loaded (a GIF's first frame), or null without one. */
    fun backgroundTone(card: Slates.Card): Int? = art[card]?.takeIf { it.background != null }?.tone

    fun setElements(ctx: Context, card: Slates.Card, elements: CardElements) = change(ctx, card) { it.copy(elements = elements) }

    /** Keeps [raw] as typed (cut to the limit); the stream only uses it once it's a valid https address. */
    fun setWebUrl(ctx: Context, card: Slates.Card, raw: String) = change(ctx, card) { it.copy(webUrl = raw.take(CardRules.MAX_WEB_URL)) }

    /** Copies a picked picture in, at most 512 px on its long side. Blocking: call off the main thread. */
    fun setPicture(ctx: Context, card: Slates.Card, uri: Uri): Boolean = runCatching {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { d, info, _ ->
            val long = maxOf(info.size.width, info.size.height)
            if (long > MAX_PICTURE) d.setTargetSampleSize((long + MAX_PICTURE - 1) / MAX_PICTURE)
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val tmp = File(dir(ctx), "${card.name.lowercase()}.tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        tmp.renameTo(pictureFile(ctx, card))
    }.getOrDefault(false).also { ok ->
        if (ok) {
            update(ctx, card) { it.copy(hasPicture = true) }
            apply(ctx)
        }
    }

    fun clearPicture(ctx: Context, card: Slates.Card) {
        pictureFile(ctx, card).delete()
        update(ctx, card) { it.copy(hasPicture = false) }
        dropArt(ctx, card, picture = true)
    }

    /**
     * Copies a picked background in. An animated GIF is kept as it is (checked first: at most 8 MB and 1280 px, see
     * [CardRules]); anything else, a one-frame GIF included, is stored as a still at most one 1080p frame, JPEG unless
     * it has transparency. Blocking: call off the main thread. On failure the old background stays.
     */
    fun setBackground(ctx: Context, card: Slates.Card, uri: Uri): BackgroundResult {
        val result = runCatching { storeBackground(ctx, card, uri) }.getOrElse {
            Log.w(TAG, "background not stored: ${it.javaClass.simpleName}")
            BackgroundResult.UNREADABLE
        }
        if (result == BackgroundResult.OK) {
            update(ctx, card) { it.copy(background = kindOf(backgroundFile(ctx, card))) }
            apply(ctx)
        }
        return result
    }

    private fun storeBackground(ctx: Context, card: Slates.Card, uri: Uri): BackgroundResult {
        val cr = ctx.contentResolver
        val head = cr.openInputStream(uri)?.use { it.readUpTo(6) } ?: return BackgroundResult.UNREADABLE
        if (GifScan.isGif(head)) {
            val bytes = cr.openInputStream(uri)?.use { it.readUpTo(CardRules.MAX_GIF_BYTES + 1) } ?: return BackgroundResult.UNREADABLE
            val info = if (bytes.size > CardRules.MAX_GIF_BYTES) null else GifScan.inspect(bytes)
            val check = CardRules.gifCheck(bytes.size.toLong(), info)
            if (check != BackgroundResult.OK) return check
            if (info!!.frames >= 2) {
                // Decode its first frame once, so a GIF the phone can't actually play is turned away here, not on stream.
                firstFrame(bytes)?.recycle() ?: return BackgroundResult.UNREADABLE
                return if (writeBackground(ctx, card, "gif") { it.write(bytes) }) BackgroundResult.OK else BackgroundResult.UNREADABLE
            }
            // One frame: nothing to loop, so it's just a picture.
        }
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { d, info, _ ->
            val (w, h) = CardRules.backgroundSize(info.size.width, info.size.height)
            if (w != info.size.width || h != info.size.height) d.setTargetSize(w, h)
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val alpha = bmp.hasAlpha()
        val ok = writeBackground(ctx, card, if (alpha) "png" else "jpg") {
            if (alpha) bmp.compress(Bitmap.CompressFormat.PNG, 100, it) else bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
        bmp.recycle()
        return if (ok) BackgroundResult.OK else BackgroundResult.UNREADABLE
    }

    /** Up to [max] bytes (InputStream.readNBytes is API 33; this app starts at 31). */
    private fun java.io.InputStream.readUpTo(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (total < max) {
            val n = read(buf, 0, minOf(buf.size, max - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    /** Writes [card]'s background through a temporary file under a fresh name, then removes the one it replaces. */
    private fun writeBackground(ctx: Context, card: Slates.Card, ext: String, write: (java.io.OutputStream) -> Unit): Boolean {
        val tmp = File(dir(ctx), "bg_${card.name.lowercase()}.tmp")
        tmp.outputStream().use(write)
        val target = File(dir(ctx), bgPrefix(card) + java.lang.Long.toHexString(System.currentTimeMillis()) + "." + ext)
        if (!tmp.renameTo(target)) { tmp.delete(); return false }
        backgroundFiles(ctx, card).filter { it != target }.forEach { it.delete() }
        return true
    }

    fun clearBackground(ctx: Context, card: Slates.Card) {
        backgroundFiles(ctx, card).forEach { it.delete() }
        update(ctx, card) { it.copy(background = CardBackground.NONE) }
        dropArt(ctx, card, background = true)
    }

    /**
     * [card] back to its own card: its words, no picture, no background, the classic layout, nothing live, no web page,
     * the brand's face and colours. One of your own scenes keeps its name.
     */
    fun reset(ctx: Context, card: Slates.Card) {
        prefs(ctx).edit { CardPrefs.FIELDS.forEach { remove(CardPrefs.key(card, it)) } }
        pictureFile(ctx, card).delete()
        backgroundFiles(ctx, card).forEach { it.delete() }
        update(ctx, card) { CardText(name = it.name, created = it.created) }
        dropArt(ctx, card, picture = true, background = true)
    }

    /**
     * Loads every card's settings, pictures and backgrounds into the engine's card drawing. Reads the files (a few MB at
     * most) and decodes only what changed: call off the main thread. Idempotent; returns the cards whose look changed.
     */
    fun apply(ctx: Context): Set<Slates.Card> {
        state(ctx)
        while (true) {
            val gen = artGen
            val (keep, next) = loadArt(ctx)
            synchronized(lock) {
                // Something changed the art while the files were read (a removal, another apply): read them again.
                if (gen == artGen) {
                    artGen++
                    decoded = keep
                    art = next
                    return push(ctx)
                }
            }
        }
    }

    /** Every card's art from its files, reusing bitmaps already decoded from identical bytes. */
    private fun loadArt(ctx: Context): Pair<Map<String, Bitmap>, Map<Slates.Card, Art>> {
        val keep = HashMap<String, Bitmap>()
        val old = decoded
        fun load(f: File?, gif: Boolean): Bitmap? {
            if (f == null || !f.exists()) return null
            return runCatching {
                val bytes = f.readBytes()
                val key = "${bytes.size}:${CRC32().apply { update(bytes) }.value}"
                (keep[key] ?: old[key] ?: (if (gif) firstFrame(bytes) else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)))
                    ?.also { keep[key] = it }
            }.onFailure { Log.w(TAG, "card art not loaded: ${it.javaClass.simpleName}") }.getOrNull()
        }
        val tones = HashMap<Bitmap, Int?>()
        val next = Slates.Card.entries.associateWith { c ->
            val bg = backgroundFile(ctx, c)
            val isGif = kindOf(bg) == CardBackground.GIF
            val frame = load(bg, isGif)
            // A GIF whose first frame won't decode isn't handed on: the card falls back to its plain field.
            val tone = frame?.let { f -> tones.getOrPut(f) { averageColor(f) } }
            Art(load(pictureFile(ctx, c), gif = false), frame, bg.takeIf { isGif && frame != null }, tone)
        }
        return keep to next
    }

    /** [b]'s average colour, opaque (transparent parts read as the Ink field under them); null if it can't be read. */
    private fun averageColor(b: Bitmap): Int? = runCatching {
        val one = b.scale(1, 1)
        val px = one[0, 0]
        if (one !== b) one.recycle()
        val a = (px ushr 24) / 255f
        fun ch(shift: Int) = (((px shr shift) and 0xFF) * a + ((StreamPalette.INK shr shift) and 0xFF) * (1 - a)).roundToInt().coerceIn(0, 255)
        0xFF000000.toInt() or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }.getOrNull()

    /**
     * A GIF's first frame, at most one 1080p frame, or null when it won't decode. Set as the card's still background
     * too, so the card drawn whole (the server's loop, the thumbnails, the compositor's fallback) shows it.
     */
    private fun firstFrame(bytes: ByteArray): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, info, _ ->
            val (w, h) = CardRules.backgroundSize(info.size.width, info.size.height)
            if (w != info.size.width || h != info.size.height) d.setTargetSize(w, h)
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull()

    /** Forgets [card]'s removed art without touching the files of the others, then redraws. Cheap: no decoding. */
    private fun dropArt(ctx: Context, card: Slates.Card, picture: Boolean = false, background: Boolean = false) {
        synchronized(lock) {
            artGen++
            val a = art[card]
            if (a != null) {
                art = art + (card to Art(
                    if (picture) null else a.picture, if (background) null else a.background, if (background) null else a.gif,
                    if (background) null else a.tone,
                ))
            }
            push(ctx)
        }
    }

    /** Saves a settings change to [card], updates the page and redraws. Cheap: no picture is reloaded. */
    private fun change(ctx: Context, card: Slates.Card, transform: (CardText) -> CardText) {
        val next = update(ctx, card, transform)
        prefs(ctx).edit { CardPrefs.write(card, next).forEach { (k, v) -> put(this, k, v) } }
        synchronized(lock) { push(ctx) }
    }

    private fun put(e: SharedPreferences.Editor, k: String, v: Any?) {
        when (v) {
            null -> e.remove(k)
            is String -> e.putString(k, v)
            is Int -> e.putInt(k, v)
            is Boolean -> e.putBoolean(k, v)
            else -> error("unsupported setting type")
        }
    }

    /** Hands every card's style to the engine. Hold [lock]. */
    private fun push(ctx: Context): Set<Slates.Card> {
        val a = art
        return Slates.setStyles(
            Slates.Card.entries.associateWith { c ->
                val art = a[c]
                CardPrefs.style(c, text(ctx, c), art?.picture, art?.background, art?.gif)
            },
        )
    }

    private fun update(ctx: Context, card: Slates.Card, change: (CardText) -> CardText): CardText {
        state(ctx)
        val f = flow ?: return change(CardText())
        var out = CardText()
        f.update { m -> change(m[card] ?: CardText()).also { out = it }.let { m + (card to it) } }
        SceneNames.created = SceneNames.created(f.value)
        return out
    }
}

/**
 * The Settings row's value: "Default", how many cards are your own, or "Socials line" when that's all you've set.
 * A card counts once whatever you changed on it; each scene you made counts as one.
 */
fun cardsValue(texts: Map<Slates.Card, CardText>, socials: String = ""): String = when (val n = texts.values.count { it.isCustom || it.created }) {
    0 -> if (socials.isNotBlank()) "Socials line" else "Default"
    1 -> "1 custom"
    else -> "$n custom"
}
