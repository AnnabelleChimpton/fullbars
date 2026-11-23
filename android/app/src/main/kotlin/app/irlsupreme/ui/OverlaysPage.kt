package app.irlsupreme.ui

import androidx.compose.foundation.layout.Row
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import app.irlsupreme.engine.video.BrandDraw
import app.irlsupreme.engine.video.SlateAudio
import app.irlsupreme.engine.video.Slates
import app.irlsupreme.engine.video.StreamPalette
import app.irlsupreme.service.AlertCard
import app.irlsupreme.service.AlertKind
import app.irlsupreme.service.AlertOptions
import app.irlsupreme.service.AlertOptionsStore
import app.irlsupreme.service.AlertPlace
import app.irlsupreme.service.AlertSize
import app.irlsupreme.service.AlertStyles
import app.irlsupreme.service.AlertText
import app.irlsupreme.service.Corner
import app.irlsupreme.service.ImageSize
import app.irlsupreme.service.OverlayComposer
import app.irlsupreme.service.OverlayLayout
import app.irlsupreme.service.OverlaySettings
import app.irlsupreme.service.OverlayStore
import app.irlsupreme.service.SampleAlert
import app.irlsupreme.twitch.TwitchAlert
import app.irlsupreme.ui.theme.FbShape
import app.irlsupreme.ui.theme.FbSpace
import app.irlsupreme.ui.theme.FbType
import app.irlsupreme.ui.theme.LocalFbColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Settings › Overlays: chat, a line of text and an image, drawn into the stream on the phone. */
@Composable
fun OverlaysPage(c: SetupController, nav: Navigator) = PageScaffold("Overlays", nav::back) {
    val s = c.overlays
    fun set(n: OverlaySettings) = c.updateOverlays(n)
    // Chat and Twitch alerts come from the signed-in account, with a stream key too, so say so only when signed out.
    val signedOut = c.signIn is SignIn.SignedOut || c.signIn is SignIn.Waiting

    HelpText("Drawn into your stream on this phone.")
    OverlayPreview(c)

    SectionHeader("On your stream")
    SwitchRow(
        "Show chat",
        supporting = if (signedOut) "Needs Twitch sign-in." else "Your newest chat lines, a minute each.",
        checked = s.chat,
        onCheckedChange = { set(s.copy(chat = it)) },
    )
    if (s.chat) {
        ChoiceLabel("Corner")
        KeyChoice("Chat corner", Corner.entries, s.chatCorner, { it.title }) { set(s.copy(chatCorner = it)) }
    }
    SwitchRow("Show a line of text", supporting = "Your socials or today's plan.", checked = s.textOn, onCheckedChange = { set(s.copy(textOn = it)) })
    if (s.textOn) {
        PlainField(s.text, { set(s.copy(text = it.take(80))) }, "Text")
        ChoiceLabel("Corner")
        KeyChoice("Text corner", Corner.entries, s.textCorner, { it.title }) { set(s.copy(textCorner = it)) }
    }
    if (s.imageOn) {
        ValueRow("Image", "On", valueIsFigure = false)
        ChoiceLabel("Corner")
        KeyChoice("Image corner", Corner.entries, s.imageCorner, { it.title }) { set(s.copy(imageCorner = it)) }
        ChoiceLabel("Size")
        KeyChoice("Image size", ImageSize.entries, s.imageSize, { it.title }) { set(s.copy(imageSize = it)) }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FbButton("Change image", c::pickOverlayImage, kind = FbButtonKind.Text)
            FbButton("Remove", c::removeOverlayImage, kind = FbButtonKind.Text)
        }
    } else {
        FbButton("Add an image", c::pickOverlayImage, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), kind = FbButtonKind.Secondary)
        HelpText("A logo or a sponsor's mark. Transparent PNGs look best.")
    }
    // What happens on the cards belongs with the layers it affects, at the end of their group.
    SwitchRow(
        "Also show on BRB, Starting soon and Ending",
        supporting = "Never on Privacy or the Weak signal card.",
        checked = s.onCards,
        onCheckedChange = { set(s.copy(onCards = it)) },
    )

    SectionHeader("Alerts")
    SwitchRow(
        "Show alerts",
        supporting = when {
            c.followAlertsNeedSignIn -> "Follows need you to sign in to Twitch again once."
            signedOut -> "Twitch alerts need Twitch sign-in; tips don't."
            else -> "Follows, subs, raids, cheers and tips, a few seconds each."
        },
        checked = s.alertsOn,
        onCheckedChange = { set(s.copy(alertsOn = it)) },
    )
    if (s.alertsOn) {
        SwitchRow(
            "Alert sounds",
            supporting = "Viewers hear each kind's sound; your mic doesn't pick it up.",
            checked = s.alertSound,
            onCheckedChange = { set(s.copy(alertSound = it)) },
        )
        val alertCtx = androidx.compose.ui.platform.LocalContext.current
        val styles by AlertStyles.state(alertCtx).collectAsState()
        val options by AlertOptionsStore.state(alertCtx).collectAsState()
        val custom = AlertKind.entries.count { k -> styles[k]?.isCustom == true || options.of(k).isCustom }
        ValueRow(
            "Customize alerts",
            when {
                custom > 0 -> "$custom custom"
                options.place != AlertPlace.AUTO || options.size != AlertSize.NORMAL -> "Custom"
                else -> "Default"
            },
            valueIsFigure = false,
            onClick = { nav.open(Screen.ALERTS) },
        )
    }

    val ttsPrefs by app.irlsupreme.service.TtsSettings.state(androidx.compose.ui.platform.LocalContext.current).collectAsState()
    val alertOpts by AlertOptionsStore.state(androidx.compose.ui.platform.LocalContext.current).collectAsState()
    val saidKinds = AlertKind.entries.count { alertOpts.of(it).say != app.irlsupreme.service.AlertSay.OFF }
    ValueRow("Viewer TTS", app.irlsupreme.service.ttsValue(ttsPrefs, saidKinds), valueIsFigure = false, onClick = { nav.open(Screen.TTS) })

    SectionHeader("From other services")
    ValueRow(
        "StreamElements or Streamlabs",
        servicesValue(c.tipsSeJwt.isNotBlank() || c.tipsSlToken.isNotBlank(), s.browserOn),
        valueIsFigure = false,
        onClick = { nav.open(Screen.OVERLAY_SERVICES) },
    )
    // Tips ride on the alerts switch: said here too, where the switch is.
    if ((c.tipsSeJwt.isNotBlank() || c.tipsSlToken.isNotBlank()) && !s.alertsOn) {
        InlineError("Show alerts is off, so tips won't show.", actionLabel = "Turn on", onAction = { set(s.copy(alertsOn = true)) })
    }
}

