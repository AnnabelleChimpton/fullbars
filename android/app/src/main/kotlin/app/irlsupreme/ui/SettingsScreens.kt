package app.irlsupreme.ui

import android.app.ActivityManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telephony.TelephonyManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.irlsupreme.dji.FoundCamera
import app.irlsupreme.dji.PocketStep
import app.irlsupreme.dji.PocketSteps
import app.irlsupreme.dji.StepStatus
import app.irlsupreme.engine.Facing
import app.irlsupreme.service.AlsoStreamRules
import app.irlsupreme.service.AlsoStreamTo
import app.irlsupreme.service.BrbStatus
import app.irlsupreme.service.ExternalCamera
import app.irlsupreme.service.StreamDelay
import app.irlsupreme.service.HelperState
import app.irlsupreme.service.HelperStatus
import app.irlsupreme.service.CaptionSettings
import app.irlsupreme.service.CardStore
import app.irlsupreme.service.MicSettings
import app.irlsupreme.service.cardsValue
import app.irlsupreme.service.OverlaySettings
import app.irlsupreme.service.OverlayStore
import app.irlsupreme.service.micValue
import app.irlsupreme.twitch.ChatCommandParser
import app.irlsupreme.twitch.CommandPolicy
import app.irlsupreme.twitch.DeviceCode
import app.irlsupreme.ui.theme.FbMotion
import app.irlsupreme.ui.theme.FbShape
import app.irlsupreme.ui.theme.FbSpace
import app.irlsupreme.ui.theme.FbType
import app.irlsupreme.ui.theme.JetMono
import app.irlsupreme.ui.theme.LocalFbColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

// Settings and its pages. All state and actions live in SetupController; these are views. The grammar
// is Home's: a label on the left, the state on the right, hairlines between rows, no icons and no containers. Each row
// carries at most one help line; anything longer is a numbered footnote at the page foot.

// `brb` is the server command's name ("stay-live" was the old one; the server keeps it as an alias for old guides).
private const val OBS_COMMAND = "docker exec -it fullbars-brb brb obs"
private const val PAIR_COMMAND = "docker exec -it fullbars-brb brb pair"
private const val CERT_HINT = "My Fullbars mod remote. The first time, your browser says the connection isn't private " +
    "(my server signs its own certificate): tap Advanced, then continue."
private const val UNOFFICIAL = "Unofficial integration: DJI doesn't support this, and a firmware update could break it."

/** My OBS can be chosen only with Combine on and a server paired; otherwise the stream goes to Twitch. */
private val SetupController.obsAvailable: Boolean get() = combine && pairing != null
private val SetupController.toObs: Boolean get() = obsAvailable && sendToObs

// ---- Values the index reports (pure, so SettingsValuesTest pins them) ----

/** The Twitch row's value: the account in use, or the one thing that's missing. Words, never a sentence. */
internal fun twitchValue(toObs: Boolean, serverDecides: Boolean, useKey: Boolean, hasKey: Boolean, signIn: SignIn): String =
    when {
        toObs -> "My OBS"
        serverDecides -> "Set on your server"
        useKey -> if (hasKey) "Stream key" else "No stream key"
        signIn is SignIn.SignedIn -> signIn.login
        signIn is SignIn.Checking -> "Checking"
        signIn is SignIn.Waiting -> "Approve on Twitch"
        else -> "Not signed in"
    }

internal fun cameraValue(kind: ExternalCamera.Kind, pocketName: String?): String = when (kind) {
    ExternalCamera.Kind.PHONE -> "Phone"
    ExternalCamera.Kind.POCKET -> pocketName ?: "Pocket 3 · not set up"
    ExternalCamera.Kind.RTMP -> "RTMP camera"
}

/**
 * What's known about the paired BRB server. Only a check that ran and came back wrong is a failure (red): before the
 * first answer it's CHECKING, which is never red.
 */
internal enum class BrbState { OFF, UNPAIRED, CHECKING, READY, UDP_BLOCKED, UNREACHABLE }

internal fun brbState(combine: Boolean, paired: Boolean, check: BrbCheck?): BrbState = when {
    !paired -> BrbState.UNPAIRED
    !combine -> BrbState.OFF
    check == null || !check.checked -> BrbState.CHECKING
    !check.reachable -> BrbState.UNREACHABLE
    check.udpBlocked -> BrbState.UDP_BLOCKED
    else -> BrbState.READY
}

/** "On · ready" instead of the server's address (the old summary showed the IP, which says nothing about health). */
internal fun combineValue(combine: Boolean, state: BrbState): String = when {
    !combine -> "Off"
    else -> when (state) {
        BrbState.UNPAIRED -> "On · not paired"
        BrbState.CHECKING, BrbState.OFF -> "On · checking"
        BrbState.READY -> "On · ready"
        BrbState.UDP_BLOCKED -> "On · UDP blocked"
        BrbState.UNREACHABLE -> "On · can't reach server"
    }
}

/**
 * Helpers don't pair ahead of time (they scan this phone's code and join while it's live), so there's no count to
 * report outside a stream: the row says whether hosting is on, and whether it can work yet.
 */
internal fun helpersValue(helpingAnother: Boolean, hostingOn: Boolean, combine: Boolean): String = when {
    helpingAnother -> "Helping another phone"
    !hostingOn -> "Off"
    !combine -> "On · needs Combine"
    else -> "On"
}

/** "1080p · portrait": the quality is a figure (mono), the shape a word. */
internal fun videoValue(q: Quality, o: StreamOrientation): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(fontFamily = JetMono, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")) { append(q.title) }
    append(" · ")
    append(o.title.lowercase())
}

/** The layers that are on, in sentence case ("Chat · alerts"). Alerts are on by default, so they're never skipped. */
internal fun overlaysValue(s: OverlaySettings, webValid: Boolean): String {
    val on = listOfNotNull(
        "chat".takeIf { s.chat },
        (if (s.alertSound) "alerts" else "silent alerts").takeIf { s.alertsOn },
        "text".takeIf { s.textOn && s.text.isNotBlank() },
        "image".takeIf { s.imageOn },
        "web".takeIf { s.browserOn && webValid },
    )
    return if (on.isEmpty()) "Off" else on.joinToString(" · ").replaceFirstChar { it.uppercase() }
}

/**
 * Whether Android will let the stream run with the screen off. Restricted (the app's own "background usage" switch)
 * wins; then exempt from battery optimisation reads as Allowed. Anything else hasn't been allowed yet.
 */
internal fun screenOffValue(restricted: Boolean, exempt: Boolean): String = when {
    restricted -> "Restricted"
    exempt -> "Allowed"
    else -> "Not allowed yet"
}

/** The server's output, in words. "Twitch · test stream", not the old "Twitch (test stream)". */
internal fun sendsToValue(st: BrbStatus): String = when (st.outputKind) {
    "twitch" -> if (st.outputTest) "Twitch · test stream" else "Twitch"
    "record" -> "Recording on the server"
    "obs" -> "Your home OBS"
    else -> st.outputHost ?: st.outputKind
}

/** Where the BRB screen viewers see in a dropout comes from: the phone's upload, or the server's built-in one. */
internal fun brbScreenValue(st: BrbStatus): String = if (st.phoneSlate) "From this phone" else "Server's default"

