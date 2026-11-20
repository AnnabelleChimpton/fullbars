package app.irlsupreme.service

import app.irlsupreme.engine.CameraPhase
import app.irlsupreme.engine.ConnectionPhase
import app.irlsupreme.engine.EngineState
import app.irlsupreme.engine.Scene
import app.irlsupreme.engine.audio.AlertSounds
import app.irlsupreme.engine.video.AlertTier
import app.irlsupreme.twitch.TwitchAlert
import app.irlsupreme.ui.theme.FbMotion
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * An alert as drawn on the stream: a caps [label] noun (`RAID`, never "Raid!"), the [name] of who did it,
 * and an optional [amount] in mono (`$5.00`, `5 subs`, `230`). [amountUnit] is the word drawn after a raid's count in
 * Archivo ("viewers"), kept apart because the count is a figure and the word isn't.
 */
data class AlertCard(
    val id: String,
    val label: String,
    val name: String,
    val amount: String?,
    val style: AlertSounds.Style,
    val amountUnit: String? = null,
    /** Which kind this is, whatever its (possibly custom) label says: for what TalkBack hears. */
    val kind: AlertKind = AlertKind.FOLLOW,
    /** A resub (months > 1), for the spoken sentence. */
    val resub: Boolean = false,
    /** The streamer's own message with {name} and {amount} filled in; shown in place of the name. */
    val message: String? = null,
    /** The streamer's picture for this kind, drawn at the card's left. */
    val picture: android.graphics.Bitmap? = null,
) {
    /** Loudness follows the chime, so what viewers see and hear always agree. */
    val tier: AlertTier get() = AlertTier.of(style)

    /** How long it stays fully on screen, after the wipe-in and before the wipe-out. */
    val dwellMs: Long get() = AlertTiming.dwellMs(tier)

    /** The Live screen's polite announcement for TalkBack: plain words, read once when the alert lands. */
    val spoken: String get() = AlertText.spoken(this)
}

/** Words for each kind of Twitch alert. Pure, for tests. */
object AlertText {
    /**
     * [a] as a card, with the streamer's own label, message and picture for its kind ([AlertStyles]). A message that
     * shows {amount} itself takes the amount off the card's own amount line, so it's never shown twice.
     */
    fun card(a: TwitchAlert, look: AlertLook = AlertStyles.look(AlertKind.of(a))): AlertCard {
        val base = baseCard(a)
        val amountText = listOfNotNull(base.amount, base.amountUnit).joinToString(" ").ifEmpty { null }
        val message = look.message?.let { fillAlertTemplate(it, base.name, amountText) }?.ifEmpty { null }
        val amountInMessage = message != null && templateHasAmount(look.message)
        return base.copy(
            kind = AlertKind.of(a),
            label = look.label?.uppercase() ?: base.label,
            message = message,
            picture = look.picture,
            amount = if (amountInMessage) null else base.amount,
            amountUnit = if (amountInMessage) null else base.amountUnit,
        )
    }

    private fun baseCard(a: TwitchAlert): AlertCard = when (a) {
        is TwitchAlert.Follow -> AlertCard(a.id, "FOLLOW", a.user, null, AlertSounds.Style.SOFT)
        is TwitchAlert.Sub -> {
            val resub = (a.months ?: 0) > 1
            AlertCard(
                a.id, if (resub) "RESUB" else "SUB", a.user,
                listOfNotNull(
                    a.months?.takeIf { resub }?.let { "$it months" },
                    if (a.prime) "Prime" else tierName(a.tier),
                ).joinToString(" · ").ifEmpty { null },
                AlertSounds.Style.BRIGHT,
                resub = resub,
            )
        }
        is TwitchAlert.GiftSubs -> AlertCard(a.id, "GIFT SUBS", a.user, plural(a.count, "sub"), AlertSounds.Style.BRIGHT)
        is TwitchAlert.Raid -> AlertCard(
            a.id, "RAID", a.user, "${a.viewers}", AlertSounds.Style.BIG,
            amountUnit = if (a.viewers == 1) "viewer" else "viewers",
        )
        is TwitchAlert.Cheer -> AlertCard(a.id, "CHEER", a.user, plural(a.bits, "bit"), AlertSounds.Style.BRIGHT)
        // The tip's message isn't shown: anyone can type anything, and it would be on stream unmoderated.
        is TwitchAlert.Tip -> AlertCard(a.id, "TIP", a.user, a.amount.trim().ifEmpty { null }, AlertSounds.Style.BRIGHT)
    }

    /**
     * What TalkBack says when the alert lands. The caps label is for the eye; a screen reader gets a sentence
     * ("Raid from BigStreamer, 230 viewers"), so it never spells out "R A I D".
     */
    fun spoken(c: AlertCard): String {
        // A screen reader may read "·" aloud; a comma is a pause.
        val amount = listOfNotNull(c.amount, c.amountUnit).joinToString(" ").replace(" · ", ", ").ifEmpty { null }
        // By kind, not by label: a custom label ("NEW CREW") still reads as what it is.
        val lead = when (c.kind) {
            AlertKind.FOLLOW -> return "New follower, ${c.name}" + (c.message?.let { ". $it" } ?: "")
            AlertKind.SUB -> if (c.resub) "Resub from ${c.name}" else "New sub from ${c.name}"
            AlertKind.GIFT -> "Gift subs from ${c.name}"
            AlertKind.BITS -> "Cheer from ${c.name}"
            AlertKind.TIP -> "Tip from ${c.name}"
            AlertKind.RAID -> "Raid from ${c.name}"
        }
        val said = if (amount != null) "$lead, $amount" else lead
        return c.message?.let { "$said. $it" } ?: said
    }

    private fun plural(n: Int, word: String) = "$n ${if (n == 1) word else word + "s"}"

