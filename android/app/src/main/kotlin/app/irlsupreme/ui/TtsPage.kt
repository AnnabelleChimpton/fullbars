package app.irlsupreme.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.irlsupreme.service.AlertKind
import app.irlsupreme.service.AlertOptionsCodec
import app.irlsupreme.service.AlertOptionsStore
import app.irlsupreme.service.AlertSay
import app.irlsupreme.service.TtsPrefs
import app.irlsupreme.service.TtsSettings
import app.irlsupreme.service.TtsSpeaker
import kotlinx.coroutines.launch

/** "Off", "30 s", "1 min", "5 min": a wait or a cooldown. Pure. */
internal fun ttsSeconds(s: Int): String = when {
    s == 0 -> "Off"
    s < 60 -> "$s s"
    s % 60 == 0 -> "${s / 60} min"
    else -> "${s / 60} min ${s % 60} s"
}

/** What [kind] can be said as: off, a thank-you, and (subs and cheers) the thank-you with their words. Pure. */
internal fun alertSayChoices(kind: AlertKind): List<AlertSay> =
    if (AlertOptionsCodec.hasMessage(kind)) AlertSay.entries else listOf(AlertSay.OFF, AlertSay.THANKS)

/** Short enough for a key: "Off", "Thanks", "+ message". Pure. */
internal fun alertSayTitle(s: AlertSay): String = when (s) {
    AlertSay.OFF -> "Off"
    AlertSay.THANKS -> "Thanks"
    AlertSay.MESSAGE -> "Thanks + message"
}

/**
 * Settings › Overlays › Viewer TTS: viewers make the stream speak, with a channel points reward Fullbars makes on Twitch
 * or a cheer with words. Off means folded, like Captions: the rest is kept meanwhile.
 */
@Composable
fun TtsPage(c: SetupController, nav: Navigator) = PageScaffold("Viewer TTS", nav::back) {
    val ctx = LocalContext.current
    val p by TtsSettings.state(ctx).collectAsState()
    fun set(change: (TtsPrefs) -> TtsPrefs) = TtsSettings.update(ctx, change)

    SwitchRow(
        "Let viewers make you talk",
        supporting = "Their message, said on stream in this phone's voice. Nothing is said until it passes your filters.",
        checked = p.on,
        onCheckedChange = { on -> set { it.copy(on = on) } },
    )
    if (!p.on) return@PageScaffold

    SectionHeader("Channel points")
    SwitchRow(
        "A “Make me talk” reward",
        supporting = if (c.ttsNeedsSignIn) "Needs you to sign in to Twitch again once." else "Fullbars adds it to your channel, and pauses it while you're not live.",
        checked = p.pointsOn,
        onCheckedChange = { on -> set { it.copy(pointsOn = on) } },
    )
    if (p.pointsOn && c.ttsNeedsSignIn) {
        InlineError("Channel points need a new Twitch permission.", actionLabel = "Sign in again", onAction = c::startSignIn)
    }
    if (p.pointsOn) {
        ChoiceLabel("Price")
        KeyChoice("Price", TtsPrefs.COSTS, p.cost, { "%,d".format(it) }) { v -> set { it.copy(cost = v) } }
        ChoiceLabel("Cooldown")
        KeyChoice("Cooldown", TtsPrefs.COOLDOWNS, p.cooldownS, ::ttsSeconds) { v -> set { it.copy(cooldownS = v) } }
        HelpText("Points for a message that isn't said (filtered, skipped, or left when you end) go back to the viewer.")
    }

    SectionHeader("Alerts")
    HelpText("Said as the alert comes in: a thank-you, or the thank-you and what they wrote. Each kind's page has the smallest worth saying.")
    val alertOptions by AlertOptionsStore.state(ctx).collectAsState()
    AlertKind.entries.forEach { k ->
        val o = alertOptions.of(k)
        ChoiceLabel(k.title)
        KeyChoice(k.title, alertSayChoices(k), o.say, ::alertSayTitle) { v -> AlertOptionsStore.setKind(ctx, k, o.copy(say = v)) }
    }

    SectionHeader("Voice")
    HelpText("Every message is said in the diva voice: bright, high and a little dramatic.")
    // Its own text-to-speech for Try it; closed when the page goes.
    val speaker = remember { TtsSpeaker(ctx) }
    DisposableEffect(speaker) { onDispose { speaker.shutdown() } }
    SoundFader("Volume", p.gainDb, TtsPrefs.MIN_GAIN_DB, TtsPrefs.MAX_GAIN_DB, { v -> set { it.copy(gainDb = v) } })
    SwitchRow(
        "Hear it in your earbuds",
        supporting = "At the same moment as viewers, only with headphones in. On a Test stream, which nobody else hears, it plays from the speaker when they're out.",
        checked = p.earbuds,
        onCheckedChange = { on -> set { it.copy(earbuds = on) } },
    )
    val player = rememberSoundPlayer()
    val scope = rememberCoroutineScope()
    var trying by remember { mutableStateOf(false) }
    FbButton(
        if (trying) "Getting it ready…" else "Try it",
        onClick = {
            if (trying) return@FbButton
            trying = true
            scope.launch {
                speaker.synthesize("Oh my gosh chat, thank you so much for the bits, darling!")?.let { player.play(it.toPcmClip()) }
                trying = false
            }
        },
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        kind = FbButtonKind.Secondary,
    )

    SectionHeader("Safety")
    ChoiceLabel("Wait before speaking")
    KeyChoice("Wait before speaking", TtsPrefs.WAITS, p.waitS, ::ttsSeconds) { v -> set { it.copy(waitS = v) } }
    HelpText("Time for mods to type !skip, or you to tap Skip in Scenes, before it's said.")
    PlainField(
        p.ownWords,
        { v -> set { it.copy(ownWords = v.take(TtsPrefs.MAX_OWN_WORDS)) } },
        "Your blocked words",
        Modifier.padding(horizontal = 16.dp),
        supporting = "Commas between them. A * at either end also matches longer words.",
    )
    SwitchRow(
        "Use your Twitch blocked terms",
        supporting = "The words your channel already blocks in chat.",
        checked = p.twitchTerms,
        onCheckedChange = { on -> set { it.copy(twitchTerms = on) } },
    )
    SwitchRow(
        "A skip clears the queue",
        supporting = "Everything waiting goes too, not just the one being said.",
        checked = p.skipClearsQueue,
        onCheckedChange = { on -> set { it.copy(skipClearsQueue = on) } },
    )
    HelpText("Some words are never said: the worst slurs, and telling someone to kill themselves. A message with a blocked word isn't said at all.")
}
