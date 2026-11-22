package app.irlsupreme.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.irlsupreme.engine.SessionSummary
import app.irlsupreme.engine.audio.PcmClip
import app.irlsupreme.service.ExternalCamera
import app.irlsupreme.twitch.ChannelInfo
import app.irlsupreme.twitch.DeviceCode
import app.irlsupreme.twitch.HelixClient
import app.irlsupreme.twitch.TwitchCategory
import app.irlsupreme.ui.theme.FbHaptics
import app.irlsupreme.ui.theme.FbMotion
import app.irlsupreme.ui.theme.FbShape
import app.irlsupreme.ui.theme.FbSpace
import app.irlsupreme.ui.theme.FbType
import app.irlsupreme.ui.theme.HapticPattern
import app.irlsupreme.ui.theme.JetMono
import app.irlsupreme.ui.theme.LocalFbColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/*
 * Home: a field instrument's preflight. A wordmark bar; the recap when a stream just
 * ended; the PREFLIGHT checklist (a state square, label left, value right, hairlines, no cards); the verdict and its
 * readout; and the dock, where TEST and LIVE are physically different pieces joined to the CTA. Signed out, it's the
 * first run instead: the promise, Continue with Twitch, and the defaults as one unchecked row.
 */

/**
 * The sign-in arrival: the Twitch square fills, then the rest of the preflight expands, then the dock rises
 * once the verdict is in. [NONE] is everything at rest.
 */
private enum class Arrival { NONE, SIGNED_IN, LIST, DOCK }

/** Settle for a size: the device-code plate and the preflight expanding are neutral-to-better changes. */
private val SettleSize = spring(FbMotion.SETTLE_DAMPING, FbMotion.SETTLE_STIFFNESS, IntSize.VisibilityThreshold)

/** How long the dock waits for the first verdict on arrival before rising anyway (a server that never answers). */
private const val ARRIVAL_DOCK_TIMEOUT_MS = 4_000L

/** How long a focus hand-off waits for the dock to join the tree before giving up (the dock may not show at all). */
private const val DOCK_ATTACH_TIMEOUT_MS = 2_000L

@Composable
fun HomeScreen(c: SetupController, nav: Navigator) {
    val colors = LocalFbColors.current
    val view = LocalView.current
    val reduced = FbMotion.rememberReduced()
    val ctaFocus = remember { FocusRequester() }
    // Set by the dock while it's in the tree, so focus hand-offs wait for the CTA's requester to be attached.
    val dockAttached = remember { mutableStateOf(false) }
    val ready = homeReady(c)
    val firstRun = ready && showsWelcome(c)

    // The arrival starts on the very composition that first sees the new sign-in, so the Twitch row is drawn
    // unchecked first and fills in place (an effect would run a frame late and flash the end state).
    val signIn = c.signIn
    val seen = remember { arrayOfNulls<SignIn>(1) }
    val arrival: MutableState<Arrival> = remember(signIn) {
        val was = seen[0]
        seen[0] = signIn
        mutableStateOf(if (was is SignIn.Waiting && signIn is SignIn.SignedIn) Arrival.SIGNED_IN else Arrival.NONE)
    }
    val preflight = if (ready && !firstRun) rememberPreflight(c.quality, c.checkHost, c.checkPort, c.brbCheck) else null
    val latestPreflight by rememberUpdatedState(preflight)
    LaunchedEffect(arrival) {
        if (arrival.value != Arrival.SIGNED_IN) return@LaunchedEffect
        FbHaptics.play(view, HapticPattern.SIGNED_IN)
        (signIn as? SignIn.SignedIn)?.let { announce(view, "Signed in as ${it.login}") }
        if (!reduced) delay(FbMotion.TICK_MS.toLong())
        arrival.value = Arrival.LIST
        var waited = 0L
        while (latestPreflight == null && waited < ARRIVAL_DOCK_TIMEOUT_MS) { delay(100); waited += 100 }
        arrival.value = Arrival.DOCK
        focusCtaWhenDocked(dockAttached, ctaFocus, reduced)
        arrival.value = Arrival.NONE
    }
    // A clean test moved the bus to LIVE: "Go live now" takes focus once.
    LaunchedEffect(c.handoffToLive, ready) {
        if (!c.handoffToLive || !ready) return@LaunchedEffect
        focusCtaWhenDocked(dockAttached, ctaFocus, reduced)
        c.handoffToLive = false
    }

    val dockShown = ready && showsGoLiveBar(c) && arrival.value != Arrival.SIGNED_IN && arrival.value != Arrival.LIST
    val scroll = rememberScrollState()
    val body: @Composable (Modifier) -> Unit = { m ->
        val recap = c.recap
        // The recap sits at the top, scrolled to 0; its count-up and announcement play once.
        val countUp = remember(recap?.endedAtMs) { recap != null && c.claimRecapIntro(recap.endedAtMs) }
        LaunchedEffect(recap?.endedAtMs) {
            if (recap == null) return@LaunchedEffect
            scroll.scrollTo(0)
            if (countUp) announce(view, recapAnnouncement(recap, c.lastStartWasTest, c.lastStartWasBonded))
        }
        Column(m.verticalScroll(scroll).padding(bottom = 24.dp)) {
            c.unexpectedEnd?.let { UnexpectedEndCard(it, onFix = c::openAppSettings, onDismiss = c::dismissUnexpectedEnd) }
            if (c.unexpectedEnd == null) RecapSlot(c, countUp, reduced, onDone = {
                FbHaptics.play(view, HapticPattern.KEY)
                c.dismissRecap()
                announce(view, "Recap closed.")
            })
            if (firstRun) FirstRun(c, nav, reduced) else SignedInHome(c, nav, preflight, arrival.value, reduced)
        }
    }
    if (isWideLayout()) {
        // Sideways: the checklist on the left at a readable width, the go-live panel down the right where the right
        // thumb rests on a phone held in landscape. The bar spans only the checklist, so the panel runs full height.
        Row(
            Modifier.fillMaxSize().background(colors.bg)
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        ) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                HomeBar(onSettings = { nav.open(Screen.SETTINGS) }, insets = false)
                if (ready) body(Modifier.fillMaxWidth().weight(1f).navigationBarsPadding().widthIn(max = WIDE_CONTENT))
            }
            AnimatedVisibility(
                visible = dockShown,
                enter = if (reduced) EnterTransition.None else slideInHorizontally(tween(FbMotion.ENTER_MS, easing = FbMotion.Enter)) { it },
                exit = ExitTransition.None,
            ) {
                Row(Modifier.fillMaxHeight()) {
                    Box(Modifier.fillMaxHeight().width(1.dp).background(colors.rule))
                    Column(Modifier.width(DOCK_SIDE).fillMaxHeight().navigationBarsPadding(), verticalArrangement = Arrangement.Bottom) {
                        Dock(c, nav, ctaFocus, dockAttached, side = true)
                    }
                }
            }
        }
    } else {
        Scaffold(
            containerColor = colors.bg,
            topBar = { HomeBar(onSettings = { nav.open(Screen.SETTINGS) }) },
            bottomBar = {
                AnimatedVisibility(
                    visible = dockShown,
                    enter = if (reduced) EnterTransition.None else slideInVertically(tween(FbMotion.ENTER_MS, easing = FbMotion.Enter)) { it },
                    exit = ExitTransition.None,
                ) { Dock(c, nav, ctaFocus, dockAttached) }
            },
        ) { inner ->
            if (ready) body(Modifier.fillMaxSize().padding(inner))
        }
    }
    if (c.streamInfoOpen) StreamInfoSheet(c)
}