/** The 60 fps row's line: why it can't be chosen, or what it does. [can60] null: still checking the phone. */
internal fun fps60Help(phoneCamera: Boolean, can60: Boolean?): String = when {
    !phoneCamera -> "Phone camera only."
    can60 == null -> "Checking this phone…"
    !can60 -> "This phone can't film 60 fps at this size."
    else -> "Smoother motion, a little less detail in each frame."
}

/** A quality's figures: "6 Mb/s · 2.8 GB/h". Mb/s is the one unit everywhere. */
internal fun qualityValue(q: Quality): String = "${q.mbps} Mb/s · %.1f GB/h".format(q.gbPerHour)

/** The shape's ratio (mono) and its one help line. */
internal fun orientationValue(o: StreamOrientation): String = when (o) {
    StreamOrientation.LANDSCAPE -> "16:9"
    StreamOrientation.PORTRAIT -> "9:16"
}

internal fun orientationHelp(o: StreamOrientation): String = when (o) {
    StreamOrientation.LANDSCAPE -> "The usual shape for Twitch."
    StreamOrientation.PORTRAIT -> "Vertical video. Hold the phone upright."
}

// ---- Settings ----

@Composable
fun SettingsScreen(c: SetupController, nav: Navigator) = PageScaffold("Settings", nav::back) {
    val helping by HelperStatus.state.collectAsState()
    val brb = brbState(c.combine, c.pairing != null, c.brbCheck)
    var licences by rememberSaveable { mutableStateOf(false) }

    SectionHeader("Stream")
    ValueRow(
        "Twitch", twitchValue(c.toObs, c.serverDecides, c.useKey, c.key.isNotBlank(), c.signIn), valueIsFigure = false,
        onClick = { nav.open(Screen.TWITCH) },
    )
    val also by AlsoStreamTo.state(LocalContext.current).collectAsState()
    ValueRow(
        "Also stream to", alsoStreamValue(AlsoStreamRules.onNames(also), c.combine && c.pairing != null), valueIsFigure = false,
        onClick = { nav.open(Screen.ALSO_STREAM) },
    )
    ValueRow("Camera", cameraValue(c.cameraKind, c.pocketDevice?.second), valueIsFigure = false, onClick = { nav.open(Screen.CAMERA) })
    ValueRow("Combine connections", combineValue(c.combine, brb), valueIsFigure = false, onClick = { nav.open(Screen.CONNECTIONS) })
    ValueRow(
        "Helper phones", helpersValue(helping != HelperState.Off, c.helpersOn, c.combine), valueIsFigure = false,
        onClick = { nav.open(Screen.HELPERS) },
    )
    ValueRow("Video", videoValue(c.quality, c.orientation), valueIsFigure = false, onClick = { nav.open(Screen.QUALITY) })
    val mic by MicSettings.state(LocalContext.current).collectAsState()
    ValueRow("Microphone", micValue(mic), valueIsFigure = false, onClick = { nav.open(Screen.MIC) })
    ValueRow(
        "Overlays", overlaysValue(c.overlays, OverlayStore.validBrowserUrl(c.overlayUrl)), valueIsFigure = false,
        onClick = { nav.open(Screen.OVERLAYS) },
    )
    val cards by CardStore.state(LocalContext.current).collectAsState()
    val socials by CardStore.socialsState(LocalContext.current).collectAsState()
    ValueRow("Scene cards", cardsValue(cards, socials), valueIsFigure = false, onClick = { nav.open(Screen.CARDS) })
    val captions by CaptionSettings.state(LocalContext.current).collectAsState()
    ValueRow("Captions", captionsSettingValue(captions), valueIsFigure = false, onClick = { nav.open(Screen.CAPTIONS) })

    if (isAggressiveOem) {
        SectionHeader("Phone")
        ValueRow("Keep streaming with the screen off", rememberScreenOffValue(), valueIsFigure = false, onClick = { nav.open(Screen.SCREEN_OFF) })
    }

    SectionHeader("Labs")
    SwitchRow("DJI Pocket 3${footnoteMark(1)}", checked = c.labsDji, onCheckedChange = c::setLabs)

    Footnote(1, "DJI Pocket 3: Fullbars starts it and its stream over Bluetooth. $UNOFFICIAL")
    AboutFootnotes(onLicences = { licences = true })
    if (licences) LicencesDialog(onDismiss = { licences = false })
}

/** The screen-off state, read again on every return (the user flips it in Android's settings and comes back). */
@Composable
internal fun rememberScreenOffValue(): String {
    val ctx = LocalContext.current
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }
    return remember(resumed) {
        val restricted = runCatching { ctx.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true }.getOrDefault(false)
        val exempt = runCatching { ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true }
            .getOrDefault(false)
        screenOffValue(restricted, exempt)
    }
}

/**
 * Which build this is (testers need to say), the GPL source offer and the licence notices the APK must carry, as two
 * footnotes rather than a section: they matter, but nobody comes to Settings for them. The offer is in words rather
 * than a link, so it stays true for builds passed on by anyone.
 */
@Composable
private fun AboutFootnotes(onLicences: () -> Unit) {
    val ctx = LocalContext.current
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty() }
    Footnote(
        null,
        (if (version.isEmpty()) "Fullbars. " else "Fullbars $version. ") + "Not made by or affiliated with DJI or Twitch.",
        link = "Open-source licences", onLink = onLicences,
    )
    Footnote(
        null,
        "Fullbars is free software under the GNU General Public License, version 3: you can share and change it. " +
            "Whoever gave you this app can give you its source code too; ask them for it.",
    )
}

/** A block of the notices file: the asset is Markdown, kept to headings, bullets and paragraphs so this can show it. */
internal sealed interface NoticeBlock {
    data class Heading(val level: Int, val text: String) : NoticeBlock
    data class Para(val text: String) : NoticeBlock
}

/** Headings, "- " bullets, and paragraphs re-flowed to the screen width (licence texts are hard-wrapped at 80). */
internal fun noticeBlocks(md: String): List<NoticeBlock> {
    val out = mutableListOf<NoticeBlock>()
    val para = StringBuilder()
    fun flush() { if (para.isNotEmpty()) { out += NoticeBlock.Para(para.toString().replace("`", "").replace("**", "")); para.clear() } }
    md.lineSequence().map { it.trim() }.forEach { line ->
        when {
            line.isEmpty() -> flush()
            line.startsWith("#") -> {
                flush()
                val level = line.takeWhile { it == '#' }.length
                out += NoticeBlock.Heading(level, line.drop(level).trim())
            }
            line.startsWith("- ") -> { flush(); para.append("• ").append(line.drop(2)) }
            else -> { if (para.isNotEmpty()) para.append(' '); para.append(line) }
        }
    }
    flush()
    return out
}

/**
 * The bundled fonts' notices (engine assets, merged into the APK under licences/), as Markdown for [noticeBlocks]. The
 * OFL text's rules of dashes are dropped: they're typewriter decoration, not content. A missing file is left out.
 */
