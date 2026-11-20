package app.irlsupreme.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Size
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
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import app.irlsupreme.engine.audio.AlertSounds
import app.irlsupreme.engine.audio.PcmClip
import app.irlsupreme.engine.video.BrandDraw
import app.irlsupreme.engine.video.StreamPalette
import app.irlsupreme.service.AlertKind
import app.irlsupreme.service.AlertStyleText
import app.irlsupreme.service.AlertStyles
import app.irlsupreme.service.AlertText
import app.irlsupreme.service.OverlayComposer
import app.irlsupreme.service.SampleAlert
import app.irlsupreme.ui.theme.FbShape
import app.irlsupreme.ui.theme.LocalFbColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings > Overlays > Customize alerts: your own label, message and picture for each alert kind, with a live preview
 * drawn by the stream's own alert code and a Try button that plays its chime. The card keeps its size class (a raid
 * stays the big slab), so viewers still read how big a moment it is.
 */
@Composable
fun AlertsPage(c: SetupController, nav: Navigator) = PageScaffold("Customize alerts", nav::back) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val styles by AlertStyles.state(ctx).collectAsState()
    var picked by rememberSaveable { mutableStateOf(AlertKind.FOLLOW.name) }
    val kind = AlertKind.valueOf(picked)
    val t = styles[kind] ?: AlertStyleText()
    var pictureError by rememberSaveable { mutableStateOf(false) }
    val player = rememberSoundPlayer()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { pictureError = !withContext(Dispatchers.IO) { AlertStyles.setPicture(ctx, kind, uri) } }
    }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { AlertStyles.apply(ctx) } }

    HelpText("Your own words and picture for each kind. The card keeps its size, so a raid still reads bigger than a follow.")
    KeyChoice("Alert", AlertKind.entries, kind, { it.title }) { picked = it.name; pictureError = false }

    AlertPreview(c, kind)
    FbButton(
        "Try it", {
            val style = AlertText.card(SampleAlert.valueOf(kind.name).alert(0)).style
            player.play(PcmClip(AlertSounds.chime(style, 48_000, true), 48_000, 2))
        },
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp), kind = FbButtonKind.Secondary,
    )

    SectionHeader("Words")
    PlainField(
        t.label, { AlertStyles.setText(ctx, kind, it, t.message) }, "Label",
        supporting = "The small caps word on top. Blank shows “${kind.defaultLabel}”. Up to ${AlertStyles.MAX_LABEL} characters.",
    )
    PlainField(
        t.message, { AlertStyles.setText(ctx, kind, t.label, it) }, "Message",
        supporting = "Shown instead of just the name. Use {name} and {amount}, e.g. “{name} just joined the crew!”. " +
            "Blank shows the name. Up to ${AlertStyles.MAX_MESSAGE} characters.",
    )

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

    if (t.isCustom) {
        Spacer(Modifier.height(16.dp))
        Hairline()
        FbButton("Back to the default", { AlertStyles.reset(ctx, kind) }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), kind = FbButtonKind.Text)
    }
    Footnote(
        null,
        "To see it move on stream, start a Test stream and use Test an alert in Scenes. Viewers never see test alerts.",
        Modifier.padding(top = 16.dp),
    )
}

/** The sample alert of [kind] with your style, drawn where the stream puts it, over the stream's empty ground. */
@Composable
private fun AlertPreview(c: SetupController, kind: AlertKind) {
    val colors = LocalFbColors.current
    val (ow, oh) = c.orientation.size(c.quality)
    val w = if (ow >= oh) 640 else 640 * ow / oh
    val h = if (ow >= oh) 640 * oh / ow else 640
    val version by AlertStyles.version.collectAsState()
    val wide = isWideLayout()
    val bmp by produceState<Bitmap?>(null, kind, version, w, h) {
        delay(80)
        value = withContext(Dispatchers.Default) {
            createBitmap(w, h).also { out ->
                val canvas = Canvas(out)
                canvas.drawColor(StreamPalette.INK)
                BrandDraw.regTicks(canvas, w, h, minOf(w, h) / 100f)
                val card = AlertText.card(SampleAlert.valueOf(kind.name).alert(0))
                val (alert, _) = OverlayComposer.alertBitmap(card, w, h)
                val at = OverlayComposer.alertPosition(w, h, onCard = false, occupied = emptySet(), size = Size(alert.width, alert.height))
                canvas.drawBitmap(alert, at.x.toFloat(), at.y.toFloat(), null)
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
                .semantics { contentDescription = "Preview of a ${kind.title.lowercase()} alert with your words and picture" },
        ) {
            bmp?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
        }
    }
}