/**
 * First run: Twitch sign-in chosen and signed out (or approving). Shown with Combine on too: pairing first (as
 * the BRB guide suggests) must not skip sign-in, or the server would only record. Not shown when the stream goes to My
 * OBS (sign-in is optional there), when the paired server already streams somewhere of its own, or in a build without
 * sign-in.
 */
internal fun showsWelcome(c: SetupController) =
    c.signInAvailable && !c.useKey && !(c.combine && c.sendToObs) && !c.serverHasOwnOutput &&
        (c.signIn is SignIn.SignedOut || (c.signIn is SignIn.Waiting && !c.resigningIn))

/**
 * Saved secrets and the saved sign-in are read (a few milliseconds, off the main thread). Until then Home shows
 * nothing, rather than a flash of the dock on a first launch or of the first run for a signed-in streamer.
 */
private fun homeReady(c: SetupController) = c.loaded && c.signIn !is SignIn.Checking

/** The dock: Home, once ready, whenever there's a way to go live (AppNav keeps snackbars above it). */
internal fun showsGoLiveBar(c: SetupController) = homeReady(c) && !showsWelcome(c)

/** A one-off polite sentence for TalkBack (Compose has no announcement API of its own). */
@Suppress("DEPRECATION")
private fun announce(view: View, text: String) = view.announceForAccessibility(text)

/** Focus may land before the CTA is attached (a fast recomposition); missing it once is harmless. */
private fun focus(r: FocusRequester) {
    runCatching { r.requestFocus() }
}

/**
 * Moves focus to the CTA only once the dock is really in the tree. The dock joins the composition a
 * frame after it's asked for (AnimatedVisibility and Scaffold's bottomBar subcompose it), so with reduce motion on, when
 * there's no rise to wait out, an immediate request found no attached requester and TalkBack users never got there.
 * Reduce motion keeps focus behaving the same: only the 280ms wait for the rise is dropped.
 */
private suspend fun focusCtaWhenDocked(dockAttached: MutableState<Boolean>, ctaFocus: FocusRequester, reduced: Boolean) {
    withTimeoutOrNull(DOCK_ATTACH_TIMEOUT_MS) { snapshotFlow { dockAttached.value }.first { it } } ?: return
    withFrameNanos { } // one laid-out frame with the dock in it, so the request lands on a placed node
    if (!reduced) delay(FbMotion.ENTER_MS.toLong())
    focus(ctaFocus)
}

/** Width of the go-live panel beside the checklist in landscape: the tabs and the CTA's longest label fit. */
private val DOCK_SIDE = 340.dp

