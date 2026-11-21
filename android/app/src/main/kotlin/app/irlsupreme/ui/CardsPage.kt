package app.irlsupreme.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.irlsupreme.engine.Scene
import app.irlsupreme.engine.video.CardFont
import app.irlsupreme.engine.video.CardLayout
import app.irlsupreme.engine.video.CardStyle
import app.irlsupreme.engine.video.CardTextSize
import app.irlsupreme.engine.video.CardTypefaces
import app.irlsupreme.engine.video.SlateAudio
import app.irlsupreme.engine.video.Slates
import app.irlsupreme.engine.video.StreamPalette
import app.irlsupreme.service.CardBackground
import app.irlsupreme.service.CardRules
import app.irlsupreme.service.CardStore
import app.irlsupreme.service.CardText
import app.irlsupreme.service.ColorMath
import app.irlsupreme.service.SceneNames
import app.irlsupreme.service.cardWebUrl
import app.irlsupreme.ui.theme.FbShape
import app.irlsupreme.ui.theme.FbSpace
import app.irlsupreme.ui.theme.FbType
import app.irlsupreme.ui.theme.LocalFbColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The built-in cards in the order a stream meets them: the four you choose, then the three that switch on by themselves. */
internal val CARD_ORDER = listOf(
    Slates.Card.STARTING_SOON, Slates.Card.BRB, Slates.Card.PRIVACY, Slates.Card.ENDING,
    Slates.Card.SIGNAL_LOST, Slates.Card.WEAK_SIGNAL, Slates.Card.CAMERA_LOST,
)

/** The chooser's cards: the four you choose, your own scenes that exist ([created]), then the automatic three. */
internal fun cardOrder(created: Map<Scene, String>): List<Slates.Card> =
    CARD_ORDER.take(4) + created.keys.mapNotNull { Slates.Card.of(it) } + CARD_ORDER.drop(4)

/** A card's short name for the chooser ("Signal lost"), from its default headline where that's the name; yours by theirs. */
internal fun cardName(card: Slates.Card, created: Map<Scene, String> = SceneNames.created): String = when {
    card == Slates.Card.BRB -> "BRB"
    card == Slates.Card.ENDING -> "Ending"
    card.isCustom -> SceneNames.sceneOf(card)?.let { SceneNames.name(it, created) } ?: card.headline
    else -> card.headline
}

/** What an accent or text colour is, for the line under its swatches: its name, and a word if it could read as a warning. */
internal fun colorHelp(argb: Int, preset: Boolean): String = when {
    preset -> "${CardRules.accentName(argb)}. Picked so it never reads as a signal colour."
    ColorMath.looksLikeSignal(argb) -> "${ColorMath.name(argb)}. Close to a warning colour: viewers might read it as one."
    else -> "${ColorMath.name(argb)}, your own colour."
}

/** The Text colour presets: the brand's Bone and Ink. */
internal val TEXT_PRESETS = listOf(StreamPalette.BONE, StreamPalette.INK)

internal fun textPresetName(argb: Int): String = when (argb) {
    StreamPalette.BONE -> "Bone"
    StreamPalette.INK -> "Ink"
    else -> ColorMath.name(argb)
}

/** True for the cards nobody chooses; their help says the label stays so viewers know. */
internal fun isAutomatic(card: Slates.Card) = card.family == Slates.Family.AUTOMATIC

/** A layout's name and its one help line, in the order the page offers them. */
internal fun layoutName(l: CardLayout): String = when (l) {
    CardLayout.CLASSIC -> "Classic"
    CardLayout.CENTERED -> "Centred"
    CardLayout.SIDE_BY_SIDE -> "Side by side"
    CardLayout.FULL_PICTURE -> "Full picture"
}

internal fun layoutHelp(l: CardLayout): String = when (l) {
    CardLayout.CLASSIC -> "Headline lower left, picture above it."
    CardLayout.CENTERED -> "Headline in the middle, picture above it."
    CardLayout.SIDE_BY_SIDE -> "Picture on one side, words on the other."
    CardLayout.FULL_PICTURE -> "Your picture fills the card. The words sit on a plate."
}

/** A dim step's key: "Off" or "35%". */
internal fun dimLabel(percent: Int): String = if (percent == 0) "Off" else "$percent%"

/** A countdown key: "Off" or "5 min". */
internal fun countdownLabel(minutes: Int): String = if (minutes == 0) "Off" else "$minutes min"

