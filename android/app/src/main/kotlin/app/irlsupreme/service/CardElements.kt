package app.irlsupreme.service

import app.irlsupreme.engine.Scene
import app.irlsupreme.engine.video.CardLayout
import app.irlsupreme.engine.video.Slates

/**
 * One timer on a card: a mono caps [label] ("BACK IN") over a mono [figure] ("2:10"). A countdown that has reached 0:00
 * has no figure; [words] say it calmly instead ("Back any moment"), so a card never shows a negative or stuck clock.
 */
data class TimerReadout(val label: String, val figure: String?, val words: String? = null)

/**
 * How a card looks under the overlay's live elements, as far as they care: [plated] puts them on Ink plates (the card
 * has a background, a page or a picture, any of which could sit under the top right or the bottom row: even CLASSIC's
 * picture reaches 70% of the text width on a portrait card, under the timer block); otherwise they're drawn straight
 * on the card's field like its own tag, Ink on the Bone bookends ([bookend]) and Bone on Ink elsewhere. [web] is a web
 * page shown in place of the card. [layout] and [headline] (the words the card prints, the streamer's or the default)
 * say where the card's words are, so chat can stop above them ([CardZones.chatFloor]).
 */
data class CardLook(
    val plated: Boolean = false,
    val bookend: Boolean = false,
    val web: Boolean = false,
    val layout: CardLayout = CardLayout.CLASSIC,
    val headline: String? = null,
)

/** Everything the overlay draws for the card on screen. Null from [LiveElements.plan] when there's nothing. */
data class CardOverlayPlan(
    val card: Slates.Card,
    val timers: List<TimerReadout>,
    /** Chat in the card's own column, because this card asks for it and the streamer's chat layer isn't already on it. */
    val chat: Boolean,
    /** The socials line along the bottom, or null. */
    val socials: String?,
    val look: CardLook,
) {
    /** A timer is up, so the overlay redraws on its ticks (and only then): see [LiveElements.tickPeriodMs]. */
    val ticking: Boolean get() = timers.isNotEmpty()
}

/**
 * The live elements drawn over a phone-drawn scene card while it's up (Settings › Scene cards: [CardElements] and
 * [CardStore.socials]): a countdown, the time away, chat and a socials line. Pure, so the words, the end states and
 * which card gets what are unit-tested; [OverlayComposer] draws them and [StreamService] times them.
 *
 * Only the cards the engine lays the overlay over get them: BRB, Starting soon and Ending (full-frame, chosen) and
 * Camera lost (drawn in Live's place, so Live's layers already show on it). Never Privacy or Weak signal, where the
 * engine hides every layer (Privacy promises stillness; Weak signal must stay cheap to send), and never Signal lost,
 * which the BRB server loops from a still encoded at the start of the stream, so nothing on it can tick.
 */
object LiveElements {
    private val DRAWN_ON = setOf(Slates.Card.STARTING_SOON, Slates.Card.BRB, Slates.Card.ENDING, Slates.Card.CAMERA_LOST)
    private val COUNTS_DOWN = setOf(Slates.Card.STARTING_SOON, Slates.Card.BRB, Slates.Card.ENDING)

    /** Longest countdown honoured, and the longest time any figure shows, so the block's width stays bounded. */
    const val MAX_COUNTDOWN_MINUTES = 24 * 60
    private const val MAX_SHOWN_S = 99L * 3600 + 59 * 60 + 59

    /** The socials line is cut to this many characters (about one line along a portrait card). */
    const val MAX_SOCIALS = 60

    /**
     * The card viewers see, from the engine's state: the scene's card, else Weak signal, else Camera lost when nothing
     * is coming from any camera (the compositor reports `activeSource` "none" exactly when it draws that card), else
     * null for Live.
     */
    fun cardOnScreen(scene: Scene, weakSignal: Boolean, activeSource: String): Slates.Card? =
        Slates.Card.of(scene) ?: when {
            weakSignal -> Slates.Card.WEAK_SIGNAL
            activeSource == "none" -> Slates.Card.CAMERA_LOST
            else -> null
        }

    /** The overlay's live elements can show on [card] (see the class note for why the others never do). */
    fun drawsOn(card: Slates.Card): Boolean = card in DRAWN_ON

