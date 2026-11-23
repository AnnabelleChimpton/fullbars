package app.irlsupreme.twitch

/** Mod chat commands: what the streamer and their mods can type in chat to control the stream hands-free. */
sealed interface ChatCommand {
    data object Brb : ChatCommand
    data object Live : ChatCommand
    data object Privacy : ChatCommand
    data object StartingSoon : ChatCommand
    data object Ending : ChatCommand
    /**
     * `!scene <name>`: any scene by its name, the streamer's own included. [name] is what was typed after the command
     * (at most [ChatCommandParser.MAX_SCENE_NAME] characters); the app matches it, since only it knows the names.
     */
    data class ShowScene(val name: String) : ChatCommand
    /** Only produced for `!end <confirmationWord>`. */
    data object End : ChatCommand
    /**
     * Never produced: `!quality` isn't parsed, because changing quality from chat isn't built and a "not supported"
     * reply in public chat looks broken. Kept so exhaustive `when`s over [ChatCommand] still compile.
     */
    data class Quality(val preset: String) : ChatCommand
    data object RestartCam : ChatCommand
    /** `!skip`: stops the viewer TTS message that's speaking. */
    data object Skip : ChatCommand
}

/** Who may issue commands. The broadcaster is always allowed. */
data class CommandPolicy(
    val allowModerators: Boolean = true,
    /** Extra Twitch logins (lowercase) allowed even without the moderator badge. */
    val allowedLogins: Set<String> = emptySet(),
    val endConfirmationWord: String = "confirm",
)

/** One command as a mod types it, and what it does, for the list on the Twitch page. */
data class CommandHelp(val usage: String, val meaning: String)

sealed interface CommandResult {
    /** Not a known command (ordinary chat or someone else's bot command). Ignore. */
    data object NotACommand : CommandResult

    /** A known command from someone not allowed to use it. Ignore silently. */
    data class NotAllowed(val login: String) : CommandResult

    /** A known command from an allowed user, but malformed; [hint] can be sent back as an acknowledgement. */
    data class Invalid(val command: String, val hint: String) : CommandResult

    data class Accepted(val command: ChatCommand, val issuedByLogin: String) : CommandResult
}

/** Who sent the text, for [ChatCommandParser.parse]. */
data class CommandSender(
    val login: String,
    val isBroadcaster: Boolean,
    val isModerator: Boolean,
    /** Messages relayed from another channel by Shared Chat are never trusted. */
    val isFromSharedChat: Boolean = false,
)

/** Pure parser for mod commands. No I/O, no state. */
object ChatCommandParser {
    private val COMMANDS = setOf("brb", "live", "privacy", "starting", "ending", "scene", "end", "restartcam", "skip")

    /** The most of a `!scene` name kept: longer than any scene's name, short enough to echo back. */
    const val MAX_SCENE_NAME = 40

    /**
     * Every command, as typed and in plain words, so the Twitch page lists exactly what this parser accepts (a test
     * checks that each usage parses). The end word comes from [policy], so the list never names a word that fails.
     */
    fun help(policy: CommandPolicy = CommandPolicy()): List<CommandHelp> = listOf(
        CommandHelp("!brb", "Shows your BRB card."),
        CommandHelp("!live", "Back to your camera (and your mic, after !privacy)."),
        CommandHelp("!privacy", "Shows your Privacy card and turns your mic off."),
        CommandHelp("!starting", "Shows Starting soon."),
        CommandHelp("!ending", "Shows your Ending card."),
        CommandHelp("!scene <name>", "Shows any scene by its name, your own too. !scene camera goes back to your camera."),
        CommandHelp("!restartcam", "Restarts the camera."),
        CommandHelp("!skip", "Stops the viewer TTS that's speaking."),
        CommandHelp("!end ${policy.endConfirmationWord}", "Ends the stream."),
    )

    /** A notice's message (a watch streak's shared words) is never a command, whatever it says. */
    fun parse(message: ChatMessage, policy: CommandPolicy = CommandPolicy()): CommandResult = if (message.notice != null) CommandResult.NotACommand else parse(
        text = message.text,
        sender = CommandSender(
            login = message.chatterLogin,
            isBroadcaster = message.isBroadcaster,
            isModerator = message.isModerator,
            isFromSharedChat = message.isFromSharedChat,
        ),
        policy = policy,
    )

    fun parse(text: String, sender: CommandSender, policy: CommandPolicy = CommandPolicy()): CommandResult {
        val trimmed = text.trim()
        if (!trimmed.startsWith("!")) return CommandResult.NotACommand
        val parts = trimmed.substring(1).split(Regex("\\s+")).filter { it.isNotEmpty() }
        val name = parts.firstOrNull()?.lowercase() ?: return CommandResult.NotACommand
        if (name !in COMMANDS) return CommandResult.NotACommand

        val login = sender.login.lowercase()
        if (!isAllowed(sender, login, policy)) return CommandResult.NotAllowed(login)

        val args = parts.drop(1)
        val command: ChatCommand = when (name) {
            "brb" -> ChatCommand.Brb
            "live" -> ChatCommand.Live
            "privacy" -> ChatCommand.Privacy
            "starting" -> ChatCommand.StartingSoon
            "ending" -> ChatCommand.Ending
            "restartcam" -> ChatCommand.RestartCam
            "skip" -> ChatCommand.Skip
            "scene" -> {
                if (args.isEmpty()) return CommandResult.Invalid(name, "Type !scene and a scene's name, like !scene brb")
                ChatCommand.ShowScene(args.joinToString(" ").take(MAX_SCENE_NAME))
            }
            "end" -> {
                if (args.size != 1 || !args[0].equals(policy.endConfirmationWord, ignoreCase = true)) {
                    return CommandResult.Invalid(name, "To end the stream type: !end ${policy.endConfirmationWord}")
                }
                ChatCommand.End
            }
            else -> return CommandResult.NotACommand
        }
        return CommandResult.Accepted(command, login)
    }

    private fun isAllowed(sender: CommandSender, login: String, policy: CommandPolicy): Boolean {
        if (sender.isFromSharedChat) return false
        if (sender.isBroadcaster) return true
        if (policy.allowModerators && sender.isModerator) return true
        return policy.allowedLogins.any { it.equals(login, ignoreCase = true) }
    }
}