/** What the Background section's value says: a picture, or a GIF (only its first frame on the server's cards). */
internal fun backgroundValue(card: Slates.Card, kind: CardBackground): String = when (kind) {
    CardBackground.NONE -> "None"
    CardBackground.STILL -> "Picture"
    CardBackground.GIF -> if (CardRules.loopsGif(card)) "GIF, looping" else "GIF, first frame"
}

/**
 * Settings › Scene cards: your own words, face, size and colour, picture, background, layout, accent, live elements and
 * web page on each card viewers see instead of your camera, the automatic ones included, and up to four scenes of your
 * own. A live preview drawn by the stream's own card code,
 * at the stream's shape. Sections run from what every card has (words) to what only power users want (a web page).
 */
@Composable
fun CardsPage(c: SetupController, nav: Navigator) = PageScaffold("Scene cards", nav::back) {
    val ctx = LocalContext.current
    val colors = LocalFbColors.current
    val scope = rememberCoroutineScope()
    val texts by CardStore.state(ctx).collectAsState()
    val socials by CardStore.socialsState(ctx).collectAsState()
    val created = SceneNames.created(texts)
    var picked by rememberSaveable { mutableStateOf(Slates.Card.BRB.name) }
    // One of your own scenes that's been deleted falls back to BRB.
    val card = Slates.Card.valueOf(picked).takeIf { !it.isCustom || texts[it]?.created == true } ?: Slates.Card.BRB
    val t = texts[card] ?: CardText()
    val scene = SceneNames.sceneOf(card)
    var deleting by remember { mutableStateOf(false) }
    var pictureError by rememberSaveable { mutableStateOf(false) }
    var backgroundError by rememberSaveable { mutableStateOf<String?>(null) }
    var backgroundBusy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            pictureError = !withContext(Dispatchers.IO) { CardStore.setPicture(ctx, card, uri) }
        }
    }
    // ImageOnly includes GIFs. The card is captured now, so switching cards while a big GIF is checked can't misfile it.
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            backgroundBusy = true
            val r = withContext(Dispatchers.IO) { CardStore.setBackground(ctx, card, uri) }
            backgroundBusy = false
            backgroundError = CardRules.backgroundError(r)
        }
    }
    fun pickImage(launcher: androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest>) =
        launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    // The card styles reach the engine once at start (MainActivity); make sure they're loaded before the first preview.
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { CardStore.apply(ctx) } }

    HelpText(
        "What viewers see instead of your camera. The small label at the top and the sound line at the bottom stay, so " +
            "viewers always know when a card switched on by itself and what they can hear.",
    )
    KeyChoice("Card", cardOrder(created), card, { cardName(it, created) }) { picked = it.name; pictureError = false; backgroundError = null }
    if (created.size < Scene.CUSTOM.size) {
        FbButton(
            "Add a scene of your own", {
                CardStore.createScene(ctx)?.let { picked = it.name; pictureError = false; backgroundError = null }
            },
            Modifier.padding(horizontal = 16.dp), kind = FbButtonKind.Text,
        )
        if (created.isEmpty()) HelpText("Up to ${Scene.CUSTOM.size}, for things like Lunch or Q&A. They show in Scenes, on the mod remote and with !scene.")
    }

    CardPreview(c, card, t)

    // --- Your own scene: its name ---
    if (scene != null && card.isCustom) {
        SectionHeader("Scene")
        val problem = SceneNames.nameProblem(t.name, scene, created)
        val shown = SceneNames.name(scene, created)
        NameField(
            t.name, { CardStore.setSceneName(ctx, card, it) },
            supporting = problem ?: "Shows in Scenes and on the mod remote. Mods can type !scene ${shown.lowercase()}. " +
                "Up to ${SceneNames.MAX_NAME} characters.",
            isError = problem != null,
            placeholder = SceneNames.defaultName(scene),
        )
    }

    // --- Words ---
    SectionHeader("Words")
    val ownHeadline = if (scene != null && card.isCustom) SceneNames.name(scene, created) else card.headline
    PlainField(
        t.headline, { CardStore.setText(ctx, card, it, t.sub) }, "Headline",
        supporting = "Blank shows “$ownHeadline”. Up to ${CardStore.MAX_HEADLINE} characters.",
    )
    val ownSub = Slates.copy(card, SlateAudio.ON, CardStyle.DEFAULT).sub
    PlainField(
        t.sub, { CardStore.setText(ctx, card, t.headline, it) }, "Line under it",
        supporting = (if (ownSub.isEmpty()) "Blank shows no line." else "Blank shows “$ownSub”.") + " Up to ${CardStore.MAX_SUB} characters.",
    )
    ChoiceLabel("Font")
    FontChoice(t.font) { CardStore.setFont(ctx, card, it) }
    if (!CardTypefaces.shows(t.font)) HelpText("${t.font.label} isn't on this phone, so it shows as Sans.")
    ChoiceLabel("Size")
    KeyChoice("Text size", CardTextSize.entries, t.textSize, { it.label }) { CardStore.setTextSize(ctx, card, it) }
    ChoiceLabel("Text colour")
    ColorChoice("Text colour", t.textColor, TEXT_PRESETS, { CardStore.setTextColor(ctx, card, it) }, defaultLabel = "Automatic", presetName = ::textPresetName)
    HelpText(
        t.textColor?.let { if (it in TEXT_PRESETS) "${textPresetName(it)}." else colorHelp(it, preset = false) }
            ?: "Bone on dark cards, Ink on light ones.",
    )
    // Said plainly when the words would be hard to read; the background's colour is known once it's loaded.
    val contrast by produceState<String?>(null, card, t) {
        repeat(3) {
            value = CardRules.contrastProblem(card, t, CardStore.backgroundTone(card))
            delay(500)
        }
    }
    contrast?.let { Caution(it) }

    // --- Picture ---
    SectionHeader("Picture")
    HelpText(
        if (isAutomatic(card)) "Shown in place of the symbol. The coloured rule under it stays."
        else "Shown with your headline. Square pictures and transparent PNGs look best.",
    )
    if (t.hasPicture) {
        FbButton("Choose another picture", { pickImage(picker) }, Modifier.padding(horizontal = 16.dp), kind = FbButtonKind.Text)
        FbButton("Remove picture", { CardStore.clearPicture(ctx, card) }, Modifier.padding(horizontal = 16.dp), kind = FbButtonKind.Text)
    } else {
        FbButton("Add a picture", { pickImage(picker) }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), kind = FbButtonKind.Secondary)
    }
    if (pictureError) InlineError("That picture couldn't be opened. Try another.", modifier = Modifier.padding(top = 8.dp))

    // --- Background ---
    SectionHeader("Background")
    HelpText(
        if (CardRules.loopsGif(card)) "Behind the whole card, cropped to fill. A GIF loops."
        else "Behind the whole card, cropped to fill. Your BRB server shows this card as a still, so a GIF shows its first frame.",
    )
    if (t.background != CardBackground.NONE) {
        ValueRow("Background", backgroundValue(card, t.background), valueIsFigure = false)
        FbButton("Choose another background", { pickImage(backgroundPicker) }, Modifier.padding(horizontal = 16.dp), kind = FbButtonKind.Text, enabled = !backgroundBusy)
        FbButton("Remove background", { CardStore.clearBackground(ctx, card); backgroundError = null }, Modifier.padding(horizontal = 16.dp), kind = FbButtonKind.Text, enabled = !backgroundBusy)
        ChoiceLabel("Darken it")
        KeyChoice("Darken the background", CardRules.DIM_STEPS, CardRules.snapDim(t.dimPercent), ::dimLabel) { CardStore.setDim(ctx, card, it) }
        HelpText("Ink laid over the background so your words stay readable.")
    } else {
        FbButton(
            if (backgroundBusy) "Checking…" else "Add a background", { pickImage(backgroundPicker) },
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp), kind = FbButtonKind.Secondary, enabled = !backgroundBusy,
        )
    }
    backgroundError?.let { InlineError(it, modifier = Modifier.padding(top = 8.dp)) }

    // --- Layout and accent ---
    SectionHeader("Layout")
    Column(Modifier.fillMaxWidth().semantics { contentDescription = "Layout" }.selectableGroup()) {
        CardLayout.entries.forEach { l ->
            LayoutRow(l, selected = t.layout == l, portrait = c.orientation == StreamOrientation.PORTRAIT) { CardStore.setLayout(ctx, card, l) }
        }
    }
    if (t.layout == CardLayout.FULL_PICTURE && !t.hasPicture) HelpText("This layout is built around your picture. Add one under Picture.")
    ChoiceLabel("Accent")
    ColorChoice("Accent", t.accent, Slates.ACCENTS, { CardStore.setAccent(ctx, card, it) }, presetName = CardRules::accentName)
    HelpText(t.accent?.let { colorHelp(it, preset = it in Slates.ACCENTS) } ?: "The card's own Ink and Bone.")

    // --- Live ---
    SectionHeader("Live")
    if (!CardRules.hasLiveElements(card)) {
        HelpText("Your BRB server shows this card as a still, so it can't show timers or chat.")
    } else {
        val e = t.elements
        if (CardRules.hasCountdown(card)) {
            ChoiceLabel("Countdown")
            KeyChoice("Countdown", CardRules.COUNTDOWN_CHOICES, e.countdownMinutes.takeIf { it in CardRules.COUNTDOWN_CHOICES } ?: 0, ::countdownLabel) {
                CardStore.setElements(ctx, card, e.copy(countdownMinutes = it))
            }
            HelpText("Counts down from when the card appears: “${CardRules.countdownExample(card)}”.")
        }
        SwitchRow("Show time away", "Counts up how long this card has been up.", checked = e.showTimeAway) {
            CardStore.setElements(ctx, card, e.copy(showTimeAway = it))
        }
        SwitchRow("Show chat on this card", "Even when chat is off on cards in Overlays.", checked = e.showChat) {
            CardStore.setElements(ctx, card, e.copy(showChat = it))
        }
    }
    PlainField(
        socials, { CardStore.setSocials(ctx, it) }, "Socials line",
        supporting = "Every card your phone draws, along the bottom. Like “@you everywhere”. Up to ${CardRules.MAX_SOCIALS} characters.",
    )

    // --- Music: chosen cards and your own scenes only (nothing for the automatic ones) ---
    scene?.let { CardMusicRow(it.name) }

    // --- Web page ---
    SectionHeader("Web page")
    if (!CardRules.canUseWebPage(card)) {
        HelpText("Signal lost and Weak signal can't use a web page: your BRB server shows them as a still.")
    } else {
        HelpText("A page shown instead of this whole card on stream, label and sound line included. A StreamElements scene or your own HTML.")
        WebUrlField(t.webUrl) { CardStore.setWebUrl(ctx, card, it) }
    }

    if (t.isCustom) {
        Spacer(Modifier.height(16.dp))
        Hairline(color = colors.rule)
        FbButton(
            "Back to the card's own look", { CardStore.reset(ctx, card); pictureError = false; backgroundError = null },
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp), kind = FbButtonKind.Text,
        )
    }
    if (card.isCustom) {
        if (!t.isCustom) {
            Spacer(Modifier.height(16.dp))
            Hairline(color = colors.rule)
        }
        FbButton("Delete this scene", { deleting = true }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), kind = FbButtonKind.Text)
    }
    if (deleting) {
        val name = cardName(card, created)
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete $name?", style = FbType.cta) },
            text = { Text("Its name, words, picture and background go. If it's on screen now, switch scenes first.") },
            confirmButton = {
                FbButton("Delete", {
                    deleting = false
                    CardStore.deleteScene(ctx, card)
                    picked = Slates.Card.BRB.name
                }, kind = FbButtonKind.Text)
            },
            dismissButton = { FbButton("Cancel", { deleting = false }, kind = FbButtonKind.Text) },
            shape = FbShape.medium, containerColor = colors.s1, titleContentColor = colors.text, textContentColor = colors.text2,
            tonalElevation = 0.dp,
        )
    }

    Footnote(
        null,
        "Signal lost and Weak signal are also sent to your BRB server when each stream starts, so viewers see your " +
            "version during dropouts too. Changes here show from your next stream.",
        Modifier.padding(top = 16.dp),
    )
}