/** The Overlays row for StreamElements and Streamlabs: what's in use from them. */
internal fun servicesValue(tips: Boolean, webOverlay: Boolean): String = when {
    tips && webOverlay -> "Tips · Web overlay"
    tips -> "Tips"
    webOverlay -> "Web overlay"
    else -> "Off"
}

/**
 * Settings › Overlays › StreamElements or Streamlabs: tips as alerts (a token from your account) and a web overlay (the
 * browser-source link you'd give OBS). Both warnings about doubled alerts stay here, beside the fields they're about.
 */
@Composable
fun OverlayServicesPage(c: SetupController, nav: Navigator) = PageScaffold("StreamElements or Streamlabs", nav::back) {
    val s = c.overlays
    fun set(n: OverlaySettings) = c.updateOverlays(n)
    SectionHeader("Tips")
    HelpText("Tips show as alerts too, with the name and amount (not the message).")
    val tipTokenSet = c.tipsSeJwt.isNotBlank() || c.tipsSlToken.isNotBlank()
    // Tips ride on the alerts switch (StreamService skips every alert while it's off): say so rather than stay silent.
    if (tipTokenSet && !s.alertsOn) {
        InlineError("Show alerts is off, so tips won't show.", actionLabel = "Turn on", onAction = { set(s.copy(alertsOn = true)) })
    }
    if (tipTokenSet && s.alertsOn && s.browserOn) {
        HelpText("If your web overlay already shows tips, leave these empty or you'll get two alerts per tip.")
    }
    val problems by app.irlsupreme.service.AlertQueue.tipProblems.collectAsState()
    SecretField(
        c.tipsSeJwt, c::changeTipsSeJwt, "StreamElements token",
        supporting = if ("StreamElements" in problems) "StreamElements refused this token. Copy it again."
        else "Keep it private. StreamElements dashboard › your avatar › Channels › Show secrets › JWT token",
    )
    SecretField(
        c.tipsSlToken, c::changeTipsSlToken, "Streamlabs token",
        supporting = if ("Streamlabs" in problems) "Streamlabs refused this token. Copy it again."
        else "Keep it private. Streamlabs › Settings › API Settings › API Tokens › Your Socket API Token",
    )

    SectionHeader("Web overlay")
    SwitchRow(
        "Show a web overlay",
        supporting = "The link you'd add to OBS. Its alerts show without their sounds, and it isn't in the preview.",
        checked = s.browserOn,
        onCheckedChange = { set(s.copy(browserOn = it)) },
    )
    if (s.browserOn) {
        if (s.alertsOn) HelpText("If this overlay has its own alerts, turn off Show alerts in Overlays so viewers don't see each one twice.")
        SecretField(
            c.overlayUrl, c::changeOverlayUrl, "Overlay link",
            supporting = when {
                c.overlayUrl.isBlank() -> "Paste the whole link, starting with https://"
                !OverlayStore.validBrowserUrl(c.overlayUrl) -> "That doesn't look like an https:// link"
                else -> "Keep it private: anyone with it can change your overlay"
            },
        )
    }
}

