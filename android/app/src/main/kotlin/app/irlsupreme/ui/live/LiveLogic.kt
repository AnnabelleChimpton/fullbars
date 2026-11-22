package app.irlsupreme.ui.live

import app.irlsupreme.engine.AudioInput
import app.irlsupreme.engine.Facing
import app.irlsupreme.engine.Health
import app.irlsupreme.engine.LinkInfo
import app.irlsupreme.engine.Scene
import app.irlsupreme.ui.audioProblem
import app.irlsupreme.ui.theme.FbMotion
import java.util.Locale

/*
 * The Live screen's decisions, kept pure so the rules are unit-tested rather than eyeballed on a phone in the street.
 * The composables in this package only draw what these return.
 */

// --- Severity and motion -----------------------------------------------------------------------------------------

/**
 * How bad a health state is, for the direction of a pill change. Cards and Connecting are neutral: they are chosen or
 * expected, not trouble. Equal ranks are a neutral change (Weak to Hot is a swap, not news).
 */
internal fun severity(h: Health): Int = when (h) {
    Health.GREAT -> 0
    Health.OFF, Health.CONNECTING, Health.BRB -> 1
    Health.WEAK, Health.WEAK_SCREEN, Health.HOT -> 2
    Health.CAMERA_LOST -> 3
    Health.RECONNECTING, Health.FAILED -> 4
}

/** Which way a pill change goes: worse rolls the headline down and never springs. */
internal fun direction(prev: Health?, now: Health): FbMotion.Change {
    if (prev == null) return FbMotion.Change.NEUTRAL
    val a = severity(prev)
    val b = severity(now)
    return when {
        b > a -> FbMotion.Change.WORSE
        b < a -> FbMotion.Change.BETTER
        else -> FbMotion.Change.NEUTRAL
    }
}

/** The token the pill's size change uses from [prev] to [now]. "Bounce is a reward": worse is Enter or a cut. */
internal fun pillGrowth(prev: Health?, now: Health, reduced: Boolean): FbMotion.Token =
    FbMotion.growthFor(direction(prev, now), reduced)

// --- Link columns ----------------------------------------------------------------------------------------------------

/** What a link column shows. Only [WEAK] and [DOWN] carry a hue; a link at 0.0 or still connecting never does. */
internal enum class LinkState { CONNECTING, FINE, WEAK, DOWN }

/** One instrument column for a link (or, on a direct stream, the uplink). [value] is never "0.0". */
internal data class LinkCell(val label: String, val level: Int, val state: LinkState, val value: String, val spoken: String)

internal object LinkMeter {
    /** Below this a link is carrying nothing worth a number yet: it shows `···`, never 0.0. */
    const val IDLE_KBPS = 50L
    const val BARS = 4
    const val CONNECTING_VALUE = "···"
    const val DOWN_VALUE = "DOWN"

    /**
     * Bars for a link carrying [kbps] when the stream aims for [targetKbps] over [upLinks] links: four bars when it
     * carries its even share (target ÷ links up), stepping down by quarters of that share, never below one while it
     * carries anything. Even share rather than the whole target, so two healthy links both read full.
     */
    fun level(kbps: Long, targetKbps: Long, upLinks: Int): Int {
        if (kbps < IDLE_KBPS) return 0
        if (targetKbps <= 0) return BARS
        val share = targetKbps.toDouble() / upLinks.coerceAtLeast(1)
        return kotlin.math.ceil(kbps / share * BARS).toInt().coerceIn(1, BARS)
    }

    /** One decimal, Mb/s (the one unit), and never "0.0": anything that small is [CONNECTING_VALUE] instead. */
    fun mbps(kbps: Long): String = String.format(Locale.US, "%.1f", kbps / 1000.0)
}

/**
 * The link columns, in link order. [targetBps] is the encoder's target; [weak] is the announced Weak state (the
 * column turns amber in the same Tick as the pill); [connecting] is before the stream first went live (bonded links
 * are listed while still registering, so it wins over a link's own down report); [reconnecting] means every
 * link is down whatever its last report said. On a direct stream ([links] empty) one UPLINK column shows [sentBps].
 */
