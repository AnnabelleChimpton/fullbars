package app.irlsupreme.service

import app.irlsupreme.engine.Scene
import app.irlsupreme.engine.video.Slates
import java.util.Locale

/**
 * What each scene is called, the streamer's own ones included ("Lunch", "Q&A"), and how a typed name finds one
 * (`!scene lunch`). Pure but for [created], which [CardStore] keeps current so the live screen, chat commands and the
 * mod remote all use the same names. Logs use the enum's name ([logName]), which never changes when a scene is renamed.
 */
object SceneNames {
    /** The longest name a scene can have: it's the card's tag, a key in the Scenes sheet and a word mods type. */
    const val MAX_NAME = 20

    /** The scenes everyone has, in the Scenes sheet's order. */
    val BUILT_IN: List<Scene> = listOf(Scene.LIVE, Scene.BRB, Scene.PRIVACY, Scene.STARTING_SOON, Scene.ENDING)

    /** The streamer's own scenes that exist now, in slot order, with their names. Set by [CardStore]. */
    @Volatile var created: Map<Scene, String> = emptyMap()

    /** A built-in scene's name; null for the streamer's own. */
    fun builtInName(scene: Scene): String? = when (scene) {
        Scene.LIVE -> "Live"
        Scene.BRB -> "BRB"
        Scene.PRIVACY -> "Privacy"
        Scene.STARTING_SOON -> "Starting soon"
        Scene.ENDING -> "Ending"
        Scene.CUSTOM_1, Scene.CUSTOM_2, Scene.CUSTOM_3, Scene.CUSTOM_4 -> null
    }

    /** What an unnamed custom scene goes by ("Scene 1"), the same words its card prints. */
    fun defaultName(scene: Scene): String = Slates.Card.of(scene)?.headline ?: scene.name

    /** [scene]'s name: its own for the built-ins, the streamer's for theirs ([custom]), else the slot's. */
    fun name(scene: Scene, custom: Map<Scene, String> = created): String =
        builtInName(scene) ?: custom[scene]?.let { clean(it) }?.ifEmpty { null } ?: defaultName(scene)

    /** A typed name as it's kept: one line, spaces collapsed, at most [MAX_NAME] characters. */
    fun clean(raw: String): String = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(MAX_NAME).trimEnd()

    /** A name reduced to what a mod's typing must match: lower case letters and digits only ("Q&A" -> "qa"). */
    fun slug(name: String): String = name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /** What each built-in answers to in `!scene`, besides its name. */
    private fun aliases(scene: Scene): List<String> = when (scene) {
        Scene.LIVE -> listOf("Live", "camera", "cam")
        Scene.BRB -> listOf("BRB", "be right back")
        Scene.PRIVACY -> listOf("Privacy")
        Scene.STARTING_SOON -> listOf("Starting soon", "starting", "start")
        Scene.ENDING -> listOf("Ending")
        else -> emptyList()
    }

    /**
     * The scene [query] names, case and punctuation aside: a built-in by its name or a short word ("live", "brb",
     * "starting"), else one of the streamer's own that exists ([custom]) by its name, then by its letters and digits
     * ("q&a", "QA"). Null when nothing matches.
     */
    fun match(query: String, custom: Map<Scene, String> = created): Scene? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val s = slug(q)
        if (s.isNotEmpty()) BUILT_IN.firstOrNull { b -> aliases(b).any { slug(it) == s } }?.let { return it }
        custom.entries.firstOrNull { clean(it.value).equals(clean(q), ignoreCase = true) }?.let { return it.key }
        if (s.isEmpty()) return null
        return custom.entries.firstOrNull { slug(name(it.key, custom)) == s }?.key
    }

    /**
     * What's wrong with [raw] as [scene]'s name, in plain words, or null. Blank is fine (the slot's own name is used);
     * a name with no letters or digits can't be typed after `!scene`; one that a built-in or another of the streamer's
     * scenes already answers to would send `!scene` to the other one.
     */
    fun nameProblem(raw: String, scene: Scene, custom: Map<Scene, String>): String? {
        val n = clean(raw)
        if (n.isEmpty()) return null
        val s = slug(n)
        if (s.isEmpty()) return "Use at least one letter or number, so mods can type it."
        BUILT_IN.firstOrNull { b -> aliases(b).any { slug(it) == s } }?.let { return "${builtInName(it)} is already a scene. Pick another name." }
        custom.keys.firstOrNull { it != scene && slug(name(it, custom)) == s }?.let { return "Your scene ${name(it, custom)} has that name. Pick another." }
        return null
    }

    /** The streamer's own scenes that exist, in slot order, with their names as shown. */
    fun created(texts: Map<Slates.Card, CardText>): Map<Scene, String> = buildMap {
        for (scene in Scene.CUSTOM) {
            val t = texts[Slates.Card.of(scene)] ?: continue
            if (t.created) put(scene, name(scene, mapOf(scene to t.name)))
        }
    }

    /** The scenes to offer (the Scenes sheet, the mod remote): the built-ins, then the streamer's own that exist. */
    fun offered(custom: Map<Scene, String> = created): List<Scene> = BUILT_IN + Scene.CUSTOM.filter { it in custom }

    /** The scene that shows [card], if any (the automatic cards aren't scenes). */
    fun sceneOf(card: Slates.Card): Scene? = Scene.entries.firstOrNull { Slates.Card.of(it) == card }

    /** A scene in the session log and other records: the enum's name ("CUSTOM_1"), stable whatever it's called. */
    fun logName(scene: Scene): String = scene.name
}
