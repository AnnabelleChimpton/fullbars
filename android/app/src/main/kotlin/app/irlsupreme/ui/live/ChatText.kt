package app.irlsupreme.ui.live

import androidx.compose.foundation.Image
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import androidx.compose.foundation.layout.fillMaxSize
import app.irlsupreme.service.ChatLine
import app.irlsupreme.service.EmoteCache
import app.irlsupreme.twitch.ChatFragment

/** A chat line ready to draw: the text with emote placeholders, and the images that fill them. */
internal class ChatText(val text: AnnotatedString, val inline: Map<String, InlineTextContent>)

/** An emote's height in the chat text's em: a touch taller than the letters, as on Twitch. */
private const val EMOTE_EM = 1.3f

/**
 * [l]'s name (in [nameStyle]) and message as one text, with each Twitch emote whose image is ready drawn inline at the
 * text's size; an emote still loading shows its name. Rebuilt when another emote image arrives.
 */
@Composable
internal fun rememberChatText(l: ChatLine, nameStyle: SpanStyle): ChatText {
    val emotes by EmoteCache.version.collectAsState()
    return remember(l.id, l.text, emotes, nameStyle) { chatText(l, nameStyle) }
}

internal fun chatText(l: ChatLine, nameStyle: SpanStyle): ChatText {
    val inline = HashMap<String, InlineTextContent>()
    val text = buildAnnotatedString {
        withStyle(nameStyle) { append(l.name) }
        append(" ")
        if (l.fragments.isEmpty()) { append(l.text); return@buildAnnotatedString }
        for (f in l.fragments) {
            val bmp = (f as? ChatFragment.Emote)?.let { EmoteCache.get(it.id) }
            if (f !is ChatFragment.Emote || bmp == null || bmp.height <= 0) { append(f.text); continue }
            val key = "emote:${f.id}"
            if (key !in inline) {
                val image = bmp.asImageBitmap()
                inline[key] = InlineTextContent(
                    Placeholder((EMOTE_EM * bmp.width / bmp.height).em, EMOTE_EM.em, PlaceholderVerticalAlign.TextCenter),
                ) {
                    Image(image, contentDescription = f.text, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
            appendInlineContent(key, f.text)
        }
    }
    return ChatText(text, inline)
}