internal fun fontNotices(archivo: String?, jetBrainsMono: String?): String = buildString {
    if (archivo == null && jetBrainsMono == null) return@buildString
    append("## Fonts (SIL Open Font License 1.1)\n\n")
    fun add(name: String, text: String?) {
        if (text == null) return
        append("### ").append(name).append("\n\n")
        text.lineSequence().filterNot { it.isNotBlank() && it.trim().all { ch -> ch == '-' } }.forEach { append(it).append('\n') }
        append('\n')
    }
    add("Archivo", archivo)
    add("JetBrains Mono", jetBrainsMono)
}

/** assets/THIRD_PARTY_NOTICES.md plus the font licences, full screen. A dialog, so it needs no Screen entry. */
@Composable
private fun LicencesDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalFbColors.current
    val blocks by produceState<List<NoticeBlock>?>(null) {
        value = withContext(Dispatchers.IO) {
            fun asset(name: String) = runCatching { ctx.assets.open(name).bufferedReader().use { it.readText() } }.getOrNull()
            val md = asset("THIRD_PARTY_NOTICES.md").orEmpty()
            noticeBlocks(md + "\n\n" + fontNotices(asset("licences/OFL-Archivo.txt"), asset("licences/OFL-JetBrainsMono.txt")))
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(c.bg)) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphButton(FbIcons.Back, contentDescription = "Back", onClick = onDismiss)
            }
            val list = blocks
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                item {
                    Text(
                        "Open-source licences", style = FbType.title, color = c.text,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp).semantics { heading() },
                    )
                }
                if (list == null) item { Spinner(Modifier.padding(vertical = 16.dp)) }
                else items(list.size) { i ->
                    when (val b = list[i]) {
                        is NoticeBlock.Heading -> if (b.level > 1) Text( // the file's title is the page's
                            b.text,
                            style = if (b.level <= 2) FbType.cta else FbType.row,
                            color = c.text,
                            modifier = Modifier.padding(top = if (b.level <= 2) 24.dp else 16.dp, bottom = 4.dp).semantics { heading() },
                        )
                        is NoticeBlock.Para -> Text(b.text, style = FbType.help, color = c.text2, modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
    }
}

/** The one spinner: 12dp, a 1.5dp Bone stroke. With reduce motion it's the still dashed square instead. */
@Composable
private fun Spinner(modifier: Modifier = Modifier, size: Dp = 12.dp) {
    if (FbMotion.rememberReduced()) StateSquare(StateMark.UNCHECKED, modifier, size = size)
    else CircularProgressIndicator(modifier.size(size), color = LocalFbColors.current.text, strokeWidth = FbShape.stroke, trackColor = Color.Transparent)
}

// ---- Twitch ----

@Composable
fun TwitchPage(c: SetupController, nav: Navigator) = PageScaffold("Twitch", nav::back) {
    var confirmSignOut by remember { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }

    // 1. Where the stream goes
    SectionHeader("Where the stream goes")
    Column(Modifier.selectableGroup()) {
        // The relay is the recommended everyday path once a server is paired:
        // it keeps Twitch live through dropouts with nothing running at home. OBS is for produced streams.
        RadioRow(
            if (c.obsAvailable) "Twitch (recommended)" else "Twitch",
            supporting = if (c.obsAvailable) "Your server sends to Twitch and covers dropouts. Nothing runs at home." else null,
            selected = !c.toObs,
        ) { c.changeSendToObs(false) }
        RadioRow(
            if (c.obsAvailable) "My OBS (advanced)" else "My OBS",
            supporting = if (c.obsAvailable) "Your home OBS adds scenes and alerts. Your PC must stay on while you stream."
            else "Needs Combine connections and your server.",
            selected = c.toObs,
            enabled = c.obsAvailable,
        ) { c.changeSendToObs(true) }
    }
    if (c.obsAvailable && !c.toObs) {
        HelpText("Overlays and alerts are drawn on this phone (Settings › Overlays). Use My OBS for full OBS scenes.")
    }
    if (!c.obsAvailable) {
        FbButton("Set up Combine connections", { nav.open(Screen.CONNECTIONS) }, Modifier.padding(horizontal = 16.dp), kind = FbButtonKind.Text)
    }
    if (c.toObs) {
        CodeBlock(OBS_COMMAND)
        HelpText("Run this on the server to see the OBS settings.")
    }

    val s = c.signIn
    val signedIn = s as? SignIn.SignedIn
    val signedOut = s is SignIn.SignedOut || s is SignIn.Waiting

    // 2. How Fullbars connects (not used when the stream goes to OBS)
    if (!c.toObs) {
        SectionHeader("How Fullbars connects to Twitch")
        Column(Modifier.selectableGroup()) {
            // A build without a Twitch client ID can't sign in: offer only the stream key.
            if (c.signInAvailable) RadioRow(
                "Sign in with Twitch",
                supporting = "Recommended. Gets your stream key for you; chat and mod commands work.",
                selected = !c.useKey,
            ) { c.chooseUseKey(false) }
            // With a key and a sign-in, chat, alerts and commands follow the signed-in account: name the channel.
            RadioRow(
                "Stream key",
                supporting = if (signedIn != null) "Chat, alerts and mod commands use ${signedIn.login}'s channel."
                else "Chat and mod commands need sign-in.",
                selected = c.useKey,
            ) { c.chooseUseKey(true) }
        }
        if (c.serverDecides) {
            // In key mode only a saved key decides, signed in or not (serverDecides ignores sign-in there), so
            // the fix is the key. Only in sign-in mode does signing in change where the stream goes.
            HelpText(
                if (c.useKey) "Without a key, your server decides where the stream goes. Add one to choose Test or Live."
                else "Without sign-in or a key, your server decides where the stream goes. Sign in to choose Test or Live.",
            )
        }
    }

    // 3. Account. Shown in every mode: with a stream key or My OBS, sign-in is optional but keeps chat and commands.
    SectionHeader("Account")
    if (c.toObs && s !is SignIn.SignedIn) HelpText("Optional. Sign in to keep chat and mod commands.")
    else if (c.useKey && s !is SignIn.SignedIn) HelpText("Optional. Sign in to keep chat, alerts and mod commands.")
    when (s) {
        SignIn.Checking -> ValueRow(
            "Account", "Checking", valueIsFigure = false, state = StateMark.UNCHECKED,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        is SignIn.SignedIn -> {
            ValueRow("Signed in as", s.login, valueIsFigure = false, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            // Title and category only where they're this stream's: the signed-in channel, sign-in mode.
            c.channel?.takeIf { c.showsStreamInfo }?.let { ch ->
                ValueRow("Title", ch.title.ifBlank { "None" }, valueIsFigure = false)
                ValueRow("Category", ch.categoryName?.ifBlank { null } ?: "None", valueIsFigure = false)
            }
            FbButton("Sign out", { confirmSignOut = true }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), kind = FbButtonKind.Text)
        }
        is SignIn.Waiting -> ApproveBlock(s.code, onReopen = c::reopenSignInPage, onCancel = c::cancelSignIn)
        SignIn.SignedOut -> FbButton(
            "Continue with Twitch", c::startSignIn,
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), kind = FbButtonKind.Twitch,
        )
    }
    c.signInError?.let { InlineError(it) }

    // 4. Stream key
    if (c.useKey && !c.toObs) {
        SectionHeader("Stream key")
        SecretField(
            c.key, c::updateKey, "Stream key",
            supporting = "Find it on Twitch: Creator Dashboard › Settings › Stream.",
        )
        ExpandRow("Advanced", expanded = advanced) { advanced = !advanced }
        if (advanced) {
            PlainField(
                c.ingest, c::updateIngest, "Ingest server",
                supporting = "Leave this unless you need another Twitch server. Empty means Twitch's default.",
            )
            if (!c.ingestIsDefault) {
                FbButton("Reset to Twitch's default", c::resetIngest, Modifier.padding(horizontal = 16.dp), kind = FbButtonKind.Text)
            }
        }
    }

    SectionHeader("Read chat aloud")
    HelpText(
        "Into your headphones while you're live, never from the phone's speaker (it would go into your mic)." +
            if (signedOut) " Needs Twitch sign-in." else "",
    )
    Column(Modifier.selectableGroup()) {
        app.irlsupreme.service.ReadAloud.entries.forEach { r ->
            RadioRow(r.title, supporting = if (r == app.irlsupreme.service.ReadAloud.OFF) null else "${r.detail}.", selected = c.readAloud == r) { c.changeReadAloud(r) }
        }
    }

    ModsSection(c, signedOut)

    Footnote(
        1,
        "The mod remote switches Camera, BRB, Privacy, Starting soon, Ending and your own scenes. It's served by your " +
            "server, so it works while you're live with Combine connections. Share the link only with people you trust.",
    )

    if (confirmSignOut) {
        ConfirmDialog(
            title = "Sign out of Twitch?",
            text = "Fullbars forgets your Twitch sign-in on this phone. You can sign in again at any time.",
            confirmLabel = "Sign out",
            onConfirm = c::signOut,
            onDismiss = { confirmSignOut = false },
        )
    }
}

/** A row that shows or hides the rows under it, with its state spoken (TalkBack can't see a chevron's direction). */
/** The device-code step: the code on a sunk rM plate, spelled out for TalkBack. No card. */
@Composable
private fun ApproveBlock(code: DeviceCode, onReopen: () -> Unit, onCancel: () -> Unit) {
    val c = LocalFbColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Approve on Twitch", style = FbType.row, color = c.text, modifier = Modifier.semantics { heading() })
        Text("Your browser opened twitch.tv/activate. If it asks for a code, enter:", style = FbType.help, color = c.text2)
        Row(Modifier.fillMaxWidth().background(c.s1, FbShape.medium).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                code.userCode, style = FbType.code, color = c.text,
                // Read character by character, not as a word.
                modifier = Modifier.weight(1f).padding(vertical = 12.dp)
                    .semantics { contentDescription = code.userCode.toList().joinToString(" ") },
            )
            CopyKey(code.userCode, label = "Twitch code")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FbButton("Open Twitch again", onReopen, kind = FbButtonKind.Secondary)
            FbButton("Cancel", onCancel, kind = FbButtonKind.Text)
        }
    }
}