/** A small visible name over a set of keys ("Countdown"), in the row face. The keys carry the same name for TalkBack. */
@Composable
private fun ChoiceLabel(text: String) {
    Text(
        text, style = FbType.row, color = LocalFbColors.current.text,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp).clearAndSetSemantics {},
    )
}

/**
 * One layout as a whole-row radio: [FbRadio], the name, one help line and a small schematic of where the words and
 * picture sit, at the stream's shape. The schematic is decorative: the name and help line say the same thing.
 */
@Composable
private fun LayoutRow(l: CardLayout, selected: Boolean, portrait: Boolean, onClick: () -> Unit) {
    val c = LocalFbColors.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        Modifier.fillMaxWidth().bottomRule(c.rule)
            .background(if (pressed) c.s2 else Color.Transparent)
            .selectable(selected = selected, interactionSource = source, indication = null, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = FbSpace.rowTappable)
            .padding(horizontal = FbSpace.gutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FbRadio(selected)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(layoutName(l), style = FbType.row, color = c.text)
            Text(layoutHelp(l), style = FbType.help, color = c.text2, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(12.dp))
        val (w, h) = if (portrait) 27.dp to 48.dp else 64.dp to 36.dp
        Canvas(Modifier.size(w, h).clearAndSetSemantics {}) { drawLayoutSchematic(l, c.ruleStrong, c.text2, c.text) }
    }
}

