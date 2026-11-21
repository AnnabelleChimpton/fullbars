package app.irlsupreme.service

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import app.irlsupreme.engine.video.AlertRule
import app.irlsupreme.engine.video.AlertTier
import app.irlsupreme.engine.video.StreamPalette
import app.irlsupreme.twitch.TwitchAlert
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where alerts land on the stream. [AUTO] is the stream's own choice: clear of the streamer's other layers. */
enum class AlertPlace(val title: String) {
    AUTO("Automatic"),
    TOP_LEFT("Top left"),
    TOP_CENTER("Top centre"),
    TOP_RIGHT("Top right"),
    CENTER("Centre"),
    BOTTOM_LEFT("Bottom left"),
    BOTTOM_CENTER("Bottom centre"),
    BOTTOM_RIGHT("Bottom right"),
}

/** How big alerts are drawn: the whole alert scales, the engine's time-left rule with it. */
enum class AlertSize(val title: String, val factor: Float) {
    SMALL("Small", 0.8f),
    NORMAL("Normal", 1f),
    LARGE("Large", 1.25f),
}

/** How long one kind of alert stays up, against its usual dwell ([AlertTiming.dwellMs]). */
enum class AlertLength(val title: String, val factor: Float) {
    SHORT("Short", 0.6f),
    NORMAL("Normal", 1f),
    LONG("Long", 1.6f),
}

/** A line of colour around or along an alert. */
enum class AlertAccent(val title: String) {
    OFF("Off"),
    /** A thin line all round the plate. */
    KEYLINE("Keyline"),
    /** A bar down the plate's left edge. */
    STRIPE("Stripe"),
}

/** One kind's colours as the streamer chose them; null is the default. Colours are opaque ARGB. */
data class AlertPaint(
    /** Null: Ink, or Bone for the raid slab. */
    val plate: Int? = null,
    /** Null: whichever of Bone and Ink reads better on the plate. */
    val text: Int? = null,
    val accent: AlertAccent = AlertAccent.OFF,
    val accentColor: Int = AlertColors.DEFAULT_ACCENT,
) {
    val isCustom: Boolean get() = plate != null || text != null || accent != AlertAccent.OFF
}

/**
 * What the streamer set for one kind, beyond its words and picture ([AlertStyles]): whether it shows at all, the
 * smallest one worth showing, how long it stays up and its colours. The defaults are how alerts have always been.
 */
data class AlertKindOptions(
    val on: Boolean = true,
    /**
     * The smallest that shows: bits, gift subs or raid viewers; for a tip, hundredths of its amount (500 is 5.00 in
     * the tip's own currency). 0 shows every one.
     */
    val minimum: Long = 0,
    /** Subs: Prime subs don't show. */
    val skipPrime: Boolean = false,
    /** Subs: only resubs of at least this many months show (new subs don't); 0 shows every sub. */
    val resubMonths: Int = 0,
    val length: AlertLength = AlertLength.NORMAL,
    val paint: AlertPaint = AlertPaint(),
) {
    val isCustom: Boolean get() = this != AlertKindOptions()
}

/** Every alert option: where and how big (all kinds), and each kind's own. */
data class AlertOptions(
    val place: AlertPlace = AlertPlace.AUTO,
    val size: AlertSize = AlertSize.NORMAL,
    val kinds: Map<AlertKind, AlertKindOptions> = emptyMap(),
) {
    fun of(k: AlertKind): AlertKindOptions = kinds[k] ?: AlertKindOptions()
}

/** Whether an alert shows, and if not, why. */
enum class AlertVerdict { SHOW, TURNED_OFF, BELOW_MINIMUM }

/** Which alerts show, by the streamer's on/off and minimums. Pure, for tests. */
object AlertFilter {
    fun verdict(a: TwitchAlert, o: AlertKindOptions): AlertVerdict {
        if (!o.on) return AlertVerdict.TURNED_OFF
        val shows = when (a) {
            is TwitchAlert.Follow -> true
            is TwitchAlert.Sub -> !(o.skipPrime && a.prime) && (o.resubMonths <= 0 || (a.months ?: 1) >= o.resubMonths)
            is TwitchAlert.GiftSubs -> a.count >= o.minimum
            is TwitchAlert.Cheer -> a.bits >= o.minimum
            is TwitchAlert.Raid -> a.viewers >= o.minimum
            // An amount that can't be read is shown: a tip is never dropped on a guess.
            is TwitchAlert.Tip -> o.minimum <= 0 || (TipAmount.hundredths(a.amount)?.let { it >= o.minimum } ?: true)
        }
        return if (shows) AlertVerdict.SHOW else AlertVerdict.BELOW_MINIMUM
    }