// ---- Camera ----

/** The "Start on" choices, in the order shown. */
internal fun facingTitle(f: Facing): String = when (f) {
    Facing.FRONT -> "Front"
    Facing.BACK -> "Back"
}

@Composable
fun CameraPage(c: SetupController, nav: Navigator) = PageScaffold("Camera", nav::back) {
    var confirmLabs by remember { mutableStateOf(false) }
    SectionHeader("Camera")
    Column(Modifier.selectableGroup()) {
        RadioRow(
            "Phone camera", supporting = "This phone's camera and mic.",
            selected = c.cameraKind == ExternalCamera.Kind.PHONE,
        ) { c.chooseCamera(ExternalCamera.Kind.PHONE) }
        // Always choosable: with Labs off, one dialog turns it on here instead of a detour through Settings.
        RadioRow(
            "DJI Pocket 3",
            supporting = if (c.labsDji) "Labs · Fullbars starts it over Bluetooth." else "Labs · unofficial. Fullbars starts it over Bluetooth.",
            selected = c.cameraKind == ExternalCamera.Kind.POCKET,
        ) { if (c.labsDji) c.chooseCamera(ExternalCamera.Kind.POCKET) else confirmLabs = true }
        RadioRow(
            "Other camera (RTMP)",
            supporting = "Any camera that streams to an RTMP address, such as DJI Mimo's custom RTMP.",
            selected = c.cameraKind == ExternalCamera.Kind.RTMP,
        ) { c.chooseCamera(ExternalCamera.Kind.RTMP) }
    }

    when (c.cameraKind) {
        ExternalCamera.Kind.PHONE -> StartOn(c)
        ExternalCamera.Kind.POCKET -> PocketSetup(c)
        ExternalCamera.Kind.RTMP -> RtmpSetup(c)
    }

    if (confirmLabs) {
        ConfirmDialog(
            title = "Use DJI Pocket 3 (Labs)?",
            text = UNOFFICIAL,
            confirmLabel = "Turn on",
            onConfirm = { c.setLabs(true); c.chooseCamera(ExternalCamera.Kind.POCKET) },
            onDismiss = { confirmLabs = false },
        )
    }
}

/**
 * Which phone camera a stream starts on. After the first real stream the streamer couldn't find how to turn to the
 * front camera with the phone alone; choosing it here means a selfie stream starts the right way round.
 */
@Composable
private fun StartOn(c: SetupController) {
    SectionHeader("Start on")
    Column(Modifier.selectableGroup()) {
        listOf(Facing.FRONT, Facing.BACK).forEach { f ->
            RadioRow(facingTitle(f), selected = c.startFacing == f) { c.chooseStartFacing(f) }
        }
    }
    HelpText("The camera a stream starts with. You can still flip it while you're live.")
}

private fun signalWords(rssi: Int) = when {
    rssi >= -60 -> "Strong signal"
    rssi >= -75 -> "Good signal"
    else -> "Weak signal"
}

/**
 * One numbered step: its number in mono, its name, where it stands (DONE / NOW / NEXT, or nothing for the go-live
 * list, which only runs once live) and one line on what to do. Read by TalkBack as one heading, so the steps can be
 * walked with heading navigation and the controls under each follow it.
 */
@Composable
private fun StepRow(number: Int, total: Int, title: String, status: StepStatus?, detail: String?) {
    val colors = LocalFbColors.current
    val later = status == StepStatus.NEXT
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
            .clearAndSetSemantics {
                heading()
                contentDescription = "Step $number of $total: $title" + (status?.let { ", ${it.word.lowercase()}" } ?: "") +
                    (detail?.let { ". $it" } ?: "")
            },
        verticalAlignment = Alignment.Top,
    ) {
        Text("$number", style = FbType.value, color = if (later) colors.text2 else colors.text, modifier = Modifier.width(28.dp).padding(top = 1.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = FbType.row, color = if (later) colors.text2 else colors.text)
            if (detail != null) Text(detail, style = FbType.help, color = colors.text2, modifier = Modifier.padding(top = 2.dp))
        }
        if (status != null) {
            Row(Modifier.padding(start = 12.dp, top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                if (status == StepStatus.DONE) StateSquare(StateMark.VERIFIED, Modifier.padding(end = 6.dp))
                Text(status.word.uppercase(), style = FbType.label, color = if (later) colors.text2 else colors.text)
            }
        }
    }
}

