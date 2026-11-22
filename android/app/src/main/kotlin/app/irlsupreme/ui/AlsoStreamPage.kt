package app.irlsupreme.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import app.irlsupreme.service.AlsoStreamRules
import app.irlsupreme.service.AlsoStreamTo
import app.irlsupreme.service.ExtraDestination
import app.irlsupreme.service.ExtraKind

/**
 * Settings > Also stream to: Kick, YouTube or another RTMP server next to Twitch. The BRB server sends each one the
 * same stream, untouched, so the phone still uploads once; that's why it needs Combine connections. Test streams go to
 * Twitch's private test only, never here.
 */
@Composable
fun AlsoStreamPage(c: SetupController, nav: Navigator) = PageScaffold("Also stream to", nav::back) {
    val ctx = LocalContext.current
    val all by AlsoStreamTo.state(ctx).collectAsState()
    val ready = c.combine && c.pairing != null
    // The server's view: which destinations it holds and any that are failing (a refused key, say).
    LaunchedEffect(ready) { if (ready) runCatching { c.refreshBrbStatus() } }
    val reported = c.brbServerStatus?.extras.orEmpty()

    HelpText(
        "Your server sends the same stream to these too, so your phone uploads it once. " +
            "Test streams stay on Twitch's private test and go nowhere else.",
    )
    if (!ready) {
        // Nothing here can work yet: the reason and the way to fix it, nothing else.
        InlineError(
            "Needs Combine connections and your server. Without them, the phone streams straight to Twitch only.",
            actionLabel = "Set up Combine connections",
            onAction = { nav.open(Screen.CONNECTIONS) },
        )
        return@PageScaffold
    }

    ExtraKind.entries.forEach { kind ->
        val d = all[kind] ?: ExtraDestination()
        fun set(next: ExtraDestination) = AlsoStreamTo.set(ctx, kind, next)
        // Where to find the key shows only once you've said you want this one.
        SwitchRow("Stream to ${kind.title}", supporting = if (d.on) whereFrom(kind) else null, checked = d.on) { set(d.copy(on = it)) }
        if (d.on) {
            if (kind.fixedServer == null) {
                PlainField(
                    d.server, { set(d.copy(server = it)) }, "Stream URL",
                    supporting = if (kind == ExtraKind.KICK) "Starts with rtmps://" else "Starts with rtmp:// or rtmps://",
                )
            }
            SecretField(d.key, { set(d.copy(key = it)) }, "Stream key")
            val problem = AlsoStreamRules.problem(kind, d)
            val serverSays = reported.firstOrNull { it.name == kind.title }?.error
            when {
                problem != null && (d.server.isNotBlank() || d.key.isNotBlank()) -> InlineError(problem)
                problem != null -> HelpText(problem)
                serverSays != null -> InlineError("${kind.title} on your last stream: $serverSays")
            }
        }
    }

    HelpText("Chat, alerts and mod commands stay on Twitch.")
    if (all.values.any { it.on }) HelpText("Each one adds your stream's bitrate to your server's upload, not your phone's.")
}

private fun whereFrom(kind: ExtraKind): String = when (kind) {
    ExtraKind.KICK -> "From Kick's creator dashboard: Settings › Stream. Copy the Stream URL and the Stream key."
    ExtraKind.YOUTUBE -> "From YouTube Studio: Go live › Stream. Copy the stream key."
    ExtraKind.OTHER -> "Any server that takes RTMP, such as Restream or your own: its address and stream key."
}

/** The Settings row's value: what's on, or why nothing will go there. */
internal fun alsoStreamValue(on: List<String>, ready: Boolean): String = when {
    on.isEmpty() -> "Off"
    !ready -> "Needs Combine connections"
    else -> on.joinToString(", ")
}
