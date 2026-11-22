package app.irlsupreme.ui

import app.irlsupreme.service.AlertSoundSetting
import app.irlsupreme.service.AlertSoundChoice
import app.irlsupreme.service.AlertSoundChoices
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import app.irlsupreme.engine.audio.AlertSounds
import app.irlsupreme.engine.audio.PcmClip
import app.irlsupreme.engine.video.AlertTier
import app.irlsupreme.engine.video.BrandDraw
import app.irlsupreme.engine.video.StreamPalette
import app.irlsupreme.service.AlertAccent
import app.irlsupreme.service.AlertColors
import app.irlsupreme.service.AlertKind
import app.irlsupreme.service.AlertKindOptions
import app.irlsupreme.service.AlertLength
import app.irlsupreme.service.AlertOptions
import app.irlsupreme.service.AlertOptionsCodec
import app.irlsupreme.service.AlertOptionsStore
import app.irlsupreme.service.AlertPaint
import app.irlsupreme.service.AlertPlace
import app.irlsupreme.service.AlertSize
import app.irlsupreme.service.AlertStyleText
import app.irlsupreme.service.AlertStyles
import app.irlsupreme.service.AlertText
import app.irlsupreme.service.AlertTiming
import app.irlsupreme.service.ColorMath
import app.irlsupreme.service.OverlayComposer
import app.irlsupreme.service.SampleAlert
import app.irlsupreme.service.TipAmount
import app.irlsupreme.service.alertPlaceholders
import app.irlsupreme.ui.theme.FbShape
import app.irlsupreme.ui.theme.FbType
import app.irlsupreme.ui.theme.LocalFbColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Settings > Overlays > Customize alerts: a preview, where alerts land and how big they are, then one row per kind with
 * what's set for it. Each kind has its own page ([AlertKindPage]). The card keeps its size class (a raid stays the big
 * slab), so viewers still read how big a moment it is.
 */
@Composable
fun AlertsPage(c: SetupController, nav: Navigator) = PageScaffold("Customize alerts", nav::back) {
    val ctx = LocalContext.current
    val options by AlertOptionsStore.state(ctx).collectAsState()
    val styles by AlertStyles.state(ctx).collectAsState()
    val sounds by AlertSoundChoices.state(ctx).collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { AlertStyles.apply(ctx) } }

    AlertPreview(c, AlertKind.FOLLOW, options)

    SectionHeader("Look")
    ChoiceLabel("Where")
    KeyChoice("Where alerts go", AlertPlace.entries, options.place, { it.title }) { AlertOptionsStore.setPlace(ctx, it) }
    HelpText(placeHelp(options.place))
    ChoiceLabel("Size")
    KeyChoice("Alert size", AlertSize.entries, options.size, { it.title }) { AlertOptionsStore.setSize(ctx, it) }

    SectionHeader("Each kind")
    AlertKind.entries.forEach { k ->
        val snd = sounds[k] ?: AlertSoundSetting()
        ValueRow(
            k.title,
            alertKindSummary(k, options.of(k), styles[k]?.isCustom == true, snd.choice, snd.hasFile, soundsOn = c.overlays.alertSound),
            valueIsFigure = false,
            onClick = { AlertKindNav.kind = k; nav.open(Screen.ALERT_KIND) },
        )
    }
    HelpText(
        "To see one move on stream, use Test an alert in Scenes during a Test stream. Test alerts ignore the minimums, " +
            "and viewers never see them.",
    )
}

/** Which kind [AlertKindPage] shows: set by the row tapped on Customize alerts. */
internal object AlertKindNav {
    var kind by mutableStateOf(AlertKind.FOLLOW)
}

/**
 * A kind's row on Customize alerts: "Off", or what's set beyond the default ("From 5 · your sound · custom look"), or
 * "Default". [soundsOn]: Overlays' Alert sounds switch, without which no kind makes a sound.
 */
internal fun alertKindSummary(
    kind: AlertKind,
    o: AlertKindOptions,
    lookCustom: Boolean,
    sound: AlertSoundChoice,
    hasFile: Boolean,
    soundsOn: Boolean = true,
): String {
    if (!o.on) return "Off"
    val parts = buildList {
        when {
            kind == AlertKind.TIP && o.minimum > 0 -> add("From ${TipAmount.format(o.minimum)}")
            kind != AlertKind.TIP && o.minimum > 0 -> add("From ${o.minimum}")
        }
        if (kind == AlertKind.SUB && o.resubMonths > 0) add("Resubs from ${o.resubMonths} months")
        if (kind == AlertKind.SUB && o.skipPrime) add("No Prime")
        if (o.length != AlertLength.NORMAL) add(o.length.title)
        if (soundsOn) when {
            sound == AlertSoundChoice.CUSTOM && hasFile -> add("your sound")
            sound == AlertSoundChoice.NONE -> add("no sound")
        }
        if (lookCustom || o.paint != AlertPaint()) add("custom look")
    }
    return if (parts.isEmpty()) "Default" else parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
}