/**
 * The Pocket 3, set up in the order it happens: find it, give it a hotspot or Wi‑Fi, go live; then the five steps
 * Fullbars takes once live, in the live screen's words ([PocketStep]). Each step's controls sit right under it.
 */
@Composable
private fun PocketSetup(c: SetupController) {
    val chosen = c.pocketDevice
    val net = c.cameraNet
    val steps = PocketSteps.setup(chosen != null, c.scanning, net, c.camSsid.isNotBlank(), c.hotspotUp)
    @Composable fun step(i: Int) = StepRow(i + 1, steps.size, steps[i].title, steps[i].status, steps[i].detail)

    SectionHeader("Set up your Pocket 3")
    step(0)
    if (chosen != null) {
        ValueRow("Pocket 3", chosen.second, valueIsFigure = false, sub = "…${chosen.first.takeLast(5)}", subIsFigure = true)
        FbButton("Find another", c::findPocket, Modifier.padding(horizontal = 16.dp), kind = FbButtonKind.Text, enabled = !c.scanning)
    } else {
        FbButton(
            if (c.scanning) "Looking for your Pocket 3…" else "Find my Pocket 3", c::findPocket,
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), enabled = !c.scanning,
        )
    }
    if (c.scanning && chosen != null) {
        ValueRow(
            "Looking for your Pocket 3…", "", valueIsFigure = false, state = StateMark.UNCHECKED,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    c.found.forEach { cam: FoundCamera ->
        NavRow(cam.name, supporting = signalWords(cam.rssi), trailing = null) { c.choosePocket(cam) }
    }
    c.scanError?.let { InlineError(it, actionLabel = "Try again", onAction = c::findPocket) }

    step(1)
    Column(Modifier.selectableGroup()) {
        // "(recommended)" moves to Fullbars hotspot on Android 16+ only once a Pocket is confirmed to join it on
        // 5 GHz, the same gate as the default change in SetupController.
        RadioRow(
            "Your hotspot (recommended)",
            supporting = "This phone's own hotspot. Turn it on (5 GHz if offered) and type its name and password below.",
            selected = net == ExternalCamera.CameraNet.PHONE_HOTSPOT,
        ) { c.chooseCameraNet(ExternalCamera.CameraNet.PHONE_HOTSPOT) }
        RadioRow(
            "Fullbars hotspot",
            supporting = if (android.os.Build.VERSION.SDK_INT >= 36) "Nothing to type. Fullbars makes a 5 GHz hotspot just for the Pocket 3."
            else "Nothing to type, but Android may put it on 2.4 GHz, which can be too slow for the Pocket 3.",
            selected = net == ExternalCamera.CameraNet.PRIVATE,
        ) { c.chooseCameraNet(ExternalCamera.CameraNet.PRIVATE) }
        RadioRow(
            "Other Wi‑Fi",
            supporting = "A travel router or home Wi‑Fi that this phone and the Pocket 3 both join.",
            selected = net == ExternalCamera.CameraNet.WIFI,
        ) { c.chooseCameraNet(ExternalCamera.CameraNet.WIFI) }
    }
    if (net != ExternalCamera.CameraNet.PRIVATE) {
        val what = if (net == ExternalCamera.CameraNet.WIFI) "Wi‑Fi" else "Hotspot"
        PlainField(c.camSsid, c::updateCamSsid, "$what name")
        SecretField(c.camPassword, c::updateCamPassword, "$what password")
    }
    // The one check this page can make before going live: the hotspot the Pocket 3 will join is on.
    if (net == ExternalCamera.CameraNet.PHONE_HOTSPOT && !c.hotspotUp) {
        ValueRow(
            "Your hotspot", "Off", valueIsFigure = false, state = StateMark.FAILED,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        FbButton("Open hotspot settings", c::openHotspotSettings, Modifier.padding(horizontal = 16.dp), kind = FbButtonKind.Text)
    }

    step(2)

    SectionHeader("When you go live")
    HelpText("Fullbars does these in order. The live screen shows each one as it happens.${footnoteMark(1)}")
    PocketStep.entries.forEach { s -> StepRow(s.number, PocketStep.entries.size, s.title(net), status = null, detail = s.guide(net)) }
    Footnote(1, "For sound, pair your DJI Mic with the Pocket 3. $UNOFFICIAL")
}

@Composable
private fun RtmpSetup(c: SetupController) {
    // Recomputed each time this opens (the network may have changed since), not on every recomposition.
    val urls = remember { c.rtmpUrls }
    SectionHeader("Camera address")
    if (urls.isEmpty()) {
        HelpText("Connect the phone to Wi‑Fi (or turn on its hotspot) so a camera can reach it.")
    } else {
        HelpText("Point your camera at:")
        urls.forEach { CodeBlock(it, sensitive = true) } // the random path is what keeps strangers off it
    }
    HelpText("Its sound replaces the phone mic. If it drops out, the phone camera and mic take over.")
}

// ---- Combine connections ----

@Composable
fun ConnectionsPage(c: SetupController, nav: Navigator) = PageScaffold("Combine connections", nav::back) {
    var confirmForget by remember { mutableStateOf(false) }
    var showPaste by rememberSaveable { mutableStateOf(false) }
    val p = c.pairing
    val check = c.brbCheck
    val state = brbState(c.combine, p != null, check)

    // A pairing that lands while this page is open is reported here, in the Status row, not in a passing snackbar.
    // The saved pairing arriving at start-up (secrets load off the main thread) isn't a new pairing, so only a change
    // after the first load counts.
    var lastPairing by remember { mutableStateOf(p) }
    var loadedBefore by remember { mutableStateOf(c.loaded) }
    var justPaired by remember { mutableStateOf(false) }
    LaunchedEffect(p, c.loaded) {
        if (p != null && p != lastPairing && loadedBefore) justPaired = true
        if (p == null) justPaired = false
        lastPairing = p
        loadedBefore = c.loaded
    }

    // Master-switch rule: the label says what the switch does, never the page title again.
    SwitchRow(
        "Use Wi‑Fi and mobile data together",
        supporting = "Needs your own server.${footnoteMark(1)}",
        checked = c.combine,
        onCheckedChange = c::changeCombine,
    )

    if (c.combine) RouteReadout(c, state, check)

    if (c.combine) {
        SectionHeader("Delay")
        Column(Modifier.selectableGroup()) {
            StreamDelay.entries.forEach { d ->
                RadioRow(d.title, supporting = d.detail, selected = c.delay == d) { c.chooseDelay(d) }
            }
        }
    }

    SectionHeader("Your server", trailing = rememberCheckedStamp(check)?.let { "checked $it" })
    if (p != null) {
        BrbStatusRow(state, failure = c.brbStatus, port = p.port, justPaired = justPaired)
        ValueRow(
            "Address", p.host, valueIsFigure = true,
            sub = p.name?.takeIf { it.isNotBlank() && it != p.host }, subIsFigure = false,
        )
        // Only what the server told us: no "Sends to" until it has answered.
        c.brbServerStatus?.takeIf { state == BrbState.READY || state == BrbState.UDP_BLOCKED }?.let { st ->
            ValueRow("Sends to", sendsToValue(st), valueIsFigure = false)
            ValueRow("Signal lost card", brbScreenValue(st), valueIsFigure = false)
        }
        NavRow("Pair a different server", onClick = c::scanPairingCode)
        // Set apart by space and a rule, not by colour: it already confirms in a dialog, and red is kept for real failures.
        Spacer(Modifier.height(24.dp))
        Hairline()
        NavRow("Forget server", trailing = null) { confirmForget = true }
    } else {
        BrbStatusRow(state, failure = null, port = 0, justPaired = false)
        // The only way in without a server: say what it is and what it costs (footnote) before asking for a command.
        HelpText("Set one up once on a small rented server.${footnoteMark(2)}")
        HelpText("1 · On your server, run")
        CodeBlock(PAIR_COMMAND)
        HelpText("2 · Scan the code it shows")
        FbButton("Scan pairing code", c::scanPairingCode, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        if (!showPaste) {
            FbButton("Paste a link instead", { showPaste = true }, Modifier.padding(horizontal = 16.dp), kind = FbButtonKind.Text)
        } else {
            PlainField(c.pasteLink, c::updatePasteLink, "Pairing link", supporting = "It starts with fullbars://")
        }
    }

    Footnote(1, "If both connections drop, viewers see the Signal lost card instead of a frozen stream.")
    if (p == null) {
        Footnote(
            2,
            "About €4–8 a month, or Oracle's free tier: a Linux server with Docker and the Fullbars BRB server. Its " +
                "setup guide (BRB-SERVER.md) comes with Fullbars' source code. Without one, Fullbars streams straight to Twitch.",
        )
    }

    if (confirmForget) {
        ConfirmDialog(
            title = "Forget this server?",
            text = "You'll need to scan a new code from the server to pair again.",
            confirmLabel = "Forget",
            onConfirm = c::forgetServer,
            onDismiss = { confirmForget = false },
        )
    }
}

/**
 * The clock time of the last server check seen on this page, for the section stamp. Each check carries a fresh round
 * trip, so a new [check] value means a new answer. The value already there on arrival isn't stamped (its time isn't
 * known): the stamp appears with the next check, within 30 s.
 */
@Composable
private fun rememberCheckedStamp(check: BrbCheck?): String? {
    val ctx = LocalContext.current
    var seen by remember { mutableStateOf(check) }
    var at by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(check) {
        if (check == null || !check.checked) at = null
        else if (check != seen) at = System.currentTimeMillis()
        seen = check
    }
    return at?.let { android.text.format.DateFormat.getTimeFormat(ctx).format(Date(it)) }
}

/** What the Status row says for [state]: the word and its square. */
internal fun brbStatusWord(state: BrbState, port: Int, justPaired: Boolean): Pair<String, StateMark> = when (state) {
    BrbState.OFF -> "Not in use" to StateMark.NONE
    BrbState.UNPAIRED -> "Not paired" to StateMark.UNCHECKED
    BrbState.CHECKING -> (if (justPaired) "Paired · checking" else "Checking") to StateMark.UNCHECKED
    BrbState.READY -> (if (justPaired) "Paired · ready" else "Ready") to StateMark.VERIFIED
    BrbState.UDP_BLOCKED -> "UDP $port blocked" to StateMark.FAILED
    BrbState.UNREACHABLE -> "Can't reach it" to StateMark.FAILED
}

/**
 * Status: the square sits before the value ("■ Ready"), a spinner while the first check is out. Red only for a check
 * that came back wrong, with the server's own reason under it. A polite live region, and the value swaps in a Tick, so
 * a pairing or a re-check reads in place.
 */
@Composable
private fun BrbStatusRow(state: BrbState, failure: String?, port: Int, justPaired: Boolean) {
    val c = LocalFbColors.current
    val reduced = FbMotion.rememberReduced()
    val (word, mark) = brbStatusWord(state, port, justPaired)
    val detail = when (state) {
        BrbState.UDP_BLOCKED -> "Allow UDP $port in your server's firewall, or streams won't connect."
        BrbState.UNREACHABLE -> failure?.takeUnless { it.startsWith("Checking") }
        else -> null
    }
    Row(
        Modifier.fillMaxWidth().bottomRule(c.rule)
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                mark.spoken?.let { stateDescription = it }
            }
            .heightIn(min = FbSpace.rowStatic)
            .padding(horizontal = FbSpace.gutter, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text("Status", style = FbType.row, color = c.text)
        Spacer(Modifier.width(16.dp).weight(1f))
        AnimatedContent(
            targetState = Triple(word, mark, state == BrbState.CHECKING),
            transitionSpec = {
                val swap = if (reduced) EnterTransition.None togetherWith ExitTransition.None
                else fadeIn(tween(FbMotion.TICK_MS, easing = FbMotion.TickEasing)) togetherWith
                    fadeOut(tween(FbMotion.TICK_MS, easing = FbMotion.TickEasing))
                swap using SizeTransform(clip = false) { _, _ -> snap() }
            },
            contentAlignment = Alignment.TopEnd,
            label = "status",
            modifier = Modifier.weight(3f, fill = false),
        ) { (w, m, spinning) ->
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        spinning -> Spinner(Modifier.padding(end = 8.dp))
                        m != StateMark.NONE -> StateSquare(m, Modifier.padding(end = 8.dp))
                    }
                    Text(w, style = FbType.rowValue, color = if (m == StateMark.FAILED) c.error else c.text)
                }
                if (detail != null) {
                    Text(detail, style = FbType.help, color = c.text2, textAlign = TextAlign.End, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

// ---- The PATH readout ----

/** One link of the route: a name, whether Android has it up now, and Android's upload estimate when it gives one. */
internal data class RouteLink(val name: String, val up: Boolean, val upKbps: Int? = null)

/**
 * The route the stream takes with Combine on: phone → one track per link → BRB server → where it ends. Values appear
 * only when measured; a stop's shape is the StateMark grammar (filled = verified, dashed = not yet, crossed = failed).
 */
internal data class Route(
    val tracks: List<RouteLink>,
    /** Links beyond [RouteRules.MAX_TRACKS], drawn as "+N". */
    val extra: Int,
    val linkCount: Int,
    val linksUp: Int,
    val server: BrbState,
    val serverMs: Long?,
    val end: String,
    val endValue: String?,
    val endMark: StateMark,
)

internal object RouteRules {
    /** More tracks than this stop reading as a route; the rest collapse into "+N". */
    const val MAX_TRACKS = 4

    fun route(
        links: List<RouteLink>, server: BrbState, serverMs: Long?, end: String, endValue: String?, endMark: StateMark,
    ): Route = Route(
        tracks = links.take(MAX_TRACKS), extra = (links.size - MAX_TRACKS).coerceAtLeast(0),
        linkCount = links.size, linksUp = links.count { it.up },
        server = server, serverMs = serverMs, end = end, endValue = endValue, endMark = endMark,
    )

    /** "~18 Mb/s up", or null without an estimate (nothing is shown rather than a guess). */
    fun speed(kbps: Int?): String? {
        val k = kbps?.takeIf { it > 0 } ?: return null
        return if (k < 1000) "~%.1f Mb/s up".format(k / 1000.0) else "~${(k + 500) / 1000} Mb/s up"
    }

    fun serverValue(state: BrbState, ms: Long?): String? = when (state) {
        BrbState.READY -> "Ready" + (ms?.let { " · $it ms" } ?: "")
        BrbState.CHECKING -> "Checking"
        BrbState.UDP_BLOCKED -> "UDP blocked"
        BrbState.UNREACHABLE -> "Can't reach"
        BrbState.UNPAIRED -> "Not paired"
        BrbState.OFF -> null
    }

    fun serverMark(state: BrbState): StateMark = when (state) {
        BrbState.READY -> StateMark.VERIFIED
        BrbState.UDP_BLOCKED, BrbState.UNREACHABLE -> StateMark.FAILED
        else -> StateMark.UNCHECKED
    }

    /** The one merged TalkBack node: "Path: phone, 2 links, BRB server ready, Twitch, test." */
    fun describe(r: Route): String {
        val links = when {
            r.linkCount == 1 && r.linksUp == 1 -> "1 link"
            r.linksUp == r.linkCount -> "${r.linkCount} links"
            else -> "${r.linksUp} of ${r.linkCount} links up"
        }
        val server = when (r.server) {
            BrbState.READY -> "your server ready"
            BrbState.CHECKING, BrbState.OFF -> "your server not checked yet"
            BrbState.UDP_BLOCKED -> "your server's UDP port blocked"
            BrbState.UNREACHABLE -> "your server can't be reached"
            BrbState.UNPAIRED -> "your server not paired"
        }
        return "Path: phone, $links, $server, ${r.end}" + (r.endValue?.let { ", $it" } ?: "") + "."
    }
}

/** Where the route ends, in the same words as the rest of the page, and whether that end is set up. */
private fun routeEnd(c: SetupController): Triple<String, String?, StateMark> {
    val st = c.brbServerStatus
    val signedInHere = (c.signIn is SignIn.SignedIn && !c.useKey) || (c.useKey && c.key.isNotBlank())
    return when {
        c.sendsToObs -> Triple("My OBS", null, StateMark.VERIFIED)
        c.serverDecides -> when (st?.outputKind) {
            null -> Triple("Set on your server", null, StateMark.UNCHECKED)
            "twitch" -> Triple("Twitch", if (st.outputTest) "test" else "live", StateMark.VERIFIED)
            "record" -> Triple("Recording only", null, StateMark.UNCHECKED)
            "obs" -> Triple("My OBS", null, StateMark.VERIFIED)
            else -> Triple(st.outputHost ?: st.outputKind, null, StateMark.VERIFIED)
        }
        else -> Triple("Twitch", if (c.testMode) "test" else "live", if (signedInHere) StateMark.VERIFIED else StateMark.UNCHECKED)
    }
}

/**
 * Wi‑Fi and mobile data as Android sees them now, listened to (never requested: asking for mobile data would bring it
 * up and spend it just to draw a diagram). A link that isn't up is still drawn, dashed, because Combine will use it.
 */
@Composable
private fun rememberLinks(): List<RouteLink> {
    val ctx = LocalContext.current
    val nets = remember { mutableStateMapOf<Network, Pair<Int, Int?>>() }
    DisposableEffect(ctx) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val main = Handler(Looper.getMainLooper())
        val callbacks = listOf(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR).map { t ->
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    nets[network] = t to caps.linkUpstreamBandwidthKbps.takeIf { it > 0 }
                }
                override fun onLost(network: Network) { nets.remove(network) }
            }.also { cb ->
                val req = NetworkRequest.Builder().addTransportType(t).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
                runCatching { cm?.registerNetworkCallback(req, cb, main) }
            }
        }
        onDispose { callbacks.forEach { runCatching { cm?.unregisterNetworkCallback(it) } } }
    }
    val carrier = remember {
        runCatching { ctx.getSystemService(TelephonyManager::class.java)?.networkOperatorName }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "Mobile data"
    }
    fun link(name: String, transport: Int): RouteLink {
        val up = nets.values.filter { it.first == transport }
        return RouteLink(name, up.isNotEmpty(), up.mapNotNull { it.second }.maxOrNull())
    }
    return listOf(link("Wi‑Fi", NetworkCapabilities.TRANSPORT_WIFI), link(carrier, NetworkCapabilities.TRANSPORT_CELLULAR))
}

private val RouteX = 24.dp
private val StopSize = 12.dp
private val TrackStopSize = 8.dp
private val TrackX = 44.dp

@Composable
private fun RouteReadout(c: SetupController, state: BrbState, check: BrbCheck?) {
    val links = rememberLinks().let { l ->
        // Helpers join only once live, so their track is dashed until then; it's drawn so the split is the real one.
        if (c.helpersOn) l + RouteLink("Helper phones", up = false) else l
    }
    val (end, endValue, endMark) = routeEnd(c)
    val route = RouteRules.route(links, state, check?.ms, end, endValue, endMark)
    SectionHeader("Path")
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .clearAndSetSemantics { contentDescription = RouteRules.describe(route) },
    ) {
        RouteStopRow("Phone", null, valueIsFigure = false, StateMark.VERIFIED, top = false, bottom = true)
        route.tracks.forEach { t -> RouteTrackRow(t.name, RouteRules.speed(t.upKbps), t.up) }
        if (route.extra > 0) RouteTrackRow("+${route.extra}", null, up = false, more = true)
        RouteStopRow("Your server", RouteRules.serverValue(state, check?.ms), valueIsFigure = false, RouteRules.serverMark(state), top = true, bottom = true)
        RouteStopRow(route.end, route.endValue, valueIsFigure = false, route.endMark, top = true, bottom = false)
    }
}

/** A StateMark-shaped stop centred on [center]. Not-yet and failed stops are hollow, so the line is cleared behind them. */
private fun DrawScope.drawStop(mark: StateMark, center: Offset, side: Float, ink: Color, dim: Color, bg: Color, error: Color) {
    val tl = Offset(center.x - side / 2, center.y - side / 2)
    val sz = Size(side, side)
    when (mark) {
        StateMark.VERIFIED -> drawRect(ink, tl, sz)
        StateMark.UNCHECKED -> {
            val w = 1.dp.toPx()
            drawRect(bg, tl, sz)
            drawRect(
                dim, Offset(tl.x + w / 2, tl.y + w / 2), Size(side - w, side - w),
                style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 1.5.dp.toPx()))),
            )
        }
        StateMark.FAILED -> {
            val w = FbShape.stroke.toPx()
            drawRect(bg, tl, sz)
            drawRect(error, Offset(tl.x + w / 2, tl.y + w / 2), Size(side - w, side - w), style = Stroke(w))
            drawLine(error, tl, Offset(tl.x + side, tl.y + side), w)
            drawLine(error, Offset(tl.x + side, tl.y), Offset(tl.x, tl.y + side), w)
        }
        StateMark.OPTIONAL, StateMark.NONE -> Unit
    }
}

