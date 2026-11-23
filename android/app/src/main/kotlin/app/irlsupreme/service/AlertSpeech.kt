package app.irlsupreme.service

import app.irlsupreme.twitch.TwitchAlert

/**
 * What viewer TTS says for an alert ([AlertSay]): a thank-you in the streamer's voice of choice (the diva), and for
 * [AlertSay.MESSAGE] the words that came with it. Pure, for tests.
 *
 * The viewer's parts go through the same filters as any TTS message ([TtsText.isBlocked]): a name with a blocked word
 * is said as "someone", and a message with one is left out (the thank-you is still said). Bits can't be given back,
 * so dropping the whole thing would only punish the streamer.
 */
object AlertSpeech {
    /** The line for [a], or null when [say] is off. [blocked]: the streamer's own words and Twitch's blocked terms. */
    fun line(a: TwitchAlert, say: AlertSay, blocked: Collection<String> = emptyList()): String? {
        if (say == AlertSay.OFF) return null
        val anonymous = a.user.isBlank() || a.user.equals("anonymous", ignoreCase = true)
        val name = when {
            anonymous -> null
            TtsText.isBlocked(a.user, blocked) -> "someone"
            else -> a.user
        }
        val thanks = thanks(a, name)
        if (say != AlertSay.MESSAGE) return thanks
        val words = message(a)?.takeIf { it.isNotBlank() && !TtsText.isBlocked(it, blocked) } ?: return thanks
        return "$thanks $words"
    }

    /** Whether [say] would leave the message out of [a] for a blocked word (for the log). */
    fun messageBlocked(a: TwitchAlert, say: AlertSay, blocked: Collection<String> = emptyList()): Boolean =
        say == AlertSay.MESSAGE && message(a)?.let { it.isNotBlank() && TtsText.isBlocked(it, blocked) } == true

    private fun message(a: TwitchAlert): String? = when (a) {
        is TwitchAlert.Sub -> a.message
        is TwitchAlert.Cheer -> a.message
        else -> null
    }

    private fun thanks(a: TwitchAlert, name: String?): String {
        val who = name ?: "you"
        return when (a) {
            is TwitchAlert.Follow -> "Thank you for the follow, $who!"
            is TwitchAlert.Sub -> {
                val months = a.months ?: 0
                when {
                    months > 1 -> "Thank you for $months months, $who!"
                    a.prime -> "Thank you for the Prime sub, $who!"
                    else -> "Thank you for subscribing, $who!"
                }
            }
            is TwitchAlert.GiftSubs -> {
                val subs = if (a.count == 1) "the gifted sub" else "the ${a.count} gifted subs"
                if (name == null) "Thank you for $subs!" else "Thank you $name for $subs!"
            }
            is TwitchAlert.Cheer -> {
                val bits = if (a.bits == 1) "the bit" else "the ${a.bits} bits"
                if (name == null) "Thank you for $bits!" else "Thank you $name for $bits!"
            }
            is TwitchAlert.Tip -> {
                val tip = a.amount.trim().takeIf { it.isNotEmpty() }?.let { "the $it tip" } ?: "the tip"
                if (name == null) "Thank you for $tip!" else "Thank you $name for $tip!"
            }
            is TwitchAlert.Raid -> if (name == null) "Welcome, raiders!" else "Welcome, raiders! Thank you $name for the raid!"
        }
    }
}
