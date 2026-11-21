package app.irlsupreme.ui

import android.os.BatteryManager
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.toColorInt
import app.irlsupreme.dji.PocketState
import app.irlsupreme.engine.EngineState
import app.irlsupreme.engine.Facing
import app.irlsupreme.engine.Health
import app.irlsupreme.engine.Scene
import app.irlsupreme.engine.StreamEngine
import app.irlsupreme.engine.CameraPhase
import app.irlsupreme.service.AlertQueue
import app.irlsupreme.service.CameraPrefs
import app.irlsupreme.service.ChatFeed
import app.irlsupreme.service.ChatLine
import app.irlsupreme.service.HelperHotspotStatus
import app.irlsupreme.engine.audio.InputClass
import app.irlsupreme.service.MicNotice
import app.irlsupreme.service.MicSettings
import app.irlsupreme.service.PocketScreen
import app.irlsupreme.service.OverlayStore
import app.irlsupreme.service.PocketStatus
import app.irlsupreme.service.StreamService
import app.irlsupreme.ui.live.ChatSheet
import app.irlsupreme.ui.live.EngagedTag
import app.irlsupreme.ui.live.FLIP_RESERVE_DP
import app.irlsupreme.ui.live.OncePerStream
import app.irlsupreme.ui.live.facingSpoken
import app.irlsupreme.ui.live.flipped
import app.irlsupreme.ui.live.startFacingChange
import app.irlsupreme.ui.live.HealthPill
import app.irlsupreme.ui.live.InstrumentLayout
import app.irlsupreme.ui.live.InstrumentLine
import app.irlsupreme.ui.live.LiveDetails
import app.irlsupreme.ui.live.LONG_NOTICE_MS
import app.irlsupreme.ui.live.NOTICE_MS
import app.irlsupreme.ui.live.PocketScreenOverlay
import app.irlsupreme.ui.live.pocketHintDue
import app.irlsupreme.ui.live.externalCameraNotice
import app.irlsupreme.ui.live.msToNextSecond
import app.irlsupreme.ui.live.NotOnCameraFrame
import app.irlsupreme.ui.live.RailActions
import app.irlsupreme.ui.live.PreviewPlate
import app.irlsupreme.ui.live.RailKeys
import app.irlsupreme.ui.live.ScenesSheet
import app.irlsupreme.ui.live.TimerCell
import app.irlsupreme.ui.live.alertWaitMs
import app.irlsupreme.ui.live.bigType
import app.irlsupreme.ui.live.brbKey
import app.irlsupreme.ui.live.captureSurface
import app.irlsupreme.ui.live.endKey
import app.irlsupreme.ui.live.linkCells
import app.irlsupreme.ui.live.micCell
import app.irlsupreme.ui.live.rememberChatText
import app.irlsupreme.ui.live.MicSheet
import app.irlsupreme.ui.live.MIC_LOUD_NOTICE_MS
import app.irlsupreme.ui.live.muteKey
import app.irlsupreme.ui.live.notice
import app.irlsupreme.ui.live.noticeHeight
import app.irlsupreme.ui.live.pillAssertive
import app.irlsupreme.ui.live.pillModel
import app.irlsupreme.ui.live.pillSlot
import app.irlsupreme.ui.live.railColumnWidth
import app.irlsupreme.ui.live.rememberKeyNeeds
import app.irlsupreme.ui.live.rememberPrevious
import app.irlsupreme.ui.live.sceneCardBitmap
import app.irlsupreme.ui.live.scenesKey
import app.irlsupreme.ui.live.timerLabel
import app.irlsupreme.ui.live.timerSpoken
import app.irlsupreme.ui.live.timerText
import app.irlsupreme.ui.theme.FbHaptics
import app.irlsupreme.ui.theme.FbLiveType
import app.irlsupreme.ui.theme.FbMotion
import app.irlsupreme.ui.theme.FbShape
import app.irlsupreme.ui.theme.FbSpace
import app.irlsupreme.ui.theme.LocalFbColors
import app.irlsupreme.ui.theme.HapticPattern
import app.irlsupreme.ui.theme.OverVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live screen: a full-bleed program preview with fixed slots over it: the health pill top-left (Details
 * under it), the chat ticker and the notice slot bottom-left, and one Ink plate holding the instrument line and the
 * four-key rail. Nothing changes rows in any state or scene. No modals; haptics on announced state changes; hold to end.
 *
 * [onSecretShown]: called with true while a secret (the helper hotspot password) is shown in Details and false once it
 * is hidden again, so the activity can block screenshots for exactly that long.
 */
