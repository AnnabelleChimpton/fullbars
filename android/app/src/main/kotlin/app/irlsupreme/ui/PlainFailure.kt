package app.irlsupreme.ui

import app.irlsupreme.engine.AudioInput

/**
 * Why a stream isn't reaching viewers, in plain words, from the engine's raw [lastError] (RootEncoder and SRTLA
 * messages, which name sockets and errno codes). Null when there's no error to explain.
 *
 * Pure so the live screen, the end-of-stream recap and the session log all say the same thing, and so it's unit
 * tested. The raw text stays in the live screen's Details for anyone helping debug.
 *
 * [bonded]: the stream goes through the user's BRB server rather than straight to Twitch, so "the server" is theirs to
 * check (address, running) rather than Twitch's.
 */
fun plainFailure(lastError: String?, bonded: Boolean): String? {
    val raw = lastError?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val e = raw.lowercase()
    return when {
        // BondedOutput's own wording: no network link could register with srtla_rec at all.
        "no connection can reach the brb server" in e -> "Can't reach your BRB server"
        // SRT handshake refused: wrong passphrase or stream id, i.e. the server was paired with something else.
        "brb server rejected" in e -> "Your BRB server refused the stream, try pairing again"
        "stream key rejected" in e || "auth" in e || "unauthorized" in e || "forbidden" in e ->
            "Twitch refused the stream key"
        "disconnected" in e || "broken pipe" in e || "connection reset" in e -> "The connection dropped"
        REACH_WORDS.any { it in e } ->
            if (bonded) "Can't reach your BRB server, check the address or signal" else "Can't reach Twitch, check your signal"
        "microphone" in e || "audiorecord" in e -> "Your mic isn't working"
        // Something we don't have words for: the pill opens Details, which shows the raw reason.
        else -> "Tap for details"
    }
}

/** Timeouts, refusals, DNS and routing failures: the server couldn't be reached at all. */
private val REACH_WORDS = listOf(
    "timed out", "timeout", "refused", "unreachable", "no route", "unable to resolve", "unknownhost",
    "unknown host", "no address associated", "resolve host", "failed to connect", "network is down", "endpoint",
)

/**
 * A nudge once a first connection has failed a few times ([attempt] = the engine's reconnect attempt), pointing at the
 * likeliest cause; null before that, so a single slow attempt doesn't send anyone chasing their server.
 */
fun firstConnectHint(attempt: Int, bonded: Boolean): String? = when {
    attempt < 3 -> null
    bonded -> "Is your BRB server running?"
    else -> "Check your stream key and internet"
}

/**
 * Why viewers can't hear the mic, for the live screen's notice line (the MIC column shows NO AUDIO beside it); null
 * when it's delivering audio. Only shown when the user hasn't muted it themselves (that's the MUTED tag).
 */
fun audioProblem(input: AudioInput): String? = when (input) {
    AudioInput.OK -> null
    // Android hands a mic held by another app (a call, a recorder) to us as pure zeros.
    AudioInput.SILENCED -> "Mic in use by another app"
    AudioInput.UNAVAILABLE -> "Mic unavailable, sending silence"
    AudioInput.FAILING -> "Mic reconnecting"
}