@Composable
private fun RouteStopRow(name: String, value: String?, valueIsFigure: Boolean, mark: StateMark, top: Boolean, bottom: Boolean) {
    val c = LocalFbColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(48.dp).fillMaxHeight()) {
            val x = RouteX.toPx()
            val cy = size.height / 2
            val w = FbShape.stroke.toPx()
            if (top) drawLine(c.text, Offset(x, 0f), Offset(x, cy), w)
            if (bottom) drawLine(c.text, Offset(x, cy), Offset(x, size.height), w)
            drawStop(mark, Offset(x, cy), StopSize.toPx(), c.text, c.text2, c.bg, c.error)
        }
        Text(name, style = FbType.row, color = c.text, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        if (value != null) {
            Text(
                value, style = if (valueIsFigure) FbType.value else FbType.rowValue,
                color = if (mark == StateMark.FAILED) c.error else c.text2, textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = 200.dp).padding(start = 12.dp, end = 16.dp),
            )
        }
    }
}

/** A link: the trunk runs on, a short branch leaves it for the link's own stop. A link that isn't up is dashed. */
@Composable
private fun RouteTrackRow(name: String, value: String?, up: Boolean, more: Boolean = false) {
    val c = LocalFbColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(56.dp).fillMaxHeight()) {
            val x = RouteX.toPx()
            val cy = size.height / 2
            val w = FbShape.stroke.toPx()
            drawLine(c.text, Offset(x, 0f), Offset(x, size.height), w)
            if (more) return@Canvas
            val stopX = TrackX.toPx()
            val dash = if (up) null else PathEffect.dashPathEffect(floatArrayOf(FbShape.dashOff.toPx(), 2.dp.toPx()))
            drawLine(if (up) c.text else c.text2, Offset(x, cy), Offset(stopX - TrackStopSize.toPx() / 2, cy), w, pathEffect = dash)
            drawStop(if (up) StateMark.VERIFIED else StateMark.UNCHECKED, Offset(stopX, cy), TrackStopSize.toPx(), c.text, c.text2, c.bg, c.error)
        }
        Text(
            name, style = if (more) FbType.value else FbType.rowValue, color = if (up) c.text else c.text2,
            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
        )
        if (value != null) {
            Text(value, style = FbType.value, color = c.text2, modifier = Modifier.padding(start = 12.dp, end = 16.dp))
        }
    }
}

