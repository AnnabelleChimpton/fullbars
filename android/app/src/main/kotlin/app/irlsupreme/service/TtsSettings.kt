package app.irlsupreme.service

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Viewer TTS (Settings › Overlays › Viewer TTS): viewers make the stream speak, with channel points (a reward Fullbars
 * makes on Twitch), and alerts said out loud, each kind as the streamer chose ([AlertSay]). Off by default.
 */
data class TtsPrefs(
    val on: Boolean = false,
    val pointsOn: Boolean = true,
    /** The reward's price in channel points. */
    val cost: Int = 500,
    /** Seconds between redemptions, anyone's (Twitch's global cooldown); 0 for none. */
    val cooldownS: Int = 60,
    /** Level on stream, dB, against the alert sounds' loudness. */
    val gainDb: Int = 0,
    /** The streamer hears it too, in headphones only. */
    val earbuds: Boolean = true,
    /** Seconds a message waits before it's spoken, so mods can !skip it. */
    val waitS: Int = 3,
    /** The streamer's own blocked words and phrases, comma or line separated. */
    val ownWords: String = "",
    /** Also block the channel's Twitch blocked terms. */
    val twitchTerms: Boolean = true,
    /** A skip clears everything waiting too, not just the one speaking. */
    val skipClearsQueue: Boolean = true,
    /** The reward Fullbars made on Twitch (only its maker can refund it); null until made. */
    val rewardId: String? = null,
) {
    /** The streamer's own words as a list. */
    val ownList: List<String> get() = ownWords.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        val COSTS = listOf(100, 250, 500, 1000, 2500, 5000)
        val COOLDOWNS = listOf(0, 30, 60, 120, 300)
        val WAITS = listOf(0, 3, 5, 10)
        const val MIN_GAIN_DB = -12
        const val MAX_GAIN_DB = 6
        const val MAX_OWN_WORDS = 2000
    }
}

/**
 * What Fullbars' channel points reward should be, from the settings (only what Twitch is told, so a change elsewhere
 * doesn't touch the reward). Pure.
 */
data class TtsReward(
    val on: Boolean,
    val pointsOn: Boolean,
    val cost: Int,
    val cooldownS: Int,
    val twitchTerms: Boolean,
    val prompt: String,
) {
    companion object {
        /** The reward's name on Twitch (45 characters at most). */
        const val TITLE = "Make me talk"

        fun wanted(p: TtsPrefs) = TtsReward(p.on, p.pointsOn, p.cost, p.cooldownS, p.twitchTerms, PROMPT)

        /** What viewers read when they redeem it (200 characters at most). */
        const val PROMPT = "Your message is said out loud on stream, in my diva voice."
    }
}

/** What Settings › Viewer TTS shows on the Overlays row; [alertKinds]: how many kinds of alert are said out loud. Pure. */
internal fun ttsValue(p: TtsPrefs, alertKinds: Int = 0): String = when {
    !p.on -> "Off"
    p.pointsOn && alertKinds > 0 -> "Points · Alerts"
    p.pointsOn -> "Points"
    alertKinds > 0 -> "Alerts"
    else -> "On, nothing sends it"
}

/** The TTS settings, shared by Settings, the live screen and the stream. */
object TtsSettings {
    private var flow: MutableStateFlow<TtsPrefs>? = null

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Synchronized
    fun state(ctx: Context): StateFlow<TtsPrefs> = (flow ?: MutableStateFlow(load(ctx)).also { flow = it }).asStateFlow()

    fun current(ctx: Context): TtsPrefs = state(ctx).value

    fun update(ctx: Context, change: (TtsPrefs) -> TtsPrefs) = save(ctx, change(current(ctx)))

    private fun load(ctx: Context): TtsPrefs = prefs(ctx).let { p ->
        val d = TtsPrefs()
        TtsPrefs(
            on = p.getBoolean("tts_on", d.on),
            pointsOn = p.getBoolean("tts_points", d.pointsOn),
            cost = p.getInt("tts_cost", d.cost).coerceIn(1, 1_000_000),
            cooldownS = p.getInt("tts_cooldown", d.cooldownS).coerceIn(0, 7 * 24 * 3600),
            gainDb = p.getInt("tts_gain", d.gainDb).coerceIn(TtsPrefs.MIN_GAIN_DB, TtsPrefs.MAX_GAIN_DB),
            earbuds = p.getBoolean("tts_earbuds", d.earbuds),
            waitS = p.getInt("tts_wait", d.waitS).coerceIn(0, 60),
            ownWords = p.getString("tts_words", "").orEmpty().take(TtsPrefs.MAX_OWN_WORDS),
            twitchTerms = p.getBoolean("tts_twitch_terms", d.twitchTerms),
            skipClearsQueue = p.getBoolean("tts_skip_clears", d.skipClearsQueue),
            rewardId = p.getString("tts_reward_id", null),
        )
    }

    @Synchronized
    private fun save(ctx: Context, n: TtsPrefs) {
        prefs(ctx).edit {
            putBoolean("tts_on", n.on)
            putBoolean("tts_points", n.pointsOn)
            putInt("tts_cost", n.cost)
            putInt("tts_cooldown", n.cooldownS)
            remove("tts_bits") // older builds: cheers are an alert kind's setting now
            remove("tts_min_bits")
            remove("tts_fun") // older builds' voice options
            remove("tts_voice")
            putInt("tts_gain", n.gainDb)
            putBoolean("tts_earbuds", n.earbuds)
            putInt("tts_wait", n.waitS)
            putString("tts_words", n.ownWords.take(TtsPrefs.MAX_OWN_WORDS))
            putBoolean("tts_twitch_terms", n.twitchTerms)
            putBoolean("tts_skip_clears", n.skipClearsQueue)
            if (n.rewardId == null) remove("tts_reward_id") else putString("tts_reward_id", n.rewardId)
        }
        (flow ?: MutableStateFlow(n).also { flow = it }).value = n
    }
}