/** What the preview shows the layers over. A preview control only: it changes nothing about the stream. */
internal enum class PreviewOn(val title: String) { CAMERA("On camera"), BRB("On BRB card") }

/**
 * Which layers a preview over [on] would draw (pure, for tests): over the camera, every layer that's on; over the BRB
 * card, the corner layers only with "Also show on scene cards", but alerts either way (they show on BRB too).
 */
internal fun previewLayers(s: OverlaySettings, on: PreviewOn): OverlaySettings =
    if (on == PreviewOn.CAMERA || s.onCards) s else s.copy(chat = false, textOn = false, imageOn = false)

/** The sample follow, built through the same text model as a real one, so the preview's words are the stream's. */
/** The preview's pretend alert of [kind], as the stream draws it. */
private fun sampleCard(kind: SampleAlert): AlertCard = AlertText.card(kind.alert(0))

/**
 * The real composite in the stream's shape, letterboxed inside a 1px rule: the background (no camera frame
 * is kept outside a stream, so Ink with the stream's registration ticks), or the real BRB card; over it the
 * [OverlayComposer] layers with sample chat and a sample follow. Drawn off the main thread, never per keystroke.
 */
@Composable
private fun OverlayPreview(c: SetupController) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val colors = LocalFbColors.current
    val (ow, oh) = c.orientation.size(c.quality)
    val w = if (ow >= oh) 640 else 640 * ow / oh
    val h = if (ow >= oh) 640 * oh / ow else 640
    val s = c.overlays
    var on by rememberSaveable { mutableStateOf(PreviewOn.CAMERA) }
    val sample = SampleAlert.FOLLOW
    val login = (c.signIn as? SignIn.SignedIn)?.login.orEmpty()

    // Decoding and drawing are too slow for composition, which runs on every keystroke in the Text field. So the image
    // is decoded on IO only when it changes, and the overlay is drawn on Default once typing pauses for 100 ms (a key
    // change restarts the producer, cancelling the wait). The old picture stays up meanwhile.
    val image by produceState<Bitmap?>(null, s.imageOn, c.overlayImageStamp) {
        value = if (s.imageOn) withContext(Dispatchers.IO) { runCatching { OverlayStore.loadImage(ctx) }.getOrNull() } else null
    }
    val ground by produceState<Bitmap?>(null, on, w, h, login) {
        value = withContext(Dispatchers.Default) { previewGround(on, w, h, login) }
    }
    val alertOptions by AlertOptionsStore.state(ctx).collectAsState()
    val alertLooks by AlertStyles.version.collectAsState()
    val layers by produceState<Bitmap?>(null, s, image, w, h, on, sample, alertOptions, alertLooks) {
        delay(100)
        value = withContext(Dispatchers.Default) {
            val base = OverlayComposer.compose(previewLayers(s, on), OverlayComposer.sampleChat, image, w, h)
            if (s.alertsOn) withSampleAlert(base, sampleCard(sample), s, on, w, h, alertOptions) else base
        }
    }

    val wide = isWideLayout()
    KeyChoice("Preview", PreviewOn.entries, on, { it.title }) { on = it }
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier
                // Sideways the page is only ~340dp tall: small enough that the whole preview fits on screen.
                .widthIn(max = if (ow >= oh) (if (wide) 400.dp else 480.dp) else (if (wide) 130.dp else 160.dp))
                .fillMaxWidth()
                .aspectRatio(w.toFloat() / h)
                .border(1.dp, colors.rule, FbShape.small)
                .padding(1.dp)
                .clip(FbShape.small)
                .background(Color(StreamPalette.INK))
                .semantics {
                    contentDescription = "Preview of your stream ${on.title.lowercase()}, with sample chat and a sample ${sample.title.lowercase()} alert"
                },
        ) {
            ground?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
            layers?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
        }
    }
}