    /** The session log's line for a held-back alert: its kind and why, never who sent it. */
    fun logLine(k: AlertKind, v: AlertVerdict): String =
        "alert ${k.name.lowercase()}: " + if (v == AlertVerdict.TURNED_OFF) "filtered (turned off)" else "filtered (below minimum)"
}

/**
 * The number in a tip's amount, whatever its currency and format: "$4.20", "€5,00", "1,234.56 USD", "¥500",
 * "1.234,56 €". Only the number counts: 5 is $5, €5 or ¥5 alike. Pure, for tests.
 */
object TipAmount {
    /** The amount in hundredths (420 for "$4.20"), or null when there's no number in it. */
    fun hundredths(amount: String): Long? {
        val t = Regex("[0-9][0-9.,'   ]*").find(amount)?.value?.trimEnd('.', ',', ' ', ' ', ' ', '\'')
            ?: return null
        val digitsOnly = t.filter { it.isDigit() || it == '.' || it == ',' }
        val lastSep = digitsOnly.indexOfLast { it == '.' || it == ',' }
        // A last separator with one or two digits after it is the decimal point; three is grouping ("1,234").
        val decimals = if (lastSep >= 0) digitsOnly.length - lastSep - 1 else 0
        val whole: String
        val frac: String
        if (lastSep >= 0 && decimals in 1..2) {
            whole = digitsOnly.substring(0, lastSep).filter { it.isDigit() }
            frac = digitsOnly.substring(lastSep + 1).padEnd(2, '0')
        } else {
            whole = digitsOnly.filter { it.isDigit() }
            frac = "00"
        }
        if (whole.length > 13) return null
        return (whole.ifEmpty { "0" }.toLong() * 100) + frac.toLong()
    }

    /** "5", "2.50": a minimum in hundredths as the page shows it. */
    fun format(hundredths: Long): String =
        if (hundredths % 100 == 0L) "${hundredths / 100}" else "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"

    /** A typed minimum ("5", "2.5", "2,50") in hundredths; blank is 0; null when it isn't a number. */
    fun parseMinimum(text: String): Long? {
        val t = text.trim()
        if (t.isEmpty()) return 0
        if (!t.all { it.isDigit() || it == '.' || it == ',' }) return null
        return hundredths(t)
    }
}

/**
 * Where an alert goes for a fixed [AlertPlace]: in that corner, edge or the middle, never closer to the frame's edges
 * than the title-safe margin every layer keeps ([OverlayLayout.margin]). [AlertPlace.AUTO] is
 * [OverlayLayout.alertBox], which also keeps clear of the streamer's other layers. Pure, for tests.
 */
object AlertPlacement {
    fun box(place: AlertPlace, width: Int, height: Int, onCard: Boolean, occupied: Set<Corner>, w: Float, h: Float): OverlayLayout.Box {
        if (place == AlertPlace.AUTO) return OverlayLayout.alertBox(width, height, onCard, occupied, w, h)
        val m = OverlayLayout.margin(width, height)
        val x = when (place) {
            AlertPlace.TOP_LEFT, AlertPlace.BOTTOM_LEFT -> m
            AlertPlace.TOP_RIGHT, AlertPlace.BOTTOM_RIGHT -> width - m - w
            else -> (width - w) / 2f
        }
        val y = when (place) {
            AlertPlace.TOP_LEFT, AlertPlace.TOP_CENTER, AlertPlace.TOP_RIGHT -> m
            AlertPlace.BOTTOM_LEFT, AlertPlace.BOTTOM_CENTER, AlertPlace.BOTTOM_RIGHT -> height - m - h
            else -> (height - h) / 2f
        }
        // Never past the margin, even for an alert as wide as the frame allows.
        return OverlayLayout.Box(x.coerceIn(m, maxOf(m, width - m - w)), y.coerceIn(m, maxOf(m, height - m - h)), w, h)
    }