    /**
     * [card] can count down. Not Camera lost: nobody chose it, so a promise of when it ends would be made up. Its time
     * away still counts up, which is only ever true.
     */
    fun countsDown(card: Slates.Card): Boolean = card in COUNTS_DOWN

    /** Shown over a running countdown. */
    fun countdownLabel(card: Slates.Card): String = when (card) {
        Slates.Card.STARTING_SOON -> "STARTING IN"
        Slates.Card.ENDING -> "ENDING IN"
        else -> "BACK IN"
    }

    /** What a countdown says once it reaches 0:00: calm, no figure, never "-0:12" or "Late". */
    fun countdownEnd(card: Slates.Card): String = when (card) {
        Slates.Card.STARTING_SOON -> "Starting any moment"
        Slates.Card.ENDING -> "Ending any moment"
        else -> "Back any moment"
    }

    /** Over the time the card has been up: what that time means on this card. */
    fun awayLabel(card: Slates.Card): String = when (card) {
        Slates.Card.STARTING_SOON -> "WAITING"
        Slates.Card.ENDING -> "ENDED"
        Slates.Card.CAMERA_LOST -> "LOST FOR"
        else -> "AWAY"
    }

    /** [seconds] as "4:32", or "1:04:32" from an hour; negatives read 0:00 and it stops at 99:59:59. */
    fun clock(seconds: Long): String {
        val s = seconds.coerceIn(0L, MAX_SHOWN_S)
        val h = s / 3600
        val m = (s / 60) % 60
        val sec = s % 60
        return if (h > 0) "$h:${two(m)}:${two(sec)}" else "$m:${two(sec)}"
    }

    private fun two(n: Long) = if (n < 10) "0$n" else "$n"

    /**
     * A [minutes] countdown from when [card] appeared, [upForMs] ago. The figure rounds up, so "5:00" shows for the
     * whole first second and "0:01" for the last, and the end words take over exactly at 0:00.
     */
    fun countdown(card: Slates.Card, minutes: Int, upForMs: Long): TimerReadout {
        val leftMs = minutes.coerceIn(0, MAX_COUNTDOWN_MINUTES) * 60_000L - upForMs.coerceAtLeast(0L)
        if (leftMs <= 0L) return TimerReadout(countdownLabel(card), null, countdownEnd(card))
        return TimerReadout(countdownLabel(card), clock((leftMs + 999) / 1000))
    }

    /** The time [card] has been up, counting up in whole seconds. */
    fun away(card: Slates.Card, upForMs: Long): TimerReadout = TimerReadout(awayLabel(card), clock(upForMs.coerceAtLeast(0L) / 1000))

    /** The streamer's socials line as it's drawn: one line, spaces collapsed, at most [MAX_SOCIALS]; null when blank. */
    fun socialsLine(raw: String): String? {
        val one = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        if (one.isEmpty()) return null
        return if (one.length <= MAX_SOCIALS) one else one.take(MAX_SOCIALS - 1).trimEnd() + "…"
    }

    /**
     * How [card] looks under the elements: plated when anything but its plain field could sit under them (a still or
     * animated background, a web page, or a picture in any layout: CLASSIC's picture starts at the tag line and runs
     * up to 70% of the text width, which on a portrait card reaches under the timer block and the chat column).
     * [headline] is the headline the card prints, for where chat must stop.
     */
    fun look(
        card: Slates.Card, background: Boolean, picture: Boolean, layout: CardLayout, web: Boolean, headline: String? = null,
    ): CardLook {
        val plated = background || web || picture
        return CardLook(
            plated = plated, bookend = !plated && card.family == Slates.Family.BOOKEND, web = web, layout = layout,
            headline = headline,
        )
    }

    /**
     * What to draw over [card], up [upForMs]: the countdown first, then the time away, in one block; chat when the card
     * asks for it and the streamer's own chat layer isn't already drawn there ([userChatShown]); the socials line except
     * over a web page (the page is the streamer's whole design, and the line is set once for every card). Null when
     * there's nothing, so the overlay stays as it was.
     */
    fun plan(
        card: Slates.Card, elements: CardElements, socials: String, upForMs: Long, userChatShown: Boolean, look: CardLook,
    ): CardOverlayPlan? {
        if (!drawsOn(card)) return null
        val timers = buildList {
            if (elements.countdownMinutes > 0 && countsDown(card)) add(countdown(card, elements.countdownMinutes, upForMs))
            if (elements.showTimeAway) add(away(card, upForMs))
        }
        val chat = elements.showChat && !userChatShown
        val line = if (look.web) null else socialsLine(socials)
        if (timers.isEmpty() && !chat && line == null) return null
        return CardOverlayPlan(card, timers, chat, line, look)
    }

