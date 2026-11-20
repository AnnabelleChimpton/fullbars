package app.irlsupreme.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.irlsupreme.dji.DjiScanner
import app.irlsupreme.dji.FoundCamera
import app.irlsupreme.service.AlsoStreamTo
import app.irlsupreme.service.BrbClient
import app.irlsupreme.service.BrbException
import app.irlsupreme.service.BrbPairing
import app.irlsupreme.service.BrbPairingStore
import app.irlsupreme.service.BrbStatus
import app.irlsupreme.service.CameraPrefs
import app.irlsupreme.service.ExternalCamera
import app.irlsupreme.service.HelperService
import app.irlsupreme.service.Helpers
import app.irlsupreme.service.PendingPairing
import app.irlsupreme.service.SecretStore
import app.irlsupreme.service.SessionStore
import app.irlsupreme.service.StreamService
import app.irlsupreme.service.Twitch
import app.irlsupreme.service.srtlaReachable
import app.irlsupreme.engine.Facing
import app.irlsupreme.engine.SessionSummary
import app.irlsupreme.service.UnexpectedEnd
import app.irlsupreme.twitch.ChannelInfo
import app.irlsupreme.twitch.DeviceCode
import app.irlsupreme.twitch.HelixClient
import app.irlsupreme.twitch.TwitchCategory
import app.irlsupreme.twitch.TwitchConfig
import app.irlsupreme.twitch.TwitchException
import app.irlsupreme.twitch.isTransient
import app.irlsupreme.twitch.userMessage
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Quality presets: the user picks a "Quality", never a bitrate or codec. Output is always H.264. */
enum class Quality(val title: String, val detail: String, val width: Int, val height: Int, val bitrate: Int) {
    HD1080("1080p", "Sharpest", 1920, 1080, 6_000_000),
    HD720("720p", "Steadier on weak signal", 1280, 720, 3_500_000),
    ;

    /** Video + audio, as the pre-flight check estimates it. */
    val gbPerHour: Double get() = (bitrate / 1000 + 128) * 1000.0 * 3600 / 8 / 1e9
    val mbps: String get() = if (bitrate % 1_000_000 == 0) "${bitrate / 1_000_000}" else "%.1f".format(bitrate / 1e6)
    /** "6 Mb/s": one unit everywhere, never kbps or Mbps. */
    val rate: String get() = "$mbps Mb/s"
    /** "Up to 6 Mb/s · about 2.8 GB per hour" */
    val summary: String get() = "Up to $rate · about %.1f GB per hour".format(gbPerHour)
}

/**
 * Which way the stream is shaped, chosen before going live and fixed for the whole stream (like PRISM Live, Larix and
 * Streamlabs). The live screen locks to it; turning the phone to its other side flips the picture with the screen.
 */
enum class StreamOrientation(val title: String, val detail: String) {
    LANDSCAPE("Landscape", "16:9, the usual shape for Twitch"),
    PORTRAIT("Portrait", "9:16, vertical video. Hold the phone upright"),
    ;

    /** Output size for [q]: the quality's landscape size, turned on its side for portrait. */
    fun size(q: Quality): Pair<Int, Int> = if (this == PORTRAIT) q.height to q.width else q.width to q.height
}

/** Twitch's global RTMPS ingest; used only for the paste-a-key fallback (sign-in picks the ingest itself). */
const val DEFAULT_INGEST = "rtmps://ingest.global-contribute.live-video.net:443/app/"
private const val TWITCH_INGEST_HOST = "ingest.global-contribute.live-video.net"

private val AGGRESSIVE_OEMS = setOf("oneplus", "oppo", "realme")

/** OnePlus/Oppo/Realme stop background apps ~5 min after screen-off unless "Allow background activity" is on. */
val isAggressiveOem: Boolean get() = Build.MANUFACTURER.lowercase() in AGGRESSIVE_OEMS

sealed interface SignIn {
    data object Checking : SignIn
    data object SignedOut : SignIn
    data class Waiting(val code: DeviceCode) : SignIn
    data class SignedIn(val login: String) : SignIn
}

/** Why the main button is disabled, with where to fix it. */
enum class Blocker(val text: String, val action: String?) {
    PAIR_SERVER("Pair your BRB server, or turn off Combine connections", "Set up"),
    SIGN_IN("Sign in to Twitch to go live", "Sign in"),
    /** Device-code sign-in in progress: no action, or a second tap would throw away the code being approved. */
    APPROVING("Approve the sign-in on Twitch to continue", null),
    CHECKING("Checking your Twitch account…", null),
    ADD_KEY("Add your Twitch stream key", "Add"),
    // The Pocket's blockers use the Camera page's step words (PocketSteps): step 1 "Find your Pocket 3", step 2 "Choose
    // its hotspot or Wi‑Fi". One name per thing: Pocket 3 (the camera), hotspot, Wi‑Fi. After the first real stream the
    // old "Choose your camera for Camera link" read as a riddle.
    CHOOSE_POCKET("Find your Pocket 3 first", "Set up"),
    CAMERA_WIFI("Add the hotspot or Wi‑Fi for your Pocket 3", "Add"),
    HOTSPOT_OFF("Turn on your hotspot so your Pocket 3 can join it", "Settings"),
}

/**
 * The Home verdict line. [ready] is the only case that may say "Ready for 1080p" and carry the filled
 * square; [text] is otherwise the reason, so the line never claims more than the button can do.
 */
internal data class ReadyVerdict(val text: String, val ready: Boolean, val suggest720: Boolean = false)

/**
 * One pure source for the verdict, gated on [goLiveBlocker] first: a phone with great signal still isn't "ready" while
 * sign-in or the camera link is missing (the old card said "Ready" before sign-in). Then the preflight readings decide:
 * still running ([preflight] null), good, or the worst finding in words.
 */
internal fun readyVerdict(blocker: Blocker?, preflight: Preflight?, quality: Quality): ReadyVerdict = when {
    blocker != null -> ReadyVerdict(blocker.text, ready = false)
    preflight == null -> ReadyVerdict("Checking your setup…", ready = false)
    preflight.verdict.level == Level.GOOD -> ReadyVerdict("Ready for ${quality.title}", ready = true)
    else -> ReadyVerdict(
        preflight.verdict.text, ready = false, suggest720 = preflight.verdict.suggest720 && quality == Quality.HD1080,
    )
}

/**
 * A clean test: it reached Twitch, nothing dropped, the video never had to recover and nothing failed. Only
 * then does Home hand the streamer straight on to "Go live now"; any doubt and Test stays selected.
 */
internal fun isCleanTest(s: SessionSummary, wasTest: Boolean): Boolean =
    wasTest && s.liveDurationMs > 0 && s.recoveredDrops == 0 && s.encoderRecoveries == 0 && s.lastError == null

/**
 * Combine on, Twitch chosen, but neither sign-in nor a key: the BRB server keeps whatever output it already has (set on
 * the server, or left from the last stream). Its default is to record on the server, which reaches no viewers.
 */
internal fun serverDecides(combine: Boolean, paired: Boolean, sendToObs: Boolean, useKey: Boolean, hasKey: Boolean, signIn: SignIn) =
    combine && paired && !sendToObs && !(signIn is SignIn.SignedIn && !useKey) && !(useKey && hasKey)

/**
 * Why Go live can't work yet, or null when it can. Pure, so the rules are tested (SetupLogicTest) and the button and the
 * hint under it can never disagree. [serverOutput] is the paired server's output kind from its last status, if known.
 */