@Composable
fun LiveScreen(engine: StreamEngine, state: EngineState, isTest: Boolean = false, onSecretShown: (Boolean) -> Unit = {}) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val config = LocalConfiguration.current
    val reduced = FbMotion.rememberReduced()
    var details by remember { mutableStateOf(false) }
    var scenesOpen by remember { mutableStateOf(false) }
    var micOpen by remember { mutableStateOf(false) }
    var chatOpen by remember { mutableStateOf(false) }
    // Which sheet the shared slot draws, kept through its exit animation so the closing sheet doesn't swap content.
    var sheetKind by remember { mutableStateOf(SheetKind.SCENES) }
    val anySheet = scenesOpen || micOpen || chatOpen
    // Reloaded when the overlay settings change, so the ticker always knows what the stream itself shows.
    val overlayRev by OverlayStore.changes.collectAsState()
    val overlays = remember(overlayRev) { OverlayStore.load(ctx) }
    BackHandler(enabled = details || anySheet) {
        when {
            chatOpen -> chatOpen = false
            micOpen -> micOpen = false
            scenesOpen -> scenesOpen = false
            else -> details = false
        }
    }
    // "Too loud" in the notice slot once the limiter has worked hard for a while; collected here, outside composition,
    // so ~47 levels a second don't recompose the screen.
    var micLoud by remember { mutableStateOf(false) }
    LaunchedEffect(engine) {
        val ballistics = MeterBallistics()
        var loudSince = 0L
        engine.micLevel.collect { level ->
            val now = System.currentTimeMillis()
            val loud = ballistics.update(level, now).loud
            loudSince = if (!loud) 0L else if (loudSince == 0L) now else loudSince
            val show = loudSince != 0L && now - loudSince >= MIC_LOUD_NOTICE_MS
            if (show != micLoud) micLoud = show
        }
    }

    // The Pocket screen: black and dimmed for a phone in a pocket, the preview no longer drawn (the stream carries on).
    // Offered once a stream when the phone first warms up; entered from Scenes; a double tap leaves it.
    // Saveable: a dark-mode or font change recreates the activity, and the screen mustn't light up in a pocket for it.
    var pocketScreen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = pocketScreen) { pocketScreen = false }
    DisposableEffect(pocketScreen, state.running) {
        engine.setPreviewSuppressed(pocketScreen)
        PocketScreen.on.value = pocketScreen
        onDispose {
            engine.setPreviewSuppressed(false)
            PocketScreen.on.value = false
        }
    }
    var pocketHintAt by remember { mutableLongStateOf(0L) }
    val warm = pocketHintDue(state.thermalRung, state.thermalStatus, state.thermalHeadroom)
    LaunchedEffect(warm) {
        if (warm && !pocketScreen && PocketHintOnce.claim(engine.lastSession.value)) {
            pocketHintAt = System.currentTimeMillis()
            delay(LONG_NOTICE_MS)
            pocketHintAt = 0L
        }
    }
    // A change of mic kind the engine reported ("External mic disconnected"), for a few seconds.
    val micNotice by MicNotice.text.collectAsState()
    var micNoticeShown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(micNotice) {
        val (text, at) = micNotice ?: return@LaunchedEffect
        val left = LONG_NOTICE_MS - (System.currentTimeMillis() - at)
        if (left <= 0) return@LaunchedEffect
        micNoticeShown = text
        delay(left)
        micNoticeShown = null
    }
    // Hot-phone preview halving, with full rate back while the screen is touched (set once the compositor exists).
    LaunchedEffect(state.running) { if (state.running) engine.setPreviewHalving(true) }

    // Keep the screen awake while the live screen is visible, so the phone doesn't time out and lock mid-stream.
    // (The stream itself survives screen-off regardless: the service holds a partial wake lock and Wi-Fi locks.)
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // The camera follows the screen's rotation. A DisplayListener, not the configuration: a flip
    // between the two landscape (or portrait) sides is a 180° rotation with no configuration change.
    DisposableEffect(view) {
        val dm = ctx.getSystemService(android.hardware.display.DisplayManager::class.java)
        fun report() { view.display?.let { engine.setDisplayRotation(it.rotation * 90) } }
        val listener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) { if (displayId == view.display?.displayId) report() }
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        dm.registerDisplayListener(listener, android.os.Handler(android.os.Looper.getMainLooper()))
        report()
        onDispose { dm.unregisterDisplayListener(listener) }
    }
    LaunchedEffect(config.orientation) { view.display?.let { engine.setDisplayRotation(it.rotation * 90) } }

    // Haptics, TalkBack and the pill itself follow the *announced* health, not every 500 ms snapshot: a quality dip
    // that clears within 3 s says nothing, while losing the connection, the camera or the scene is felt at once
    // (announceAtOnce). Each kind of change has its own pattern (hapticFor), so you know what happened without looking.
    var announced by remember { mutableStateOf<Health?>(null) }
    var sceneTapAt by remember { mutableLongStateOf(0L) }
    // When TalkBack last spoke a loss assertively: alert announcements queue behind it.
    var lastAssertiveAt by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.health) {
        val now = state.health
        val prev = announced ?: run { announced = now; return@LaunchedEffect }
        if (prev == now) return@LaunchedEffect
        // Read before the wait: a Back live tap is ours even if the announcement lands 3 s later.
        val ownTap = System.currentTimeMillis() - sceneTapAt < 3_000
        if (!announceAtOnce(prev, now)) delay(3_000)
        // Played on its own scope so a quick next change doesn't cut a pattern in half. Going live lands at +400ms,
        // after the END band has wiped in; with reduce motion it fires on the lock frame.
        hapticFor(prev, now, ownTap)?.let { p ->
            scope.launch {
                if (p == HapticPattern.WENT_LIVE && !reduced) delay(400)
                FbHaptics.play(view, p)
            }
        }
        if (pillAssertive(now)) lastAssertiveAt = System.currentTimeMillis()
        announced = now
    }

    // Once a stream has had bonded links it's a bonded stream, even if every link is gone mid-reconnect. A first
    // connect that never got a link says so in its error instead.
    var hadLinks by remember { mutableStateOf(false) }
    LaunchedEffect(state.links.isNotEmpty()) { if (state.links.isNotEmpty()) hadLinks = true }
    val bonded = hadLinks || state.lastError?.contains("BRB server") == true

    // Timed notices: the hold hint after an early release, and "Viewers can't hear you" on the first mute.
    var holdHintAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(holdHintAt) { if (holdHintAt != 0L) { delay(NOTICE_MS); holdHintAt = 0L } }
    var mutedOnce by remember { mutableStateOf(false) }
    var firstMuteAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(firstMuteAt) { if (firstMuteAt != 0L) { delay(NOTICE_MS); firstMuteAt = 0L } }

    // Ending: say so and lock the controls straight away. The stop itself waits two frames so "Ending…" is on screen
    // before the service tears the stream down; if the stream is somehow still running 10 s later, unlock again.
    var ending by remember { mutableStateOf(false) }
    LaunchedEffect(ending) {
        if (!ending) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        StreamService.stop(ctx)
        delay(10_000)
        ending = false
    }
    val onEnd: () -> Unit = { ending = true }

    // The preview: nothing is sent until GO LIVE. "Start on" (remembered) picks what viewers see first.
    val settings = remember { ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    var startOnCard by rememberSaveable { mutableStateOf(settings.getBoolean(START_ON_CARD, false)) }
    var goingLive by remember { mutableStateOf(false) }
    LaunchedEffect(goingLive, state.preview) {
        // Locked until the engine reports it's sending; unlocked again if that somehow doesn't happen.
        if (goingLive && state.preview) { delay(5_000); goingLive = false } else goingLive = false
    }
    val onGoLive: () -> Unit = {
        if (!goingLive && !ending) {
            goingLive = true
            StreamService.goLive(ctx, if (startOnCard) Scene.STARTING_SOON else null)
        }
    }
    val onStartOn: (Boolean) -> Unit = { card ->
        startOnCard = card
        settings.edit().putBoolean(START_ON_CARD, card).apply()
    }

    // Privacy mutes the mic. Only Back live from Privacy turns it on again (as the mods' !live does): moving from
    // Privacy to another card keeps it off, since those cards don't promise anything about the mic.
    val chooseScene: (Scene) -> Unit = { s ->
        scenesOpen = false
        if (s != state.scene) {
            FbHaptics.play(view, HapticPattern.TOGGLE)
            sceneTapAt = System.currentTimeMillis()
            if (s == Scene.PRIVACY) engine.setMicMuted(true)
            else if (s == Scene.LIVE && state.scene == Scene.PRIVACY) engine.setMicMuted(false)
            engine.setScene(s)
        }
    }
    val onMic: (Boolean) -> Unit = { muted ->
        FbHaptics.play(view, HapticPattern.TOGGLE)
        if (muted && !mutedOnce) { mutedOnce = true; firstMuteAt = System.currentTimeMillis() }
        engine.setMicMuted(muted)
    }

    // Flip and pinch only mean something while the phone camera is what's being sent.
    val phoneCamera = state.activeSource == "phone"
    // Said politely after a flip from the button or a double tap on the preview. The sheet's Flip row speaks its own
    // new state, so it doesn't announce twice. Cleared after a moment so the same side can be said again next time.
    var flipSaid by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(flipSaid) { if (flipSaid != null) { delay(NOTICE_MS); flipSaid = null } }
    val flip: (announce: Boolean) -> Unit = { announce ->
        if (phoneCamera && !ending) {
            FbHaptics.play(view, HapticPattern.TOGGLE)
            val to = flipped(state.cameraFacing)
            engine.setCameraFacing(to)
            if (announce) flipSaid = facingSpoken(to)
        }
    }
    val onFlip: () -> Unit = { flip(false) }
    // The "Start on" side (Settings > Camera), once per stream, as soon as the phone camera is what's being sent.
    LaunchedEffect(phoneCamera) {
        if (!phoneCamera || !StartFacingOnce.claim(engine.lastSession.value)) return@LaunchedEffect
        startFacingChange(CameraPrefs.startFacing(ctx), state.cameraFacing, phoneCamera)?.let { engine.setCameraFacing(it) }
    }
    // The engine clamps to what the camera supports and resets zoom on a flip; mirror the reset here.
    var zoom by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(state.cameraFacing) { zoom = 1f }
    // Before the stream was ever live there's nothing to hold on to: one tap backs out.
    val neverLive = state.liveSinceMs == 0L &&
        (state.health == Health.CONNECTING || state.health == Health.RECONNECTING || state.health == Health.FAILED)

    val helperProblem by HelperHotspotStatus.problem.collectAsState()
    val hotspot by HelperHotspotStatus.state.collectAsState()
    // Opening Details is seeing the reason, so it dismisses the notice line (a different problem shows again).
    var seenProblem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(details, helperProblem) { if (details) seenProblem = helperProblem }
    val pocket by PocketStatus.state.collectAsState()

    // Phone battery, for the MIC column's BAT reading below 15%. Polled: it moves slowly and a receiver is overkill.
    val battery by produceState<Int?>(null) {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        while (true) {
            value = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
            delay(30_000)
        }
    }

    // A Twitch alert is felt and spoken politely, never over an assertive loss: it waits 1.5 s behind one.
    val alert by AlertQueue.current.collectAsState()
    var alertText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(alert?.id) {
        val a = alert ?: return@LaunchedEffect
        while (true) {
            val wait = alertWaitMs(System.currentTimeMillis(), lastAssertiveAt)
            if (wait <= 0L) break
            delay(wait)
        }
        FbHaptics.play(view, HapticPattern.ALERT)
        alertText = a.spoken
    }

    // Scenes sheet thumbnails: the real cards (drawn once the sheet is first opened) and the last camera frame.
    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    var cameraThumb by remember { mutableStateOf<ImageBitmap?>(null) }
    var sheetOpened by remember { mutableStateOf(false) }
    LaunchedEffect(scenesOpen) {
        if (!scenesOpen) return@LaunchedEffect
        sheetOpened = true
        val sv = surfaceView
        if (sv != null && state.scene == Scene.LIVE) {
            val (w, h) = if (state.portrait) 108 to 192 else 192 to 108
            captureSurface(sv, w, h) { bmp -> bmp?.let { cameraThumb = it.asImageBitmap() } }
        }
    }
    val cardThumbs by produceState<Map<Scene, ImageBitmap?>>(emptyMap(), sheetOpened, state.portrait, state.micMuted) {
        if (!sheetOpened) return@produceState
        val (w, h) = if (state.portrait) 270 to 480 else 480 to 270
        val muted = state.micMuted
        value = withContext(Dispatchers.Default) {
            Scene.entries.filter { it != Scene.LIVE }.associateWith { sceneCardBitmap(it, muted, w, h)?.asImageBitmap() }
        }
    }

    // The "not on camera" frame lands after the stream's 280ms crossfade to the card.
    val onCard = state.scene != Scene.LIVE
    var frameShown by remember { mutableStateOf(onCard) }
    LaunchedEffect(onCard) {
        if (onCard && !reduced) delay(FbMotion.ENTER_MS.toLong())
        frameShown = onCard
    }

    val said = announced ?: state.health
    val pill = pillModel(state, said, isTest, bonded, ending)
    val previous = rememberPrevious(said)
    val big = bigType(LocalDensity.current.fontScale)

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // On the timer's own second boundary: once a second, never twice.
    LaunchedEffect(state.liveSinceMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(msToNextSecond(state.liveSinceMs, now))
        }
    }
    val timer = TimerCell(timerLabel(state.liveSinceMs, isTest, state.preview), timerText(state.liveSinceMs, now), timerSpoken(state.liveSinceMs, now, isTest), state.liveSinceMs == 0L)
    val links = linkCells(
        state.links, state.targetVideoBitrate, state.sentBitrate,
        weak = said == Health.WEAK || said == Health.WEAK_SCREEN,
        connecting = state.liveSinceMs == 0L,
        reconnecting = said == Health.RECONNECTING,
    )
    val mic = micCell(state.micMuted, state.audioInput, battery)
    val cameraLink = cameraLinkNotice(state, pocket)
    val noticeText = notice(
        holdHint = holdHintAt != 0L,
        noAudio = if (state.micMuted) null else audioProblem(state.audioInput),
        firstMute = firstMuteAt != 0L,
        cameraLink = cameraLink,
        helperProblem = helperProblem?.takeIf { it != seenProblem }?.let { "No helper hotspot. Tap the pill for why." },
        micLoud = micLoud && !state.micMuted && !micOpen,
        micInput = micNoticeShown,
        pocketHint = pocketHintAt != 0L,
    )

    val brb = state.scene != Scene.LIVE
    val actions = RailActions(
        enabled = !ending,
        scenesState = "Viewers see ${sceneName(state.scene)}",
        muteState = if (state.micMuted) "Off. Viewers can't hear you" else "On",
        brbName = if (brb) "${sceneName(state.scene)} screen" else "BRB screen",
        brbState = if (brb) "On. Viewers see your ${sceneName(state.scene)} screen" else "Off",
        onEnd = onEnd,
        onEarlyRelease = { holdHintAt = System.currentTimeMillis() },
        onScenes = { scenesOpen = !scenesOpen; micOpen = false; chatOpen = false; sheetKind = SheetKind.SCENES },
        onMute = { onMic(!state.micMuted) },
        onBrb = { chooseScene(if (brb) Scene.LIVE else Scene.BRB) },
    )
    // With chat drawn into the stream the preview already shows it, except while the program hides it.
    // A card set to show chat (Scene cards > Live) puts it on stream even when chat isn't on cards in general.
    val cardShowsChat = app.irlsupreme.engine.video.Slates.Card.of(state.scene)
        ?.let { app.irlsupreme.service.CardStore.elements(ctx, it).showChat } == true
    val showChat = !overlays.chat || programHidesChat(state.scene, state.weakSignal, overlays.onCards || cardShowsChat)
    // The ticker opens the full chat, even when the stream draws it: the overlay is just as short.
    val onChat: (() -> Unit)? = if (ending) null else ({ chatOpen = !chatOpen; scenesOpen = false; micOpen = false; sheetKind = SheetKind.CHAT })
    val flipButton: (() -> Unit)? = if (phoneCamera && !ending) ({ flip(true) }) else null

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Any finger on the screen keeps the preview at full rate on a warm phone; only watched, never consumed.
            .pointerInput(engine) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    engine.setPreviewTouchBoost(true)
                    try {
                        do {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                        } while (e.changes.any { it.pressed })
                    } finally {
                        engine.setPreviewTouchBoost(false)
                    }
                }
            },
    ) {
        Preview(
            engine,
            fps = state.fps,
            paused = state.previewPaused,
            viewersSeeYou = state.scene == Scene.LIVE && !state.weakSignal && state.camera != CameraPhase.LOST,
            onZoom = if (phoneCamera && !ending) {
                { factor -> zoom = (zoom * factor).coerceIn(1f, MAX_ZOOM); engine.setZoom(zoom) }
            } else null,
            onTap = { if (anySheet) { scenesOpen = false; micOpen = false; chatOpen = false } else if (details) details = false },
            onDoubleTap = if (phoneCamera && !ending) ({ flip(true) }) else null,
            onSurface = { surfaceView = it },
        )
        if (frameShown) NotOnCameraFrame()

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            // The chat sheet stops short of the pill, so the preview and the pill stay in view while you read.
            val chatMax = maxHeight * 0.6f
            val sheet: @Composable (Modifier) -> Unit = { m ->
                if (sheetKind == SheetKind.CHAT) ChatSheet(
                    reduced,
                    if (landscape) m else m.heightIn(max = chatMax),
                ) else if (sheetKind == SheetKind.MIC) MicSheet(
                    level = engine.micLevel, muted = state.micMuted,
                    onGain = { db ->
                        // A USB or wired mic in use has its own level, as on Settings › Microphone.
                        val external = state.micInput?.inputClass == InputClass.EXTERNAL
                        MicSettings.setGain(ctx, db, external)
                        val p = MicSettings.current(ctx)
                        if (external) {
                            engine.setExternalMicGain(p.externalGainDb.toFloat())
                        } else {
                            engine.setMicGain(p.gainDb.toFloat())
                            engine.setRawMicGain(p.rawGainDb.toFloat())
                        }
                    },
                    onRaw = { raw -> MicSettings.setRaw(ctx, raw); engine.setRawMic(raw) },
                    modifier = m,
                ) else ScenesSheet(
                    scene = state.scene, muted = state.micMuted,
                    thumbs = cardThumbs + (Scene.LIVE to cameraThumb),
                    landscapeThumbs = !state.portrait,
                    flipValue = if (phoneCamera) (if (state.cameraFacing == Facing.FRONT) "Front" else "Back") else null,
                    onFlip = onFlip, onChoose = chooseScene, modifier = m,
                    onTestAlert = if (isTest) ({ k -> scenesOpen = false; AlertQueue.post(k.alert()) }) else null,
                    alertsOn = overlays.alertsOn,
                    onPocketScreen = { scenesOpen = false; pocketHintAt = 0L; pocketScreen = true },
                )
            }
            val instrument: @Composable (InstrumentLayout, Modifier) -> Unit = { layout, m ->
                InstrumentLine(
                    timer, links, mic, layout, reduced, m,
                    micLevel = engine.micLevel,
                    onMic = if (ending) null else ({ micOpen = !micOpen; scenesOpen = false; chatOpen = false; sheetKind = SheetKind.MIC }),
                )
            }
            val rail: @Composable (Boolean, Modifier) -> Unit = { vertical, m ->
                if (state.preview) PreviewPlate(
                    isTest = isTest, startOnCard = startOnCard, onStartOn = onStartOn,
                    onCancel = onEnd, onGoLive = onGoLive, vertical = vertical, big = big,
                    enabled = !ending && !goingLive, modifier = m,
                ) else RailKeys(endKey(neverLive), scenesKey(scenesOpen), muteKey(state.micMuted), brbKey(state.scene), actions, vertical, big, reduced, m)
            }
            val gestureBottom = WindowInsets.systemGestures.asPaddingValues().calculateBottomPadding()
            val bottomPad = max(12.dp, gestureBottom)

            if (!landscape) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                        Slots(
                            pill = { slot -> HealthPill(pill, said, previous, pillAssertive(said) && !ending, slot, details, reduced, onClick = { details = !details }) },
                            details = details,
                            detailsContent = { m -> LiveDetails(state, pocket?.label, hotspot, helperProblem, onSecretShown, m) },
                            showChat = showChat,
                            onChat = onChat,
                            flip = flipButton,
                            notOnCamera = frameShown,
                            notice = noticeText,
                            noticeHeight = noticeHeight(big).dp,
                        )
                        SheetSlot(anySheet, reduced, fromSide = false, Modifier.align(Alignment.BottomCenter)) { sheet(Modifier.fillMaxWidth()) }
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .absorbTouches()
                            .background(OverVideo.scrim)
                            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = bottomPad),
                    ) {
                        instrument(if (big) InstrumentLayout.GRID else InstrumentLayout.ROW, Modifier)
                        // An 8dp gap between the instrument line and the rail, with a hairline in it.
                        Spacer(Modifier.height(3.dp))
                        Hairline(color = OverVideo.keyOutline.copy(alpha = 0.4f))
                        Spacer(Modifier.height(4.dp))
                        rail(false, Modifier)
                    }
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                        Slots(
                            pill = { slot -> HealthPill(pill, said, previous, pillAssertive(said) && !ending, slot, details, reduced, onClick = { details = !details }) },
                            details = details,
                            detailsContent = { m -> LiveDetails(state, pocket?.label, hotspot, helperProblem, onSecretShown, m) },
                            showChat = showChat,
                            onChat = onChat,
                            flip = flipButton,
                            notOnCamera = frameShown,
                            notice = noticeText,
                            noticeHeight = noticeHeight(big).dp,
                            bottom = bottomPad,
                        )
                        SheetSlot(anySheet, reduced, fromSide = true, Modifier.align(Alignment.BottomEnd)) {
                            sheet(Modifier.widthIn(max = 380.dp).fillMaxHeight().padding(top = 12.dp))
                        }
                    }
                    // The plate as a right-hand column: instrument cells at the top, keys END→BRB down to the thumb.
                    val keyNeeds = rememberKeyNeeds(big)
                    BoxWithConstraints(
                        Modifier
                            .fillMaxHeight()
                            .absorbTouches()
                            .background(OverVideo.scrim)
                            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End + WindowInsetsSides.Top))
                            // 96dp, wider only at large type when a key's widest word (UNMUTE, CANCEL) needs it.
                            .width(railColumnWidth(keyNeeds).dp),
                    ) {
                        Column(
                            Modifier
                                .verticalScroll(rememberScrollState())
                                .heightIn(min = maxHeight)
                                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = bottomPad),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            instrument(InstrumentLayout.STACK, Modifier)
                            Spacer(Modifier.height(12.dp))
                            rail(true, Modifier)
                        }
                    }
                }
            }
        }

        // Alerts are spoken here, politely, once each (the stream draws them; this only tells TalkBack).
        Box(
            Modifier.size(1.dp).semantics {
                liveRegion = LiveRegionMode.Polite
                alertText?.let { contentDescription = it }
            },
        )
        // The side a flip landed on, politely.
        Box(
            Modifier.size(1.dp).semantics {
                liveRegion = LiveRegionMode.Polite
                flipSaid?.let { contentDescription = it }
            },
        )
        if (pocketScreen) PocketScreenOverlay(pill, said, ending, onLeave = { pocketScreen = false })
    }
}