/** The 56dp bar: the mark and the wordmark, and the one settings key. No TopAppBar. */
@Composable
private fun HomeBar(onSettings: () -> Unit, insets: Boolean = true) {
    val c = LocalFbColors.current
    Row(
        Modifier.fillMaxWidth().background(c.bg).then(if (insets) Modifier.statusBarsPadding() else Modifier)
            .height(56.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(FbIcons.Mark, contentDescription = null, tint = c.text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text("Fullbars", style = FbType.wordmark, color = c.text, maxLines = 1, modifier = Modifier.weight(1f).semantics { heading() })
        GlyphButton(FbIcons.Sliders, contentDescription = "Settings", onClick = onSettings)
    }
}

// --- First run -------------------------------------------------------------------------------------------------------

@Composable
private fun FirstRun(c: SetupController, nav: Navigator, reduced: Boolean) {
    val colors = LocalFbColors.current
    val waiting = c.signIn as? SignIn.Waiting
    Text(
        "Stream from your phone. Stay live in dead zones.", style = FbType.display, color = colors.text,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp).semantics { heading() },
    )
    Text(
        "Sign in once and Fullbars gets your stream key. Test in private first.", style = FbType.help, color = colors.text2,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
    )
    if (c.signInAvailable) {
        // Disabled while approving: a second tap would throw away the code being approved.
        FbButton(
            "Continue with Twitch", onClick = c::startSignIn, kind = FbButtonKind.Twitch, enabled = waiting == null,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp),
        )
    }
    DeviceCodeSlot(c, reduced)
    // A go-live failure that signed the user out ("Please sign in to Twitch again.") lands here too.
    (c.signInError ?: c.error)?.let { InlineError(it, modifier = Modifier.padding(top = 8.dp)) }
    FbButton(
        "Use a stream key instead", onClick = { c.chooseUseKey(true); nav.open(Screen.TWITCH) }, kind = FbButtonKind.Text,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
    )
    SectionHeader("Defaults", trailing = "not checked yet")
    Hairline()
    ValueRow(
        label = defaultsLine(c), value = "", valueIsFigure = false, state = StateMark.UNCHECKED,
        onClick = { nav.open(Screen.SETTINGS) }, onClickLabel = "Open settings",
    )
}

/** "Phone camera · this phone's connection · 1080p": what a first stream would use, in one unchecked row. */
private fun defaultsLine(c: SetupController): String {
    val camera = when (c.cameraKind) {
        ExternalCamera.Kind.PHONE -> "Phone camera"
        ExternalCamera.Kind.POCKET -> "Pocket 3"
        ExternalCamera.Kind.RTMP -> "Other camera"
    }
    val connection = if (c.combine) "combined connections" else "this phone's connection"
    return "$camera · $connection · ${c.quality.title}"
}

/**
 * The device-code plate, under the Twitch button while approving: it expands with Settle and folds away with Exit.
 * It keeps the last code while folding, after the sign-in state has moved on.
 */
@Composable
private fun DeviceCodeSlot(c: SetupController, reduced: Boolean) {
    val code = (c.signIn as? SignIn.Waiting)?.code
    val held = remember { arrayOfNulls<DeviceCode>(1) }
    if (code != null) held[0] = code
    AnimatedVisibility(
        visible = code != null,
        enter = if (reduced) EnterTransition.None else expandVertically(SettleSize),
        exit = if (reduced) ExitTransition.None else shrinkVertically(tween(FbMotion.EXIT_MS, easing = FbMotion.Exit)) +
            fadeOut(tween(FbMotion.EXIT_MS, easing = FbMotion.Exit)),
    ) {
        held[0]?.let { DeviceCodePlate(it, onReopen = c::reopenSignInPage, onCancel = c::cancelSignIn) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceCodePlate(code: DeviceCode, onReopen: () -> Unit, onCancel: () -> Unit) {
    val c = LocalFbColors.current
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)
            .background(c.s1, FbShape.medium).padding(16.dp),
    ) {
        Text(
            "APPROVE ON TWITCH", style = FbType.label, color = c.text2,
            modifier = Modifier.clearAndSetSemantics { heading(); contentDescription = "Approve on Twitch" },
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                code.userCode, style = FbType.code, color = c.text,
                // Spelled out, so TalkBack reads "W D J B M J H T" rather than guessing at a word.
                modifier = Modifier.weight(1f).clearAndSetSemantics { contentDescription = code.userCode.toCharArray().joinToString(" ") },
            )
            CopyKey(code.userCode, label = "Twitch code")
        }
        Text(
            "Your browser opened twitch.tv/activate. Enter this code if it asks for one.", style = FbType.help, color = c.text2,
            modifier = Modifier.padding(top = 8.dp),
        )
        FlowRow(
            Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            FbButton("Open Twitch again", onClick = onReopen, kind = FbButtonKind.Secondary)
            FbButton("Cancel", onClick = onCancel, kind = FbButtonKind.Text)
        }
    }
}

// --- Recap slot ------------------------------------------------------------------------------------------------------

/**
 * The recap, and after DONE the one line it folds into. The fold is the recap shrinking away with Exit; a
 * new recap cuts in, since it arrives with the cut from Live.
 */