internal fun goLiveBlocker(
    combine: Boolean,
    paired: Boolean,
    sendToObs: Boolean,
    useKey: Boolean,
    hasKey: Boolean,
    signIn: SignIn,
    serverOutput: String?,
    camera: ExternalCamera.Kind,
    pocketChosen: Boolean,
    cameraNet: ExternalCamera.CameraNet,
    hasCameraWifi: Boolean,
    hotspotUp: Boolean,
): Blocker? {
    val twitchSignIn = !useKey && !(combine && sendToObs)
    return when {
        combine && !paired -> Blocker.PAIR_SERVER
        twitchSignIn && signIn is SignIn.Waiting -> Blocker.APPROVING
        // Without this an early tap would fall into "server decides" and stream to whatever the server had.
        twitchSignIn && signIn is SignIn.Checking -> Blocker.CHECKING
        // The server would only record: nobody would see the stream, while the app said Live.
        serverDecides(combine, paired, sendToObs, useKey, hasKey, signIn) && serverOutput == "record" ->
            if (useKey) Blocker.ADD_KEY else Blocker.SIGN_IN
        !combine && twitchSignIn && signIn is SignIn.SignedOut -> Blocker.SIGN_IN
        !combine && useKey && !hasKey -> Blocker.ADD_KEY
        camera == ExternalCamera.Kind.POCKET && !pocketChosen -> Blocker.CHOOSE_POCKET
        camera == ExternalCamera.Kind.POCKET && cameraNet != ExternalCamera.CameraNet.PRIVATE && !hasCameraWifi -> Blocker.CAMERA_WIFI
        camera == ExternalCamera.Kind.POCKET && cameraNet == ExternalCamera.CameraNet.PHONE_HOTSPOT && !hotspotUp -> Blocker.HOTSPOT_OFF
        else -> null
    }
}

/**
 * 1080p only on phones that declare a media performance class (Android 12's S or later: flagship-grade encoders and
 * cooling); everything else starts at 720p, which streams cooler and steadier. Never saved, so a phone that
 * gets an update still gets the right default until the user picks one.
 */
internal fun defaultQuality(mediaPerformanceClass: Int): Quality =
    if (mediaPerformanceClass >= Build.VERSION_CODES.S) Quality.HD1080 else Quality.HD720

/**
 * People paste the whole server line from OBS or Twitch's dashboard ("rtmps://….live-video.net:443/app/live_123_abc").
 * Keep only the key after "/app/" so the URL the app builds isn't doubled; anything else stays exactly as typed.
 */
internal fun normalizeStreamKey(v: String): String {
    val t = v.trim()
    val prefix = Regex("^rtmps?://[^/\\s]+/app/", RegexOption.IGNORE_CASE).find(t) ?: return v
    return t.substring(prefix.range.last + 1)
}

/** Twitch's own tool for seeing a test stream arrive: nobody, the streamer included, can watch a bandwidth test. */
const val TWITCH_INSPECTOR_URL = "https://inspector.twitch.tv/"

private const val TAG = "Setup"

/** SecretStore key for a Twitch sign-in waiting for approval (see SetupController.resumePendingSignIn). */
private const val PENDING_SIGN_IN = "twitch_pending_sign_in"

/**
 * Everything the setup screens (Home, Settings and its pages) show and do. This is the logic that used to live inside
 * the single SetupScreen composable, moved here unchanged so several screens can share it; the screens are stateless.
 * Lives above the live screen, so choices like Test/Live survive a stream within one app session.
 */
class SetupController internal constructor(private val ctx: Context, private val scope: CoroutineScope) {
    private val twitch = Twitch.get(ctx)
    private val secrets = SecretStore(ctx)
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val pairingStore = BrbPairingStore(secrets)
    private val session = SessionStore(ctx)
    private val djiScanner = DjiScanner(ctx)
    private var signInJob: Job? = null
    private var loadSignInJob: Job? = null

    // Wired by rememberSetupController to activity-result launchers.
    internal var launchPermissions: (Array<String>) -> Unit = {}
    internal var launchDjiPermissions: (Array<String>) -> Unit = {}
    internal var launchQrScan: () -> Unit = {}
    /** Whether the activity is at least STARTED: Android 12+ refuses a foreground service started from the background. */
    internal var isStarted: () -> Boolean = { true }

    /**
     * Secret-backed fields (stream key, camera Wi-Fi, pairing, tip tokens, overlay URL) load off the main thread at
     * start-up: each is a Keystore decrypt, and there are several. The screens wait for this (a frame or two).
     */
    var loaded by mutableStateOf(false); private set