/**
 * One kind's page: whether it shows and the smallest worth showing, how long it stays up, its sound, words, picture and
 * colours. A close-up of the card, drawn by the stream's own code, stays pinned at the top while you scroll.
 */
@Composable
fun AlertKindPage(c: SetupController, nav: Navigator) {
    val kind = AlertKindNav.kind
    val ctx = LocalContext.current
    val options by AlertOptionsStore.state(ctx).collectAsState()
    PageScaffold("${kind.title} alerts", nav::back, pinned = { AlertCloseUp(c, kind, options) }) {
        AlertKindSettings(kind, options.of(kind), soundsOn = c.overlays.alertSound, onSoundsOn = { c.updateOverlays(c.overlays.copy(alertSound = true)) })
    }
}

/** The card alone, cropped from a full-frame draw at the stream's size, over Ink: about 96dp tall. */
@Composable
private fun AlertCloseUp(c: SetupController, kind: AlertKind, options: AlertOptions) {
    val (ow, oh) = c.orientation.size(c.quality)
    val w = if (ow >= oh) 960 else 960 * ow / oh
    val h = if (ow >= oh) 960 * oh / ow else 960
    val version by AlertStyles.version.collectAsState()
    val bmp by produceState<Bitmap?>(null, kind, version, options, w, h) {
        delay(80)
        value = withContext(Dispatchers.Default) {
            val full = createBitmap(w, h)
            val canvas = Canvas(full)
            canvas.drawColor(StreamPalette.INK)
            val card = AlertText.card(SampleAlert.valueOf(kind.name).alert(0), options = options.of(kind))
            val g = OverlayComposer.alertGraphic(card, w, h, onCard = false, occupied = emptySet(), options = options)
            val m = minOf(w, h) / 50
            val x = (g.leftPx - m).coerceAtLeast(0)
            val y = (g.topPx - m).coerceAtLeast(0)
            val cw = minOf(g.bitmap.width + 2 * m, w - x)
            val ch = minOf(g.bitmap.height + 2 * m, h - y)
            OverlayComposer.drawAlertStill(canvas, g, w, h)
            Bitmap.createBitmap(full, x, y, cw, ch).also { if (it !== full) full.recycle() }
        }
    }
    Box(
        Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(FbShape.small).background(Color(StreamPalette.INK))
            .semantics { contentDescription = "Close-up of a ${kind.title.lowercase()} alert as you set it" },
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
    }
}

/** What Where means for [place]. */
internal fun placeHelp(place: AlertPlace): String = when (place) {
    AlertPlace.AUTO -> "Kept clear of your chat, text and image."
    AlertPlace.CENTER -> "Inside the frame's safe margin. It covers the middle of your shot while it's up."
    else -> "Inside the frame's safe margin. It may cover a layer in that corner."
}

/** One kind's own settings: on/off and minimums, length, sound, words, picture, colours. */
@Composable
private fun AlertKindSettings(kind: AlertKind, o: AlertKindOptions, soundsOn: Boolean, onSoundsOn: () -> Unit) {
    val ctx = LocalContext.current
    val styles by AlertStyles.state(ctx).collectAsState()
    val t = styles[kind] ?: AlertStyleText()
    fun set(next: AlertKindOptions) = AlertOptionsStore.setKind(ctx, kind, next)
    val tier = AlertText.card(SampleAlert.valueOf(kind.name).alert(0)).tier

    SectionHeader("Show")
    SwitchRow(
        "Show ${kind.title.lowercase()} alerts",
        supporting = if (o.on) null else "Off: these won't show or chime.",
        checked = o.on,
        onCheckedChange = { set(o.copy(on = it)) },
    )
    if (o.on) MinimumRows(kind, o, ::set)

    SectionHeader("Length")
    KeyChoice("Length", AlertLength.entries, o.length, { it.title }) { set(o.copy(length = it)) }
    HelpText("Up for ${seconds(AlertTiming.dwellMs(tier, o.length))} once it's in.")

    AlertSoundRow(kind, soundsOn, onSoundsOn)

    WordsSection(kind, t)
    PictureSection(kind, t)
    ColorsSection(kind, tier, o.paint) { set(o.copy(paint = it)) }

    if (t.isCustom || o.isCustom) {
        Spacer(Modifier.height(16.dp))
        Hairline()
        FbButton(
            "Back to the default",
            { AlertStyles.reset(ctx, kind); AlertOptionsStore.reset(ctx, kind) },
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp), kind = FbButtonKind.Text,
        )
    }
}