@Composable
private fun RecapSlot(c: SetupController, countUp: Boolean, reduced: Boolean, onDone: () -> Unit) {
    val recap = c.recap
    val last = c.lastStream
    AnimatedContent(
        // The fold travels with the state, so the outgoing recap keeps drawing as a recap while it shrinks away.
        targetState = when {
            recap != null -> RecapView(recap, folded = false)
            last != null -> RecapView(last, folded = true)
            else -> null
        },
        transitionSpec = {
            val folding = initialState?.folded == false && targetState?.folded == true
            if (reduced || !folding) {
                EnterTransition.None togetherWith ExitTransition.None using SizeTransform(clip = true) { _, _ -> snap() }
            } else {
                EnterTransition.None togetherWith fadeOut(tween(FbMotion.EXIT_MS, easing = FbMotion.Exit)) using
                    SizeTransform(clip = true) { _, _ -> tween(FbMotion.EXIT_MS, easing = FbMotion.Exit) }
            }
        },
        label = "recap",
    ) { v: RecapView? ->
        when {
            v == null -> Unit
            v.folded -> LastStreamRow(v.summary)
            else -> Column {
                RecapCard(
                    v.summary, wasTest = c.lastStartWasTest, bonded = c.lastStartWasBonded, links = c.recapLinks,
                    countUp = countUp, onOpenInspector = c::openInspector, onDone = onDone,
                )
                if (c.lastStartWasTest && v.summary.liveDurationMs > 0) HearTestStream(v.summary.endedAtMs)
            }
        }
    }
}

/** How much of a Test stream's end Home plays back: a few sentences, as viewers would have heard them. */
private const val RECAP_AUDIO_SECONDS = 15

/**
 * After a Test stream: "Hear how you sounded" plays its last seconds as viewers would have heard them, the stream's
 * final mix (the phone mic or the camera's audio, after the level stage), so mic trouble shows up before a public
 * stream rather than in chat during one. Not after a public stream (it's over; the VOD has it), and Home isn't shown
 * while live. Nothing shows when the engine kept no audio.
 */
@Composable
private fun HearTestStream(endedAtMs: Long) {
    val engine = LocalStreamEngine.current ?: return
    val player = rememberSoundPlayer()
    // Copied off the main thread (15 s of stereo is ~3 MB) and kept while the recap shows, so replays are instant.
    val clip by produceState<PcmClip?>(null, endedAtMs, engine) {
        value = withContext(Dispatchers.Default) { engine.recentAudio(RECAP_AUDIO_SECONDS) }
    }
    val take = clip ?: return
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        PlayClipButton("Hear how you sounded", take, player, Modifier.padding(horizontal = 16.dp))
        HelpText("The last ${take.seconds.roundToInt()} seconds of the test, as viewers would hear them. Headphones tell you the most.")
    }
}

/** What the recap slot shows: the full recap, or after DONE the [folded] one-line version of the same stream. */
private data class RecapView(val summary: SessionSummary, val folded: Boolean)

// --- Signed in: preflight and verdict --------------------------------------------------------------------------------

@Composable
private fun SignedInHome(c: SetupController, nav: Navigator, pf: Preflight?, arrival: Arrival, reduced: Boolean) {
    // Approving from Settings (key or OBS mode, where sign-in only adds chat): the plate shows here too.
    DeviceCodeSlot(c, reduced)
    PreflightHeader(pf)
    Hairline()
    // On arrival the square is drawn unchecked, then fills in one Tick, in place.
    var twitchTicked by remember(arrival == Arrival.SIGNED_IN) { mutableStateOf(arrival != Arrival.SIGNED_IN || reduced) }
    LaunchedEffect(twitchTicked) {
        if (!twitchTicked) { delay(FbMotion.TICK_HOLD_MS.toLong()); twitchTicked = true }
    }
    DestinationRow(c, nav, ticked = twitchTicked)
    if (c.followAlertsNeedSignIn && c.overlays.alertsOn && c.signInAvailable && c.signIn is SignIn.SignedIn) {
        FollowAlertsOff(onSignIn = c::startSignIn)
    }
    // A sign-in again that didn't finish says why here, where it was started, not only on the welcome screen.
    c.signInError?.let { if (c.signIn is SignIn.SignedIn) InlineError(it, modifier = Modifier.padding(bottom = 8.dp)) }
    AnimatedVisibility(
        visible = arrival != Arrival.SIGNED_IN,
        enter = if (reduced) EnterTransition.None else expandVertically(SettleSize),
        exit = ExitTransition.None,
    ) {
        Column(Modifier.fillMaxWidth()) {
            val ch = c.channel
            if (c.showsStreamInfo && ch != null) TitleRow(ch, onClick = c::openStreamInfo)
            CameraRow(c, nav)
            ConnectionRow(c, nav, pf)
            QualityRow(c, nav, pf)
            VerdictBlock(readyVerdict(c.blocker, pf, c.quality), pf, onUse720 = { c.chooseQuality(Quality.HD720) })
            if (isAggressiveOem) {
                // Can't be read by an app, so it's a link to the page that explains it, not a checked row.
                Spacer(Modifier.height(16.dp))
                Hairline()
                NavRow("Keep streaming with the screen off", onClick = { nav.open(Screen.SCREEN_OFF) })
            }
        }
    }
}

/** "PREFLIGHT ——— checked 12 s ago", ticking each second on its own so only the stamp recomposes. */
@Composable
private fun PreflightHeader(pf: Preflight?) {
    val at = pf?.checkedAtMs
    val now by produceState(System.currentTimeMillis(), at) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    SectionHeader("Preflight", trailing = if (at == null) "checking…" else checkedAgo(at, now))
}

