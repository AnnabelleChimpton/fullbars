package app.irlsupreme.service

import app.irlsupreme.engine.Scene
import app.irlsupreme.engine.video.Slates.Card
import app.irlsupreme.twitch.ChatCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Your own scenes' names, and how `!scene`, the mod remote and the logs find and name every scene. */
class SceneNamesTest {
    private val own = mapOf(Scene.CUSTOM_1 to "Lunch", Scene.CUSTOM_3 to "Q&A", Scene.CUSTOM_4 to "Driving home")

    @Test fun `every scene has a name, your own by what you called them`() {
        assertEquals(listOf("Live", "BRB", "Privacy", "Starting soon", "Ending"), SceneNames.BUILT_IN.map { SceneNames.name(it, own) })
        assertEquals("Lunch", SceneNames.name(Scene.CUSTOM_1, own))
        assertEquals("Q&A", SceneNames.name(Scene.CUSTOM_3, own))
        // Unnamed, or named only spaces: the slot's own name, the same words its card prints.
        assertEquals("Scene 2", SceneNames.name(Scene.CUSTOM_2, own))
        assertEquals("Scene 2", SceneNames.name(Scene.CUSTOM_2, mapOf(Scene.CUSTOM_2 to "   ")))
        assertEquals(Card.CUSTOM_2.headline, SceneNames.defaultName(Scene.CUSTOM_2))
        assertEquals(Scene.entries.size, Scene.entries.map { SceneNames.name(it, emptyMap()) }.toSet().size)
    }

    @Test fun `names are kept to one tidy line of at most 20 characters`() {
        assertEquals("Lunch break", SceneNames.clean("  Lunch \n  break "))
        assertEquals(SceneNames.MAX_NAME, SceneNames.clean("x".repeat(50)).length)
        assertEquals("", SceneNames.clean("   "))
        assertEquals("qa", SceneNames.slug("Q&A"))
        assertEquals("drivinghome", SceneNames.slug("Driving-home!"))
        assertEquals("café", SceneNames.slug("Café"))
    }

    @Test fun `!scene finds a scene by name or slug, any case, built-ins first`() {
        assertEquals(Scene.LIVE, SceneNames.match("live", own))
        assertEquals(Scene.LIVE, SceneNames.match("Camera", own))
        assertEquals(Scene.BRB, SceneNames.match("BRB", own))
        assertEquals(Scene.BRB, SceneNames.match("be right back", own))
        assertEquals(Scene.STARTING_SOON, SceneNames.match("starting", own))
        assertEquals(Scene.STARTING_SOON, SceneNames.match("Starting Soon", own))
        assertEquals(Scene.ENDING, SceneNames.match("ending", own))
        assertEquals(Scene.PRIVACY, SceneNames.match("PRIVACY", own))
        assertEquals(Scene.CUSTOM_1, SceneNames.match("lunch", own))
        assertEquals(Scene.CUSTOM_1, SceneNames.match("LUNCH", own))
        assertEquals(Scene.CUSTOM_3, SceneNames.match("q&a", own))
        assertEquals(Scene.CUSTOM_3, SceneNames.match("QA", own))
        assertEquals(Scene.CUSTOM_4, SceneNames.match("driving home", own))
        assertEquals(Scene.CUSTOM_4, SceneNames.match("driving-home", own))
        assertEquals(Scene.CUSTOM_4, SceneNames.match("  DrivingHome ", own))
        assertNull(SceneNames.match("dinner", own))
        assertNull(SceneNames.match("", own))
        assertNull(SceneNames.match("!!", own))
        // A scene that doesn't exist can't be shown, even by its slot's name.
        assertNull(SceneNames.match("scene 2", own))
        assertEquals(Scene.CUSTOM_2, SceneNames.match("scene 2", mapOf(Scene.CUSTOM_2 to "")))
        // A name a built-in answers to goes to the built-in.
        assertEquals(Scene.BRB, SceneNames.match("brb", mapOf(Scene.CUSTOM_1 to "BRB")))
    }

    @Test fun `a name !scene can't reach is said plainly`() {
        assertNull(SceneNames.nameProblem("Lunch", Scene.CUSTOM_1, own))
        assertNull(SceneNames.nameProblem("", Scene.CUSTOM_2, own))
        assertNotNull(SceneNames.nameProblem("!!!", Scene.CUSTOM_2, own))
        assertEquals("BRB is already a scene. Pick another name.", SceneNames.nameProblem("brb", Scene.CUSTOM_2, own))
        assertEquals("Live is already a scene. Pick another name.", SceneNames.nameProblem("Camera", Scene.CUSTOM_2, own))
        assertEquals("Your scene Lunch has that name. Pick another.", SceneNames.nameProblem("LUNCH!", Scene.CUSTOM_2, own))
        // Its own name isn't a clash.
        assertNull(SceneNames.nameProblem("lunch", Scene.CUSTOM_1, own))
    }

    @Test fun `the scenes on offer are the built-ins, then yours that exist, in slot order`() {
        assertEquals(SceneNames.BUILT_IN + listOf(Scene.CUSTOM_1, Scene.CUSTOM_3, Scene.CUSTOM_4), SceneNames.offered(own))
        assertEquals(SceneNames.BUILT_IN, SceneNames.offered(emptyMap()))
        val texts = mapOf(
            Card.CUSTOM_2 to CardText(name = "Lunch ", created = true),
            Card.CUSTOM_1 to CardText(name = "Gone", created = false),
            Card.CUSTOM_4 to CardText(created = true),
            Card.BRB to CardText(name = "x", created = true),
        )
        val created = SceneNames.created(texts)
        assertEquals(listOf(Scene.CUSTOM_2, Scene.CUSTOM_4), created.keys.toList())
        assertEquals(listOf("Lunch", "Scene 4"), created.values.toList())
    }

    @Test fun `logs and the remote use the stable enum names`() {
        assertEquals("CUSTOM_1", SceneNames.logName(Scene.CUSTOM_1))
        assertEquals("BRB", SceneNames.logName(Scene.BRB))
        assertEquals(Scene.CUSTOM_1, SceneNames.sceneOf(Card.CUSTOM_1))
        assertNull(SceneNames.sceneOf(Card.SIGNAL_LOST))
        // A remote pick: a built-in always, your own only while it exists, anything else never.
        assertEquals(Scene.BRB, ModScenes.fromRemote("BRB", emptyMap()))
        assertEquals(Scene.CUSTOM_3, ModScenes.fromRemote("CUSTOM_3", own))
        assertNull(ModScenes.fromRemote("CUSTOM_2", own))
        assertNull(ModScenes.fromRemote("EVIL", own))
        assertNull(ModScenes.fromRemote("brb", own))
    }

    @Test fun `a built-in picked by name does exactly what its own command does`() {
        assertEquals(ChatCommand.Live, ModScenes.command(Scene.LIVE))
        assertEquals(ChatCommand.Brb, ModScenes.command(Scene.BRB))
        assertEquals(ChatCommand.Privacy, ModScenes.command(Scene.PRIVACY))
        assertEquals(ChatCommand.StartingSoon, ModScenes.command(Scene.STARTING_SOON))
        assertEquals(ChatCommand.Ending, ModScenes.command(Scene.ENDING))
        assertTrue(Scene.CUSTOM.all { ModScenes.command(it) == null })
        assertEquals("Lunch screen on", ModReplies.sceneOn("Lunch"))
        assertEquals("No scene by that name. Try: Live, BRB, Lunch", ModReplies.noScene(listOf("Live", "BRB", "Lunch")))
    }
}