    /** The corners a fixed place may cover, for the page's note; none for Automatic, which keeps clear of them. */
    fun covers(place: AlertPlace): Set<Corner> = when (place) {
        AlertPlace.TOP_LEFT -> setOf(Corner.TOP_LEFT)
        AlertPlace.TOP_RIGHT -> setOf(Corner.TOP_RIGHT)
        AlertPlace.BOTTOM_LEFT -> setOf(Corner.BOTTOM_LEFT)
        AlertPlace.BOTTOM_RIGHT -> setOf(Corner.BOTTOM_RIGHT)
        AlertPlace.TOP_CENTER -> setOf(Corner.TOP_LEFT, Corner.TOP_RIGHT)
        AlertPlace.BOTTOM_CENTER -> setOf(Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT)
        AlertPlace.CENTER, AlertPlace.AUTO -> emptySet()
    }
}

/** An alert's colours, worked out for its tier: what the drawing and the engine's rule use. */
data class AlertInk(
    /** The plate, opaque; drawn at [plateAlpha]. */
    val plate: Int,
    val plateAlpha: Float,
    /** The name, the amount, the raid's mark. */
    val text: Int,
    /** The small caps label. */
    val label: Int,
    /** The engine's time-left rule. */
    val rule: Int,
    /** A line all round, or null. */
    val keyline: Int?,
    /** A bar down the left edge, or null. */
    val stripe: Int?,
)

/** How an alert's colours resolve, and when the text is hard to read. Pure, for tests. */
object AlertColors {
    const val INK = StreamPalette.INK
    const val BONE = StreamPalette.BONE
    /** Below this contrast between the text and its plate, the page says the words may be hard to read. */
    const val MIN_CONTRAST = 3.0
    /** An accent turned on before a colour is picked. */
    const val DEFAULT_ACCENT = 0xFF3CC8E8.toInt()

    val PLATE_PRESETS = listOf(INK, BONE, 0xFF1F3A5F.toInt(), 0xFF4B2E83.toInt(), 0xFF0F5E59.toInt(), 0xFF6E1F4C.toInt())
    val TEXT_PRESETS = listOf(BONE, INK, 0xFFFFC2DA.toInt(), 0xFFB8E6F0.toInt())
    val ACCENT_PRESETS = listOf(BONE, INK, 0xFFFF5FA2.toInt(), 0xFF9B6BFF.toInt(), DEFAULT_ACCENT, 0xFF3D7BFF.toInt())

    fun defaultPlate(tier: AlertTier): Int = if (tier == AlertTier.LOUD) BONE else INK

    fun resolve(p: AlertPaint, tier: AlertTier): AlertInk {
        val loud = tier == AlertTier.LOUD
        val plate = opaque(p.plate ?: defaultPlate(tier))
        val text = opaque(p.text ?: ColorMath.readableOn(plate, BONE, INK))
        // Today's quiet grey label on the Ink plate; otherwise the text colour a little quieter, still on the plate.
        val label = when {
            loud -> text
            text == BONE && plate == INK -> StreamPalette.TEXT2
            else -> StreamPalette.withAlpha(text, LABEL_ALPHA)
        }
        val keyline = when {
            p.accent == AlertAccent.KEYLINE -> opaque(p.accentColor)
            loud -> INK
            else -> null
        }
        return AlertInk(
            plate = plate,
            plateAlpha = if (loud) 1f else StreamPalette.ALERT_PLATE_ALPHA,
            text = text,
            label = label,
            // By default Bone on the Ink plate and Ink on the Bone slab ([AlertRule.color]), as it has always been.
            rule = text,
            keyline = keyline,
            stripe = if (p.accent == AlertAccent.STRIPE) opaque(p.accentColor) else null,
        )
    }

    /** The text's contrast on its plate, 1..21. */
    fun contrast(p: AlertPaint, tier: AlertTier): Double = resolve(p, tier).let { ColorMath.contrast(it.plate, it.text) }

    /** True when the words would be hard to read: under 3:1 against the plate. */
    fun hardToRead(p: AlertPaint, tier: AlertTier): Boolean = contrast(p, tier) < MIN_CONTRAST

    fun opaque(argb: Int): Int = argb or 0xFF000000.toInt()

    private const val LABEL_ALPHA = 0.78f
}

