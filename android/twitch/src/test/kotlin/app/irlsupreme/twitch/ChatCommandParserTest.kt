package app.irlsupreme.twitch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCommandParserTest {
    private val mod = CommandSender("ModPerson", isBroadcaster = false, isModerator = true)
    private val broadcaster = CommandSender("streamer", isBroadcaster = true, isModerator = false)
    private val viewer = CommandSender("viewer", isBroadcaster = false, isModerator = false)

    private fun parse(text: String, sender: CommandSender = mod, policy: CommandPolicy = CommandPolicy()) =
        ChatCommandParser.parse(text, sender, policy)

    @Test fun simpleCommands() {
        assertEquals(CommandResult.Accepted(ChatCommand.Brb, "modperson"), parse("!brb"))
        assertEquals(CommandResult.Accepted(ChatCommand.Live, "modperson"), parse("!live"))
        assertEquals(CommandResult.Accepted(ChatCommand.Privacy, "modperson"), parse("!privacy"))
        assertEquals(CommandResult.Accepted(ChatCommand.StartingSoon, "modperson"), parse("!starting"))
        assertEquals(CommandResult.Accepted(ChatCommand.Ending, "modperson"), parse("!ending"))
        assertEquals(CommandResult.Accepted(ChatCommand.RestartCam, "modperson"), parse("!restartcam"))
    }

    @Test fun caseAndWhitespaceInsensitive() {
        assertEquals(CommandResult.Accepted(ChatCommand.Brb, "modperson"), parse("  !BRB  "))
        assertEquals(CommandResult.Accepted(ChatCommand.End, "modperson"), parse("!End   Confirm"))
    }

    @Test fun endRequiresConfirmationWord() {
        assertEquals(CommandResult.Invalid("end", "To end the stream type: !end confirm"), parse("!end"))
        assertEquals(CommandResult.Invalid("end", "To end the stream type: !end confirm"), parse("!end please"))
        assertEquals(CommandResult.Invalid("end", "To end the stream type: !end confirm"), parse("!end confirm now"))
        assertEquals(CommandResult.Accepted(ChatCommand.End, "modperson"), parse("!end CONFIRM"))
        assertEquals(
            CommandResult.Accepted(ChatCommand.End, "modperson"),
            parse("!end yesreally", policy = CommandPolicy(endConfirmationWord = "yesreally")),
        )
    }

    @Test fun qualityIsNotACommand() {
        // Not built, so it stays ordinary chat: no "not supported" reply from the streamer's account.
        assertEquals(CommandResult.NotACommand, parse("!quality"))
        assertEquals(CommandResult.NotACommand, parse("!quality high"))
        assertEquals(CommandResult.NotACommand, parse("!Quality HIGH", broadcaster))
    }

    @Test fun everyListedCommandIsAccepted() {
        for (policy in listOf(CommandPolicy(), CommandPolicy(endConfirmationWord = "yesreally"))) {
            val help = ChatCommandParser.help(policy)
            help.forEach { h ->
                val r = parse(h.usage, policy = policy)
                assertTrue("${h.usage} -> $r", r is CommandResult.Accepted)
                assertTrue(h.meaning.isNotBlank())
            }
            assertEquals(help.size, help.map { it.usage }.toSet().size)
            assertTrue(help.any { it.usage == "!end ${policy.endConfirmationWord}" })
        }
    }

    @Test fun permissions() {
        assertEquals(CommandResult.Accepted(ChatCommand.Brb, "streamer"), parse("!brb", broadcaster))
        assertEquals(CommandResult.NotAllowed("viewer"), parse("!brb", viewer))
        assertEquals(CommandResult.NotAllowed("viewer"), parse("!end confirm", viewer))
        assertEquals(CommandResult.NotAllowed("modperson"), parse("!brb", policy = CommandPolicy(allowModerators = false)))
        assertEquals(
            CommandResult.Accepted(ChatCommand.Brb, "viewer"),
            parse("!brb", viewer, CommandPolicy(allowedLogins = setOf("Viewer"))),
        )
    }

    @Test fun sharedChatMessagesAreNeverTrusted() {
        assertEquals(CommandResult.NotAllowed("streamer"), parse("!end confirm", broadcaster.copy(isFromSharedChat = true)))
    }

    @Test fun nonCommandsIgnored() {
        assertEquals(CommandResult.NotACommand, parse("hello !brb"))
        assertEquals(CommandResult.NotACommand, parse("!"))
        assertEquals(CommandResult.NotACommand, parse("!lurk", viewer))
        assertEquals(CommandResult.NotACommand, parse("!brbx"))
    }

    @Test fun parsesFromChatMessageBadges() {
        val msg = ChatMessage(
            messageId = "m", broadcasterUserId = "1001", chatterUserId = "2002", chatterLogin = "amod",
            chatterDisplayName = "AMod", color = null, text = "!privacy",
            badges = listOf(ChatBadge("moderator", "1", null)), messageType = "text",
            replyParentMessageId = null, sourceBroadcasterUserId = null,
        )
        assertEquals(CommandResult.Accepted(ChatCommand.Privacy, "amod"), ChatCommandParser.parse(msg))
        assertEquals(CommandResult.NotAllowed("amod"), ChatCommandParser.parse(msg.copy(badges = emptyList())))
        assertEquals(CommandResult.NotAllowed("amod"), ChatCommandParser.parse(msg.copy(sourceBroadcasterUserId = "9999")))
    }
}
