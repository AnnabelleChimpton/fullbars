package app.irlsupreme.service

/**
 * Words viewer TTS never says, whatever the streamer's own list: the worst slurs and the commonest self-harm taunt,
 * which no streamer wants read out in public on their channel. Deliberately short: anything a channel wants beyond
 * these goes in its own list or its Twitch blocked terms. Matched as whole words, with the dodges [TtsText.words]
 * undoes; `*` at an end matches a word's start or end, as Twitch's blocked terms do.
 */
internal object TtsWords {
    val BUILT_IN: List<String> = listOf(
        // Racial and ethnic slurs.
        "nigger*", "nigga*", "niglet*", "chink*", "gook*", "spic", "spics", "kike*", "wetback*", "beaner*",
        "raghead*", "towelhead*", "paki", "pakis", "coons", "jigaboo*", "porchmonkey*", "sandnigger*",
        // Slurs about sexuality and gender.
        "faggot*", "fag", "fags", "dyke*", "tranny*", "trannies",
        // Disability.
        "retard", "retards", "retarded",
        // Telling someone to kill themselves.
        "kys", "kill yourself", "kill your self",
    )
}