    /**
     * Ms from [upForMs] to the card's next tick, when every timer on it changes together: whole seconds normally,
     * every [periodMs] (see [tickPeriodMs]) when hot.
     */
    fun msToNextTick(upForMs: Long, periodMs: Long = 1000L): Long = periodMs - Math.floorMod(upForMs, periodMs)

    /**
     * The engine's thermal rung from which it uploads a new overlay at most every [HOT_UPLOAD_MS]
     * (Compositor.OVERLAY_HOT_INTERVAL_NS, private to the engine, so mirrored here).
     */
    const val HOT_RUNG = 2
    const val HOT_UPLOAD_MS = 5_000L

    /** Margin over the hot upload interval: a few frames at 24 fps, the rate the hot ladder holds. */
    private const val SETTLE_FRAMES_MS = 200L

    /** A few frames: long enough for a pushed overlay to reach the render thread when the engine uploads every frame. */
    const val SWITCH_SETTLE_MS = 120L

    /**
     * How long a card-safe bitmap must have been the newest pushed before the "on cards" switch may go on. Cool, the
     * engine uploads a pushed overlay on the next frame, so a few frames do. From [HOT_RUNG] it uploads one at most
     * every [HOT_UPLOAD_MS], so the card-safe bitmap can wait that long on the render thread while the old one, with
     * the streamer's Live-only layers, still shows: the switch must wait out a whole interval plus a few frames, or
     * those layers would show on the card for up to five seconds.
     */
    fun switchSettleMs(thermalRung: Int): Long =
        if (thermalRung >= HOT_RUNG) HOT_UPLOAD_MS + SETTLE_FRAMES_MS else SWITCH_SETTLE_MS

    /**
     * Ms until the switch may go on: 0 once every bitmap pushed for the last [settleMs] has been fit for cards
     * ([cardSafeSinceMs] is when that run of card-safe pushes began, null when the newest push isn't one). Counting
     * from the run's start, not from the last push, keeps a new card-safe push (a ticking timer) from restarting the
     * wait, while any Live-only push in between does.
     */
    fun switchWaitMs(cardSafeSinceMs: Long?, nowMs: Long, settleMs: Long): Long =
        if (cardSafeSinceMs == null) settleMs else (settleMs - (nowMs - cardSafeSinceMs)).coerceIn(0L, settleMs)

    /**
     * How often a card's timers redraw. Every second normally. From [HOT_RUNG] the engine uploads an overlay at most
     * every [HOT_UPLOAD_MS], so drawing a 1080p bitmap each second would throw four of five away on a phone that is
     * already too hot: the timers step in fives instead, matching what viewers can see.
     */
    fun tickPeriodMs(thermalRung: Int): Long = if (thermalRung >= HOT_RUNG) HOT_UPLOAD_MS else 1000L

    /**
     * The engine's "overlay on cards" switch: on when the streamer put their layers on cards, or when this chosen card
     * has live elements of its own to show (the bitmap then holds only those, see [OverlayComposer.compose]).
     */
    fun overlayOnCards(userOnCards: Boolean, cardElementsDrawn: Boolean): Boolean = userOnCards || cardElementsDrawn

    /**
     * Turning the engine's switch on is safe only once the bitmap it already holds is fit for a card, since the switch
     * reaches the render thread at once while a new bitmap waits for its upload (a frame when cool, up to
     * [HOT_UPLOAD_MS] when hot). So a bitmap that replaces one with the streamer's Live-only layers goes up under the
     * old switch first, and the switch follows once [switchWaitMs] says it has had time to be uploaded.
     */
    fun safeSwitch(wanted: Boolean, heldBitmapFitForCards: Boolean): Boolean = wanted && heldBitmapFitForCards