/** Reads stored settings, so the rules for missing and odd values can be tested without a phone. */
interface SettingsReader {
    fun has(key: String): Boolean
    fun bool(key: String, default: Boolean): Boolean
    fun long(key: String, default: Long): Long
    fun int(key: String, default: Int): Int
    fun string(key: String): String?
}

/**
 * The alert options as stored with the other overlay settings, one key per value. A key that isn't there (an install
 * from before these options, or a value never changed) reads as the default, and every number is held to its bounds,
 * so an odd stored value can't hide every alert or stretch one for minutes. Pure, for tests.
 */
object AlertOptionsCodec {
    const val MAX_COUNT = 1_000_000L
    /** 1,000,000.00 in hundredths. */
    const val MAX_TIP = 100_000_000L
    const val MAX_MONTHS = 240

    fun key(k: AlertKind, field: String) = "alert_${k.name.lowercase()}_$field"
    const val PLACE = "alert_place"
    const val SIZE = "alert_size"

    fun decode(r: SettingsReader): AlertOptions = AlertOptions(
        place = enumOf(r.string(PLACE), AlertPlace.AUTO),
        size = enumOf(r.string(SIZE), AlertSize.NORMAL),
        kinds = AlertKind.entries.associateWith { k -> decodeKind(r, k) },
    )

    private fun decodeKind(r: SettingsReader, k: AlertKind): AlertKindOptions {
        fun color(field: String): Int? = if (r.has(key(k, field))) AlertColors.opaque(r.int(key(k, field), 0)) else null
        return AlertKindOptions(
            on = r.bool(key(k, "on"), true),
            minimum = if (hasMinimum(k)) boundMinimum(k, r.long(key(k, "min"), 0)) else 0,
            skipPrime = k == AlertKind.SUB && r.bool(key(k, "skip_prime"), false),
            resubMonths = if (k == AlertKind.SUB) boundMonths(r.int(key(k, "resub_months"), 0)) else 0,
            length = enumOf(r.string(key(k, "length")), AlertLength.NORMAL),
            paint = AlertPaint(
                plate = color("plate"),
                text = color("text"),
                accent = enumOf(r.string(key(k, "accent")), AlertAccent.OFF),
                accentColor = color("accent_color") ?: AlertColors.DEFAULT_ACCENT,
            ),
        )
    }

    /** Every key for [k] with its value; null removes the key, so a default takes no space. */
    fun encodeKind(k: AlertKind, o: AlertKindOptions): Map<String, Any?> = mapOf(
        key(k, "on") to o.on.takeIf { !it },
        key(k, "min") to boundMinimum(k, o.minimum).takeIf { it > 0 && hasMinimum(k) },
        key(k, "skip_prime") to o.skipPrime.takeIf { it && k == AlertKind.SUB },
        key(k, "resub_months") to boundMonths(o.resubMonths).takeIf { it > 0 && k == AlertKind.SUB },
        key(k, "length") to o.length.takeIf { it != AlertLength.NORMAL }?.name,
        key(k, "plate") to o.paint.plate?.let(AlertColors::opaque),
        key(k, "text") to o.paint.text?.let(AlertColors::opaque),
        key(k, "accent") to o.paint.accent.takeIf { it != AlertAccent.OFF }?.name,
        key(k, "accent_color") to AlertColors.opaque(o.paint.accentColor).takeIf { it != AlertColors.DEFAULT_ACCENT },
    )

    fun encodeGlobal(place: AlertPlace, size: AlertSize): Map<String, Any?> = mapOf(
        PLACE to place.takeIf { it != AlertPlace.AUTO }?.name,
        SIZE to size.takeIf { it != AlertSize.NORMAL }?.name,
    )

    /** [o] as it reads back from storage: bounded, with what doesn't apply to [k] dropped. */
    fun normalize(k: AlertKind, o: AlertKindOptions): AlertKindOptions =
        decode(MapReader(encodeKind(k, o).filterValues { it != null })).of(k)

    /** Kinds with a minimum: bits, gift subs, raids and tips. */
    fun hasMinimum(k: AlertKind): Boolean = k == AlertKind.BITS || k == AlertKind.GIFT || k == AlertKind.RAID || k == AlertKind.TIP