/** Where a layout puts the picture (a filled block) and the words (bars), in a 1px frame. */
private fun DrawScope.drawLayoutSchematic(l: CardLayout, frame: Color, picture: Color, words: Color) {
    val w = size.width
    val h = size.height
    val u = minOf(w, h) / 10f
    val bar = maxOf(1.5f, u * 0.9f)
    drawRect(frame, style = Stroke(1f))
    fun block(x: Float, y: Float, bw: Float, bh: Float) = drawRect(picture.copy(alpha = 0.55f), Offset(x, y), Size(bw, bh))
    fun bars(x: Float, y: Float, long: Float, centred: Boolean = false) {
        val short = long * 0.6f
        drawRect(words, Offset(if (centred) x - long / 2 else x, y), Size(long, bar))
        drawRect(words.copy(alpha = 0.6f), Offset(if (centred) x - short / 2 else x, y + bar * 1.8f), Size(short, bar * 0.7f))
    }
    when (l) {
        CardLayout.CLASSIC -> { block(2 * u, 2 * u, 2.5f * u, 2.5f * u); bars(2 * u, h - 4.5f * u, w * 0.45f) }
        CardLayout.CENTERED -> { block(w / 2 - 1.25f * u, h / 2 - 3.5f * u, 2.5f * u, 2.5f * u); bars(w / 2, h / 2 + 0.5f * u, w * 0.45f, centred = true) }
        CardLayout.SIDE_BY_SIDE -> {
            if (w >= h) { block(2 * u, 2 * u, w * 0.4f, h - 4 * u); bars(w * 0.4f + 4 * u, h / 2 - bar, w * 0.4f) }
            else { block(2 * u, 2 * u, w - 4 * u, h * 0.4f); bars(2 * u, h * 0.4f + 5 * u, w * 0.6f) }
        }
        CardLayout.FULL_PICTURE -> {
            block(0f, 0f, w, h)
            val plateH = 4.5f * u
            drawRect(Color(StreamPalette.INK).copy(alpha = 0.85f), Offset(2 * u, h - plateH - 2 * u), Size(w * 0.6f, plateH))
            bars(3 * u, h - plateH - 0.8f * u, w * 0.4f)
        }
    }
}

