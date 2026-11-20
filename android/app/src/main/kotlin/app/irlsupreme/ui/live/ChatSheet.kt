package app.irlsupreme.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import app.irlsupreme.service.ChatFeed
import app.irlsupreme.service.ChatLine
import app.irlsupreme.ui.Hairline
import app.irlsupreme.ui.theme.FbLiveType
import app.irlsupreme.ui.theme.FbShape
import app.irlsupreme.ui.theme.FbType
import app.irlsupreme.ui.theme.LocalFbColors

/*
 * The Chat sheet: the ticker only has room for the last few lines at two lines each, so a long message or one you
 * missed while walking is read here, in full. Same slot, surface and motion as the Scenes and Mic sheets: attached to
 * the plate, no scrim, closed by Back, a tap on the preview or the ticker again.
 */

/**
 * The status line under the header, or null when there's nothing worth saying: "Chat connected" is the normal case
 * (the ticker hides it too), and a sheet full of messages already says so.
 */
internal fun chatSheetStatus(status: String?): String? = status?.takeIf { it != "Chat connected" }

/**
 * The sheet body: [ChatFeed]'s history, oldest at the top, every message in full. [reduced]: reduce motion, so new
 * lines jump into view instead of scrolling there.
 */
@Composable
internal fun ChatSheet(reduced: Boolean, modifier: Modifier = Modifier) {
    val c = LocalFbColors.current
    val lines by ChatFeed.lines.collectAsState()
    val status by ChatFeed.status.collectAsState()
    // Opens on the newest line.
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (lines.size - 1).coerceAtLeast(0))
    // Follow the newest line unless you scrolled up to read something. Decided each time a scroll settles, not from
    // the list's position alone: a line arriving makes the list scrollable for a moment before it follows, which
    // isn't you scrolling. Back at the bottom, it follows again.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) follow = !list.canScrollForward
        }
    }
    LaunchedEffect(lines.lastOrNull()) {
        if (!follow || lines.isEmpty()) return@LaunchedEffect
        if (reduced) list.scrollToItem(lines.lastIndex) else list.animateScrollToItem(lines.lastIndex)
    }
    Column(
        modifier
            .background(c.s1, RoundedCornerShape(topStart = FbShape.rM, topEnd = FbShape.rM))
            .semantics { paneTitle = "Chat" },
    ) {
        Hairline(color = c.rule)
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
            Text("CHAT", style = FbType.label, color = c.text2, modifier = Modifier.semantics { heading() })
            chatSheetStatus(status)?.let { Text(it, style = FbType.help, color = c.text2) }
        }
        if (lines.isEmpty()) {
            Text(
                "No messages yet.", style = FbType.help, color = c.text2,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
        } else {
            LazyColumn(
                Modifier.weight(1f, fill = false).fillMaxWidth(),
                state = list,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // No item keys: a message redelivered after a chat reconnect would repeat its id, and a repeated
                // key crashes the list.
                items(lines) { SheetLine(it) }
            }
        }
    }
}

/**
 * One message in full (no maxLines), styled like the ticker so a line reads the same in both places: the chatter's
 * colour as a thin tick, the name in text at SemiBold, the message in text2; an accepted mod command gets an outline.
 */
@Composable
private fun SheetLine(l: ChatLine) {
    val c = LocalFbColors.current
    val tick = l.color?.let { runCatching { Color(it.toColorInt()) }.getOrNull() } ?: c.rule
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .then(if (l.isCommand) Modifier.border(1.dp, c.text, FbShape.small) else Modifier),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(tick))
        val t = rememberChatText(l, SpanStyle(color = c.text, fontWeight = FontWeight.SemiBold))
        Text(
            t.text, inlineContent = t.inline,
            style = FbLiveType.L2.copy(fontWeight = FontWeight.Normal), color = c.text2,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        )
    }
}
