package app.irlsupreme.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TtsSource(val word: String) { POINTS("points"), ALERT("alert"), TEST("test") }

/**
 * A viewer's message to speak on stream. [redemptionId] and [rewardId]: a channel points one, which can be refunded
 * (Twitch's CANCELED) when it isn't spoken. [user] is shown to the streamer only, never logged.
 */
data class TtsRequest(
    val id: String,
    val source: TtsSource,
    val user: String,
    val text: String,
    val redemptionId: String? = null,
    val rewardId: String? = null,
    /** An alert's line: said while that alert's card ([AlertCard.id]) is up rather than after it. */
    val alertId: String? = null,
    /** Seconds to wait before it's said, in place of the streamer's wait; null uses theirs. */
    val waitS: Int? = null,
    /** Milliseconds to wait at least (a thank-you lets its alert's chime land first). */
    val notBeforeMs: Long = 0,
)

/** What the live screen shows about TTS: who's up, and whether it's still waiting ([speakAtMs], wall clock) or speaking. */
data class TtsNow(val user: String, val speaking: Boolean, val speakAtMs: Long, val waiting: Int)

/**
 * Requests waiting their turn, oldest first, at most [max]: past that the oldest goes, handed to [onDropped] for its
 * refund. Thread-safe. Pure (no Android), for tests.
 */
class TtsBacklog(private val max: Int, private val onDropped: (TtsRequest) -> Unit) {
    private val items = ArrayDeque<TtsRequest>()

    fun post(r: TtsRequest) {
        val dropped = synchronized(this) {
            if (items.any { it.id == r.id }) return // Twitch can deliver an event twice
            items.addLast(r)
            if (items.size > max) items.removeFirst() else null
        }
        dropped?.let(onDropped)
    }

    @Synchronized fun next(): TtsRequest? = items.removeFirstOrNull()

    @Synchronized fun size(): Int = items.size

    /** Empties it, returning what was waiting (to refund). */
    @Synchronized fun clear(): List<TtsRequest> = items.toList().also { items.clear() }
}

/**
 * Viewer TTS between the Twitch feed, the stream service's player and the live screen. A skip ([skip]) is counted, not
 * flagged, so the player can tell a new skip from one it already acted on.
 */
object TtsQueue {
    const val MAX_WAITING = 10

    @Volatile private var dropped: (TtsRequest) -> Unit = {}
    private val backlog = TtsBacklog(MAX_WAITING) { dropped(it) }
    private val posted = MutableStateFlow(0)
    private val _now = MutableStateFlow<TtsNow?>(null)
    private val _skips = MutableStateFlow(0)

    /** Who's up, for the live screen; null when nothing is. */
    val now: StateFlow<TtsNow?> = _now.asStateFlow()

    /** Bumped by every skip. */
    val skips: StateFlow<Int> = _skips.asStateFlow()

    /** Bumped by every post, so the player can wait for one. */
    val postedCount: StateFlow<Int> = posted.asStateFlow()

    /** Where a request pushed out by a full queue goes (its refund). Set by the stream service. */
    fun onDropped(f: (TtsRequest) -> Unit) { dropped = f }

    fun post(r: TtsRequest) {
        backlog.post(r)
        posted.value++
        _now.value?.let { _now.value = it.copy(waiting = backlog.size()) }
    }

    internal fun next(): TtsRequest? = backlog.next()

    internal fun waiting(): Int = backlog.size()

    internal fun show(n: TtsNow?) { _now.value = n }

    /** Stops the one speaking or waiting to speak. The player clears the queue too if the streamer chose that. */
    fun skip() { _skips.value++ }

    /** Empties the queue, returning what was waiting (to refund). */
    internal fun clear(): List<TtsRequest> = backlog.clear()
}
