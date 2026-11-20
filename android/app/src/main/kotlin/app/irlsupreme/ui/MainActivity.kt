package app.irlsupreme.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.irlsupreme.engine.EngineState
import app.irlsupreme.engine.StreamEngine
import app.irlsupreme.engine.video.BrandType
import app.irlsupreme.service.StreamService
import app.irlsupreme.ui.theme.FullbarsTheme
import app.irlsupreme.ui.theme.LocalFbColors

class MainActivity : ComponentActivity() {
    private var engine by mutableStateOf<StreamEngine?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            engine = (binder as StreamService.LocalBinder).engine
        }
        override fun onServiceDisconnected(name: ComponentName) { engine = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The stream graphics (cards, alerts) draw in Archivo and JetBrains Mono; load them before anything draws.
        // Idempotent, and the service does the same for a stream started without this screen.
        BrandType.load(applicationContext)
        // The streamer's scene-card words and pictures, for the previews and thumbnails (a few small PNGs, off main).
        kotlin.concurrent.thread(name = "card-styles") {
            app.irlsupreme.service.CardStore.apply(applicationContext)
            app.irlsupreme.service.AlertStyles.apply(applicationContext)
        }
        // Settles the mic's starting auto level on the very first launch, while it's still a fresh install (an update
        // before the first stream or mic page would otherwise make it look like an existing one). One small read.
        app.irlsupreme.service.MicSettings.state(applicationContext)
        bindService(Intent(this, StreamService::class.java), connection, BIND_AUTO_CREATE)
        // Only for a fresh launch: a recreation (dark mode, font size) would otherwise re-run the launch intent.
        if (savedInstanceState == null) handlePairingLink(intent)
        setContent {
            val e = engine
            if (e == null) {
                // Until the stream service binds (a moment): the themed background, not an empty window.
                FullbarsTheme { Box(Modifier.fillMaxSize().background(LocalFbColors.current.bg)) }
                return@setContent
            }
            val state by e.state.collectAsState()
            Root(e, state)
        }
    }

    @Composable
    private fun Root(engine: StreamEngine, state: EngineState) {
        // Hoisted above the live screen so setup choices (Test/Live, sign-in state) survive a stream.
        val setup = rememberSetupController(state.running, links = state.links.size)
        // The app takes the stream's shape: live, the shape actually streaming; otherwise the one chosen in Video, so a
        // landscape streamer sets up sideways even with rotation lock on, and going live doesn't turn the screen. The
        // welcome (not signed in yet) follows the phone. Either side of the shape, so a flip turns with the screen.
        val welcome = setup.loaded && setup.signIn !is SignIn.Checking && showsWelcome(setup)
        LaunchedEffect(state.running, state.portrait, setup.orientation, welcome) {
            requestedOrientation = screenOrientation(state.running, state.portrait, setup.orientation, welcome)
        }
        // While live the preview is the whole screen: the system bars hide (a swipe shows them for a moment) and come
        // back on exit. Keyed on running, so a recreation mid-stream (rotation) hides them again.
        LaunchedEffect(state.running) { showSystemBars(!state.running) }
        // Setup screens can show the stream key, Wi-Fi passwords and pairing links, and the live screen can reveal the
        // helper hotspot's password: no screenshots or recents preview then. Keyed, so it runs when one of them
        // changes, not on every live recomposition (twice a second).
        var secretShown by remember { mutableStateOf(false) }
        LaunchedEffect(state.running) { if (!state.running) secretShown = false }
        LaunchedEffect(state.running, secretShown, ScreenCapture.allowed) {
            setSecure((!state.running || secretShown) && !ScreenCapture.allowed)
        }
        CompositionLocalProvider(LocalStreamEngine provides engine) {
            if (state.running) {
                FullbarsTheme(forceDark = true) { LiveScreen(engine, state, isTest = setup.lastStartWasTest, onSecretShown = { secretShown = it }) }
            } else {
                FullbarsTheme { SetupApp(setup) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairingLink(intent)
    }

    /** A tapped fullbars://pair link: the setup screen asks before pairing. */
    private fun handlePairingLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW && data.scheme == "fullbars" && data.host == "pair") {
            app.irlsupreme.service.PendingPairing.link.value = data.toString()
        }
    }

    private fun showSystemBars(show: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (show) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun setSecure(secure: Boolean) {
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onDestroy() {
        unbindService(connection)
        super.onDestroy()
    }
}

/**
 * The stream engine, for the few places below Root that need it without it being threaded through every screen:
 * Home's recap plays a Test stream's last seconds from it, and the live mic sheet reads which mic is in use. Null
 * outside the activity (previews, tests).
 */
internal val LocalStreamEngine = staticCompositionLocalOf<StreamEngine?> { null }

/**
 * Lets setup screens be captured (FLAG_SECURE off) for screenshots. Only the debug build's DebugActivity
 * (src/debug, `--ez debug_screenshots true`) sets it; nothing in a release build writes it.
 */
object ScreenCapture {
    var allowed by mutableStateOf(false)
}

/** The activity's requested orientation (see Root). Pure, for tests. */
internal fun screenOrientation(running: Boolean, livePortrait: Boolean, chosen: StreamOrientation, welcome: Boolean): Int = when {
    running -> if (livePortrait) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    welcome -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    chosen == StreamOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
}
