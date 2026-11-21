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

    /** [template] filled from [values], tidied: no doubled spaces, no space before punctuation, nothing dangling. */
    fun fill(template: String, values: Map<String, String>): String {
        // One pass, so a value that looks like a placeholder stays as written.
        val filled = PLACEHOLDER.replace(template) { m -> values[m.groupValues[1].lowercase()] ?: "" }
        return filled
            .replace(Regex("\\s+"), " ")
            .replace(Regex(" ([,.!?;:)])"), "$1")
            .replace(Regex("\\( ?\\)"), "")
            .replace(Regex(" {2,}"), " ")
            .trim()
            .trim { it in DANGLING }
            .trim()
    }

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
    private const val DANGLING = "-–—·|:,; "
}