/** What mods can do for you, in one place on the Twitch page: chat commands, then the mod remote. */
@Composable
private fun ModsSection(c: SetupController, signedOut: Boolean) {
    val colors = LocalFbColors.current
    SectionHeader("Mod commands")
    HelpText("Your mods type these in your chat while you're live. Only mods and you can use them." + if (signedOut) " Needs Twitch sign-in." else "")
    ChatCommandParser.help(CommandPolicy()).forEach { h ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(h.usage, style = FbType.value, color = colors.text, modifier = Modifier.widthIn(min = 132.dp).padding(end = 12.dp))
            Text(h.meaning, style = FbType.help, color = colors.text2)
        }
    }
    ModRemoteSection(c)
}

/** The mod remote: a link for your mods, served by your BRB server, so it needs one paired. */
@Composable
private fun ModRemoteSection(c: SetupController) {
    val ctx = LocalContext.current
    var confirmNew by remember { mutableStateOf(false) }
    SectionHeader("Mod remote")
    HelpText("A page your mods open in their browser to switch your scenes.${footnoteMark(1)}")
    val link = c.remoteLink
    if (c.pairing == null) {
        FbButton("Get the link", {}, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), kind = FbButtonKind.Secondary, enabled = false)
        HelpText("Needs your server: pair it in Settings › Combine connections.")
    } else if (link == null) {
        FbButton("Get the link", c::loadRemoteLink, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), kind = FbButtonKind.Secondary)
    } else {
        CodeBlock(link, sensitive = true)
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            FbButton("Share", {
                // Mods' first sight is a browser warning; the shared text tells them it's expected and how to get past.
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_TEXT, "$CERT_HINT\n$link")
                runCatching { ctx.startActivity(android.content.Intent.createChooser(send, "Send the mod remote")) }
            }, kind = FbButtonKind.Text)
            FbButton("Make a new link", { confirmNew = true }, kind = FbButtonKind.Text)
        }
        HelpText("Their browser warns the connection isn't private. It's still encrypted: they tap Advanced, then continue.")
    }
    c.remoteError?.let { HelpText(it) }
    if (confirmNew) {
        ConfirmDialog(
            title = "Make a new link?",
            text = "The old link stops working. Your mods will need the new one.",
            confirmLabel = "Make new link",
            onConfirm = { c.resetRemoteLink(); confirmNew = false },
            onDismiss = { confirmNew = false },
        )
    }
}