/**
 * What's under the layers. On camera: Ink with registration ticks, the stream's own "no picture here" ground. On the BRB
 * card: the card exactly as the engine draws it.
 */
private fun previewGround(on: PreviewOn, w: Int, h: Int, login: String): Bitmap = when (on) {
    PreviewOn.BRB -> brbCard(w, h, login)
    PreviewOn.CAMERA -> createBitmap(w, h).also { bmp ->
        val canvas = Canvas(bmp)
        canvas.drawColor(StreamPalette.INK)
        BrandDraw.regTicks(canvas, w, h, minOf(w, h) / 100f)
    }
}

/**
 * The sample alert drawn where the stream would put it: the same graphic the service hands the engine's alert layer
 * (size, place, colours), fully shown with its time-left rule, minus the wipe.
 */
private fun withSampleAlert(base: Bitmap?, card: AlertCard, s: OverlaySettings, on: PreviewOn, w: Int, h: Int, options: AlertOptions): Bitmap {
    val out = base?.copy(Bitmap.Config.ARGB_8888, true) ?: createBitmap(w, h)
    val onCard = on == PreviewOn.BRB
    val occupied = if (onCard && !s.onCards) emptySet() else OverlayLayout.occupied(s)
    OverlayComposer.drawAlertStill(Canvas(out), OverlayComposer.alertGraphic(card, w, h, onCard, occupied, options), w, h)
    return out
}

/** The BRB card exactly as the engine draws it for viewers. */
private fun brbCard(w: Int, h: Int, login: String): Bitmap =
    Slates.bitmap(w, h, Slates.Card.BRB, channel = login, audio = SlateAudio.ON)

/**
 * A small set of keys for one choice (a corner, a size, the preview ground): rM keys with a 1.5dp outline, the chosen
 * one inverted. Replaces Material's FilterChip; TalkBack hears a radio group with [label] as its name.
 */
@Composable
internal fun <T> KeyChoice(label: String, options: List<T>, selected: T, title: (T) -> String, onPick: (T) -> Unit) {
    val c = LocalFbColors.current
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = label }
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { o ->
            val chosen = o == selected
            val source = remember { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            Box(
                Modifier.heightIn(min = FbSpace.target)
                    .background(if (chosen) c.text else if (pressed) c.s2 else Color.Transparent, FbShape.medium)
                    .then(if (chosen) Modifier else Modifier.border(FbShape.stroke, c.ruleStrong, FbShape.medium))
                    .selectable(chosen, interactionSource = source, indication = null, role = Role.RadioButton) { onPick(o) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(title(o), style = FbType.rowValue, color = if (chosen) c.bg else c.text)
            }
        }
    }
}