/** The Pocket screen is offered once per stream, across rebuilds of this screen (see [OncePerStream]). */
private val PocketHintOnce = OncePerStream()

/**
 * The left-hand slots, top to bottom: the pill (cutout inset + 4dp) with the flip button at the other end of its row,
 * the NOT ON CAMERA tag, Details, then from the bottom up the notice slot and the chat ticker. The notice slot is
 * reserved even when empty. The tag sits under the pill rather than at the bottom because every card puts its headline
 * bottom-left, where the tag would cover it. [flip]: null hides the flip button, and the pill gets its room back.
 * [onChat]: opens the Chat sheet from the ticker (null while ending).
 */
@Composable
private fun Slots(
    pill: @Composable (slot: Dp) -> Unit,
    details: Boolean,
    detailsContent: @Composable (Modifier) -> Unit,
    showChat: Boolean,
    onChat: (() -> Unit)?,
    flip: (() -> Unit)?,
    notOnCamera: Boolean,
    notice: String?,
    noticeHeight: Dp,
    bottom: Dp = 0.dp,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = bottom),
    ) {
        val slot = pillSlot(maxWidth.value + 24f, if (flip != null) FLIP_RESERVE_DP else 0f).dp
        // At most 40 % of the width, but never narrower than a readable line on a small or portrait screen.
        val chatMax = maxOf(maxWidth * 0.4f, minOf(280.dp, maxWidth))
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                pill(slot)
                if (flip != null) FlipButton(flip)
            }
            Spacer(Modifier.height(8.dp))
            if (notOnCamera) EngagedTag("Not on camera", Modifier.padding(bottom = 8.dp).absorbTouches())
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (details) detailsContent(Modifier.widthIn(max = 420.dp).verticalScroll(rememberScrollState()))
            }
            ChatTicker(showChat, onChat, Modifier.widthIn(max = chatMax).padding(top = 8.dp))
            NoticeSlot(notice, noticeHeight)
        }
    }
}