/**
 * Twitch (or My OBS): the login when signed in. VERIFIED only when there's really a destination; anything unfinished
 * (signed out, approving, no key yet) stays UNCHECKED, never red: it isn't a failure, just not done.
 */
@Composable
private fun DestinationRow(c: SetupController, nav: Navigator, ticked: Boolean) {
    val s = c.signIn
    val toObs = c.sendsToObs
    val signedIn = s is SignIn.SignedIn
    val unfinished = c.blocker in setOf(Blocker.SIGN_IN, Blocker.ADD_KEY, Blocker.APPROVING, Blocker.CHECKING)
    val mark = when {
        !ticked -> StateMark.UNCHECKED
        unfinished -> StateMark.UNCHECKED
        toObs -> StateMark.VERIFIED
        c.useKey -> if (c.key.isNotBlank()) StateMark.VERIFIED else StateMark.UNCHECKED
        signedIn -> StateMark.VERIFIED
        c.serverHasOwnOutput -> StateMark.VERIFIED
        else -> StateMark.UNCHECKED
    }
    val value = if (signedIn && !c.useKey && !toObs) (s as SignIn.SignedIn).login else c.destinationSummary
    ValueRow(
        label = if (toObs) "My OBS" else "Twitch", value = value, valueIsFigure = false, state = mark,
        onClick = { nav.open(Screen.TWITCH) },
    )
}

/**
 * Title, stacked: the label, then the whole title wrapping under it, then the category. Never truncated;
 * the row opens the title sheet.
 */
@Composable
private fun TitleRow(ch: ChannelInfo, onClick: () -> Unit) {
    val c = LocalFbColors.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    Row(
        Modifier.fillMaxWidth().bottomRule(c.rule)
            .background(if (pressed || focused) c.s2 else Color.Transparent)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = "Change title and category", onClick = onClick)
            .semantics { stateDescription = StateMark.VERIFIED.spoken.orEmpty() }
            .heightIn(min = FbSpace.rowTappable)
            .padding(horizontal = FbSpace.gutter, vertical = 17.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StateSquare(StateMark.VERIFIED, Modifier.padding(top = 5.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Title", style = FbType.row, color = c.text)
            Text(ch.title.ifBlank { "No title yet" }, style = FbType.rowValue, color = c.text, modifier = Modifier.padding(top = 4.dp))
            Text(ch.categoryName?.takeIf { it.isNotBlank() } ?: "No category", style = FbType.rowValue, color = c.text2)
        }
        Spacer(Modifier.width(8.dp))
        Icon(FbIcons.Chevron, contentDescription = null, tint = c.text2, modifier = Modifier.size(20.dp))
    }
}

/**
 * Camera: "Phone", or "Pocket 3" with the chosen camera's name. The phone camera is VERIFIED once Android has granted
 * it; a hotspot the phone has checked and found off is the one verified failure here.
 */
@Composable
private fun CameraRow(c: SetupController, nav: Navigator) {
    val ctx = LocalContext.current
    val blocker = c.blocker
    val rtmpUrl = remember(c.cameraKind) { if (c.cameraKind == ExternalCamera.Kind.RTMP) c.rtmpUrls.firstOrNull() else null }
    val (value, sub) = when (c.cameraKind) {
        ExternalCamera.Kind.PHONE -> "Phone" to null
        ExternalCamera.Kind.POCKET -> "Pocket 3" to when (blocker) {
            Blocker.HOTSPOT_OFF -> "Your hotspot is off"
            else -> c.pocketDevice?.second ?: "Find your Pocket 3"
        }
        ExternalCamera.Kind.RTMP -> "Other camera" to (rtmpUrl ?: "Connect to Wi‑Fi so a camera can reach this phone")
    }
    val mark = when (c.cameraKind) {
        ExternalCamera.Kind.PHONE ->
            if (ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) StateMark.VERIFIED else StateMark.UNCHECKED
        ExternalCamera.Kind.POCKET -> when (blocker) {
            Blocker.HOTSPOT_OFF -> StateMark.FAILED
            Blocker.CHOOSE_POCKET, Blocker.CAMERA_WIFI -> StateMark.UNCHECKED
            else -> StateMark.VERIFIED
        }
        ExternalCamera.Kind.RTMP -> if (rtmpUrl != null) StateMark.VERIFIED else StateMark.UNCHECKED
    }
    ValueRow(
        label = "Camera", value = value, valueIsFigure = false, state = mark, sub = sub,
        subIsFigure = rtmpUrl != null, onClick = { nav.open(Screen.CAMERA) },
    )
}

/**
 * Connection. Combined: the BRB server's own probe decides (unreachable or a blocked UDP port are verified failures).
 * This phone alone: the preflight's network and ingest check. Before an answer, UNCHECKED.
 */
@Composable
private fun ConnectionRow(c: SetupController, nav: Navigator, pf: Preflight?) {
    val (value, sub, mark) = if (c.combine) {
        val p = c.pairing
        when {
            p == null -> Triple("Combined", "Pair your server", StateMark.UNCHECKED)
            c.brbCheck?.checked != true -> Triple("Combined", "Checking your server…", StateMark.UNCHECKED)
            c.brbUdpBlocked -> Triple("Combined", "UDP ${p.port} looks blocked", StateMark.FAILED)
            !c.brbOk -> Triple("Combined", c.brbStatus ?: "Can't reach your server", StateMark.FAILED)
            else -> Triple("Combined", "Server ready", StateMark.VERIFIED)
        }
    } else {
        when {
            pf == null -> Triple("This phone", null, StateMark.UNCHECKED)
            !pf.readings.online -> Triple(pf.readings.kind, "No internet", StateMark.FAILED)
            pf.readings.ingestMs == null -> Triple(pf.readings.kind, "Can't reach Twitch", StateMark.FAILED)
            else -> Triple(pf.readings.kind, null, StateMark.VERIFIED)
        }
    }
    ValueRow(
        label = "Connection", value = value, valueIsFigure = false, state = mark, sub = sub,
        onClick = { nav.open(Screen.CONNECTIONS) },
    )
}

/** Video: the figures in mono ("1080p · 6 Mb/s"), the data use under it, as Settings › Video says it. */
@Composable
private fun QualityRow(c: SetupController, nav: Navigator, pf: Preflight?) {
    val q = c.quality
    val perHour = "%.1f GB/h".format(q.gbPerHour)
    ValueRow(
        label = "Video", value = "${q.title} · ${q.rate}", valueIsFigure = true,
        state = if (pf == null) StateMark.UNCHECKED else StateMark.VERIFIED,
        sub = if (c.orientation == StreamOrientation.PORTRAIT) "Portrait · $perHour" else perHour, subIsFigure = false,
        onClick = { nav.open(Screen.QUALITY) },
    )
}

// --- The dock --------------------------------------------------------------------------------------------------------

/** Which top corner of the CTA the selected bus tab joins (that corner is square). */
private enum class Join { START, END }

/** What the CTA is: a Test (56dp, NOT PUBLIC), a Live (64dp, the mark) or a plain start (My OBS). */
private enum class CtaKind { TEST, LIVE, PLAIN }

/**
 * Pinned above the navigation bar: the fused TEST/LIVE bus, the CTA it joins, and one line under it that
 * says where the stream goes, or why it can't go yet.
 */
@Composable
private fun Dock(c: SetupController, nav: Navigator, ctaFocus: FocusRequester, attached: MutableState<Boolean>, side: Boolean = false) {
    val colors = LocalFbColors.current
    // Runs after the dock's nodes (and the CTA's focus requester) are attached; see focusCtaWhenDocked.
    DisposableEffect(Unit) {
        attached.value = true
        onDispose { attached.value = false }
    }
    // Under the checklist it spans the width over the nav bar; beside it (landscape) the panel handles the insets.
    Column(Modifier.fillMaxWidth().background(colors.bg).then(if (side) Modifier else Modifier.navigationBarsPadding())) {
        if (!side) Hairline()
        val bus = c.audienceApplies
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            if (bus) BusTabs(test = c.testMode, onSelect = { c.testMode = it })
            GoLiveCta(
                label = c.mainLabel,
                kind = when {
                    !bus -> CtaKind.PLAIN
                    c.testMode -> CtaKind.TEST
                    else -> CtaKind.LIVE
                },
                joined = if (!bus) null else if (c.testMode) Join.START else Join.END,
                enabled = c.canGo, busy = c.busy, onClick = c::onMainButton, focus = ctaFocus,
            )
        }
        DockLine(c, nav)
    }
}

