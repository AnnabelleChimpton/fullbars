package app.irlsupreme.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.irlsupreme.service.BrbPairing
import app.irlsupreme.service.PendingPairing
import app.irlsupreme.ui.theme.FbMotion

/** The setup screens. The live screen isn't here: MainActivity shows it while streaming. */
enum class Screen { HOME, SETTINGS, TWITCH, CAMERA, CONNECTIONS, QUALITY, SCREEN_OFF, HELPERS, OVERLAYS, MIC, CARDS, ALERTS, ALSO_STREAM, CAPTIONS, MODS, HELP_ANOTHER, OVERLAY_SERVICES, ALERT_KIND, TTS }

/** A tiny back stack: no navigation library needed for seven screens. */
class Navigator(internal val stack: SnapshotStateList<Screen>) {
    val current: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1
    /** For the slide direction: forward on open, backward on back (not by screen order). */
    var lastWasBack by mutableStateOf(false); private set
    /**
     * Opens [s]. If it is already in the stack (Home › Twitch › … › Twitch), goes back to it instead of stacking a
     * duplicate, so Back never walks through the same page twice.
     */
    fun open(s: Screen) {
        if (current == s) return
        val at = stack.lastIndexOf(s)
        if (at >= 0) {
            lastWasBack = true
            while (stack.lastIndex > at) stack.removeAt(stack.lastIndex)
        } else {
            lastWasBack = false
            stack.add(s)
        }
    }
    fun back() { if (canGoBack) { lastWasBack = true; stack.removeAt(stack.lastIndex) } }
}

@Composable
fun rememberNavigator(): Navigator {
    val stack = rememberSaveable(
        saver = listSaver<SnapshotStateList<Screen>, String>(save = { l -> l.map { it.name } }, restore = { l -> mutableStateListOf(*l.map { Screen.valueOf(it) }.toTypedArray()) }),
    ) { mutableStateListOf(Screen.HOME) }
    return remember(stack) { Navigator(stack) }
}

/** Home, Settings and the pages, with the pairing dialog, the permission explainer and snackbars on top. */
@Composable
fun SetupApp(c: SetupController) {
    val nav = rememberNavigator()
    val snackbars = remember { SnackbarHostState() }
    // Only while a sub-screen is open: at Home, back goes to the system (predictive back-to-home keeps working).
    BackHandler(enabled = nav.canGoBack) { nav.back() }

    LaunchedEffect(c.snackbar) {
        c.snackbar?.let { snackbars.showSnackbar(it); c.snackbar = null }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            val reduced = FbMotion.rememberReduced()
            AnimatedContent(
                targetState = nav.current,
                transitionSpec = {
                    // The incoming page arrives on Enter, the outgoing one leaves on Exit. Reduce motion
                    // makes it a cut.
                    if (reduced) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        val forward = !nav.lastWasBack
                        val enter = tween<IntOffset>(FbMotion.ENTER_MS, easing = FbMotion.Enter)
                        val exit = tween<IntOffset>(FbMotion.EXIT_MS, easing = FbMotion.Exit)
                        (slideInHorizontally(enter) { w -> if (forward) w / 4 else -w / 4 } +
                            fadeIn(tween(FbMotion.ENTER_MS, easing = FbMotion.Enter))) togetherWith
                            (slideOutHorizontally(exit) { w -> if (forward) -w / 4 else w / 4 } +
                                fadeOut(tween(FbMotion.EXIT_MS, easing = FbMotion.Exit)))
                    }
                },
                label = "screen",
            ) { screen ->
                when (screen) {
                    Screen.HOME -> HomeScreen(c, nav)
                    Screen.SETTINGS -> SettingsScreen(c, nav)
                    Screen.TWITCH -> TwitchPage(c, nav)
                    Screen.CAMERA -> CameraPage(c, nav)
                    Screen.CONNECTIONS -> ConnectionsPage(c, nav)
                    Screen.QUALITY -> QualityPage(c, nav)
                    Screen.SCREEN_OFF -> ScreenOffPage(c, nav)
                    Screen.HELPERS -> HelpersPage(c, nav)
                    Screen.OVERLAYS -> OverlaysPage(c, nav)
                    Screen.MIC -> MicPage(nav)
                    Screen.CARDS -> CardsPage(c, nav)
                    Screen.ALERTS -> AlertsPage(c, nav)
                    Screen.ALSO_STREAM -> AlsoStreamPage(c, nav)
                    Screen.CAPTIONS -> CaptionsPage(nav)
                    Screen.MODS -> ModsPage(c, nav)
                    Screen.HELP_ANOTHER -> HelpAnotherPage(c, nav)
                    Screen.OVERLAY_SERVICES -> OverlayServicesPage(c, nav)
                    Screen.ALERT_KIND -> AlertKindPage(c, nav)
                    Screen.TTS -> TtsPage(c, nav)
                }
            }
            // Home's pinned go-live bar is tall; keep snackbars above it, but only while it shows (not on the welcome).
            SnackbarHost(
                snackbars,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                    .padding(bottom = if (nav.current == Screen.HOME && showsGoLiveBar(c)) 230.dp else 16.dp),
            )
        }
    }

    PairingDialog(c)
    if (c.showPermissionExplainer) PermissionExplainer(c)
}