    // Twitch
    /** False in a build without a Twitch client ID (debug only: release builds refuse to build without one). */
    val signInAvailable: Boolean = twitch.config.isConfigured
    var signIn by mutableStateOf<SignIn>(SignIn.Checking); private set
    var signInError by mutableStateOf<String?>(null); private set
    var useKey by mutableStateOf(!signInAvailable || prefs.getBoolean("use_key", false)); private set
    var key by mutableStateOf(""); private set
    var ingest by mutableStateOf(prefs.getString("ingest", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_INGEST); private set
    /** A blank ingest field means Twitch's default (it's saved as typed, so it must never break the stream URL). */
    private val effectiveIngest: String get() = ingest.trim().ifBlank { DEFAULT_INGEST }

    /** The signed-in channel's title and category, as Twitch last reported them (null until loaded). */
    var channel by mutableStateOf<ChannelInfo?>(null); private set

    // This stream
    /**
     * Test (Twitch bandwidth test, not public) is the default on every launch, deliberately: nobody goes public by
     * accident, and the Test/Live choice sits next to the button so nobody streams to no one without seeing it.
     */
    var testMode by mutableStateOf(true)
    var quality by mutableStateOf(
        Quality.entries.firstOrNull { it.name == prefs.getString("quality", null) } ?: defaultQuality(Build.VERSION.MEDIA_PERFORMANCE_CLASS),
    ); private set
    var orientation by mutableStateOf(
        StreamOrientation.entries.firstOrNull { it.name == prefs.getString("orientation", null) } ?: StreamOrientation.LANDSCAPE,
    ); private set
    private val outW: Int get() = orientation.size(quality).first
    private val outH: Int get() = orientation.size(quality).second

    // Camera
    var cameraKind by mutableStateOf(ExternalCamera.kind(ctx)); private set
    /** Which phone camera a stream starts on (Settings › Camera, "Start on"); flipping while live doesn't change it. */
    var startFacing by mutableStateOf(CameraPrefs.startFacing(ctx)); private set
    var labsDji by mutableStateOf(ExternalCamera.labsDji(ctx)); private set
    var cameraNet by mutableStateOf(ExternalCamera.cameraNet(ctx)); private set
    var camSsid by mutableStateOf(""); private set
    var camPassword by mutableStateOf(""); private set
    var pocketDevice by mutableStateOf(ExternalCamera.pocket(ctx)); private set
    var scanning by mutableStateOf(false); private set
    var found by mutableStateOf<List<FoundCamera>>(emptyList()); private set
    var scanError by mutableStateOf<String?>(null); private set
    /** Whether the phone's own hotspot is on, for a Pocket 3 that joins it. Assumed on until checked. */
    var hotspotUp by mutableStateOf(true); private set

    // Combine connections / BRB server
    var combine by mutableStateOf(prefs.getBoolean("combine", false)); private set
    var sendToObs by mutableStateOf(prefs.getBoolean("send_to_obs", false)); private set
    var pairing by mutableStateOf<BrbPairing?>(null); private set
    var brbStatus by mutableStateOf<String?>(null); private set
    var brbOk by mutableStateOf(false); private set
    /** The paired server's last status (its output, test flag, slate), or null when unknown or unreachable. */
    var brbServerStatus by mutableStateOf<BrbStatus?>(null); private set
    /** The control API answers over TCP but SRTLA gets no answer on UDP: a firewall rule is missing. */
    var brbUdpBlocked by mutableStateOf(false); private set
    private var brbLatencyMs by mutableStateOf<Long?>(null)
    private var brbChecked by mutableStateOf(false)
    var pasteLink by mutableStateOf(""); private set
    var pairingBusy by mutableStateOf(false); private set
    var pairingError by mutableStateOf<String?>(null); private set

    // Going live
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    /** The last go-live failure needs a fresh Twitch sign-in. */
    var errorNeedsSignIn by mutableStateOf(false); private set
    /** The last go-live failure was the BRB server: offer Connection settings. */
    var errorNeedsServer by mutableStateOf(false); private set
    /** Camera or mic access was refused; offer the app's system settings. */
    var permissionDenied by mutableStateOf(false); private set
    var showPermissionExplainer by mutableStateOf(false); private set

    // Stream info sheet (title and category), shown before going public
    var streamInfoOpen by mutableStateOf(false); private set
    var streamInfoBusy by mutableStateOf(false); private set
    var streamInfoError by mutableStateOf<String?>(null); private set
    var categoryResults by mutableStateOf<List<TwitchCategory>>(emptyList()); private set
    /** Saving the sheet carries on into going live (it opened from the main button, not from the Home row). */
    var streamInfoGoesLive by mutableStateOf(false); private set
    private var categorySearchJob: Job? = null

    // After a stream
    var unexpectedEnd by mutableStateOf<UnexpectedEnd?>(null); private set
    var recap by mutableStateOf<SessionSummary?>(null); private set
    /** Whether the most recent start was a Twitch test stream (for the live screen and the recap). */
    val lastStartWasTest: Boolean get() = prefs.getBoolean("live_test", false)
    /**
     * Whether the most recent start went through the BRB server. Only then do viewers see the BRB screen through a
     * drop; on a direct stream every drop is a real disconnect from Twitch, and the recap must say so.
     */
    val lastStartWasBonded: Boolean get() = prefs.getBoolean("live_bonded", false)

    /** One-shot message for the snackbar. */
    var snackbar by mutableStateOf<String?>(null)

    /**
     * After DONE: the recap folded into one line ("0:01:22 · held"), until the next stream. In memory only: it's a
     * reminder of what just happened, not a record.
     */
    var lastStream by mutableStateOf<SessionSummary?>(null); private set
    /** The bonding links the last stream used, when this app session saw it (null after a restart: unknown). */
    var recapLinks by mutableStateOf<Int?>(null); private set
    /**
     * A clean test just ended and the bus moved to LIVE by itself: Home moves focus to "Go live now" once, then clears
     * this.
     */
    var handoffToLive by mutableStateOf(false); internal set
    /** The recap whose count-up and announcement already ran (by end time), so they play once, not on every return. */
    private var recapIntroFor = 0L
    private var engineRunning = false
    private var peakLinks = 0

    init {
        scope.launch {
            val s = withContext(Dispatchers.IO) {
                val wifi = ExternalCamera.cameraWifi(ctx) // one read for both fields
                LoadedSecrets(
                    key = secrets.get("stream_key") ?: "",
                    camSsid = wifi?.first ?: "", camPassword = wifi?.second ?: "",
                    pairing = pairingStore.load(),
                    overlayUrl = secrets.get("overlay_url") ?: "",
                    tipsSeJwt = secrets.get("tips_se_jwt") ?: "",
                    tipsSlToken = secrets.get("tips_sl_token") ?: "",
                )
            }
            key = s.key; camSsid = s.camSsid; camPassword = s.camPassword; pairing = s.pairing
            overlayUrl = s.overlayUrl; tipsSeJwt = s.tipsSeJwt; tipsSlToken = s.tipsSlToken
            loaded = true
        }
    }

    private class LoadedSecrets(
        val key: String, val camSsid: String, val camPassword: String, val pairing: BrbPairing?,
        val overlayUrl: String, val tipsSeJwt: String, val tipsSlToken: String,
    )

    // ---- derived state ----

    /** Combine on, Twitch chosen, but neither sign-in nor a key: the BRB server keeps whatever output it has. */
    val serverDecides: Boolean
        get() = serverDecides(combine, pairing != null, sendToObs, useKey, key.isNotBlank(), signIn)

    /** The stream goes to the user's home OBS (through the paired BRB server), not Twitch. */
    val sendsToObs: Boolean get() = combine && pairing != null && sendToObs

    /** In server-decides mode, the server's own output records only: nothing reaches viewers. */
    private val serverOnlyRecords: Boolean get() = serverDecides && brbServerStatus?.outputKind == "record"

    /**
     * The server has an output of its own (set on the server) that isn't just recording, so "server decides" really
     * streams somewhere. Home then skips the sign-in welcome for this setup.
     */
    val serverHasOwnOutput: Boolean get() = serverDecides && brbServerStatus?.outputKind.let { it != null && it != "record" }

    /** Where the stream goes and how, in words: the Home Twitch row and Settings › Twitch both use this. */
    val destinationSummary: String
        get() {
            val s = signIn
            return when {
                sendsToObs -> "Through your BRB server" + ((s as? SignIn.SignedIn)?.let { " · chat as ${it.login}" } ?: "")
                serverOnlyRecords -> if (useKey) "No stream key yet · your BRB server would only record"
                else "Not signed in · your BRB server would only record"
                serverDecides -> if (useKey) "No stream key, so your BRB server's own settings" else "Your BRB server's own settings"
                // Key mode with a sign-in: chat, alerts and mod commands follow the signed-in account, so name it.
                useKey -> when {
                    key.isBlank() -> "Stream key · not added yet"
                    s is SignIn.SignedIn -> "Stream key · chat from ${s.login}'s channel"
                    else -> "Stream key"
                }
                s is SignIn.Checking -> "Checking your account…"
                s is SignIn.SignedIn -> "Signed in as ${s.login}"
                s is SignIn.Waiting -> "Waiting for you to approve on Twitch"
                else -> "Not signed in"
            }
        }

    /** The Test/Live choice does something only when the phone tells Twitch (directly or via the server). */
    val audienceApplies: Boolean get() = !sendsToObs && !serverDecides

    /** The stream goes to the signed-in channel, so its title and category are what viewers will see. */
    val showsStreamInfo: Boolean get() = signInAvailable && signIn is SignIn.SignedIn && !useKey && !sendsToObs

    val blocker: Blocker?
        get() = if (!loaded) null else goLiveBlocker(
            combine = combine, paired = pairing != null, sendToObs = sendToObs, useKey = useKey, hasKey = key.isNotBlank(),
            signIn = signIn, serverOutput = brbServerStatus?.outputKind,
            camera = cameraKind, pocketChosen = pocketDevice != null, cameraNet = cameraNet,
            hasCameraWifi = camSsid.isNotBlank(), hotspotUp = hotspotUp,
        )

    /** Mirrors [blocker] exactly, so the button and the hint under it never disagree. */
    val canGo: Boolean get() = loaded && !busy && blocker == null

    /** A clean test's recap is on screen and the bus is on LIVE: the CTA reads "Go live now". */
    val cleanTestHandoff: Boolean
        get() = audienceApplies && !testMode && recap?.let { isCleanTest(it, lastStartWasTest) } == true

    val mainLabel: String
        get() = when {
            sendsToObs -> "Start sending to My OBS"
            testMode && audienceApplies -> "Start test"
            cleanTestHandoff -> "Go live now"
            else -> "Go live"
        }

    /** Where the pre-flight check probes (Twitch's ingest; the BRB server is probed by this controller, see [brbCheck]). */
    val checkHost: String
        get() = if (combine) pairing?.host ?: TWITCH_INGEST_HOST
        else Uri.parse(effectiveIngest.replace("rtmps://", "https://")).host ?: TWITCH_INGEST_HOST
    val checkPort: Int get() = if (combine) pairing?.port ?: 443 else 443
    val checkingBrbServer: Boolean get() = combine && pairing != null

    /** The BRB server's health for the Ready check, from the same probe as the Home Connection row (so they agree). */
    val brbCheck: BrbCheck?
        get() = if (!checkingBrbServer) null
        else BrbCheck(checked = brbChecked, reachable = brbOk, udpBlocked = brbUdpBlocked, ms = brbLatencyMs, port = checkPort)

    val rtmpUrls: List<String> get() = ExternalCamera.urls(ctx)

    // ---- actions ----

    /** Signed in with a token from an older version that didn't ask for moderator:read:followers, so no follow alerts. */
    var followAlertsNeedSignIn by mutableStateOf(false); private set

    /**
     * At launch and on every return to the app. Shows the saved account at once (no "Checking" flash, no welcome for
     * someone already signed in), then refreshes the token if it is due. Only a definite "sign in again" from Twitch
     * signs the user out: no signal, Twitch busy or a captive portal keeps the account, and going live checks again
     * through prepareBroadcast. Skipped while a sign-in is in progress, so returning from the browser can't undo it.
     */
    internal fun reloadSignIn() {
        if (signIn is SignIn.Waiting || signInJob?.isActive == true || loadSignInJob?.isActive == true) return
        loadSignInJob = scope.launch { loadSignIn() }
    }

    private suspend fun loadSignIn() {
        if (!signInAvailable) { signIn = SignIn.SignedOut; return }
        if (resumePendingSignIn()) return
        val stored = withContext(Dispatchers.IO) { runCatching { twitch.session.stored() }.getOrNull() }
        if (stored == null) {
            signIn = SignIn.SignedOut; followAlertsNeedSignIn = false; channel = null
            return
        }
        val savedLogin = stored.login ?: "your channel"
        if (signIn !is SignIn.SignedIn) signIn = SignIn.SignedIn(savedLogin)
        followAlertsNeedSignIn = !stored.hasScopes(listOf(TwitchConfig.SCOPE_READ_FOLLOWERS))
        try {
            val token = withContext(Dispatchers.IO) { twitch.session.current() }
            signIn = SignIn.SignedIn(token.login ?: savedLogin)
            followAlertsNeedSignIn = !token.hasScopes(listOf(TwitchConfig.SCOPE_READ_FOLLOWERS))
            if (channel == null) loadChannel()
        } catch (e: TwitchException) {
            Log.w(TAG, "sign-in check failed: ${e.message}")
            if (!e.isTransient) { signIn = SignIn.SignedOut; followAlertsNeedSignIn = false; channel = null }
        } catch (e: java.io.IOException) {
            Log.w(TAG, "sign-in check failed: ${e.javaClass.simpleName}")
        }
    }

    /** Title and category for Home and the stream info sheet. Best effort: Home just doesn't show them on failure. */
    private suspend fun loadChannel() {
        try {
            channel = withContext(Dispatchers.IO) { twitch.channelInfo() }
        } catch (e: TwitchException) {
            Log.w(TAG, "channel info failed: ${e.message}")
        }
    }

    /** Back to "unknown" when the pairing or Combine changes, so nothing stale about the old server shows. */
    internal fun resetBrbStatus() {
        brbStatus = null; brbOk = false; brbServerStatus = null; brbUdpBlocked = false; brbLatencyMs = null; brbChecked = false
    }

    /**
     * The paired server's status: its control API over TLS, then SRTLA over UDP (the stream's own path). Refreshed every
     * 30 s while the app is open and not streaming. "Checking…" shows only before the first answer, so the Home row
     * doesn't flicker on every refresh.
     */
    internal suspend fun refreshBrbStatus() {
        val p = pairing ?: return
        if (!combine) return
        if (brbStatus == null) brbStatus = "Checking your BRB server…"
        try {
            val t0 = System.nanoTime()
            val st = withContext(Dispatchers.IO) { BrbClient(p).status() }
            val ms = (System.nanoTime() - t0) / 1_000_000
            val udp = withContext(Dispatchers.IO) { srtlaReachable(p.host, p.port) }
            if (pairing != p) return
            brbOk = true; brbServerStatus = st; brbLatencyMs = ms; brbUdpBlocked = !udp
            brbStatus = if (!udp) "Answers, but UDP ${p.port} looks blocked"
            else "Ready · sends to ${st.outputLabel}" + if (st.phoneSlate) " · has your BRB screen" else ""
        } catch (e: java.io.IOException) {
            if (pairing != p) return
            brbOk = false; brbServerStatus = null; brbUdpBlocked = false; brbLatencyMs = null
            brbStatus = (e as? BrbException)?.message ?: "Can't reach your BRB server right now."
        }
        brbChecked = true
    }

    /** Re-checks the phone's hotspot for a Pocket 3 that joins it (it can be switched off from quick settings). */
    internal suspend fun refreshHotspot() {
        hotspotUp = if (!watchesHotspot) true
        else withContext(Dispatchers.IO) { ExternalCamera.phoneAddressFor(ctx, ExternalCamera.CameraNet.PHONE_HOTSPOT) != null }
    }

    internal val watchesHotspot: Boolean
        get() = cameraKind == ExternalCamera.Kind.POCKET && cameraNet == ExternalCamera.CameraNet.PHONE_HOTSPOT

    /** Android's hotspot settings where the device has that page, otherwise the network settings. */
    fun openHotspotSettings() {
        for (action in listOf("android.settings.TETHER_SETTINGS", Settings.ACTION_WIRELESS_SETTINGS)) {
            if (runCatching { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    fun openInspector() = openUrl(ctx, TWITCH_INSPECTOR_URL)

    /**
     * After a stream ends (and at start-up): the unexpected-end explanation and the recap. [afterStream]: this app
     * session just watched the stream end, so a clean test hands on to Live (at a cold start Test stays the default).
     */
    internal fun refreshSession(afterStream: Boolean = false) {
        session.consumeUnexpectedEnd()?.let { unexpectedEnd = it }
        val r = session.unseenSummary()
        recap = r
        recapLinks = when {
            !lastStartWasBonded -> 1
            afterStream && peakLinks > 0 -> peakLinks
            else -> null
        }
        if (r != null) lastStream = null
        if (afterStream && r != null && audienceApplies && isCleanTest(r, lastStartWasTest)) {
            testMode = false
            handoffToLive = true
        }
    }

    /** Called as the engine starts and stops; [links] is how many bonding links it has right now. */
    internal fun onEngine(running: Boolean, links: Int) {
        if (running && !engineRunning) peakLinks = 0 // a new stream counts afresh
        engineRunning = running
        if (running) peakLinks = maxOf(peakLinks, links)
    }

    /** Whether the recap ending at [endedAtMs] still has to play its count-up and announcement: true only once. */
    internal fun claimRecapIntro(endedAtMs: Long): Boolean {
        if (recapIntroFor == endedAtMs) return false
        recapIntroFor = endedAtMs
        return true
    }

    fun dismissUnexpectedEnd() { unexpectedEnd = null }
    fun dismissRecap() { lastStream = recap; session.dismissSummary(); recap = null }

    /**
     * The sign-in to go back to if a fresh one is abandoned or fails: signing in again (for follow alerts) must not leave
     * someone signed out. The stored token is untouched until the new sign-in completes.
     */
    private var signInBefore: SignIn? = null

    fun startSignIn() {
        signInBefore = signIn as? SignIn.SignedIn
        signInError = null
        error = null; errorNeedsSignIn = false
        if (!signInAvailable) { signInError = TwitchException.MissingClientId().userMessage(); return }
        loadSignInJob?.cancel()
        signInJob?.cancel()
        signInJob = scope.launch {
            val code = try {
                withContext(Dispatchers.IO) { twitch.auth.startDeviceFlow() }
            } catch (e: TwitchException) {
                signInFailed(e.userMessage(), e.message); return@launch
            } catch (e: java.io.IOException) {
                signInFailed("Couldn't reach Twitch. Check your connection and try again.", e.javaClass.simpleName); return@launch
            }
            // Kept (encrypted) until it ends, so a sign-in survives the phone closing Fullbars in the background while
            // you approve in the browser: the next start picks it up (resumePendingSignIn).
            withContext(Dispatchers.IO) { secrets.put(PENDING_SIGN_IN, code.encode()) }
            signIn = SignIn.Waiting(code)
            openInBrowser(ctx, code.verificationUri)
            finishSignIn(code)
        }
    }

    /** Waits for the approval of [code] and takes the new sign-in, or says why not. Clears the pending sign-in. */
    private suspend fun finishSignIn(code: DeviceCode) {
        try {
            val token = withContext(Dispatchers.IO) { twitch.session.completeSignIn(code) }
            val again = signInBefore != null
            signIn = SignIn.SignedIn(token.login ?: "your channel")
            followAlertsNeedSignIn = !token.hasScopes(listOf(TwitchConfig.SCOPE_READ_FOLLOWERS))
            if (again) snackbar = if (followAlertsNeedSignIn) "Signed in again." else "Signed in again. Follow alerts are on."
            signInBefore = null
            loadChannel()
        } catch (e: TwitchException) {
            signInFailed(e.userMessage(), e.message)
        } catch (e: java.io.IOException) {
            signInFailed("Couldn't reach Twitch. Check your connection and try again.", e.javaClass.simpleName)
        } finally {
            if (signIn !is SignIn.Waiting) withContext(Dispatchers.IO + NonCancellable) { secrets.put(PENDING_SIGN_IN, null) }
        }
    }

    /** Back to the sign-in there was before (if any), with the reason shown where the streamer will see it. */
    private fun signInFailed(message: String, logged: String?) {
        Log.w(TAG, "sign-in failed: $logged")
        signIn = signInBefore ?: SignIn.SignedOut
        signInError = message
        signInBefore = null
    }

    /**
     * A sign-in that was waiting for approval when Fullbars was closed: carry on waiting for it (Twitch keeps the code for
     * 30 minutes), so approving in the browser still works after the phone closed the app. True if one was resumed.
     */
    private suspend fun resumePendingSignIn(): Boolean {
        val code = withContext(Dispatchers.IO) { secrets.get(PENDING_SIGN_IN)?.let { DeviceCode.decode(it) } } ?: return false
        if (System.currentTimeMillis() >= code.expiresAtEpochMs) {
            withContext(Dispatchers.IO) { secrets.put(PENDING_SIGN_IN, null) }
            return false
        }
        val stored = withContext(Dispatchers.IO) { runCatching { twitch.session.stored() }.getOrNull() }
        signInBefore = stored?.let { SignIn.SignedIn(it.login ?: "your channel") }
        signIn = SignIn.Waiting(code)
        signInJob?.cancel()
        signInJob = scope.launch { finishSignIn(code) }
        return true
    }

    fun reopenSignInPage() { (signIn as? SignIn.Waiting)?.let { openInBrowser(ctx, it.code.verificationUri) } }

    fun cancelSignIn() {
        signInJob?.cancel()
        signIn = signInBefore ?: SignIn.SignedOut
        signInBefore = null
        scope.launch(Dispatchers.IO) { secrets.put(PENDING_SIGN_IN, null) }
    }

    /** Signing in again while already signed in (for follow alerts): Home stays as it is, with the code on it. */
    val resigningIn: Boolean get() = signIn is SignIn.Waiting && signInBefore != null

    fun signOut() {
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { twitch.session.signOut() } }
            signIn = SignIn.SignedOut; channel = null; followAlertsNeedSignIn = false
        }
    }

    fun chooseUseKey(on: Boolean) {
        if (!on && !signInAvailable) { signInError = TwitchException.MissingClientId().userMessage(); return }
        useKey = on; error = null; signInError = null
        prefs.edit().putBoolean("use_key", on).apply()
    }

    /**
     * Encrypted on disk a moment after typing stops (a Keystore round trip per keystroke made typing stutter), and
     * again at go-live. A pasted "rtmps://…/app/KEY" line keeps only the key.
     */
    fun updateKey(v: String) {
        val k = normalizeStreamKey(v)
        key = k; error = null
        writeLater("stream_key") { secrets.put("stream_key", k.trim().ifEmpty { null }) }
    }

    // ---- stream info (title and category) ----

    /** From the Home row: look at or change the title and category without going live. */
    fun openStreamInfo() = showStreamInfo(goesLive = false)

    private fun showStreamInfo(goesLive: Boolean) {
        streamInfoGoesLive = goesLive
        streamInfoError = null
        categoryResults = emptyList()
        streamInfoOpen = true
        if (channel == null) scope.launch {
            streamInfoBusy = true
            try {
                channel = withContext(Dispatchers.IO) { twitch.channelInfo() }
            } catch (e: TwitchException) {
                Log.w(TAG, "channel info failed: ${e.message}")
                streamInfoError = "Couldn't load your title and category. " + e.userMessage()
            } finally {
                streamInfoBusy = false
            }
        }
    }

    fun dismissStreamInfo() {
        streamInfoOpen = false; categorySearchJob?.cancel(); categoryResults = emptyList()
    }

    /** Category suggestions while the user types, a moment after they pause. */
    fun searchCategories(query: String) {
        categorySearchJob?.cancel()
        if (query.isBlank()) { categoryResults = emptyList(); return }
        categorySearchJob = scope.launch {
            delay(300)
            categoryResults = try {
                withContext(Dispatchers.IO) { twitch.searchCategories(query) }
            } catch (e: TwitchException) {
                Log.w(TAG, "category search failed: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * Saves what changed (nothing is sent when nothing changed), then goes live if the sheet opened from the main
     * button. A failed save keeps the sheet open with the reason; [continueWithoutSaving] then still goes live.
     */
    fun saveStreamInfo(title: String, category: TwitchCategory?) {
        val t = title.trim()
        if (t.isEmpty()) { streamInfoError = "Add a title."; return }
        if (t.length > HelixClient.MAX_TITLE_LENGTH) { streamInfoError = "Keep the title under ${HelixClient.MAX_TITLE_LENGTH} characters."; return }
        val current = channel
        val newTitle = t.takeIf { it != current?.title }
        val newCategory = category?.id?.takeIf { it != current?.categoryId }
        if (newTitle == null && newCategory == null) { finishStreamInfo(); return }
        streamInfoError = null
        streamInfoBusy = true
        scope.launch {
            try {
                channel = withContext(Dispatchers.IO) { twitch.updateChannelInfo(newTitle, newCategory) }
                finishStreamInfo()
            } catch (e: TwitchException) {
                Log.w(TAG, "channel update failed: ${e.message}")
                if (e is TwitchException.SignInRequired || e is TwitchException.Unauthorized) { signIn = SignIn.SignedOut; channel = null }
                streamInfoError = "Couldn't save. " + e.userMessage()
            } finally {
                streamInfoBusy = false
            }
        }
    }

    /** After a failed load or save: go live with whatever Twitch has. */
    fun continueWithoutSaving() = finishStreamInfo()

    private fun finishStreamInfo() {
        val goLive = streamInfoGoesLive
        dismissStreamInfo()
        // A save that found the sign-in gone leaves a blocker ("Sign in"); the go-live bar shows it instead.
        if (goLive && blocker == null) startGoLive()
    }

    fun updateIngest(v: String) { ingest = v; prefs.edit().putString("ingest", v).apply() }
    fun resetIngest() = updateIngest(DEFAULT_INGEST)
    val ingestIsDefault: Boolean get() = effectiveIngest == DEFAULT_INGEST

    fun chooseQuality(q: Quality) { quality = q; prefs.edit().putString("quality", q.name).apply() }
    fun chooseOrientation(o: StreamOrientation) { orientation = o; prefs.edit().putString("orientation", o.name).apply() }

    fun chooseCamera(k: ExternalCamera.Kind) { cameraKind = k; ExternalCamera.setKind(ctx, k) }
    fun chooseStartFacing(f: Facing) { startFacing = f; CameraPrefs.setStartFacing(ctx, f) }

    fun setLabs(on: Boolean) {
        val wasPocket = cameraKind == ExternalCamera.Kind.POCKET
        labsDji = on
        ExternalCamera.setLabsDji(ctx, on)
        cameraKind = ExternalCamera.kind(ctx)
        if (!on && wasPocket) snackbar = "Camera switched to the phone camera"
    }

    fun chooseCameraNet(n: ExternalCamera.CameraNet) { cameraNet = n; ExternalCamera.setCameraNet(ctx, n) }
    fun updateCamSsid(v: String) { camSsid = v; saveCameraWifi() }
    fun updateCamPassword(v: String) { camPassword = v; saveCameraWifi() }
    private fun saveCameraWifi() {
        val (ssid, password) = camSsid to camPassword
        writeLater("camera_wifi") { ExternalCamera.setCameraWifi(ctx, ssid, password) }
    }

    private val pendingWrites = LinkedHashMap<String, Pair<Job, () -> Unit>>()

    /**
     * Saves a typed secret once typing pauses, off the main thread: each save is a Keystore encrypt. [flushWrites]
     * runs anything still waiting when the screen goes away (rotation, leaving the app) or before going live.
     */
    private fun writeLater(tag: String, write: () -> Unit) {
        pendingWrites.remove(tag)?.first?.cancel()
        val job = scope.launch {
            delay(WRITE_DELAY_MS)
            pendingWrites.remove(tag)
            withContext(Dispatchers.IO) { write() }
        }
        pendingWrites[tag] = job to write
    }

    internal fun flushWrites() {
        val waiting = pendingWrites.values.toList()
        pendingWrites.clear()
        waiting.forEach { (job, write) -> job.cancel(); write() }
    }

    private val djiPermissions = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }

    /** "Find my Pocket 3" / "Find another": asks for Nearby devices (Bluetooth), then scans. */
    fun findPocket() { scanError = null; launchDjiPermissions(djiPermissions.toTypedArray()) }

    internal fun onDjiPermissions(granted: Map<String, Boolean>) {
        if (granted.values.all { it }) startScan()
        else scanError = "Allow Nearby devices so Fullbars can find and start your Pocket 3."
    }

    private fun startScan() {
        found = emptyList(); scanning = true; scanError = null
        djiScanner.scan(8, onUpdate = { found = it.filter { c -> c.model == null || c.model in 0x0020..0x0022 } }) { problem ->
            scanning = false
            if (problem != null) scanError = problem
            else if (found.isEmpty()) scanError = "No Pocket 3 found. Turn it on, keep it close and close DJI Mimo."
        }
    }

    fun choosePocket(cam: FoundCamera) {
        ExternalCamera.setPocket(ctx, cam.address, cam.name)
        pocketDevice = cam.address to cam.name
        djiScanner.stop(); scanning = false; found = emptyList()
    }

    fun changeCombine(on: Boolean) { combine = on; error = null; errorNeedsServer = false; prefs.edit().putBoolean("combine", on).apply() }

    var weakSignalScreen by mutableStateOf(prefs.getBoolean("weak_signal_screen", true)); private set

    // Mod remote: the link comes from the paired server.
    var remoteLink by mutableStateOf<String?>(null); private set
    var remoteError by mutableStateOf<String?>(null); private set
    fun loadRemoteLink() = remoteCall { it.remoteLink() }
    fun resetRemoteLink() = remoteCall { it.resetRemote() }
    private fun remoteCall(op: (BrbClient) -> String) {
        val p = pairing ?: return
        remoteError = null
        scope.launch {
            try {
                remoteLink = withContext(Dispatchers.IO) { op(BrbClient(p)) }
            } catch (e: java.io.IOException) {
                remoteError = (e as? BrbException)?.message ?: "Can't reach your BRB server right now."
            }
        }
    }
    var readAloud by mutableStateOf(
        app.irlsupreme.service.ReadAloud.entries.firstOrNull { it.name == prefs.getString("read_aloud", null) } ?: app.irlsupreme.service.ReadAloud.OFF,
    ); private set
    fun changeReadAloud(r: app.irlsupreme.service.ReadAloud) { readAloud = r; prefs.edit().putString("read_aloud", r.name).apply() }

    // Overlays
    var overlays by mutableStateOf(app.irlsupreme.service.OverlayStore.load(ctx)); private set
    var overlayImageStamp by androidx.compose.runtime.mutableLongStateOf(app.irlsupreme.service.OverlayStore.imageFile(ctx).lastModified()); private set
    internal var launchImagePick: () -> Unit = {}
    // Loaded off the main thread in init, like the other secrets.
    var overlayUrl by mutableStateOf(""); private set
    var tipsSeJwt by mutableStateOf(""); private set
    var tipsSlToken by mutableStateOf(""); private set
    fun changeTipsSeJwt(v: String) { tipsSeJwt = v; writeLater("tips_se_jwt") { secrets.put("tips_se_jwt", v.trim().ifEmpty { null }) } }
    fun changeTipsSlToken(v: String) { tipsSlToken = v; writeLater("tips_sl_token") { secrets.put("tips_sl_token", v.trim().ifEmpty { null }) } }
    fun changeOverlayUrl(v: String) { overlayUrl = v; writeLater("overlay_url") { app.irlsupreme.service.OverlayStore.setBrowserUrl(ctx, v) } }

    fun updateOverlays(s: app.irlsupreme.service.OverlaySettings) { overlays = s; app.irlsupreme.service.OverlayStore.save(ctx, s) }
    fun pickOverlayImage() = launchImagePick()
    internal fun onImagePicked(uri: Uri?) {
        uri ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { app.irlsupreme.service.OverlayStore.setImage(ctx, uri) }
            if (!ok) { snackbar = "Couldn't use that image."; return@launch }
            overlayImageStamp = app.irlsupreme.service.OverlayStore.imageFile(ctx).lastModified()
            updateOverlays(overlays.copy(imageOn = true))
        }
    }
    fun removeOverlayImage() {
        app.irlsupreme.service.OverlayStore.clearImage(ctx)
        overlayImageStamp = 0L
        updateOverlays(overlays.copy(imageOn = false))
    }
    fun changeWeakSignalScreen(on: Boolean) { weakSignalScreen = on; prefs.edit().putBoolean("weak_signal_screen", on).apply() }

    fun changeSendToObs(on: Boolean) { sendToObs = on; prefs.edit().putBoolean("send_to_obs", on).apply() }

    fun scanPairingCode() { pairingError = null; launchQrScan() }

    internal fun onQrScanned(contents: String?) {
        val v = contents?.trim() ?: return
        if (v.startsWith("fullbars://helper")) useHelperLink(v) else PendingPairing.link.value = v
    }

    // Helper phones
    var helpersOn by mutableStateOf(Helpers.hostingOn(ctx)); private set
    var helperLink by mutableStateOf(Helpers.pairingLink(ctx)); private set
    val helperPassword: String get() = Helpers.hostPassword(ctx)
    val deviceName: String get() = Helpers.deviceName(ctx)
    var helpingHost by mutableStateOf(Helpers.host(ctx)); private set

    var helperHotspot by mutableStateOf(Helpers.makeHotspot(ctx)); private set

    fun changeHelpersOn(on: Boolean) {
        helpersOn = on
        Helpers.setHostingOn(ctx, on)
        if (on && helperHotspot) withHelperPermissions(hostPermissions) {}
    }

    fun changeHelperHotspot(on: Boolean) {
        helperHotspot = on
        Helpers.setMakeHotspot(ctx, on)
        if (on) withHelperPermissions(hostPermissions) {}
    }

    /** Streaming phone: its private hotspot (Nearby devices) and sharing it over Bluetooth. */
    private val hostPermissions = buildList {
        add(Manifest.permission.BLUETOOTH_ADVERTISE); add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }

    /** Helper phone: finding the streaming phone's hotspot over Bluetooth (optional: it still works by hand). */
    private val helperPermissions = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    internal var launchHelperPermissions: (Array<String>) -> Unit = {}
    private var afterHelperPermissions: (() -> Unit)? = null

    /** Asks for what's missing, then carries on whatever the answer: these only make helpers smoother. */
    private fun withHelperPermissions(wanted: List<String>, then: () -> Unit) {
        val missing = wanted.filterNot { hasPermission(ctx, it) }
        if (missing.isEmpty()) { then(); return }
        afterHelperPermissions = then
        launchHelperPermissions(missing.toTypedArray())
    }

    internal fun onHelperPermissions() { afterHelperPermissions?.invoke(); afterHelperPermissions = null }

    fun newHelperCode() { Helpers.newHostPassword(ctx); helperLink = Helpers.pairingLink(ctx) }

    fun scanHelperCode() = launchQrScan()

    /** A scanned or tapped fullbars://helper link: this phone becomes that streaming phone's helper. */
    fun useHelperLink(link: String) {
        val h = Helpers.parseLink(link) ?: run { snackbar = "That isn't a Fullbars helper code."; return }
        Helpers.saveHost(ctx, h)
        helpingHost = h
        startHelping()
    }

    /** For Moblin streamers: their password, typed in (and optionally their address). */
    fun useMoblinStreamer(password: String, address: String) {
        if (password.isBlank()) return
        val h = Helpers.Host("", password.trim(), address.trim().takeIf { it.isNotEmpty() })
        Helpers.saveHost(ctx, h)
        helpingHost = h
        startHelping()
    }

    fun startHelping() {
        if (Helpers.host(ctx) == null) return
        withHelperPermissions(helperPermissions) { HelperService.start(ctx) }
    }
    fun stopHelping() = HelperService.stop(ctx)

    fun updatePasteLink(v: String) {
        pasteLink = v
        if (v.trim().startsWith("fullbars://")) PendingPairing.link.value = v.trim()
    }

    /** Pair button in the pairing dialog: proves the token and the pinned key work, then saves and turns Combine on. */
    fun confirmPairing(link: String) {
        val p = BrbPairing.parse(link) ?: run { pairingError = "That isn't a Fullbars pairing code."; return }
        pairingBusy = true
        pairingError = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { BrbClient(p).status() }
                // TCP worked; the stream itself is UDP. Still pair (the rule can be added later), but say so now.
                val udpOk = withContext(Dispatchers.IO) { srtlaReachable(p.host, p.port) }
                withContext(Dispatchers.IO) { pairingStore.save(p) }
                pairing = p
                changeCombine(true)
                PendingPairing.link.value = null
                pasteLink = ""
                snackbar = pairedMessage(p, udpOk)
            } catch (e: java.io.IOException) {
                pairingError = (e as? BrbException)?.message ?: "Couldn't reach that BRB server. Is it running, and is TCP port ${p.port} open?"
            } finally {
                pairingBusy = false
            }
        }
    }

    /** What pairing changed, from where streams will actually go now (not a promise that they go to Twitch). */
    private fun pairedMessage(p: BrbPairing, udpOk: Boolean): String = when {
        !udpOk -> "Paired, but UDP port ${p.port} looks blocked. Allow UDP ${p.port} in your server's firewall " +
            "(and your provider's security list) or streams won't connect."
        sendToObs -> "Paired with ${p.label}. Combine connections is on; streams go to your home OBS through it."
        (signIn is SignIn.SignedIn && !useKey) || (useKey && key.isNotBlank()) ->
            "Paired with ${p.label}. Combine connections is on; streams go to Twitch through it."
        else -> "Paired with ${p.label}. Sign in to Twitch (or add a stream key) to go live through it."
    }

    fun cancelPairing() { PendingPairing.link.value = null; pairingError = null; pasteLink = "" }

    fun forgetServer() { pairingStore.clear(); pairing = null; resetBrbStatus() }

    // ---- going live ----

    private fun neededPermissions() = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (cameraKind == ExternalCamera.Kind.POCKET) addAll(djiPermissions)
        if (combine && helpersOn && helperHotspot) addAll(hostPermissions)
    }.distinct()

    /**
     * The main button. A public stream to the signed-in channel first shows its title and category (so a first public
     * stream doesn't land in last week's game); then camera/mic access is explained once before Android's prompts.
     */
    fun onMainButton() {
        error = null; errorNeedsSignIn = false; errorNeedsServer = false; permissionDenied = false
        // The button is disabled while blocked; this guards a tap that lands as the state changes (a key just cleared).
        if (busy || blocker != null) return
        if (showsStreamInfo && audienceApplies && !testMode) { showStreamInfo(goesLive = true); return }
        startGoLive()
    }

    private fun startGoLive() {
        val missing = neededPermissions().filterNot { hasPermission(ctx, it) }
        when {
            missing.isEmpty() -> goLive()
            !prefs.getBoolean("perm_explained", false) &&
                (Manifest.permission.CAMERA in missing || Manifest.permission.RECORD_AUDIO in missing) -> showPermissionExplainer = true
            else -> launchPermissions(missing.toTypedArray())
        }
    }

    fun confirmPermissionExplainer() {
        showPermissionExplainer = false
        prefs.edit().putBoolean("perm_explained", true).apply()
        launchPermissions(neededPermissions().filterNot { hasPermission(ctx, it) }.toTypedArray())
    }

    fun dismissPermissionExplainer() { showPermissionExplainer = false }

    internal fun onPermissions(result: Map<String, Boolean>) {
        fun granted(p: String) = result[p] ?: hasPermission(ctx, p)
        when {
            !granted(Manifest.permission.CAMERA) || !granted(Manifest.permission.RECORD_AUDIO) -> {
                error = "Camera and microphone access are needed to go live."; permissionDenied = true
            }
            // Without these the Pocket can't be found or started, and the stream would fail after it went on air.
            cameraKind == ExternalCamera.Kind.POCKET && djiPermissions.any { !granted(it) } -> {
                error = "Allow Nearby devices so Fullbars can start your Pocket 3."; permissionDenied = true
            }
            else -> goLive()
        }
    }

    fun openAppSettings() {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun rememberStart(test: Boolean, bonded: Boolean) =
        prefs.edit().putBoolean("live_test", test).putBoolean("live_bonded", bonded).apply()

    private fun keyUrl(k: String) = effectiveIngest.trimEnd('/') + "/" + k + if (testMode) "?bandwidthtest=true" else ""

    /**
     * Key mode and OBS mode with a Twitch sign-in: chat, alerts and mod commands follow the signed-in account. Best
     * effort: without it the stream still starts, just without chat.
     */
    private suspend fun signedInUserId(): String? = if (signIn !is SignIn.SignedIn) null
    else withContext(Dispatchers.IO) { runCatching { twitch.session.current().userId }.getOrNull() }

    /**
     * Starts the stream service, or says why not. Android 12+ throws when a foreground service is started while the
     * app is in the background, which can happen after a slow server call if the user switched apps meanwhile.
     */
    private fun startService(test: Boolean, bonded: Boolean, start: () -> Unit) {
        if (!isStarted()) { error = BACKGROUND_START; return }
        rememberStart(test, bonded)
        try {
            start()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "service start refused: ${e.javaClass.simpleName}")
            error = BACKGROUND_START
        } catch (e: SecurityException) {
            Log.w(TAG, "service start refused: ${e.javaClass.simpleName}")
            error = "Android didn't let Fullbars start the stream. Check the app's permissions and try again."
            permissionDenied = true
        }
    }

    /** A Twitch failure at go-live, in plain words; "sign in again" when the account needs it. */
    private fun onTwitchFailure(e: TwitchException) {
        Log.w(TAG, "go live: ${e.message}")
        if (e is TwitchException.SignInRequired || e is TwitchException.Unauthorized) {
            signIn = SignIn.SignedOut; channel = null
            errorNeedsSignIn = true
        }
        error = e.userMessage()
    }

    /** The BRB server didn't answer (or refused): the Home row turns red and the error offers Connection settings. */
    private fun onBrbFailure(e: java.io.IOException) {
        Log.w(TAG, "go live: BRB server: ${e.javaClass.simpleName}")
        brbOk = false
        errorNeedsServer = true
        error = (e as? BrbException)?.message
            ?: "Couldn't reach your BRB server. Try again, or turn off Combine connections to go live straight to Twitch."
    }

    private fun goLive() {
        error = null
        flushWrites()
        if (combine) {
            // "Combine connections": tell the paired BRB server where to send the stream (the same Twitch ingest URL
            // this phone would use directly; it never leaves the phone otherwise), then bond to it.
            val p = pairing ?: run { error = "Pair your BRB server first."; return }
            busy = true
            scope.launch {
                try {
                    val client = BrbClient(p)
                    var userId: String? = null
                    var test = false
                    when {
                        sendToObs -> {
                            withContext(Dispatchers.IO) { client.setOutputObs() }
                            // Chat and mod commands still work through Twitch when signed in.
                            userId = signedInUserId()
                        }
                        signIn is SignIn.SignedIn && !useKey -> {
                            val setup = withContext(Dispatchers.IO) { twitch.prepareBroadcast(bandwidthTest = testMode) }
                            channel = setup.channel
                            withContext(Dispatchers.IO) { client.setOutput(setup.publishUrl.url) }
                            userId = setup.user.id
                            test = testMode
                        }
                        useKey && key.isNotBlank() -> {
                            val k = key.trim()
                            withContext(Dispatchers.IO) { secrets.put("stream_key", k); client.setOutput(keyUrl(k)) }
                            userId = signedInUserId()
                            test = testMode
                        }
                        else -> {
                            // The server keeps its own output. Check it first: its default only records.
                            val st = withContext(Dispatchers.IO) { client.status() }
                            brbServerStatus = st
                            if (st.outputKind == "record") {
                                error = "Your BRB server would only record. Sign in to Twitch or add a stream key first."
                                errorNeedsSignIn = !useKey && signInAvailable
                                return@launch
                            }
                            // A bandwidth test left on the server reaches nobody: the live screen must say Test.
                            test = st.outputKind == "twitch" && st.outputTest
                        }
                    }
                    // "Also stream to": the server sends this same stream to Kick, YouTube and the like. Sent every time
                    // (an empty list clears the server's), and never with a Test stream: that must reach nobody.
                    val extras = if (test) emptyList() else AlsoStreamTo.toSend(ctx)
                    val extrasSent = withContext(Dispatchers.IO) { client.setExtraOutputs(extras) }
                    if (!extrasSent && extras.isNotEmpty()) {
                        error = "Your BRB server needs an update for Also stream to. Update it, or turn off Also stream to in Settings."
                        return@launch
                    }
                    startService(test, bonded = true) {
                        StreamService.startBonded(ctx, p.host, p.port, "live/${p.publishId}", p.srtPassphrase, outW, outH, quality.bitrate, userId)
                    }
                } catch (e: TwitchException) {
                    onTwitchFailure(e)
                } catch (e: java.io.IOException) {
                    // Twitch failures arrive as TwitchException, so an IOException here is the BRB server.
                    onBrbFailure(e)
                } finally {
                    busy = false
                }
            }
            return
        }
        if (signIn is SignIn.SignedIn && !useKey) {
            busy = true
            scope.launch {
                try {
                    val setup = withContext(Dispatchers.IO) { twitch.prepareBroadcast(bandwidthTest = testMode) }
                    channel = setup.channel
                    startService(testMode, bonded = false) {
                        StreamService.start(ctx, setup.publishUrl.url, outW, outH, quality.bitrate, setup.user.id)
                    }
                } catch (e: TwitchException) {
                    onTwitchFailure(e)
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "go live: ${e.javaClass.simpleName}")
                    error = "Couldn't reach Twitch. Check your connection and try again."
                } finally {
                    busy = false
                }
            }
        } else {
            val k = key.trim()
            if (k.isEmpty()) { error = "Paste your Twitch stream key first."; return }
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { secrets.put("stream_key", k) }
                    val userId = signedInUserId()
                    startService(testMode, bonded = false) { StreamService.start(ctx, keyUrl(k), outW, outH, quality.bitrate, userId) }
                } finally {
                    busy = false
                }
            }
        }
    }

    private companion object {
        const val WRITE_DELAY_MS = 400L
        const val BACKGROUND_START = "Go live didn't start because the app was in the background. Tap again."
    }
}