/**
 * The bus: two 48dp tabs. The selected one is filled with the CTA colour and runs straight into the CTA (no gap, square
 * bottom corners), so the two read as one L-shaped piece; the other is a ruleStrong outline that stops short. The fill
 * slides across with Settle. The on-air dot is always on LIVE, the only red on Home.
 */
@Composable
private fun BusTabs(test: Boolean, onSelect: (Boolean) -> Unit) {
    val c = LocalFbColors.current
    val reduced = FbMotion.rememberReduced()
    val at by animateFloatAsState(if (test) 0f else 1f, FbMotion.settle(reduced), label = "bus")
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup()
            .drawBehind {
                val w = size.width / 2
                val r = FbShape.rM.toPx()
                val x = at * w
                drawRoundRect(c.cta, Offset(x, 0f), Size(w, size.height), CornerRadius(r))
                drawRect(c.cta, Offset(x, size.height - r), Size(w, r)) // square bottom: it joins the CTA
            },
    ) {
        BusTab("TEST", "private", spoken = "Test, private", selected = test, dot = false) { onSelect(true) }
        BusTab("LIVE", "public", spoken = "Live, public", selected = !test, dot = true) { onSelect(false) }
    }
}

@Composable
private fun RowScope.BusTab(word: String, sub: String, spoken: String, selected: Boolean, dot: Boolean, onClick: () -> Unit) {
    val c = LocalFbColors.current
    val gap = 4.dp
    Row(
        Modifier.weight(1f).fillMaxHeight().heightIn(min = FbSpace.target)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = spoken }
            .drawBehind {
                if (!selected) {
                    val w = 1.dp.toPx()
                    drawRoundRect(
                        c.ruleStrong, Offset(w / 2, w / 2), Size(size.width - w, size.height - gap.toPx() - w),
                        CornerRadius(FbShape.rM.toPx()), style = Stroke(w),
                    )
                }
            }
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp + gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(Modifier.size(8.dp).background(c.onAir, CircleShape))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            buildAnnotatedString {
                append(word)
                withStyle(
                    SpanStyle(
                        fontFamily = FbType.help.fontFamily, fontWeight = FbType.help.fontWeight, letterSpacing = 0.sp,
                        color = if (selected) c.onCta else c.text2,
                    ),
                ) {
                    append("  $sub")
                }
            },
            style = FbType.label.copy(fontSize = 14.sp, lineHeight = 18.sp),
            color = if (selected) c.onCta else c.text,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * The CTA. Test and Live differ in height (56 vs 64dp, the change springs with the bus), label size, tag and shape:
 * Test carries a dashed NOT PUBLIC tag, Live the mark. Finger-down inverts it with the KEY tick. Blocked, it drops to 38%
 * and stops taking taps; busy, it keeps full strength with a spinner. Keyboard focus draws an inner ring rather than
 * the press inversion, so focus moved here after sign-in doesn't look like a press.
 */
@Composable
private fun GoLiveCta(
    label: String,
    kind: CtaKind,
    joined: Join?,
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    focus: FocusRequester,
) {
    val c = LocalFbColors.current
    val view = LocalView.current
    val reduced = FbMotion.rememberReduced()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    LaunchedEffect(pressed) { if (pressed && enabled) FbHaptics.play(view, HapticPattern.KEY) }
    val minHeight by animateDpAsState(if (kind == CtaKind.LIVE) 64.dp else 56.dp, FbMotion.settle(reduced), label = "ctaHeight")
    val rM = FbShape.rM
    val shape = RoundedCornerShape(
        topStart = if (joined == Join.START) 0.dp else rM, topEnd = if (joined == Join.END) 0.dp else rM,
        bottomStart = rM, bottomEnd = rM,
    )
    val down = pressed && enabled
    val fill = if (down) c.bg else c.cta
    val content = if (down) c.cta else c.onCta
    Row(
        Modifier.fillMaxWidth().alpha(if (enabled || busy) 1f else 0.38f)
            .heightIn(min = minHeight)
            .background(fill, shape)
            .then(if (down) Modifier.border(FbShape.stroke, c.cta, shape) else Modifier)
            .focusRequester(focus)
            .focusProperties { canFocus = true }
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .drawBehind {
                if (focused && !down) {
                    val inset = 4.dp.toPx()
                    drawRoundRect(
                        content, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset),
                        CornerRadius((rM - 2.dp).toPx()), style = Stroke(2.dp.toPx()),
                    )
                }
            }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = if (kind == CtaKind.LIVE) FbType.ctaLive else FbType.cta, color = content, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        when {
            busy -> CircularProgressIndicator(Modifier.size(22.dp), color = content, strokeWidth = 2.dp)
            kind == CtaKind.TEST -> Tag("Not public", dashed = true, color = content)
            kind == CtaKind.LIVE -> Icon(FbIcons.Mark, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            else -> Unit
        }
    }
}

/**
 * The one line under the CTA: where this stream goes, or, when it can't go, the go-live blocker's reason and its fix
 * (the same pure source as the verdict), or a verified failure from the last attempt.
 */
@Composable
private fun DockLine(c: SetupController, nav: Navigator) {
    val colors = LocalFbColors.current
    val error = c.error
    val blocker = c.blocker
    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp)) {
        when {
            error != null -> InlineError(
                error,
                actionLabel = when {
                    c.errorNeedsSignIn && c.signInAvailable -> "Sign in"
                    c.errorNeedsServer -> "Connection settings"
                    c.permissionDenied -> "Open app settings"
                    else -> null
                },
                onAction = when {
                    c.errorNeedsSignIn && c.signInAvailable -> c::startSignIn
                    c.errorNeedsServer -> { { nav.open(Screen.CONNECTIONS) } }
                    c.permissionDenied -> c::openAppSettings
                    else -> null
                },
            )
            blocker != null && !c.busy -> Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(blocker.text, style = FbType.help, color = colors.text, modifier = Modifier.weight(1f))
                if (blocker.action != null) {
                    Spacer(Modifier.width(12.dp))
                    FbButton(blocker.action, kind = FbButtonKind.Text, onClick = {
                        when (blocker) {
                            Blocker.PAIR_SERVER -> nav.open(Screen.CONNECTIONS)
                            Blocker.SIGN_IN -> c.startSignIn()
                            Blocker.ADD_KEY -> nav.open(Screen.TWITCH)
                            Blocker.CHOOSE_POCKET, Blocker.CAMERA_WIFI -> nav.open(Screen.CAMERA)
                            Blocker.HOTSPOT_OFF -> c.openHotspotSettings()
                            Blocker.APPROVING, Blocker.CHECKING -> Unit
                        }
                    })
                }
            }
            else -> dockLine(c)?.let {
                Text(it, style = FbType.help, color = colors.text2, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
        }
    }
}