internal fun linkCells(
    links: List<LinkInfo>,
    targetBps: Int,
    sentBps: Long,
    weak: Boolean,
    connecting: Boolean,
    reconnecting: Boolean,
): List<LinkCell> {
    val targetKbps = targetBps / 1000L
    if (links.isEmpty()) {
        val kbps = sentBps / 1000
        return listOf(cell("UPLINK", "Uplink", kbps, targetKbps, 1, up = !reconnecting, connecting, weak))
    }
    val upCount = links.count { it.up && !reconnecting }
    val cells = links.map { l ->
        cell(l.name.uppercase(Locale.ROOT), l.name, l.kbps, targetKbps, upCount, up = l.up && !reconnecting, connecting, false)
    }
    if (!weak) return cells
    // Weak names the links pulling below half their share; if none is, the slowest one is the likeliest cause.
    val live = cells.withIndex().filter { it.value.state == LinkState.FINE }
    val low = live.filter { it.value.level <= 2 }.map { it.index }.toSet()
    val blamed = low.ifEmpty { setOfNotNull(live.minByOrNull { links[it.index].kbps }?.index) }
    return cells.mapIndexed { i, c -> if (i in blamed) c.copy(state = LinkState.WEAK, spoken = c.spoken + ", weak") else c }
}

private fun cell(
    label: String,
    name: String,
    kbps: Long,
    targetKbps: Long,
    upLinks: Int,
    up: Boolean,
    connecting: Boolean,
    weak: Boolean,
): LinkCell {
    val title = "$label Mb/s"
    return when {
        // Before the first lock every link is still registering: that's expected, so it reads ··· with no hue, never
        // coral DOWN under a quiet Connecting pill. DOWN only means a link was lost after the stream went live.
        connecting -> LinkCell(title, 0, LinkState.CONNECTING, LinkMeter.CONNECTING_VALUE, "$name connecting")
        !up -> LinkCell(title, 0, LinkState.DOWN, LinkMeter.DOWN_VALUE, "$name down")
        kbps < LinkMeter.IDLE_KBPS -> LinkCell(title, 0, LinkState.CONNECTING, LinkMeter.CONNECTING_VALUE, "$name connecting")
        else -> {
            val v = LinkMeter.mbps(kbps)
            LinkCell(
                title, LinkMeter.level(kbps, targetKbps, upLinks), if (weak) LinkState.WEAK else LinkState.FINE, v,
                "$name, $v megabits per second" + if (weak) ", weak" else "",
            )
        }
    }
}

/** Columns 2–3 show two links at a time; with 3+ links they page. */
internal const val LINKS_PER_PAGE = 2

internal fun linkPages(count: Int): Int = ((count + LINKS_PER_PAGE - 1) / LINKS_PER_PAGE).coerceAtLeast(1)

/** The two cells on [page] (null pads an empty column) and the mono index ("1/2"), or null when one page fits. */
internal fun linkPage(cells: List<LinkCell>, page: Int): Pair<List<LinkCell?>, String?> {
    val pages = linkPages(cells.size)
    val p = page.mod(pages)
    val slice = List(LINKS_PER_PAGE) { cells.getOrNull(p * LINKS_PER_PAGE + it) }
    return slice to if (pages > 1) "${p + 1}/$pages" else null
}

// --- MIC column ------------------------------------------------------------------------------------------------------

/**
 * What the MIC column shows, by priority: the user's mute, a mic that isn't delivering, else the live level. Heat and
 * a low battery aren't here (the pill says "Phone is hot"; the battery is a notice line), so the level you need while
 * you talk is never swapped out.
 */
internal sealed interface MicCell {
    /** The mic is on: the column draws its live level ([app.irlsupreme.engine.StreamEngine.micLevel]). */
    data object On : MicCell
    data object Muted : MicCell
    data object NoAudio : MicCell
}

/** Phone battery under this gets a notice line, once per 5% step down ([batteryStep]). */
internal const val LOW_BATTERY = 15

internal fun micCell(muted: Boolean, input: AudioInput): MicCell = when {
    muted -> MicCell.Muted
    audioProblem(input) != null -> MicCell.NoAudio
    else -> MicCell.On
}

/** Which 5% step a low battery is on (14..10 -> 2, 9..5 -> 1, 4..0 -> 0), or null while it isn't low. */
internal fun batteryStep(percent: Int?): Int? = if (percent != null && percent < LOW_BATTERY) percent / 5 else null

internal fun batteryNotice(percent: Int): String = "Phone battery $percent%"

// --- Timer -----------------------------------------------------------------------------------------------------------

internal const val TIMER_PENDING = "–:––:––"

internal fun timerLabel(liveSinceMs: Long, isTest: Boolean, preview: Boolean = false): String = when {
    preview -> "PREVIEW"
    liveSinceMs == 0L -> "CONNECTING"
    isTest -> "TEST"
    else -> "LIVE"
}

