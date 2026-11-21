package app.irlsupreme.service

import app.irlsupreme.twitch.TwitchAlert

/**
 * The streamer's label and message with {placeholders} filled in for one alert. Pure, for tests.
 *
 * {name} works everywhere; {amount} is the amount as the card shows it ("500 bits", "$5.00", "5 subs"); {months} a
 * sub's months (1 for a new sub); {viewers} a raid's viewers; {count} how many subs were gifted; {message} what the
 * viewer wrote with a sub or a cheer. Names are any case. A placeholder that's unknown, or has nothing to fill it for
 * this alert, goes, along with the space it leaves; viewers' own words are never read for placeholders.
 */
object AlertTemplate {
    /** The longest viewer message put in a card; the drawing cuts it further to fit. */
    const val MAX_VIEWER_MESSAGE = 200

    /** The values one alert offers, by lower-case placeholder name. [amount] is the card's amount line. */
    fun values(a: TwitchAlert, amount: String?): Map<String, String> = buildMap {
        put("name", clean(a.user))
        amount?.let { put("amount", it) }
        when (a) {
            is TwitchAlert.Sub -> {
                put("months", "${a.months ?: 1}")
                a.message?.let { put("message", clean(it).take(MAX_VIEWER_MESSAGE)) }
            }
            is TwitchAlert.Cheer -> a.message?.let { put("message", clean(it).take(MAX_VIEWER_MESSAGE)) }
            is TwitchAlert.Raid -> put("viewers", "${a.viewers}")
            is TwitchAlert.GiftSubs -> put("count", "${a.count}")
            is TwitchAlert.Follow, is TwitchAlert.Tip -> Unit
        }
    }.filterValues { it.isNotBlank() }

    /**
     * [template] filled from [values]. Only the gaps left by placeholders with nothing to fill them are tidied: the
     * space a gap leaves, a space it leaves before punctuation, "()" around it, and a separator left dangling at
     * either end or doubled. Everything else, the streamer's text and the viewer's words, stays exactly as written.
     */
    fun fill(template: String, values: Map<String, String>): String {
        // One pass, so a value that looks like a placeholder stays as written.
        val parts = ArrayList<Part>()
        var at = 0
        for (m in PLACEHOLDER.findAll(template)) {
            if (m.range.first > at) parts += Part(template.substring(at, m.range.first), Kind.TEXT)
            val v = values[m.groupValues[1].lowercase()].orEmpty()
            parts += Part(v, if (v.isEmpty()) Kind.GAP else Kind.VALUE)
            at = m.range.last + 1
        }
        if (at < template.length) parts += Part(template.substring(at), Kind.TEXT)

        val out = StringBuilder()
        // The end of the last value in [out]: tidying never reaches back past it into what a viewer wrote.
        var keep = 0
        for ((i, p) in parts.withIndex()) {
            when (p.kind) {
                Kind.TEXT -> out.append(p.text)
                Kind.VALUE -> { out.append(p.text); keep = out.length }
                Kind.GAP -> {
                    val rest = parts.subList(i + 1, parts.size)
                    if (rest.all { it.kind == Kind.GAP || (it.kind == Kind.TEXT && it.text.isBlank()) }) {
                        // Nothing follows: no space or separator dangling at the end.
                        trimEnd(out, keep) { it.isWhitespace() || it in SEPARATORS }
                        for (r in rest) r.text = ""
                    } else {
                        val next = rest.first().takeIf { it.kind == Kind.TEXT } ?: continue
                        next.text = tidyAfterGap(out, keep, next.text)
                    }
                }
            }
        }
        return out.toString().trim()
    }

    /** Tidies where a gap meets the text after it, [next]; returns what's left of [next]. [out] ends at the gap. */
    private fun tidyAfterGap(out: StringBuilder, keep: Int, next: String): String {
        if (out.isBlank()) {
            // Nothing before: no separator dangling at the start ("{message} · {name}" without a message).
            out.setLength(0)
            return next.replace(LEADING_SEPARATOR, "")
        }
        val endsWithSpace = out.length > keep && out.last().isWhitespace()
        // "({message})" with nothing in it goes, with the space before it.
        if (EMPTY_OPEN.containsMatchIn(out.substring(keep)) && EMPTY_CLOSE.containsMatchIn(next)) {
            trimEnd(out, keep) { it.isWhitespace() }
            out.setLength(out.length - 1)
            trimEnd(out, keep) { it.isWhitespace() }
            return next.replace(EMPTY_CLOSE, "")
        }
        if (endsWithSpace && next.firstOrNull()?.isWhitespace() == true) {
            // The gap's own space goes, and a separator doubled by it: "{name} · {message} · {months}".
            var t = next.trimStart()
            val before = out.trimEnd().lastOrNull()
            if (before != null && before in SEPARATORS) t = t.replace(LEADING_SEPARATOR, "")
            return t
        }
        // "Thanks {name}!" without a name: no space before the punctuation.
        if (endsWithSpace && next.firstOrNull()?.let { it in CLOSING } == true) trimEnd(out, keep) { it.isWhitespace() }
        return next
    }

    private fun trimEnd(sb: StringBuilder, keep: Int, drop: (Char) -> Boolean) {
        var n = sb.length
        while (n > keep && drop(sb[n - 1])) n--
        sb.setLength(n)
    }

    private enum class Kind { TEXT, VALUE, GAP }
    private class Part(var text: String, val kind: Kind)

    /** Whether [template] shows the alert's amount in some form, so the card doesn't show it twice. */
    fun showsAmount(template: String?, kind: AlertKind): Boolean {
        if (template == null) return false
        val names = PLACEHOLDER.findAll(template).map { it.groupValues[1].lowercase() }.toSet()
        return "amount" in names || when (kind) {
            AlertKind.SUB -> "months" in names
            AlertKind.RAID -> "viewers" in names
            AlertKind.GIFT -> "count" in names
            else -> false
        }
    }

    /** Viewer text on one line: no control characters or line breaks. */
    private fun clean(s: String): String = s.replace(Regex("\\s+"), " ").filter { !it.isISOControl() }.trim()

    private val PLACEHOLDER = Regex("\\{\\s*([A-Za-z]+)\\s*\\}")
    /** Separators left at either end when what they separated was empty: "{message} · {name}" without a message. */
    private const val SEPARATORS = "-–—·|:,;"
    /** Punctuation that sits right after a word, never after a space. */
    private const val CLOSING = ",.!?;:)"
    /** A separator and the space around it at the start, only when it stands alone (":)" is a smiley, not one). */
    private val LEADING_SEPARATOR = Regex("^\\s*(?:[-–—·|:,;]+(?=\\s|$))?\\s*")
    private val EMPTY_OPEN = Regex("\\(\\s*$")
    private val EMPTY_CLOSE = Regex("^\\s*\\)")
}