/** Creates the controller with its activity-result launchers and background refreshes. [running]: the engine streams. */
@Composable
fun rememberSetupController(running: Boolean, links: Int = 0): SetupController {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val c = remember { SetupController(ctx, scope) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { c.onPermissions(it) }
    val djiPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { c.onDjiPermissions(it) }
    val helperPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { c.onHelperPermissions() }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { r -> c.onQrScanned(r.contents) }
    c.launchPermissions = { permissions.launch(it) }
    c.launchDjiPermissions = { djiPermissions.launch(it) }
    c.launchHelperPermissions = { helperPermissions.launch(it) }
    c.isStarted = { lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { c.onImagePicked(it) }
    c.launchImagePick = {
        imagePicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    c.launchQrScan = { scanner.launch(scanOptions()) }
    // At launch and on every return (from the browser, from settings, after signal came back): check the sign-in again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { c.reloadSignIn() }
    // Typed secrets still waiting to be saved: save them before the app goes to the background or the screen goes.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { c.flushWrites() }
    DisposableEffect(c) { onDispose { c.flushWrites() } }
    // The paired server's status every 30 s while the app is on screen and not streaming (it used to be read once).
    LaunchedEffect(c.pairing, c.combine, running) {
        c.resetBrbStatus()
        if (running) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                c.refreshBrbStatus()
                delay(30_000)
            }
        }
    }
    // A Pocket 3 on the phone's hotspot: is the hotspot on? It can be switched off from quick settings at any time.
    LaunchedEffect(c.cameraKind, c.cameraNet, running) {
        if (running) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            do {
                c.refreshHotspot()
                if (c.watchesHotspot) delay(5_000)
            } while (c.watchesHotspot)
        }
    }
    // The bonding links while live, for the recap's LINKS count (the engine's summary doesn't keep it).
    LaunchedEffect(running, links) { c.onEngine(running, links) }
    val wasRunning = remember { booleanArrayOf(false) }
    LaunchedEffect(running) {
        // Back from a stream (or at launch): Test is the safe default again, as it always was; only a clean test that
        // this session just watched end moves the bus on to Live (refreshSession).
        if (running) {
            wasRunning[0] = true
        } else {
            c.testMode = true
            c.refreshSession(afterStream = wasRunning[0])
            wasRunning[0] = false
        }
    }
    return c
}

private fun scanOptions() = ScanOptions()
    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    .setPrompt("Scan the code your BRB server shows")
    .setBeepEnabled(false)
    .setOrientationLocked(false)

fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * Opens [url] in a web browser, never an app that claims the link. Twitch's device approval page (twitch.tv/activate) is
 * claimed by the Twitch app, which opens its own home screen instead and never shows "approve", so a phone with the
 * Twitch app installed could never finish signing in. Falls back to [openUrl] when no browser is found.
 */
fun openInBrowser(ctx: Context, url: String) {
    val browser = browserPackage(ctx)
    if (browser != null) {
        val ok = runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(browser).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (ok) return
    }
    openUrl(ctx, url)
}

/** The default web browser's package, or any installed browser's if there's no default; never Twitch's own app. */
private fun browserPackage(ctx: Context): String? {
    val pm = ctx.packageManager
    val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
    val default = pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    if (default != null && default != "android" && !default.startsWith("tv.twitch")) return default
    return pm.queryIntentActivities(probe, 0).map { it.activityInfo.packageName }.firstOrNull { !it.startsWith("tv.twitch") }
}

private fun hasPermission(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