/** The reserved notice line: one plain sentence on a scrim tag, spoken politely when it changes. */
@Composable
private fun NoticeSlot(text: String?, height: Dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (text != null) {
            Text(
                text, style = FbLiveType.L2, color = OverVideo.text,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .absorbTouches()
                    .background(OverVideo.scrim, FbShape.small)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * Flip camera, where a thumb finds it without opening Scenes: a 48dp glyph key on a scrim plate, top-right, opposite
 * the pill. Drawn like a rail key (rM, 1.5dp keyOutline, s3 while pressed) on the scrim so it reads over any video.
 * The TOGGLE haptic and the "Front camera"/"Back camera" announcement come from [onFlip].
 */
@Composable
private fun FlipButton(onFlip: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier
            .size(FbSpace.target)
            .clip(FbShape.medium)
            .background(if (pressed) LocalFbColors.current.s3 else OverVideo.scrim)
            .border(FbShape.stroke, OverVideo.keyOutline, FbShape.medium)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = "Flip camera", onClick = onFlip)
            .semantics { contentDescription = "Flip camera" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(FbIcons.Flip, contentDescription = null, tint = OverVideo.text, modifier = Modifier.size(24.dp))
    }
}

/** Which sheet the shared slot shows. One at a time: opening one closes the others. */
private enum class SheetKind { SCENES, MIC, CHAT }

/** The start side is applied once per stream, across rebuilds of this screen (see [OncePerStream]). */
private val StartFacingOnce = OncePerStream()

/**
 * The Scenes sheet's slot: it rises from behind the plate with the spring (Settle) and leaves with Exit; a cut under
 * reduce motion. [fromSide]: the landscape plate is a column, so the sheet slides out from it sideways.
 */
@Composable
private fun SheetSlot(open: Boolean, reduced: Boolean, fromSide: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val enter: EnterTransition = when {
        reduced -> EnterTransition.None
        fromSide -> slideInHorizontally(spring(FbMotion.SETTLE_DAMPING, FbMotion.SETTLE_STIFFNESS)) { it }
        else -> slideInVertically(spring(FbMotion.SETTLE_DAMPING, FbMotion.SETTLE_STIFFNESS)) { it }
    }
    val exit: ExitTransition = when {
        reduced -> ExitTransition.None
        fromSide -> slideOutHorizontally(tween(FbMotion.EXIT_MS, easing = FbMotion.Exit)) { it }
        else -> slideOutVertically(tween(FbMotion.EXIT_MS, easing = FbMotion.Exit)) { it }
    }
    // The whole sheet, header and gaps included, keeps its touches: the preview under it listens for a double tap.
    AnimatedVisibility(open, modifier, enter = enter, exit = exit) { Box(Modifier.absorbTouches()) { content() } }
}

/**
 * Keeps touches on an overlay from reaching the preview underneath. Compose hands a touch to the sibling below when
 * nothing on top has a pointer handler, so a scrim plate, a sheet header or a tag would otherwise pass a quick double
 * tap through to the preview, which flips the camera viewers see. This handler only claims the touch; it never
 * consumes it, so keys and lists inside the overlay work as before. A tap here no longer closes a sheet: the preview
 * alone is "outside".
 */
private fun Modifier.absorbTouches(): Modifier = pointerInput(Unit) {
    awaitEachGesture { awaitFirstDown(requireUnconsumed = false) }
}

/**
 * The camera link's progress while it starts (it can need you: "Approve on the camera's screen"), or that the phone
 * camera has taken over; null while the camera link streams or isn't in use. A notice line, since the chips are gone.
 */
private fun cameraLinkNotice(state: EngineState, pocket: PocketState?): String? = when {
    pocket != null && pocket !is PocketState.Streaming -> "Camera link · ${pocket.label}"
    else -> externalCameraNotice(state.externalCamera, state.activeSource)
}

/** Settings key: the preview's "Start on" choice, the Starting soon card (true) or the camera. */
internal const val START_ON_CARD = "start_on_card"

/** Highest pinch zoom asked of the engine; it clamps further to what the camera supports. */
private const val MAX_ZOOM = 10f

// ---------------------------------------------------------------------------------------------------------------------
// Haptics and announcements: pure, so the rules are unit tested.

private val WarningHealth = setOf(Health.WEAK, Health.HOT, Health.WEAK_SCREEN)

/**
 * Whether a health change is felt and announced at once. Losses, scene changes both ways and the first move out of
 * Connecting can't wait; everything else (a quality dip, a recovery) must hold for 3 s first, so a flapping connection
 * doesn't buzz and re-announce every half second. Leaving a card must land at once too: the pill speaks the announced
 * health with the *current* scene and mic, so a held BRB would speak "Viewers see your Live screen" with card copy.
 */
internal fun announceAtOnce(prev: Health, now: Health): Boolean =
    now == Health.RECONNECTING || now == Health.FAILED || now == Health.CAMERA_LOST ||
        now == Health.BRB || prev == Health.BRB || prev == Health.CONNECTING

/**
 * The pattern for an announced change from [prev] to [now], or null for none. [ownSceneTap]: the
 * streamer just picked a scene on this screen, whose own TOGGLE is enough (no second buzz). A mod's scene change is
 * news, so it gets the TOGGLE click itself.
 */
internal fun hapticFor(prev: Health, now: Health, ownSceneTap: Boolean): HapticPattern? = when {
    prev == now || now == Health.OFF || now == Health.CONNECTING -> null
    now == Health.CAMERA_LOST -> HapticPattern.CAMERA_LOST
    now == Health.RECONNECTING || now == Health.FAILED -> HapticPattern.RECONNECTING
    prev == Health.CONNECTING -> HapticPattern.WENT_LIVE
    now == Health.BRB -> if (ownSceneTap) null else HapticPattern.TOGGLE
    now in WarningHealth -> HapticPattern.WORSE
    now == Health.GREAT -> if (prev == Health.BRB && ownSceneTap) null else HapticPattern.RECOVERED
    else -> null
}

fun sceneName(s: Scene) = when (s) {
    Scene.LIVE -> "Live"
    Scene.BRB -> "BRB"
    Scene.PRIVACY -> "Privacy"
    Scene.STARTING_SOON -> "Starting soon"
    Scene.ENDING -> "Ending"
}

// ---------------------------------------------------------------------------------------------------------------------
// Chat ticker

/** Chat lines older than this leave the ticker: a quiet chat shouldn't leave a stale question on screen for an hour. */
internal const val CHAT_MAX_AGE_MS = 120_000L

/**
 * Whether what viewers see right now leaves out the chat overlay: the compositor never draws layers over Privacy or the
 * Weak signal screen, and draws them over the other cards only when the user chose "on cards" (Compositor.kt).
 */
internal fun programHidesChat(scene: Scene, weakSignal: Boolean, overlayOnCards: Boolean): Boolean = when (scene) {
    Scene.PRIVACY -> true
    Scene.LIVE -> weakSignal
    else -> !overlayOnCards
}

/**
 * The ticker's lines at [nowMs]: the last [max] recent ones when [showChat], and always the last accepted mod command,
 * so a streamer whose chat is drawn into the stream still sees what a mod just did.
 */
internal fun tickerLines(lines: List<ChatLine>, nowMs: Long, showChat: Boolean, max: Int = 4): List<ChatLine> {
    val recent = lines.filter { nowMs - it.atMs <= CHAT_MAX_AGE_MS }
    val shown = if (showChat) recent.takeLast(max) else emptyList()
    val command = recent.lastOrNull { it.isCommand }
    return if (command == null || command in shown) shown else listOf(command) + shown
}

/**
 * Last few chat messages, bottom-left: the name in Bone, the chatter's colour as a thin tick (so every name reads at
 * 12.74:1 whatever colour they picked), the message in text2. Accepted mod commands get a Bone outline. Hidden when not
 * signed in. [showChat] false: the stream already shows chat (the preview has it), so only the status and the last
 * command remain.
 *
 * Lines stop at two, so the whole ticker is one button that opens the Chat sheet ([onOpen]) where every message is in
 * full, and an OPEN CHAT tag under the lines says so (a cut-off line alone doesn't tell you there's more to read).
 */
@Composable
private fun ChatTicker(showChat: Boolean, onOpen: (() -> Unit)?, modifier: Modifier = Modifier) {
    val lines by ChatFeed.lines.collectAsState()
    val status by ChatFeed.status.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    if (status == null && lines.isEmpty()) return
    val message = FbLiveType.L2.copy(fontWeight = FontWeight.Normal)
    val openable = onOpen != null && lines.isNotEmpty()
    Column(
        // Claims touches even when there's nothing to open, so a double tap on a line never reaches the preview.
        modifier.absorbTouches().then(
            if (openable) {
                Modifier
                    .heightIn(min = FbSpace.target)
                    .clickable(onClickLabel = "Open chat", role = Role.Button) { onOpen?.invoke() }
            } else Modifier,
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
    ) {
        tickerLines(lines, now, showChat).forEach { l ->
            val tick = l.color?.let { runCatching { Color(it.toColorInt()) }.getOrNull() } ?: OverVideo.keyOutline
            Row(
                Modifier
                    .height(IntrinsicSize.Min)
                    .background(OverVideo.scrim, FbShape.small)
                    .then(if (l.isCommand) Modifier.border(1.dp, OverVideo.text, FbShape.small) else Modifier),
            ) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(tick))
                val t = rememberChatText(l, SpanStyle(color = OverVideo.text, fontWeight = FontWeight.SemiBold))
                Text(
                    t.text, inlineContent = t.inline,
                    style = message, color = OverVideo.text2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                )
            }
        }
        val s = status
        if (s != null && s != "Chat connected") {
            Text(
                s, style = message, color = OverVideo.text2, maxLines = 1,
                modifier = Modifier.background(OverVideo.scrim, FbShape.small).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        if (openable) {
            // The button's own label ("Open chat") says this to TalkBack, so the tag itself is silent.
            Text(
                "OPEN CHAT", style = FbLiveType.L3, color = OverVideo.text,
                modifier = Modifier
                    .clearAndSetSemantics {}
                    .background(OverVideo.scrim, FbShape.small)
                    .border(1.dp, OverVideo.keyOutline, FbShape.small)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * The full-bleed preview. [onZoom] (null: no pinch) gets each pinch step's scale factor; [onTap] closes the Scenes
 * sheet or Details (the sheet has no scrim, so the preview is its "tap outside"); [onDoubleTap] (null: none) flips the
 * camera, as camera apps do; it's only listened for while it can flip, since waiting for a second tap delays a single
 * tap's close by the double-tap timeout. Every overlay drawn over the preview (plate, sheets, ticker, tags) claims
 * its own touches ([absorbTouches]), so only a double tap on the video itself flips. [paused]: the thermal ladder stopped drawing the preview, so the frozen frame
 * is labelled rather than looking like a hang; [viewersSeeYou] says whether the stream itself is showing the camera
 * right now, so the label doesn't promise more than it is. [onSurface] hands over the SurfaceView, for the Live row's
 * thumbnail.
 */
@Composable
private fun Preview(
    engine: StreamEngine,
    fps: Int,
    paused: Boolean,
    viewersSeeYou: Boolean,
    onZoom: ((Float) -> Unit)?,
    onTap: () -> Unit,
    onDoubleTap: (() -> Unit)?,
    onSurface: (SurfaceView?) -> Unit,
) {
    val previewFps by rememberUpdatedState(fps)
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                SurfaceView(c).apply {
                    val sv = this
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) = onSurface(sv)
                        override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hgt: Int) {
                            // The display can run at the stream's own rate (or a multiple) instead of guessing.
                            runCatching { h.surface.setFrameRate(previewFps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE) }
                            engine.attachPreview(h.surface, w, hgt)
                        }
                        override fun surfaceDestroyed(h: SurfaceHolder) {
                            onSurface(null)
                            engine.detachPreview()
                        }
                    })
                }
            },
        )
        // A transparent layer over the SurfaceView rather than on it, so gestures don't go through View interop. The
        // controls sit above it, so taps on them never reach here.
        val zoom by rememberUpdatedState(onZoom)
        val tap by rememberUpdatedState(onTap)
        val doubleTap by rememberUpdatedState(onDoubleTap)
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(onDoubleTap != null) {
                    if (onDoubleTap == null) detectTapGestures { tap() }
                    else detectTapGestures(onDoubleTap = { doubleTap?.invoke() }) { tap() }
                }
                .pointerInput(onZoom != null) {
                    if (onZoom == null) return@pointerInput
                    detectTransformGestures { _, _, factor, _ -> if (factor != 1f) zoom?.invoke(factor) }
                },
        )
        if (paused) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                Text(
                    if (viewersSeeYou) "Preview paused to cool the phone · viewers still see you"
                    else "Preview paused to cool the phone · your stream carries on",
                    style = FbLiveType.L2, color = OverVideo.text,
                    modifier = Modifier.padding(horizontal = 24.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .background(OverVideo.scrim, FbShape.small).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
    DisposableEffect(Unit) { onDispose { engine.detachPreview() } }
}
