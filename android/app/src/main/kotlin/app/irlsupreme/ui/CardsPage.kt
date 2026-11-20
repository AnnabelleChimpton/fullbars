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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.irlsupreme.engine.video.CardLayout
import app.irlsupreme.engine.video.CardStyle
import app.irlsupreme.engine.video.SlateAudio
import app.irlsupreme.engine.video.Slates
import app.irlsupreme.engine.video.StreamPalette
import app.irlsupreme.service.CardBackground
import app.irlsupreme.service.CardRules
import app.irlsupreme.service.CardStore
import app.irlsupreme.service.CardText
import app.irlsupreme.service.cardWebUrl
import app.irlsupreme.ui.theme.FbShape
import app.irlsupreme.ui.theme.FbSpace
import app.irlsupreme.ui.theme.FbType
import app.irlsupreme.ui.theme.LocalFbColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The cards in the order a stream meets them: the four you choose, then the three that switch on by themselves. */
internal val CARD_ORDER = listOf(
    Slates.Card.STARTING_SOON, Slates.Card.BRB, Slates.Card.PRIVACY, Slates.Card.ENDING,
    Slates.Card.SIGNAL_LOST, Slates.Card.WEAK_SIGNAL, Slates.Card.CAMERA_LOST,
)

/** A card's short name for the chooser ("Signal lost"), from its default headline where that's the name. */
internal fun cardName(card: Slates.Card): String = when (card) {
    Slates.Card.BRB -> "BRB"
    Slates.Card.ENDING -> "Ending"
    else -> card.headline
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
 * Settings › Scene cards: your own words, picture, background, layout, accent, live elements and web page on each card
 * viewers see instead of your camera, the automatic ones included. A live preview drawn by the stream's own card code,
 * at the stream's shape. Sections run from what every card has (words) to what only power users want (a web page).
 */
@Composable
fun CardsPage(c: SetupController, nav: Navigator) = PageScaffold("Scene cards", nav::back) {
    val ctx = LocalContext.current
    val colors = LocalFbColors.current
    val scope = rememberCoroutineScope()
    val texts by CardStore.state(ctx).collectAsState()
    val socials by CardStore.socialsState(ctx).collectAsState()
    var picked by rememberSaveable { mutableStateOf(Slates.Card.BRB.name) }
    val card = Slates.Card.valueOf(picked)
    val t = texts[card] ?: CardText()
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
    KeyChoice("Card", CARD_ORDER, card, ::cardName) { picked = it.name; pictureError = false; backgroundError = null }

    CardPreview(c, card, t)

    // --- Words ---
    SectionHeader("Words")
    PlainField(
        t.headline, { CardStore.setText(ctx, card, it, t.sub) }, "Headline",
        supporting = "Blank shows “${card.headline}”. Up to ${CardStore.MAX_HEADLINE} characters.",
    )
    PlainField(
        t.sub, { CardStore.setText(ctx, card, t.headline, it) }, "Line under it",
        supporting = "Blank shows “${Slates.copy(card, SlateAudio.ON, CardStyle.DEFAULT).sub}”. Up to ${CardStore.MAX_SUB} characters.",
    )

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
    AccentChoice(t.accent) { CardStore.setAccent(ctx, card, it) }
    HelpText(
        t.accent?.let { "${CardRules.accentName(it)}. Picked so it never reads as a signal colour." }
            ?: "The card's own Ink and Bone.",
    )

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
 * "Default" and the curated accents as a radio group. Each swatch is named for TalkBack ("Sky"), and the chosen one gets
 * a Bone ring and an Ink square inside, so the choice never rests on colour alone.
 */
@Composable
private fun AccentChoice(selected: Int?, onPick: (Int?) -> Unit) {
    val c = LocalFbColors.current
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics { contentDescription = "Accent" }.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        val defaultChosen = selected == null
        Box(
            Modifier.heightIn(min = FbSpace.target)
                .background(if (defaultChosen) c.text else Color.Transparent, FbShape.medium)
                .then(if (defaultChosen) Modifier else Modifier.border(FbShape.stroke, c.ruleStrong, FbShape.medium))
                .selectable(defaultChosen, role = Role.RadioButton) { onPick(null) }
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Default", style = FbType.rowValue, color = if (defaultChosen) c.bg else c.text) }
        Slates.ACCENTS.forEach { a ->
            val chosen = a == selected
            val name = CardRules.accentName(a)
            Box(
                Modifier.size(FbSpace.target)
                    .then(if (chosen) Modifier.border(FbShape.outline, c.text, FbShape.small) else Modifier)
                    .selectable(chosen, role = Role.RadioButton) { onPick(a) }
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(34.dp).background(Color(a), FbShape.small), contentAlignment = Alignment.Center) {
                    if (chosen) Box(Modifier.size(10.dp).background(Color(StreamPalette.INK)))
                }
            }
        }
    }
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