/**
 * The fonts as a radio group of keys, each name set in its own face (a family the phone lacks shows in Sans, as it
 * would on stream).
 */
@Composable
private fun FontChoice(selected: CardFont, onPick: (CardFont) -> Unit) {
    val c = LocalFbColors.current
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics { contentDescription = "Font" }.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CardFont.entries.forEach { f ->
            val chosen = f == selected
            val family = remember(f) { runCatching { FontFamily(CardTypefaces.head(f)) }.getOrNull() }
            Box(
                Modifier.heightIn(min = FbSpace.target)
                    .background(if (chosen) c.text else Color.Transparent, FbShape.medium)
                    .then(if (chosen) Modifier else Modifier.border(FbShape.stroke, c.ruleStrong, FbShape.medium))
                    .selectable(chosen, role = Role.RadioButton) { onPick(f) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(f.label, style = FbType.rowValue.copy(fontFamily = family ?: FbType.rowValue.fontFamily), color = if (chosen) c.bg else c.text)
            }
        }
    }
}

/** One of your own scenes' name: kept as typed, the problem (a name !scene can't reach) said under it. */
@Composable
private fun NameField(text: String, onChange: (String) -> Unit, supporting: String, isError: Boolean, placeholder: String) {
    val c = LocalFbColors.current
    OutlinedTextField(
        value = text, onValueChange = onChange, label = { Text("Name") }, singleLine = true,
        placeholder = { Text(placeholder) },
        textStyle = FbType.rowValue,
        isError = isError,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        supportingText = { Text(supporting) },
        shape = FbShape.medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = c.text, unfocusedTextColor = c.text, errorTextColor = c.text,
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, errorContainerColor = Color.Transparent,
            cursorColor = c.text, errorCursorColor = c.error,
            focusedBorderColor = c.text, unfocusedBorderColor = c.ruleStrong, errorBorderColor = c.error,
            focusedLabelColor = c.text, unfocusedLabelColor = c.text2, errorLabelColor = c.error,
            focusedPlaceholderColor = c.text2, unfocusedPlaceholderColor = c.text2,
            focusedSupportingTextColor = c.text2, unfocusedSupportingTextColor = c.text2, errorSupportingTextColor = c.error,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** A plain heads-up in the text colour, read out when it appears: not an error, the choice stays the streamer's. */
@Composable
private fun Caution(text: String) {
    Text(
        text, style = FbType.help, color = LocalFbColors.current.text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * The web page address: a URL keyboard with no autocorrect, and the problem in plain words once typing pauses (so a
 * half-typed "https://ex" isn't flagged as you go).
 */
@Composable
private fun WebUrlField(text: String, onChange: (String) -> Unit) {
    val c = LocalFbColors.current
    val problem by produceState<String?>(null, text) {
        value = null
        delay(700)
        value = CardRules.webUrlProblem(text)
    }
    OutlinedTextField(
        value = text, onValueChange = onChange, label = { Text("Page address") }, singleLine = true,
        placeholder = { Text("https://") },
        textStyle = FbType.rowValue,
        isError = problem != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        supportingText = { Text(problem ?: "If it can't load, viewers see the card as set above.") },
        shape = FbShape.medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = c.text, unfocusedTextColor = c.text, errorTextColor = c.text,
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, errorContainerColor = Color.Transparent,
            cursorColor = c.text, errorCursorColor = c.error,
            focusedBorderColor = c.text, unfocusedBorderColor = c.ruleStrong, errorBorderColor = c.error,
            focusedLabelColor = c.text, unfocusedLabelColor = c.text2, errorLabelColor = c.error,
            focusedPlaceholderColor = c.text2, unfocusedPlaceholderColor = c.text2,
            focusedSupportingTextColor = c.text2, unfocusedSupportingTextColor = c.text2, errorSupportingTextColor = c.error,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * The card exactly as the stream draws it, at the stream's shape, redrawn a moment after a change. A card with a web
 * page shows a plain placeholder naming the site instead: the page itself only loads on stream.
 */
@Composable
private fun CardPreview(c: SetupController, card: Slates.Card, t: CardText) {
    val colors = LocalFbColors.current
    val portrait = c.orientation == StreamOrientation.PORTRAIT
    val (w, h) = if (portrait) 540 to 960 else 960 to 540
    val login = (c.signIn as? SignIn.SignedIn)?.login.orEmpty()
    val wide = isWideLayout()
    val web = if (CardRules.canUseWebPage(card)) cardWebUrl(t.webUrl) else null
    val bmp by produceState<Bitmap?>(null, card, t, Slates.version, portrait, login) {
        delay(120)
        value = withContext(Dispatchers.Default) { Slates.bitmap(w, h, card, channel = login, audio = SlateAudio.ON) }
    }
    val host = web?.let { CardRules.webHost(it) }
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier
                .widthIn(max = if (portrait) (if (wide) 150.dp else 220.dp) else (if (wide) 400.dp else 480.dp))
                .fillMaxWidth()
                .aspectRatio(w.toFloat() / h)
                .border(1.dp, colors.rule, FbShape.small)
                .padding(1.dp)
                .clip(FbShape.small)
                .background(if (host != null) Color(StreamPalette.INK) else colors.s1)
                .semantics {
                    contentDescription = if (host != null) "Web page: $host. It loads on stream, not in this preview."
                    else "Preview of the ${cardName(card)} card as viewers see it"
                },
        ) {
            if (host != null) {
                Column(Modifier.align(Alignment.CenterStart).padding(16.dp).clearAndSetSemantics {}) {
                    Text("Web page: $host", style = FbType.rowValue, color = Color(StreamPalette.BONE))
                    Text("Loads on stream, not here.", style = FbType.help, color = Color(StreamPalette.TEXT2), modifier = Modifier.padding(top = 4.dp))
                }
            } else {
                bmp?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
            }
        }
    }
}