/** "4 s", "3.6 s". */
private fun seconds(ms: Long): String =
    if (ms % 1000 == 0L) "${ms / 1000} s" else String.format(Locale.US, "%.1f s", ms / 1000.0)

/** The smallest of [kind] worth showing: a number, or for subs, Prime and resub months. */
@Composable
private fun MinimumRows(kind: AlertKind, o: AlertKindOptions, set: (AlertKindOptions) -> Unit) {
    when (kind) {
        AlertKind.FOLLOW -> Unit
        AlertKind.SUB -> {
            SwitchRow("Skip Prime subs", checked = o.skipPrime, onCheckedChange = { set(o.copy(skipPrime = it)) })
            SwitchRow(
                "Only resubs",
                supporting = "New subs won't show.",
                checked = o.resubMonths > 0,
                onCheckedChange = { set(o.copy(resubMonths = if (it) 2 else 0)) },
            )
            if (o.resubMonths > 0) {
                NumberField(
                    "At least this many months", "${o.resubMonths}", decimal = false,
                    supporting = "2 or more.",
                    parse = { s -> s.trim().toIntOrNull()?.takeIf { it >= 2 }?.toLong() },
                ) { set(o.copy(resubMonths = AlertOptionsCodec.boundMonths(it.toInt()))) }
            }
        }
        AlertKind.TIP -> NumberField(
            "Smallest tip to show", if (o.minimum > 0) TipAmount.format(o.minimum) else "", decimal = true,
            supporting = "Just the number, in the tip's own currency: 5 is $5, €5 or ¥5 alike. Blank shows every tip.",
            parse = TipAmount::parseMinimum,
        ) { set(o.copy(minimum = AlertOptionsCodec.boundMinimum(kind, it))) }
        else -> {
            val (label, unit) = when (kind) {
                AlertKind.BITS -> "Fewest bits to show" to "cheer"
                AlertKind.GIFT -> "Fewest gift subs to show" to "gift"
                else -> "Fewest raiders to show" to "raid"
            }
            NumberField(
                label, if (o.minimum > 0) "${o.minimum}" else "", decimal = false,
                supporting = "Blank shows every $unit.",
                parse = { s -> if (s.isBlank()) 0 else s.trim().toLongOrNull()?.takeIf { it >= 0 } },
            ) { set(o.copy(minimum = AlertOptionsCodec.boundMinimum(kind, it))) }
        }
    }
}

/**
 * A number typed in, saved as soon as it reads as one ([parse] gives null while it doesn't). Kept as typed while the
 * field is in use, so "2." on the way to "2.5" isn't rewritten under the thumb.
 */
@Composable
private fun NumberField(
    label: String,
    stored: String,
    decimal: Boolean,
    supporting: String,
    parse: (String) -> Long?,
    onValue: (Long) -> Unit,
) {
    var text by rememberSaveable(label) { mutableStateOf(stored) }
    // Changed elsewhere (Back to the default): show it, unless what's typed already says the same.
    LaunchedEffect(stored) { if (parse(text) != parse(stored)) text = stored }
    val bad = parse(text) == null
    PlainField(
        text,
        { typed ->
            text = typed.take(12)
            parse(text)?.let(onValue)
        },
        label,
        supporting = if (bad) "That isn't a number." else supporting,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        isError = bad,
    )
}

@Composable
private fun WordsSection(kind: AlertKind, t: AlertStyleText) {
    val ctx = LocalContext.current
    SectionHeader("Words")
    PlainField(
        t.label, { AlertStyles.setText(ctx, kind, it, t.message) }, "Label",
        supporting = "Blank shows “${kind.defaultLabel}”." + nearLimit(t.label.length, AlertStyles.MAX_LABEL),
    )
    val tags = alertPlaceholders(kind)
    PlainField(
        t.message, { AlertStyles.setText(ctx, kind, t.label, it) }, "Message",
        supporting = "Blank shows the name. You can use ${tags.joinToString(", ")}." +
            nearLimit(t.message.length, AlertStyles.MAX_MESSAGE),
    )
}

/** " 76/80" once a field is within 5 of its limit, else nothing: a count is noise until it matters. */
internal fun nearLimit(length: Int, max: Int): String = if (length >= max - 5) " $length/$max" else ""

