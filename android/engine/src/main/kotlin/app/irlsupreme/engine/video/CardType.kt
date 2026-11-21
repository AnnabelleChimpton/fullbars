package app.irlsupreme.engine.video

import android.graphics.Typeface
import java.util.concurrent.ConcurrentHashMap

/**
 * A card's typeface for its headline and the line under it (Settings › Scene cards › Font). Only faces already on the
 * phone: the bundled Archivo widths and JetBrains Mono ([BrandType]), and Android's own families by name. [FULLBARS]
 * is today's look. The tag and the audio footer always stay in the brand's mono, so every card still reads as one.
 *
 * [family] is the Android family name for the system faces (null for the bundled ones); [headWeight] and [subWeight]
 * are what's asked of it, and the phone picks its nearest weight (most system families have only regular and bold).
 */
enum class CardFont(val label: String, val family: String?, val headWeight: Int, val subWeight: Int) {
    /** Archivo ExtraCondensed 800 over Archivo SemiCondensed 500: the brand's own. */
    FULLBARS("Fullbars", null, 800, 500),
    /** Archivo Condensed 800 over SemiCondensed 500: the same family, a wider headline. */
    WIDE("Wide", null, 800, 500),
    /** JetBrains Mono 500 over 400, the only weights bundled. */
    MONO("Mono", null, 500, 400),
    SERIF("Serif", "serif", 700, 400),
    SANS("Sans", "sans-serif", 800, 500),
    CONDENSED("Condensed", "sans-serif-condensed", 700, 400),
    CASUAL("Casual", "casual", 700, 400),
    HANDWRITTEN("Handwritten", "cursive", 700, 400),
    ;

    /** Bundled with the app, so it looks the same on every phone. */
    val bundled: Boolean get() = family == null

    /** The headline's tracking: Archivo is set a touch tight, as the brand does; other faces as they come. */
    val headTracking: Float get() = if (this == FULLBARS || this == WIDE) -0.01f else 0f

    companion object {
        /** What a family the phone lacks is drawn in. */
        const val FALLBACK_FAMILY = "sans-serif"

        /**
         * The Android family [font] is drawn in: its own when the phone has it ([has]), else [FALLBACK_FAMILY]; null for
         * the bundled faces. Pure, so the fallback is unit tested.
         */
        fun familyFor(font: CardFont, has: (String) -> Boolean): String? {
            val f = font.family ?: return null
            return if (f == FALLBACK_FAMILY || has(f)) f else FALLBACK_FAMILY
        }

        /** [name] as stored, or [FULLBARS] for anything unknown (an older or newer build, a hand edit). */
        fun parse(name: String?): CardFont = entries.firstOrNull { it.name == name } ?: FULLBARS
    }
}

/**
 * How big a card's words are, as a share of today's type. Every size still fits the frame: the headline breaks over
 * more lines or shrinks ([Slates.plan]), and a word too long for a line shrinks until it fits.
 */
enum class CardTextSize(val label: String, val scale: Float) {
    SMALLER("Smaller", 0.85f),
    NORMAL("Normal", 1f),
    LARGER("Larger", 1.2f),
    ;

    companion object {
        fun parse(name: String?): CardTextSize = entries.firstOrNull { it.name == name } ?: NORMAL
    }
}

/**
 * The typefaces a [CardFont] draws with. System faces are made once per family and weight and kept; the bundled ones
 * come from [BrandType] (call [BrandType.load] first, as for any card).
 */
object CardTypefaces {
    private val made = ConcurrentHashMap<String, Typeface>()

    /**
     * Whether this phone has [family]. Android hands back its default face for a family it doesn't know, so anything
     * but that face (or the default family itself) is the real one.
     */
    fun available(family: String): Boolean =
        family == CardFont.FALLBACK_FAMILY || runCatching { Typeface.create(family, Typeface.NORMAL) !== Typeface.DEFAULT }.getOrDefault(false)

    /** Whether [font] shows as itself on this phone (false: it falls back to Sans). */
    fun shows(font: CardFont): Boolean = font.bundled || CardFont.familyFor(font, ::available) == font.family

    fun head(font: CardFont): Typeface = when (font) {
        CardFont.FULLBARS -> BrandType.xc800
        CardFont.WIDE -> BrandType.c800
        CardFont.MONO -> BrandType.mono500
        else -> system(font, font.headWeight)
    }

    fun sub(font: CardFont): Typeface = when (font) {
        CardFont.FULLBARS, CardFont.WIDE -> BrandType.sc500
        CardFont.MONO -> BrandType.mono400
        else -> system(font, font.subWeight)
    }

    private fun system(font: CardFont, weight: Int): Typeface {
        val family = CardFont.familyFor(font, ::available) ?: CardFont.FALLBACK_FAMILY
        return made.getOrPut("$family/$weight") {
            runCatching { Typeface.create(Typeface.create(family, Typeface.NORMAL), weight, false) }.getOrNull() ?: Typeface.DEFAULT
        }
    }
}