/**
 * A QR scan, a pasted link or a tapped fullbars://pair link: confirm before pairing. Anyone can put a
 * fullbars://pair link on a web page, and the label in it is whatever its maker chose, so the dialog leads with the
 * address, says what the server will receive, and asks "Replace" when it would replace the server already paired.
 */
@Composable
private fun PairingDialog(c: SetupController) {
    val link by PendingPairing.link.collectAsState()
    val l = link ?: return
    val p = remember(l) { BrbPairing.parse(l) }
    if (p == null) {
        AlertDialog(
            onDismissRequest = c::cancelPairing,
            title = { Text("Can't pair") },
            text = { Text("That isn't a Fullbars pairing code.") },
            confirmButton = { TextButton(onClick = c::cancelPairing) { Text("OK") } },
        )
        return
    }
    val current = c.pairing
    val replaces = current != null && (current.host != p.host || current.port != p.port)
    AlertDialog(
        onDismissRequest = { if (!c.pairingBusy) c.cancelPairing() },
        title = { Text(if (replaces) "Replace your server?" else "Pair with this server?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (replaces && current != null) {
                    Text("Now: ${hostPort(current)}")
                    Text("New: ${hostPort(p)}" + calledLine(p))
                } else {
                    Text(hostPort(p) + calledLine(p))
                }
                Text("This server will receive your video and your Twitch stream key. Only pair a server you run.")
                c.pairingError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = { c.confirmPairing(l) }, enabled = !c.pairingBusy) {
                if (c.pairingBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(
                    when {
                        c.pairingError != null -> "Try again"
                        replaces -> "Replace"
                        else -> "Pair"
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = c::cancelPairing, enabled = !c.pairingBusy) { Text("Cancel") } },
    )
}

/** "203.0.113.7:5000", with brackets for an IPv6 address. */
private fun hostPort(p: BrbPairing) = (if (':' in p.host) "[${p.host}]" else p.host) + ":${p.port}"

/** The name in the link, only when it says something the address doesn't (no "1.2.3.4 (1.2.3.4)"). */
private fun calledLine(p: BrbPairing): String =
    p.name?.takeIf { it.isNotBlank() && it != p.host }?.let { "\ncalls itself \u201c$it\u201d" } ?: ""

@Composable
private fun PermissionExplainer(c: SetupController) {
    AlertDialog(
        onDismissRequest = c::dismissPermissionExplainer,
        title = { Text("Camera and microphone") },
        text = {
            Text(
                "Fullbars needs the camera and microphone to stream. Notifications show that you're live, " +
                    "and let you get back to the live screen.",
            )
        },
        confirmButton = { TextButton(onClick = c::confirmPermissionExplainer) { Text("Continue") } },
        dismissButton = { TextButton(onClick = c::dismissPermissionExplainer) { Text("Not now") } },
    )
}