@Composable
private fun PictureSection(kind: AlertKind, t: AlertStyleText) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pictureError by rememberSaveable(kind) { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { pictureError = !withContext(Dispatchers.IO) { AlertStyles.setPicture(ctx, kind, uri) } }
    }
    SectionHeader("Picture")
    HelpText("Shown at the left of the card. Your emote or logo works well; transparent PNGs look best.")
    if (t.hasPicture) {
        FbButton("Choose another picture", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.padding(horizontal = 16.dp), kind = FbButtonKind.Text)
        FbButton("Remove picture", { AlertStyles.clearPicture(ctx, kind) }, Modifier.padding(horizontal = 16.dp), kind = FbButtonKind.Text)
    } else {
        FbButton(
            "Add a picture", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp), kind = FbButtonKind.Secondary,
        )
    }
    if (pictureError) InlineError("That picture couldn't be opened. Try another.", modifier = Modifier.padding(top = 8.dp))
}

/** Plate, text and accent colours, with a plain warning when the words would be hard to read. */
@Composable
private fun ColorsSection(kind: AlertKind, tier: AlertTier, p: AlertPaint, set: (AlertPaint) -> Unit) {
    SectionHeader("Colours")
    ChoiceLabel("Plate")
    ColorChoice(
        "Plate colour", p.plate, AlertColors.PLATE_PRESETS, { set(p.copy(plate = it)) },
        defaultLabel = if (tier == AlertTier.LOUD) "Default: Bone" else "Default: Ink", presetName = ::paletteName,
    )
    ChoiceLabel("Text")
    ColorChoice(
        "Text colour", p.text, AlertColors.TEXT_PRESETS, { set(p.copy(text = it)) },
        defaultLabel = "Automatic", presetName = ::paletteName,
    )
    if (AlertColors.hardToRead(p, tier)) {
        InlineError(contrastWarning(AlertColors.contrast(p, tier)))
    }
    ChoiceLabel("Accent")
    KeyChoice("Accent", AlertAccent.entries, p.accent, { it.title }) { set(p.copy(accent = it)) }
    if (p.accent != AlertAccent.OFF) {
        ColorChoice(
            "Accent colour", p.accentColor, AlertColors.ACCENT_PRESETS, { it?.let { c -> set(p.copy(accentColor = c)) } },
            defaultLabel = null, presetName = ::paletteName,
        )
    }
}

/** What the page says when the text is hard to read on its plate. */
internal fun contrastWarning(ratio: Double): String =
    "Hard to read: the text has ${String.format(Locale.US, "%.1f", ratio)}:1 contrast with the plate. Aim for 3:1 or more."

/** The brand's two colours by their names, the rest by a plain one. */
private fun paletteName(argb: Int): String = when (argb) {
    AlertColors.INK -> "Ink"
    AlertColors.BONE -> "Bone"
    else -> ColorMath.name(argb)
}


/**
 * The sample alert of [kind] with everything the streamer set, drawn by the stream's own code where the stream puts it
 * and at its size, fully shown with its time-left rule, over the stream's empty ground.
 */
@Composable
private fun AlertPreview(c: SetupController, kind: AlertKind, options: AlertOptions) {
    val colors = LocalFbColors.current
    val (ow, oh) = c.orientation.size(c.quality)
    val w = if (ow >= oh) 640 else 640 * ow / oh
    val h = if (ow >= oh) 640 * oh / ow else 640
    val version by AlertStyles.version.collectAsState()
    val wide = isWideLayout()
    val bmp by produceState<Bitmap?>(null, kind, version, options, w, h) {
        delay(80)
        value = withContext(Dispatchers.Default) {
            createBitmap(w, h).also { out ->
                val canvas = Canvas(out)
                canvas.drawColor(StreamPalette.INK)
                BrandDraw.regTicks(canvas, w, h, minOf(w, h) / 100f)
                val card = AlertText.card(SampleAlert.valueOf(kind.name).alert(0), options = options.of(kind))
                val g = OverlayComposer.alertGraphic(card, w, h, onCard = false, occupied = emptySet(), options = options)
                OverlayComposer.drawAlertStill(canvas, g, w, h)
            }
        }
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier
                .widthIn(max = if (ow >= oh) (if (wide) 400.dp else 480.dp) else (if (wide) 130.dp else 220.dp))
                .fillMaxWidth()
                .aspectRatio(w.toFloat() / h)
                .border(1.dp, colors.rule, FbShape.small)
                .padding(1.dp)
                .clip(FbShape.small)
                .background(Color(StreamPalette.INK))
                .semantics { contentDescription = "Preview of a ${kind.title.lowercase()} alert as you set it" },
        ) {
            bmp?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
        }
    }
}