    /** Tier 1 is the usual sub, so it isn't spelled out. */
    private fun tierName(tier: String): String? = when (tier) {
        "2000" -> "Tier 2"
        "3000" -> "Tier 3"
        else -> null
    }
}

/**
 * Alert timing on stream. Pure, for tests. The wipe durations are the app's Enter and Exit tokens, so an
 * alert lands with the same feel as everything else; the phone's own reduce-motion setting never changes what viewers
 * see, only the thermal ladder does ([cut]).
 */
object AlertTiming {
    /** A follow wipes in at the Exit token's length (with the Enter curve): quick, because it's the quiet one. */
    const val QUIET_ENTER_MS = FbMotion.EXIT_MS
    const val ENTER_MS = FbMotion.ENTER_MS
    const val EXIT_MS = FbMotion.EXIT_MS
    /** Between one alert's exit and the next one's entry. */
    const val GAP_MS = 400L
    /** How often a held alert checks whether it may show yet. */
    const val HOLD_POLL_MS = 250L
    /** Thermal rung at which alerts cut in and out instead of wiping (Compositor.kt: the hot rung). */
    const val CUT_AT_THERMAL_RUNG = 2

    fun dwellMs(tier: AlertTier): Long = when (tier) {
        AlertTier.QUIET -> 4_000L
        AlertTier.MEDIUM -> 6_000L
        AlertTier.LOUD -> 7_200L
    }

    fun enterMs(tier: AlertTier, cut: Boolean): Long = when {
        cut -> 0L
        tier == AlertTier.QUIET -> QUIET_ENTER_MS.toLong()
        else -> ENTER_MS.toLong()
    }

    fun exitMs(cut: Boolean): Long = if (cut) 0L else EXIT_MS.toLong()

    fun cut(thermalRung: Int) = thermalRung >= CUT_AT_THERMAL_RUNG

    /**
     * When to start the chime so its attack peaks on the first fully revealed frame: the wipe's length less the
     * chime's own attack, never negative (a cut has no wipe, so the chime starts at once).
     */
    fun chimeDelayMs(enterMs: Long, attackMs: Int): Long = (enterMs - attackMs).coerceAtLeast(0L)
}

/**
 * Which scenes an alert may land on. Shown over Live and the chosen cards (BRB, Starting soon, Ending);
 * held, chime included, while viewers see Privacy (it promises stillness), the Weak signal or Camera lost card (both
 * must stay cheap and calm), or nothing at all (not connected: the server is showing its Signal lost card, or there's
 * no stream yet). The engine's own `alertsAllowed` says the same for what it draws; the service checks both, so an
 * alert is never spent where nobody can see it.
 */
object AlertHold {
    fun allowed(st: EngineState): Boolean = allowed(st.scene, st.weakSignal, st.camera, st.connection)

    fun allowed(scene: Scene, weakSignal: Boolean, camera: CameraPhase, connection: ConnectionPhase): Boolean = when {
        connection != ConnectionPhase.LIVE -> false
        scene == Scene.PRIVACY -> false
        // The automatic cards only stand in for Live; a chosen card covers them.
        scene == Scene.LIVE && (weakSignal || camera == CameraPhase.LOST) -> false
        else -> true
    }

    /** The chosen full-frame cards, where an alert sits higher (30%) to clear the headline. */
    fun onCard(scene: Scene): Boolean = scene == Scene.BRB || scene == Scene.STARTING_SOON || scene == Scene.ENDING
}

/**
 * The newest [max] alerts waiting their turn. Held alerts (while Privacy, Weak signal or Camera lost shows) wait here
 * in order, and a burst while held drops the oldest, so what finally plays is the most recent. Thread-safe; pure, for
 * tests.
 */
class AlertBacklog<T>(private val max: Int) {
    private val items = ArrayDeque<T>()

    @Synchronized fun add(item: T) {
        items.addLast(item)
        while (items.size > max) items.removeFirst()
    }

    @Synchronized fun peek(): T? = items.firstOrNull()
    @Synchronized fun poll(): T? = items.removeFirstOrNull()
    @Synchronized fun clear() = items.clear()
    @get:Synchronized val size: Int get() = items.size
}

/**
 * Alerts waiting to be shown, one at a time. Fed by Twitch while live (and by debug intents); shown by
 * [StreamService], which hands each to the engine's alert layer and mixes the chime into the stream. A burst (a raid
 * with many follows) keeps the newest [MAX_WAITING].
 */
object AlertQueue {
    const val MAX_WAITING = 20

    private val waiting = AlertBacklog<TwitchAlert>(MAX_WAITING)
    /** Wakes the alert loop when something arrives; conflated, so a burst is one wake-up and none is ever lost. */
    private val posted = Channel<Unit>(Channel.CONFLATED)
    private val _current = MutableStateFlow<AlertCard?>(null)

    /**
     * The alert fully on screen right now (null between alerts), set on its first fully revealed frame: the Live
     * screen announces it politely from here, after any assertive health announcement.
     */
    val current: StateFlow<AlertCard?> get() = _current

    fun post(a: TwitchAlert) {
        waiting.add(a)
        posted.trySend(Unit)
    }

    internal fun peek(): TwitchAlert? = waiting.peek()
    internal fun poll(): TwitchAlert? = waiting.poll()
    internal suspend fun awaitPosted() { posted.receive() }
    internal fun show(card: AlertCard?) { _current.value = card }

    /** Drops what's waiting (a new stream starts fresh). */
    fun clear() {
        waiting.clear()
        while (posted.tryReceive().isSuccess) Unit
        _current.value = null
    }

    /** StreamElements / Streamlabs refused their token this stream (shown in Settings › Overlays). */
    val tipProblems = MutableStateFlow<Set<String>>(emptySet())
}