/** The line for a stream that can go: private, public (and where), or the hand-on after a clean test. */
private fun dockLine(c: SetupController): String? {
    if (!c.audienceApplies) return null
    // The TEST tab already says "private"; a second line saying so is noise.
    if (c.testMode) return null
    if (c.cleanTestHandoff && c.showsStreamInfo) return "The test was clean. Your title sheet comes next."
    val login = (c.signIn as? SignIn.SignedIn)?.login?.takeIf { !c.useKey && it.none(Char::isWhitespace) }
    return if (login != null) "Goes out on twitch.tv/$login." else "Everyone on your channel can see you."
}

// --- Title sheet -----------------------------------------------------------------------------------------------------

/**
 * Title and category, read from Twitch and saved back (PATCH /channels, scope channel:manage:broadcast). Opens before
 * every public stream to the signed-in channel, so a first public stream doesn't go out under last week's game; and
 * from the Home row any time. Nothing is sent when nothing changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StreamInfoSheet(c: SetupController) {
    val colors = LocalFbColors.current
    val ch = c.channel
    // Filled once the channel arrives (the sheet can open before it has loaded).
    var title by rememberSaveable(ch?.broadcasterId) { mutableStateOf(ch?.title ?: "") }
    var categoryQuery by rememberSaveable(ch?.broadcasterId) { mutableStateOf(ch?.categoryName ?: "") }
    var picked by remember(ch?.broadcasterId) { mutableStateOf<TwitchCategory?>(null) }
    LaunchedEffect(ch?.broadcasterId) {
        if (ch != null) { title = ch.title; categoryQuery = ch.categoryName ?: "" }
    }
    ModalBottomSheet(
        onDismissRequest = { if (!c.streamInfoBusy) c.dismissStreamInfo() },
        containerColor = colors.s1,
        contentColor = colors.text,
        shape = RoundedCornerShape(topStart = FbShape.rM, topEnd = FbShape.rM),
        dragHandle = { Box(Modifier.padding(vertical = 12.dp).size(32.dp, 4.dp).background(colors.ruleStrong, FbShape.small)) },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (c.streamInfoGoesLive) "Before you go live" else "Title and category",
                style = FbType.wordmark, color = colors.text,
                modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
            )
            Text(
                "This is what people see on Twitch next to your stream.", style = FbType.help, color = colors.text2,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (ch == null && c.streamInfoBusy) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.text)
                    Text("Getting your title and category…", style = FbType.help, color = colors.text2, modifier = Modifier.padding(start = 12.dp))
                }
            }
            if (ch != null) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(HelixClient.MAX_TITLE_LENGTH) },
                    label = { Text("Title") },
                    supportingText = { Text("${title.length}/${HelixClient.MAX_TITLE_LENGTH}", fontFamily = JetMono) },
                    enabled = !c.streamInfoBusy,
                    textStyle = FbType.rowValue,
                    shape = FbShape.medium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                OutlinedTextField(
                    value = categoryQuery,
                    onValueChange = { categoryQuery = it; picked = null; c.searchCategories(it) },
                    label = { Text("Category") },
                    supportingText = { Text(if (picked != null) "Picked: ${picked?.name}" else "Type to search, then pick one") },
                    singleLine = true,
                    enabled = !c.streamInfoBusy,
                    textStyle = FbType.rowValue,
                    shape = FbShape.medium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                if (picked == null && c.categoryResults.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth()) {
                        Hairline()
                        c.categoryResults.forEach { cat ->
                            CategoryRow(cat.name) { picked = cat; categoryQuery = cat.name; c.searchCategories("") }
                        }
                    }
                }
            }
            c.streamInfoError?.let { InlineError(it) }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A failed load or save must not trap a streamer who is about to go live.
                if (c.streamInfoGoesLive && c.streamInfoError != null) {
                    FbButton("Go live without changes", onClick = c::continueWithoutSaving, kind = FbButtonKind.Text, enabled = !c.streamInfoBusy)
                } else {
                    FbButton("Cancel", onClick = c::dismissStreamInfo, kind = FbButtonKind.Text, enabled = !c.streamInfoBusy)
                }
                FbButton(
                    if (c.streamInfoBusy && ch != null) "Saving…" else if (c.streamInfoGoesLive) "Go live" else "Save",
                    onClick = { if (ch == null) c.continueWithoutSaving() else c.saveStreamInfo(title, picked) },
                    enabled = !c.streamInfoBusy && (ch != null || c.streamInfoGoesLive),
                )
            }
        }
    }
}

/** A category suggestion: a whole-row choice with a hairline, no ListItem. */
@Composable
private fun CategoryRow(name: String, onClick: () -> Unit) {
    val c = LocalFbColors.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier.fillMaxWidth().bottomRule(c.rule)
            .background(if (pressed) c.s2 else Color.Transparent)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .heightIn(min = FbSpace.rowTappable).padding(horizontal = 16.dp, vertical = 17.dp),
    ) { Text(name, style = FbType.row, color = c.text) }
}

/** The one purple element in the app: kept for any screen that offers sign-in. */
@Composable
fun TwitchButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FbButton("Continue with Twitch", onClick = onClick, kind = FbButtonKind.Twitch, modifier = modifier.fillMaxWidth())
}

/**
 * Follow alerts need a Twitch permission (moderator:read:followers) that sign-ins from before follow alerts existed
 * don't have; without it Twitch never sends follows, and nothing else would say so. One plain line under the Twitch
 * row and a way to fix it, rather than a line deep in Settings.
 */
@Composable
private fun FollowAlertsOff(onSignIn: () -> Unit) {
    val colors = LocalFbColors.current
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Follow alerts are off. Twitch needs you to sign in again once to show who follows.",
            style = FbType.help, color = colors.text2, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        FbButton("Sign in again", onSignIn, kind = FbButtonKind.Text)
    }
}