// ---- Video ----

@Composable
fun QualityPage(c: SetupController, nav: Navigator) = PageScaffold("Video", nav::back) {
    SectionHeader("Shape", top = 8.dp)
    Column(Modifier.selectableGroup()) {
        StreamOrientation.entries.forEach { o ->
            RadioRow(o.title, supporting = orientationHelp(o), value = orientationValue(o), selected = c.orientation == o) { c.chooseOrientation(o) }
        }
    }
    SectionHeader("Quality")
    Column(Modifier.selectableGroup()) {
        Quality.entries.forEach { q ->
            RadioRow(q.title, supporting = "${q.detail}.", value = qualityValue(q), selected = c.quality == q) { c.chooseQuality(q) }
        }
    }
    SectionHeader("Frame rate")
    LaunchedEffect(c.quality, c.orientation, c.startFacing, c.cameraKind) { c.checkFps() }
    val phone = c.cameraKind == ExternalCamera.Kind.PHONE
    val can60 = c.can60 == true
    Column(Modifier.selectableGroup()) {
        RadioRow("30 fps", supporting = "Steadiest on a weak signal.", selected = !(c.fps == 60 && can60)) { c.chooseFps(30) }
        RadioRow("60 fps", supporting = fps60Help(phone, c.can60), selected = c.fps == 60 && can60, enabled = can60) { c.chooseFps(60) }
    }

    SectionHeader("When the signal is too weak")
    SwitchRow(
        "Show the Weak signal card",
        supporting = "Viewers see the card and still hear you.",
        checked = c.weakSignalScreen,
        onCheckedChange = c::changeWeakSignalScreen,
    )
}

// ---- Screen off (OnePlus, Oppo, Realme) ----

@Composable
fun ScreenOffPage(c: SetupController, nav: Navigator) = PageScaffold("Keep streaming with the screen off", nav::back) {
    ValueRow("Background activity", rememberScreenOffValue(), valueIsFigure = false, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    HelpText(
        "Your phone stops background apps about 5 minutes after the screen turns off. " +
            "Open settings › Battery usage › turn on “Allow background activity”.",
        modifier = Modifier.padding(top = 8.dp),
    )
    FbButton(
        "Open settings", c::openAppSettings, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), kind = FbButtonKind.Secondary,
        trailing = {
            Icon(FbIcons.External, contentDescription = null, tint = LocalFbColors.current.text, modifier = Modifier.size(20.dp))
        },
    )
}