    /**
     * The web overlay is held back while a chosen card shows its own elements and the streamer left "on cards" off:
     * the engine's switch lets the page through with the bitmap, and they asked for neither on cards.
     */
    fun holdBrowser(card: Slates.Card?, userOnCards: Boolean, cardElementsDrawn: Boolean): Boolean =
        card != null && card != Slates.Card.CAMERA_LOST && cardElementsDrawn && !userOnCards

    /**
     * The corners an alert keeps out of ([OverlayLayout.alertBox]): the streamer's layers, plus the lower right while
     * a card's timers or chat hold the right-hand column ([rightColumnTaken]), so an alert never mirrors over them.
     */
    fun alertOccupied(layers: Set<Corner>, rightColumnTaken: Boolean): Set<Corner> =
        if (rightColumnTaken) layers + Corner.BOTTOM_RIGHT else layers
}

/**
 * When the card on screen went up, so its timers count from that moment. A different card, or Live in between,
 * starts again: "Back in 2:10" means from this BRB, not the last one. Feed it a monotonic clock
 * (SystemClock.elapsedRealtime), so a wall-clock change mid-stream can't jump a timer.
 */
class CardClock {
    private var card: Slates.Card? = null
    private var sinceMs = 0L

    /** Notes [now] card is [shown] (null = Live); returns how long it has been up, in ms (0 on Live). */
    fun update(shown: Slates.Card?, nowMs: Long): Long {
        if (shown != card) { card = shown; sinceMs = nowMs }
        return if (shown == null) 0L else (nowMs - sinceMs).coerceAtLeast(0L)
    }
}

/**
 * Where the live elements sit on a card, in px from the top left, sized in u like the card itself ([Slates.unit]).
 * One fixed spot each, chosen to clear the card's words and its own frame:
 * - the timer block, top right: its figures end on the card's 8u margin under the mark (whose foot is at 13u), the
 *   block's text starting at [TIMER_TOP_U], or lower when the streamer put a layer in the top right corner;
 * - chat: a column on the same right margin under the timers, ending at [chatFloor], which depends on where the
 *   card's words are (see there), and left out when fewer than two lines fit ([chatFits]);
 * - the socials line: left on the 8u margin, its baseline [SOCIALS_ABOVE_RULER_U] above the ruler, clear of the
 *   ruler's 3.6u cursor under it and of the audio footer below the ruler.
 * The card drawing keeps the timer block, the chat column and the socials row free in its other layouts.
 */
object CardZones {
    const val MARGIN_U = 8f
    const val TIMER_TOP_U = 16f
    /** Chat never goes below half the height, so a portrait card keeps its lower half for its words. */
    const val CHAT_FLOOR = 0.5f
    const val CHAT_MAX_W_U = 46f
    const val SOCIALS_ABOVE_RULER_U = 5.5f

    /** Clear space kept between the chat column and the card's words or picture. */
    const val WORDS_GAP_U = 2f

    /**
     * The automatic cards (Camera lost) draw a 20u glyph or picture from 18u and a rule at 42u (18u + 20u + 4u), then
     * the headline under it: chat ends 2u above the rule, so it can't reach the headline in either orientation.
     */
    const val AUTOMATIC_CHAT_FLOOR_U = 40f

    /** The automatic cards' hero: up to 40u wide (a picture at twice the glyph's 20u) from the 8u margin. */
    const val AUTOMATIC_HERO_RIGHT_U = 48f

    /**
     * The band at the top right that the layouts other than CLASSIC keep free (requested of card-layouts): chat stops
     * at its foot, whatever their words do below it.
     */
    const val TOP_BAND_FLOOR_U = 40f

    // CLASSIC's headline, mirrored from Slates.draw (private there): its last baseline 30u above the ruler, 17u down to
    // a 12u floor, two lines (three at the floor), lines 0.92 of the size apart.
    private const val CLASSIC_LAST_ABOVE_RULER_U = 30f
    private const val CLASSIC_START_U = 17f
    private const val CLASSIC_FLOOR_U = 12f
    private const val CLASSIC_LEAD = 0.92f
    /** Cap height over size, rounded up from Archivo ExtraCondensed's ~0.71, for the worst case with no font to hand. */
    private const val CAP_RATIO_MAX = 0.75f

    /** The right edge the timer figures and the chat column end on. */
    fun right(width: Int, height: Int): Float = width - MARGIN_U * Slates.unit(width, height)

