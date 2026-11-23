package app.irlsupreme.service

/**
 * The one voice viewer TTS speaks in: the diva. One of the phone's own female voices ([BASES], the first one
 * installed; the phone's default when none is), pitched up and a touch slow, for drama. The bases are Google's offline
 * English voices, which most Android phones have. Viewers don't pick a voice: every message sounds like this.
 */
object TtsVoice {
    const val PITCH = 1.35f
    const val RATE = 0.88f
    /** Bright American voices, the one the streamer chose (tpf) first. */
    val BASES = listOf("en-us-x-tpf-local", "en-us-x-iob-local", "en-us-x-sfg-local", "en-us-x-tpc-local", "en-us-x-iog-local")
}

/** Why a message wasn't spoken: logged (never the message), and a channel points one is refunded. */
enum class TtsDrop(val reason: String) {
    EMPTY("nothing to say"),
    BLOCKED("blocked word"),
}

sealed interface TtsPrepared {
    data class Speak(val text: String) : TtsPrepared
    data class Drop(val why: TtsDrop) : TtsPrepared
}

/**
 * What a viewer's message becomes before it's spoken on stream. Pure, for tests.
 *
 * Anything a viewer types is read out in public on the streamer's channel, so a message with a blocked word is dropped
 * whole (never bleeped: the rest of a message built around a slur isn't worth saying). Blocked words come from the
 * built-in list of the worst slurs ([TtsWords]), the streamer's own list and the channel's Twitch blocked terms. They
 * match whole words, case-insensitively, through the usual dodges: digits and symbols for letters ("n1gg3r"),
 * stretched letters, and letters spaced or dotted apart. Twitch's `*` wildcards at either end match word starts and
 * ends. The rest is tidied like chat read aloud: links say "a link", long runs of one character are shortened, and it
 * stops at [MAX] characters.
 */
object TtsText {
    const val MAX = 150

    private val url = Regex("""(?i)\b(?:https?://|www\.)\S+""")
    private val repeats = Regex("""(.)\1{4,}""")
    private val control = Regex("""[\p{Cc}\p{Cf}]""")
    private val spaces = Regex("""\s+""")

    /**
     * [raw] as it'll be said, or why not. [blocked]: the streamer's own words and the channel's Twitch blocked terms; the
     * built-in list always applies.
     */
    fun prepare(raw: String?, blocked: Collection<String> = emptyList()): TtsPrepared {
        var text = control.replace(raw.orEmpty(), " ")
        if (isBlocked(text, blocked)) return TtsPrepared.Drop(TtsDrop.BLOCKED)
        text = repeats.replace(url.replace(text, "a link")) { it.value.take(3) }
        text = spaces.replace(text, " ").trim()
        if (text.length > MAX) text = text.take(MAX).substringBeforeLast(' ').ifEmpty { text.take(MAX) }
        if (text.none { it.isLetterOrDigit() }) return TtsPrepared.Drop(TtsDrop.EMPTY)
        return TtsPrepared.Speak(text)
    }

    /** Whether [text] holds a built-in or [extra] blocked word or phrase (see the class comment for how they match). */
    fun isBlocked(text: String, extra: Collection<String> = emptyList()): Boolean {
        val words = words(text)
        if (words.isEmpty()) return false
        val joined = " " + words.joinToString(" ") + " "
        val terms = TtsWords.BUILT_IN.asSequence() + extra.asSequence()
        return terms.any { matches(it, words, joined) }
    }

    private fun matches(term: String, words: List<String>, joined: String): Boolean {
        val raw = term.trim()
        val startsWild = raw.startsWith("*")
        val endsWild = raw.endsWith("*")
        val core = words(raw.trim('*'))
        if (core.isEmpty()) return false
        if (core.size > 1) return joined.contains(" " + core.joinToString(" ") + " ")
        val t = core[0]
        return words.any { w ->
            when {
                startsWild && endsWild -> w.contains(t)
                startsWild -> w.endsWith(t)
                endsWild -> w.startsWith(t)
                else -> w == t
            }
        }
    }

    /**
     * The words of [s] for matching: lowercased, digits and symbols read as the letters they stand for, runs of three
     * or more of a letter cut to one, and single letters standing in a row ("n i g" or "n.i.g") joined into one word.
     */
    internal fun words(s: String): List<String> {
        val folded = StringBuilder()
        for (c in s.lowercase()) folded.append(LOOKALIKE[c] ?: c)
        val tokens = folded.split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }.map { stretch.replace(it) { m -> m.value.take(1) } }
        val out = ArrayList<String>(tokens.size)
        val run = StringBuilder()
        for (t in tokens) {
            if (t.length == 1) { run.append(t); continue }
            if (run.isNotEmpty()) { out.add(run.toString()); run.clear() }
            out.add(t)
        }
        if (run.isNotEmpty()) out.add(run.toString())
        return out
    }

    private val stretch = Regex("""(\p{L})\1{2,}""")

    /** Characters that stand in for letters. */
    private val LOOKALIKE = mapOf(
        '0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '8' to 'b',
        '@' to 'a', '$' to 's', '!' to 'i', '|' to 'l', '€' to 'e',
    )
}

/** Where the streamer hears a viewer's message, besides the stream itself; logged with it. */
enum class TtsHeard(val words: String) {
    EARBUDS("in your earbuds"),
    SPEAKER("from the phone's speaker, Test stream"),
    NOT_HERE("not played on the phone"),
    ;

    companion object {
        /**
         * In headphones when they're in and the streamer wants it ([earbudsOn]). Never from the speaker on a real stream:
         * the mic would pick it up and viewers would hear it twice, and people nearby would hear it too. On a Test stream
         * nobody else can hear it at all, so with no headphones in it plays from the speaker (the mic picking it up
         * doesn't matter there).
         */
        fun of(headphones: Boolean, earbudsOn: Boolean, test: Boolean): TtsHeard = when {
            headphones -> if (earbudsOn) EARBUDS else NOT_HERE
            test -> SPEAKER
            else -> NOT_HERE
        }
    }
}