internal fun timerText(liveSinceMs: Long, nowMs: Long): String {
    if (liveSinceMs == 0L) return TIMER_PENDING
    val s = ((nowMs - liveSinceMs) / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
}

/** What TalkBack reads for the timer: whole minutes, so it changes once a minute rather than every second. */
internal fun timerSpoken(liveSinceMs: Long, nowMs: Long, isTest: Boolean): String {
    if (liveSinceMs == 0L) return "Not live yet"
    val m = ((nowMs - liveSinceMs) / 60_000).coerceAtLeast(0)
    return "${if (isTest) "Test" else "Live"} for $m ${if (m == 1L) "minute" else "minutes"}"
}

// --- Keys ------------------------------------------------------------------------------------------------------------

/** The glyph on a key, named so the key faces are testable without drawing. */
internal enum class KeyGlyph { X, STOP, LAYERS, MIC, MIC_OFF, CARD, CAMERA }

/** A key's printed face. State shows by inversion ([engaged]) plus a changed word; there is no hue and no LED. */
internal data class KeyFace(val label: String, val glyph: KeyGlyph, val engaged: Boolean = false, val band: Boolean = false)

/** Before the stream was ever live there's nothing to protect: one-tap CANCEL, no band. After lock-on, banded END. */
internal fun endKey(cancel: Boolean): KeyFace =
    if (cancel) KeyFace("CANCEL", KeyGlyph.X) else KeyFace("END", KeyGlyph.STOP, band = true)

internal fun scenesKey(open: Boolean): KeyFace = KeyFace("SCENES", KeyGlyph.LAYERS, engaged = open)

internal fun muteKey(muted: Boolean): KeyFace =
    if (muted) KeyFace("UNMUTE", KeyGlyph.MIC_OFF, engaged = true) else KeyFace("MUTE", KeyGlyph.MIC)

/** Any chosen card (BRB, Privacy, Starting soon, Ending) turns BRB into TO CAMERA, and a tap goes back to the camera. */
internal fun brbKey(scene: Scene): KeyFace =
    if (scene != Scene.LIVE) KeyFace("TO CAMERA", KeyGlyph.CAMERA, engaged = true) else KeyFace("BRB", KeyGlyph.CARD)

// --- Layout ----------------------------------------------------------------------------------------------------------

/** Rail sizes in dp. Fixed by position and job, never shrunk to fit. */
internal object Rail {
    const val END = 64f
    const val SCENES = 76f
    const val MUTE = 76f
    /** END is set apart from the rest. */
    const val END_GAP = 12f
    const val GAP = 8f
    /** No key is drawn narrower than this, whatever the screen. */
    const val MIN = 56f
    const val KEY_HEIGHT = 60f
    const val KEY_HEIGHT_BIG = 84f
    /** Landscape: the plate is a right-hand column this wide, with this side padding. */
    const val COLUMN = 96f
    const val COLUMN_PAD = 12f
    /** A key's face: side padding (tighter at large type), top and bottom padding, the glyph and the gap under it. */
    const val PAD = 4f
    const val PAD_BIG = 2f
    const val PAD_V = 6f
    const val GLYPH = 24f
    const val GLYPH_GAP = 4f
}

/**
 * Key widths [END, SCENES, MUTE, BRB] across [available] dp (the plate's inner width). BRB takes the rest: about
 * 144dp at 412dp, 92dp at 360dp. It never goes below [Rail.MIN]; if a very narrow screen can't fit that, the rail
 * scrolls rather than shrinking a key.
 *
 * [needs] is each key's widest single word plus its padding (see [keyNeeds]): a key grows to it so a word never breaks
 * mid-letter at large type (CANCEL at 200% is about 73dp of caps, wider than the 64dp END key). At normal type every
 * need is under the base width, so nothing changes there.
 */
internal fun railWidths(available: Float, needs: FloatArray = FloatArray(4)): FloatArray {
    val end = maxOf(Rail.END, needs[0])
    val scenes = maxOf(Rail.SCENES, needs[1])
    val mute = maxOf(Rail.MUTE, needs[2])
    val fixed = end + scenes + mute + Rail.END_GAP + Rail.GAP * 2
    return floatArrayOf(end, scenes, mute, (available - fixed).coerceAtLeast(maxOf(Rail.MIN, needs[3])))
}

/**
 * Every label each key can show, [END, SCENES, MUTE, BRB]. Sizes come from all of them rather than the face showing
 * now, so muting or going to BRB never changes the rail's size (the layout is chosen by scale, never by state).
 */
internal val KEY_LABELS: List<List<String>> = listOf(
    listOf(endKey(cancel = true).label, endKey(cancel = false).label),
    listOf(scenesKey(open = false).label, scenesKey(open = true).label),
    listOf(muteKey(muted = false).label, muteKey(muted = true).label),
    listOf(brbKey(Scene.LIVE).label, brbKey(Scene.BRB).label),
).map { it.distinct() }

/** The single words a key's labels can break into: the one thing that must fit across a key on one line. */
internal fun keyWords(labels: List<String>): List<String> = labels.flatMap { it.split(' ') }.filter { it.isNotEmpty() }.distinct()

/** A key's inner side padding: tighter at large type, so a whole word fits a 360dp rail at 200%. */
internal fun keyPad(big: Boolean): Float = if (big) Rail.PAD_BIG else Rail.PAD

/** The width a key needs for a word measuring [wordDp]: the word, both side paddings, and 1dp against rounding. */
internal fun keyNeed(wordDp: Float, big: Boolean): Float = kotlin.math.ceil(wordDp) + keyPad(big) * 2 + 1f

/** Font scale at which the plate switches to its large layout. Chosen by scale, never by state. */
internal const val BIG_TYPE_SCALE = 1.5f

internal fun bigType(fontScale: Float): Boolean = fontScale >= BIG_TYPE_SCALE

internal fun keyHeight(big: Boolean): Float = if (big) Rail.KEY_HEIGHT_BIG else Rail.KEY_HEIGHT

/**
 * The height all four keys share: the base height, or taller when the tallest label (a wrapped TO CAMERA at 200% is
 * two 26dp lines) needs it. The face is padding, the 24dp glyph, the gap and the label, so a wrapped label grows the
 * keys instead of being clipped. Every key grows together; none is ever drawn shorter than [keyHeight].
 */
internal fun railKeyHeight(big: Boolean, labelDp: Float): Float =
    maxOf(keyHeight(big), Rail.PAD_V * 2 + Rail.GLYPH + Rail.GLYPH_GAP + kotlin.math.ceil(labelDp) + 1f)

/** The landscape column: 96dp, wider at large type when a key's widest word needs it. */
internal fun railColumnWidth(needs: FloatArray): Float = maxOf(Rail.COLUMN, (needs.maxOrNull() ?: 0f) + Rail.COLUMN_PAD * 2)

internal fun noticeHeight(big: Boolean): Float = if (big) 56f else 32f

/**
 * The full slot a worse pill grows to: the width less the 12dp gutters, capped so it stays a pill on a tablet.
 * [reserveEndDp]: room kept clear at the right end of the pill's row (the flip button), so a full-slot pill never
 * runs under it.
 */
internal fun pillSlot(widthDp: Float, reserveEndDp: Float = 0f): Float = (widthDp - 24f - reserveEndDp).coerceIn(0f, 420f)

// --- Flip camera -----------------------------------------------------------------------------------------------------

/** The flip button's 48dp target plus the 8dp gap before it: what the pill's row gives up while it shows. */
internal const val FLIP_RESERVE_DP = 56f

/** The other phone camera. */
internal fun flipped(f: Facing): Facing = if (f == Facing.FRONT) Facing.BACK else Facing.FRONT

/** What TalkBack says after a flip to [f]: the side now in use, not the action. */
internal fun facingSpoken(f: Facing): String = if (f == Facing.FRONT) "Front camera" else "Back camera"

/**
 * The camera to switch to as a stream starts, or null to leave it: the "Start on" choice ([preferred]) when the phone
 * camera is what's being sent and isn't already on that side. The engine remembers the last stream's side, so without
 * this a stream after a mid-stream flip would start on whichever side you flipped to.
 */
internal fun startFacingChange(preferred: Facing, current: Facing, phoneCamera: Boolean): Facing? =
    if (phoneCamera && preferred != current) preferred else null

/**
 * "Once per stream" that survives the live screen being rebuilt mid-stream (the activity recreated, the app left and
 * reopened), so the start side is applied once and never undoes a flip you made since. A stream is told apart by
 * [claim]'s marker: the engine's last finished session, which is a new object once a stream has ended.
 */
internal class OncePerStream {
    private var claimed = false
    private var marker: Any? = null

    /** True the first time it's called for [marker] (by identity), false after that. */
    @Synchronized
    fun claim(marker: Any?): Boolean {
        if (claimed && this.marker === marker) return false
        claimed = true
        this.marker = marker
        return true
    }
}

// --- Notice slot -----------------------------------------------------------------------------------------------------

internal const val HOLD_HINT = "Hold END for 2 seconds to end the stream"
internal const val FIRST_MUTE_HINT = "Viewers can't hear you"
internal const val MIC_LOUD_HINT = "You're too loud. Tap MIC to turn it down."
/** How long the limiter must keep working hard before the notice says so: one shout isn't a problem. */
internal const val MIC_LOUD_NOTICE_MS = 2_500L

/** How long the timed notices (the hold hint, the first-mute line) stay up. */
internal const val NOTICE_MS = 2_500L

/**
 * The one line the notice slot shows, by priority: the hold hint, a mic that isn't delivering, the first
 * mute of the stream; then, below those three, what used to be status chips so nothing is lost: the camera link's
 * progress and a helper hotspot that couldn't start (both also in Details).
 */
internal fun notice(
    holdHint: Boolean,
    noAudio: String?,
    firstMute: Boolean,
    cameraLink: String?,
    helperProblem: String?,
    micLoud: Boolean = false,
    micInput: String? = null,
    pocketHint: Boolean = false,
    battery: String? = null,
): String? = when {
    holdHint -> HOLD_HINT
    noAudio != null -> noAudio
    micInput != null -> micInput
    micLoud -> MIC_LOUD_HINT
    firstMute -> FIRST_MUTE_HINT
    battery != null -> battery
    pocketHint -> POCKET_HINT
    cameraLink != null -> cameraLink
    helperProblem != null -> helperProblem
    else -> null
}

/** Offered once a stream, when the phone first warms up (thermal rung 1). */
internal const val POCKET_HINT = "Phone is warming up. Scenes › Pocket screen keeps it cooler."

/**
 * Whether to offer the Pocket screen for heat: the ladder has stepped down and heat is the reason (Android's status at
 * moderate or worse, or its forecast at 0.85 or more). A low battery alone also holds the first rung on a cool phone,
 * and "warming up" would not be true then.
 */
internal fun pocketHintDue(thermalRung: Int, thermalStatus: Int, thermalHeadroom: Float): Boolean =
    thermalRung >= 1 && (thermalStatus >= 2 || thermalHeadroom >= 0.85f)
/** How long the Pocket screen offer and a mic change stay in the notice line. */
internal const val LONG_NOTICE_MS = 6_000L

// --- Timer -----------------------------------------------------------------------------------------------------------

/**
 * How long until the timer's next whole second (counted from [liveSinceMs], or the clock's own seconds before going
 * live), plus a few ms so the redraw lands just after it turns, never just before.
 */
internal fun msToNextSecond(liveSinceMs: Long, nowMs: Long): Long {
    val into = Math.floorMod(nowMs - liveSinceMs, 1_000L)
    return 1_000L - into + TIMER_SLACK_MS
}

private const val TIMER_SLACK_MS = 5L

// --- Pocket screen ---------------------------------------------------------------------------------------------------

/**
 * Where the Pocket screen's small health line sits in minute [minute], as fractions of the free width and height: it
 * moves every minute so an hour in one place never marks an OLED screen, and never sits where it was the minute before.
 */
internal fun pocketSpot(minute: Long): Pair<Float, Float> = POCKET_SPOTS[Math.floorMod(minute, POCKET_SPOTS.size.toLong()).toInt()]

private val POCKET_SPOTS = listOf(0.1f to 0.15f, 0.7f to 0.4f, 0.25f to 0.75f, 0.8f to 0.85f, 0.45f to 0.3f, 0.05f to 0.55f)

/** The Pocket screen shows these at full brightness: trouble the streamer must notice, or the stream ending. */
internal fun pocketAlarm(health: Health, ending: Boolean): Boolean =
    ending || health == Health.RECONNECTING || health == Health.FAILED || health == Health.CAMERA_LOST || health == Health.OFF

// --- External camera -------------------------------------------------------------------------------------------------

/**
 * The notice while the phone camera stands in for an external one, from the engine's camera line ([externalCamera])
 * and what's on screen; null while the external camera is on screen or there isn't one.
 */
internal fun externalCameraNotice(externalCamera: String?, activeSource: String): String? = when {
    externalCamera == null || activeSource != "phone" -> null
    externalCamera.startsWith("waiting") -> "Phone camera on air · waiting for the camera"
    externalCamera == "connected" -> "Phone camera on air · camera connecting"
    externalCamera == "connected, no video yet" -> "Phone camera on air · camera connected, no video yet"
    externalCamera.startsWith("camera sends") -> "Phone camera on air · ${externalCamera.replaceFirst("camera sends", "the camera sends")}"
    externalCamera.startsWith("can't listen") -> "Phone camera on air · $externalCamera"
    else -> "Phone camera on air · camera lost"
}

/**
 * The Pocket 3's line in the notice slot while it starts, leading with what viewers get. A step that needs the
 * streamer (tap OK on the Pocket 3) shows in full with its "n of 5"; so does every step in preview, where there's time
 * to watch them. Live, it's short: on the camera scene it says the phone camera is on air meanwhile, on a card just
 * the Pocket's state. The long reason for a failure is in Details (tap the pill).
 */
internal fun pocketNotice(pocket: app.irlsupreme.dji.PocketState, preview: Boolean, onCamera: Boolean): String {
    val failing = pocket is app.irlsupreme.dji.PocketState.Failed || pocket is app.irlsupreme.dji.PocketState.Reconnecting
    if (pocket is app.irlsupreme.dji.PocketState.ApproveOnCamera) return pocket.label
    if (preview) return if (failing) "Pocket 3 not ready: you'd go live on the phone camera" else pocket.label
    val short = when (pocket) {
        is app.irlsupreme.dji.PocketState.Failed -> "Pocket 3 didn't start"
        is app.irlsupreme.dji.PocketState.Reconnecting -> "Pocket 3 not connected, retrying"
        app.irlsupreme.dji.PocketState.Stopped -> "Pocket 3 stopped"
        else -> "Pocket 3 connecting"
    }
    return if (onCamera) "Phone camera on air · $short" else short
}

// --- Hold to end -----------------------------------------------------------------------------------------------------

/**
 * One press on END, timed by the clock. The composable feeds it frame times from withFrameNanos and a
 * parallel delay(2000) guard; nothing here reads the animator scale, so "Remove animations" can't turn the hold into a
 * tap the way `animateTo` with a 0× duration scale did. [reducedMotion] only changes how the ticks drain on release.
 */
internal class HoldSession(private val startNanos: Long, val reducedMotion: Boolean = false) {
    /** Ticks lit so far, 0..[TICKS]. */
    var lit: Int = 0
        private set
    var done: Boolean = false
        private set

    /** Progress 0..1 at [nowNanos]. */
    fun progress(nowNanos: Long): Float = ((nowNanos - startNanos) / HOLD_NANOS.toDouble()).toFloat().coerceIn(0f, 1f)

    /**
     * Advances to [nowNanos]; returns the ticks that lit since the last frame (1..8, in order). The 8th is the end:
     * [done] turns true with it and never before [HOLD_MS] of clock time.
     */
    fun onFrame(nowNanos: Long): List<Int> {
        if (done) return emptyList()
        val elapsedMs = (nowNanos - startNanos) / 1_000_000
        val target = (elapsedMs / TICK_MS).toInt().coerceIn(0, TICKS)
        if (target <= lit) return emptyList()
        val newly = (lit + 1..target).toList()
        lit = target
        if (lit == TICKS) done = true
        return newly
    }

    /** The delay(2000) guard fired: end now if the frame loop hasn't. Returns the ticks this lit. */
    fun onGuard(): List<Int> {
        if (done) return emptyList()
        val newly = (lit + 1..TICKS).toList()
        lit = TICKS
        done = true
        return newly
    }

    companion object {
        const val HOLD_MS = 2_000L
        const val TICKS = 8
        const val TICK_MS = HOLD_MS / TICKS
        private const val HOLD_NANOS = HOLD_MS * 1_000_000
    }
}

// --- Alerts ----------------------------------------------------------------------------------------------------------

/** An alert announcement waits this long after any assertive health announcement, so it never talks over a loss. */
internal const val ALERT_AFTER_ASSERTIVE_MS = 1_500L

/** How long an alert announcement must still wait at [nowMs] (0: speak now). */
internal fun alertWaitMs(nowMs: Long, lastAssertiveAtMs: Long): Long =
    if (lastAssertiveAtMs == 0L) 0L else (lastAssertiveAtMs + ALERT_AFTER_ASSERTIVE_MS - nowMs).coerceAtLeast(0L)