    /** The left edge the socials line starts on. */
    fun left(width: Int, height: Int): Float = MARGIN_U * Slates.unit(width, height)

    /** The timer block's text top: [TIMER_TOP_U], or [gap] under [topRightBottom] (the streamer's top right layers). */
    fun timerTop(width: Int, height: Int, topRightBottom: Float?, gap: Float): Float =
        maxOf(TIMER_TOP_U * Slates.unit(width, height), if (topRightBottom != null) topRightBottom + gap else 0f)

    /**
     * Top of CLASSIC's headline cap, laid out as [lines] lines at [size] px whose cap height is [capHeight] px (all
     * from [Slates.fitLines] and the headline face).
     */
    fun classicHeadlineTop(width: Int, height: Int, lines: Int, size: Float, capHeight: Float): Float =
        Slates.rulerY(width, height) - CLASSIC_LAST_ABOVE_RULER_U * Slates.unit(width, height) -
            (lines.coerceAtLeast(1) - 1) * CLASSIC_LEAD * size - capHeight

    /**
     * The highest CLASSIC's headline can reach, whatever its words: two lines at 17u or three at the 12u floor
     * ([Slates.fitLines] never takes more), with a generous cap height. Used when the headline can't be measured.
     */
    fun classicHeadlineTopWorst(width: Int, height: Int): Float {
        val u = Slates.unit(width, height)
        val two = classicHeadlineTop(width, height, 2, CLASSIC_START_U * u, CAP_RATIO_MAX * CLASSIC_START_U * u)
        val three = classicHeadlineTop(width, height, 3, CLASSIC_FLOOR_U * u, CAP_RATIO_MAX * CLASSIC_FLOOR_U * u)
        return minOf(two, three)
    }

    /**
     * Chat lines never reach below this, so they never cover the card's words: half the height at most, and
     * - on the automatic cards (Camera lost), above the hero's rule ([AUTOMATIC_CHAT_FLOOR_U]); its headline sits
     *   under the rule and runs as wide as the card, so it would meet any column lower down;
     * - on a chosen or bookend card in CLASSIC, [WORDS_GAP_U] above its headline's top: [headlineTop] when it was
     *   measured, else the highest any headline can reach ([classicHeadlineTopWorst]). On a landscape card a long
     *   headline climbs to about 31u, which leaves no room, and the column is dropped ([chatFits]);
     * - in the other layouts, at the foot of the top band they keep free ([TOP_BAND_FLOOR_U]);
     * - over a web page (no words of ours), half the height.
     */
    fun chatFloor(width: Int, height: Int, look: CardLook, family: Slates.Family, headlineTop: Float?): Float {
        val u = Slates.unit(width, height)
        val half = height * CHAT_FLOOR
        val words = when {
            look.web -> half
            family == Slates.Family.AUTOMATIC -> AUTOMATIC_CHAT_FLOOR_U * u
            look.layout == CardLayout.CLASSIC -> (headlineTop ?: classicHeadlineTopWorst(width, height)) - WORDS_GAP_U * u
            else -> TOP_BAND_FLOOR_U * u
        }
        return minOf(half, words)
    }

    /** At least two one-line chat rows ([rowH] each, [lineGap] apart) fit between [top] and [floor]. */
    fun chatFits(top: Float, floor: Float, rowH: Float, lineGap: Float): Boolean = floor - top >= 2 * rowH + lineGap

    /**
     * The chat column's widest line: [CHAT_MAX_W_U], within the margins, and on the automatic cards clear of the
     * hero's picture, which reaches [AUTOMATIC_HERO_RIGHT_U] (a portrait card is only 100u wide).
     */
    fun chatMaxWidth(width: Int, height: Int, family: Slates.Family? = null): Float {
        val u = Slates.unit(width, height)
        val plain = minOf(CHAT_MAX_W_U * u, width - 2 * MARGIN_U * u)
        if (family != Slates.Family.AUTOMATIC) return plain
        return minOf(plain, right(width, height) - (AUTOMATIC_HERO_RIGHT_U + WORDS_GAP_U) * u).coerceAtLeast(0f)
    }

    /** The socials line's baseline. */
    fun socialsBaseline(width: Int, height: Int): Float =
        Slates.rulerY(width, height) - SOCIALS_ABOVE_RULER_U * Slates.unit(width, height)
}