    fun boundMinimum(k: AlertKind, v: Long): Long = v.coerceIn(0, if (k == AlertKind.TIP) MAX_TIP else MAX_COUNT)

    /** 0 (every sub) or 2 and up: a resub is at least its second month. */
    fun boundMonths(v: Int): Int = when {
        v <= 0 -> 0
        else -> v.coerceIn(2, MAX_MONTHS)
    }

    private inline fun <reified E : Enum<E>> enumOf(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}

/** The {placeholders} each kind's label and message can use, for the page. */
fun alertPlaceholders(k: AlertKind): List<String> = when (k) {
    AlertKind.FOLLOW -> listOf("{name}")
    AlertKind.SUB -> listOf("{name}", "{months}", "{message}")
    AlertKind.GIFT -> listOf("{name}", "{count}", "{amount}")
    AlertKind.BITS -> listOf("{name}", "{amount}", "{message}")
    AlertKind.RAID -> listOf("{name}", "{viewers}")
    AlertKind.TIP -> listOf("{name}", "{amount}")
}

/**
 * The alert options (Settings › Overlays › Customize alerts), kept with the other overlay settings like
 * [AlertStyles]' words. [options] is what the stream reads for each alert, so the stream, the previews and Test an
 * alert all use the same.
 */
object AlertOptionsStore {
    @Volatile private var flow: MutableStateFlow<AlertOptions>? = null

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("overlays", Context.MODE_PRIVATE)

    @Synchronized
    fun state(ctx: Context): StateFlow<AlertOptions> =
        (flow ?: MutableStateFlow(AlertOptionsCodec.decode(PrefsReader(prefs(ctx)))).also { flow = it }).asStateFlow()

    /** The options as last loaded; the defaults until [state] has run once. */
    val options: AlertOptions get() = flow?.value ?: AlertOptions()

    fun setKind(ctx: Context, k: AlertKind, o: AlertKindOptions) {
        write(ctx, AlertOptionsCodec.encodeKind(k, o))
        update(ctx) { it.copy(kinds = it.kinds + (k to AlertOptionsCodec.normalize(k, o))) }
    }

    fun setPlace(ctx: Context, place: AlertPlace) {
        val now = state(ctx).value
        write(ctx, AlertOptionsCodec.encodeGlobal(place, now.size))
        update(ctx) { it.copy(place = place) }
    }

    fun setSize(ctx: Context, size: AlertSize) {
        val now = state(ctx).value
        write(ctx, AlertOptionsCodec.encodeGlobal(now.place, size))
        update(ctx) { it.copy(size = size) }
    }

    fun reset(ctx: Context, k: AlertKind) = setKind(ctx, k, AlertKindOptions())

    private fun write(ctx: Context, values: Map<String, Any?>) = prefs(ctx).edit {
        values.forEach { (key, v) ->
            when (v) {
                null -> remove(key)
                is Boolean -> putBoolean(key, v)
                is Int -> putInt(key, v)
                is Long -> putLong(key, v)
                is String -> putString(key, v)
            }
        }
    }

    private fun update(ctx: Context, change: (AlertOptions) -> AlertOptions) {
        state(ctx)
        val f = flow ?: return
        f.value = change(f.value)
    }

    private class PrefsReader(private val p: SharedPreferences) : SettingsReader {
        override fun has(key: String) = p.contains(key)
        override fun bool(key: String, default: Boolean) = runCatching { p.getBoolean(key, default) }.getOrDefault(default)
        override fun long(key: String, default: Long) = runCatching { p.getLong(key, default) }.getOrDefault(default)
        override fun int(key: String, default: Int) = runCatching { p.getInt(key, default) }.getOrDefault(default)
        override fun string(key: String): String? = runCatching { p.getString(key, null) }.getOrNull()
    }
}

/** A [SettingsReader] over a plain map, for [AlertOptionsCodec.normalize] and tests. */
class MapReader(private val m: Map<String, Any?>) : SettingsReader {
    override fun has(key: String) = m[key] != null
    override fun bool(key: String, default: Boolean) = m[key] as? Boolean ?: default
    override fun long(key: String, default: Long) = (m[key] as? Number)?.toLong() ?: default
    override fun int(key: String, default: Int) = (m[key] as? Number)?.toInt() ?: default
    override fun string(key: String) = m[key] as? String
}
