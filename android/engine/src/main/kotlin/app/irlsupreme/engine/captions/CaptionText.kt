package app.irlsupreme.engine.captions

/**
 * What the caption strip says, from the recogniser's results. Pure (the clock and the text measure are passed in),
 * so the rules are unit tested.
 *
 * The recogniser sends a growing guess for what's being said ([partial], revised as it hears more) and, at a pause,
 * the finished words of that stretch ([segment]); with no pause, one guess can run on for half a minute. The strip
 * shows the newest words, wrapped to [lines] the newest last, like a roll-up caption: older lines move up and out.
 *
 * After [clearAfterMs] with nothing new (a pause in talking), the strip clears, and those words don't come back when
 * the talking resumes: only the words said since show.
 */
class CaptionText(private val clearAfterMs: Long = CLEAR_AFTER_MS) {
    /** Words of finished stretches, newest last (only the recent ones are kept). */
    private val committed = ArrayList<String>()
    /** The current guess's words. */
    private var current: List<String> = emptyList()
    /** Words of committed + current that are no longer shown (cleared after a pause, or by [clear]). */
    private var hidden = 0
    private var lastChangeMs = 0L

    private val total: Int get() = committed.size + current.size

    /** The recogniser's latest guess for the stretch being said. Returns true if the shown words changed. */
    fun partial(text: String, nowMs: Long): Boolean {
        val words = split(text)
        if (words == current) return false
        current = words
        return touched(nowMs)
    }

    /** A finished stretch: its words replace the guess. Returns true if the shown words changed. */
    fun segment(text: String, nowMs: Long): Boolean {
        val before = shown()
        committed += split(text)
        current = emptyList()
        if (committed.size > KEEP_WORDS) {
            val drop = committed.size - KEEP_WORDS
            committed.subList(0, drop).clear()
            hidden = (hidden - drop).coerceAtLeast(0)
        }
        lastChangeMs = nowMs
        return shown() != before
    }

    /** Clears the strip now (the mic was muted); what was said before doesn't come back. */
    fun clear(): Boolean {
        val had = shown().isNotEmpty()
        hidden = total
        return had
    }

    /** Clears the strip after a pause. Returns true if that changed it. */
    fun tick(nowMs: Long): Boolean = if (shown().isNotEmpty() && nowMs - lastChangeMs >= clearAfterMs) clear() else false

    /** The words on the strip now, oldest first. */
    fun shown(): List<String> {
        val all = committed + current
        return if (hidden >= all.size) emptyList() else all.subList(hidden, all.size)
    }

    /** The strip's lines: [shown] wrapped to [maxWidth] by [measure], at most [maxLines], the newest last. */
    fun lines(maxLines: Int, maxWidth: Float, measure: (String) -> Float): List<String> =
        wrap(shown(), maxWidth, measure).takeLast(maxLines)

    private fun touched(nowMs: Long): Boolean {
        // A guess that shrank below what's hidden (the recogniser took words back) shows nothing new yet.
        if (hidden > total) hidden = total
        lastChangeMs = nowMs
        return true
    }

    companion object {
        /** A pause this long clears the strip. */
        const val CLEAR_AFTER_MS = 4_000L
        /** Finished words kept (far more than two lines ever show). */
        private const val KEEP_WORDS = 200

        private fun split(text: String): List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

        /** Greedy word wrap; a word wider than a line gets a line of its own (the drawing shrinks it). */
        fun wrap(words: List<String>, maxWidth: Float, measure: (String) -> Float): List<String> {
            val lines = ArrayList<String>()
            val line = StringBuilder()
            for (w in words) {
                if (line.isEmpty()) { line.append(w); continue }
                val candidate = "$line $w"
                if (measure(candidate) <= maxWidth) line.append(' ').append(w)
                else { lines += line.toString(); line.setLength(0); line.append(w) }
            }
            if (line.isNotEmpty()) lines += line.toString()
            return lines
        }
    }
}
