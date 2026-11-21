package app.irlsupreme.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.util.Size
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withTranslation
import app.irlsupreme.engine.audio.AlertSounds
import app.irlsupreme.engine.video.AlertTier
import app.irlsupreme.engine.video.BrandDraw
import app.irlsupreme.engine.video.BrandType
import app.irlsupreme.engine.video.CardLayout
import app.irlsupreme.engine.video.Slates
import app.irlsupreme.engine.video.StreamPalette
import kotlin.math.ceil

/**
 * Draws what the phone puts over the stream, in the brand's faces ([BrandType]) and on-stream palette
 * ([StreamPalette]), sized in u = 1% of the short side so portrait and landscape read alike.
 *
 * [compose] draws the overlay layers ([OverlaySettings]) into one transparent bitmap the size of the stream, which the
 * engine blends over the picture: Ink plates with Bone type, so they read over a white sky. Layers in the same corner
 * stack: the image nearest the corner, then the text, then chat. Alerts are not in it: each alert is its own small
 * bitmap ([alertBitmap]) that the engine's alert layer wipes in and out without redrawing the 1080p overlay.
 */
object OverlayComposer {
    /**
     * Null when nothing would be drawn (so the engine can drop the layer entirely).
     *
     * With [card] (a phone-drawn card is up and has live elements, see [LiveElements.plan]) its timers, chat column and
     * socials line are drawn too, in their own spots ([CardZones]), the timers moved down under anything the streamer
     * put in the top right. [userLayers] false leaves out the streamer's own layers (chat, text, image): they asked
     * for them on Live only, and this bitmap is going to be shown on the card.
     */
    fun compose(
        s: OverlaySettings, chat: List<ChatLine>, image: Bitmap?, width: Int, height: Int,
        card: CardOverlayPlan? = null, userLayers: Boolean = true,
    ): Bitmap? {
        val showImage = userLayers && s.imageOn && image != null
        val text = s.text.trim().takeIf { userLayers && s.textOn && it.isNotEmpty() }
        val lines = if (userLayers && s.chat) chat else emptyList()
        val cardChat = if (card?.chat == true) chat else emptyList()
        val cardShows = card != null && (card.timers.isNotEmpty() || cardChat.isNotEmpty() || card.socials != null)
        if (!showImage && text == null && lines.isEmpty() && !cardShows) return null

        val out = createBitmap(width, height)
        val c = Canvas(out)
        val u = OverlayLayout.unit(width, height)
        val gap = 1.5f * u
        val used = HashMap<Corner, Float>()
        fun take(corner: Corner, h: Float) { used[corner] = (used[corner]?.let { it + gap } ?: 0f) + h }

        if (showImage) {
            val img = image!!
            val long = u * 100f * s.imageSize.fraction
            val scale = long / maxOf(img.width, img.height)
            val w = img.width * scale
            val h = img.height * scale
            val box = OverlayLayout.place(s.imageCorner, w, h, width, height, used[s.imageCorner] ?: 0f, gap)
            c.drawBitmap(img, null, RectF(box.x, box.y, box.x + w, box.y + h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            take(s.imageCorner, h)
        }

        if (text != null) {
            val p = paint(BrandType.sc600, 4.5f * u, StreamPalette.BONE)
            val padX = 1.6f * u
            val padY = 1.2f * u
            val maxW = width * 0.6f - 2 * padX
            val shown = TextUtils.ellipsize(text, p, maxW, TextUtils.TruncateAt.END).toString()
            val fm = p.fontMetrics
            val w = p.measureText(shown) + 2 * padX
            val h = (fm.descent - fm.ascent) + 2 * padY
            val box = OverlayLayout.place(s.textCorner, w, h, width, height, used[s.textCorner] ?: 0f, gap)
            plate(c, box, 0.5f * u, StreamPalette.withAlpha(StreamPalette.INK, StreamPalette.CHAT_PLATE_ALPHA))
            c.drawText(shown, box.x + padX, box.y + padY - fm.ascent, p)
            take(s.textCorner, h)
        }

        val chatEnd = if (lines.isNotEmpty()) drawChat(c, s.chatCorner, lines, width, height, u, used[s.chatCorner] ?: 0f, gap) else null
        if (card != null && cardShows) {
            // Below whatever of the streamer's own the card shows in the top right (image, text, then chat).
            val topRight = used[Corner.TOP_RIGHT]?.let { OverlayLayout.margin(width, height) + it }
            val chatTopRight = chatEnd?.takeIf { s.chatCorner == Corner.TOP_RIGHT }
            drawCardElements(c, card, cardChat, width, height, u, listOfNotNull(topRight, chatTopRight).maxOrNull(), gap)
        }
        return out
    }

    /**
     * A card's live elements ([CardOverlayPlan]): the timer block top right, chat in a column under it, the socials
     * line along the bottom. Straight on the card's field in its own colours, or on Ink plates with Bone type when the
     * card has a background, a page or a moved picture under them ([CardLook.plated]). [topRightBottom] is where the
     * streamer's own top right layers end, if any are drawn on this card.
     */
    private fun drawCardElements(
        c: Canvas, plan: CardOverlayPlan, chat: List<ChatLine>, width: Int, height: Int, u: Float, topRightBottom: Float?, gap: Float,
    ) {
        val look = plan.look
        val primary = if (look.bookend) StreamPalette.INK else StreamPalette.BONE
        val secondary = if (look.bookend) StreamPalette.withAlpha(StreamPalette.INK, 0.6f) else StreamPalette.TEXT2
        val divider = if (look.bookend) StreamPalette.withAlpha(StreamPalette.INK, StreamPalette.BOOKEND_TICK_ALPHA) else StreamPalette.TICK
        val right = CardZones.right(width, height)
        var top = CardZones.timerTop(width, height, topRightBottom, gap)
        if (plan.timers.isNotEmpty()) top = drawTimers(c, plan.timers, look.plated, right, top, u, primary, secondary, divider) + 2f * u
        if (chat.isNotEmpty()) {
            val family = plan.card.family
            val floor = CardZones.chatFloor(width, height, look, family, classicHeadlineTop(plan, width, height))
            drawChatColumn(c, chat, right, top, floor, CardZones.chatMaxWidth(width, height, family), u)
        }
        plan.socials?.let { drawSocials(c, it, look.plated, width, height, u, secondary) }
    }

    /**
     * The timer block: one column per timer, left to right, the countdown first. Each is a label (JetBrains Mono 500
     * 2.4u caps, tracked like the card's tag) over its figure (Mono 500 6u, tabular, so a ticking clock never jitters);
     * a countdown at its end shows its words (Archivo SC 600 3.6u) centred in the column instead. A hairline (the
     * ruler's tick colour) parts the columns. The text ends on [right]; a plate hangs 1.6u outside it. Returns the
     * block's bottom.
     */
    private fun drawTimers(
        c: Canvas, timers: List<TimerReadout>, plated: Boolean, right: Float, top: Float, u: Float,
        primary: Int, secondary: Int, divider: Int,
    ): Float {
        val ink = if (plated) StreamPalette.BONE else primary
        val ink2 = if (plated) StreamPalette.TEXT2 else secondary
        val labelP = label(2.4f * u, ink2)
        val figP = paint(BrandType.mono500, 6f * u, ink)
        val wordsP = paint(BrandType.sc600, 3.6f * u, ink)
        val labelCap = capHeight(labelP, "H")
        val figCap = capHeight(figP, "0")
        val wordsCap = capHeight(wordsP, "H")
        val blockH = labelCap + 1.4f * u + figCap
        val colGap = 3f * u
        val widths = timers.map { t ->
            if (t.figure != null) maxOf(labelP.measureText(t.label), figP.measureText(t.figure)) else wordsP.measureText(t.words.orEmpty())
        }
        val total = widths.sum() + colGap * (timers.size - 1)
        val padX = 1.6f * u
        val padY = 1.4f * u
        var x = right - total
        if (plated) {
            plate(c, RectF(x - padX, top - padY, right + padX, top + blockH + padY), 0.5f * u,
                StreamPalette.withAlpha(StreamPalette.INK, StreamPalette.ALERT_PLATE_ALPHA))
        }
        val rule = Paint().apply { color = if (plated) StreamPalette.TICK else divider }
        timers.forEachIndexed { i, t ->
            if (t.figure != null) {
                c.drawText(t.label, x, top + labelCap, labelP)
                c.drawText(t.figure, x, top + blockH, figP)
            } else {
                c.drawText(t.words.orEmpty(), x, top + (blockH + wordsCap) / 2, wordsP)
            }
            if (i < timers.lastIndex) {
                val mid = x + widths[i] + colGap / 2
                c.drawRect(mid - 0.125f * u, top, mid + 0.125f * u, top + blockH, rule)
            }
            x += widths[i] + colGap
        }
        return top + blockH + (if (plated) padY else 0f)
    }

    /**
     * Where CLASSIC's headline starts on [plan]'s card, measured as the engine lays it out (the streamer's words, face
     * and size: [Slates.classicHeadlineTop]), so a short headline leaves chat its room and a long one pushes chat up.
     * Null for the other layouts, the automatic cards and web pages (their floor doesn't depend on it) or when it can't
     * be measured, in which case [CardZones.chatFloor] assumes the highest a headline can reach.
     */
    private fun classicHeadlineTop(plan: CardOverlayPlan, width: Int, height: Int): Float? {
        val look = plan.look
        if (look.web || look.layout != CardLayout.CLASSIC || plan.card.family == Slates.Family.AUTOMATIC) return null
        if (look.headline == null) return null
        return runCatching { Slates.classicHeadlineTop(width, height, plan.card) }.getOrNull()
    }

    /**
     * Chat on a card that asks for it: the same chat lines as the chat layer, stacked down from [top] with the newest
     * first and their text ending on [right], no wider than [maxW]. Lines that would cross [floor] are left out, oldest
     * first, so chat never reaches the card's words; when not even two one-line rows fit, the column is left out
     * altogether rather than show a lone line that keeps changing.
     */
    private fun drawChatColumn(c: Canvas, lines: List<ChatLine>, right: Float, top: Float, floor: Float, maxW: Float, u: Float) {
        if (!CardZones.chatFits(top, floor, chatOneLineHeight(u), CHAT_LINE_GAP_U * u)) return
        val rows = chatRows(lines, u, maxW + CHAT_PAD_RIGHT_U * u)
        val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = StreamPalette.withAlpha(StreamPalette.INK, StreamPalette.CHAT_PLATE_ALPHA) }
        val tickPaint = Paint().apply { style = Paint.Style.FILL }
        var y = top
        for (row in rows.asReversed()) {
            if (y + row.h > floor) break
            drawChatRow(c, row, right + CHAT_PAD_RIGHT_U * u - row.w, y, u, platePaint, tickPaint)
            y += row.h + CHAT_LINE_GAP_U * u
        }
    }

    /**
     * The socials line on the card's 8u margin above the ruler (Archivo SC 500 3.2u: it's words, not figures), in the
     * card's secondary colour like its sub line, or Bone on an Ink plate over a background. Cut with an ellipsis to the
     * card's text width.
     */
    private fun drawSocials(c: Canvas, text: String, plated: Boolean, width: Int, height: Int, u: Float, secondary: Int) {
        val p = paint(BrandType.sc500, 3.2f * u, if (plated) StreamPalette.BONE else secondary)
        val x = CardZones.left(width, height)
        val base = CardZones.socialsBaseline(width, height)
        val shown = fit(text, p, width - 2 * x)
        if (shown.isEmpty()) return
        if (plated) {
            val pad = 1.2f * u
            val fm = p.fontMetrics
            plate(c, RectF(x - pad, base + fm.ascent * 0.8f - 0.8f * u, x + p.measureText(shown) + pad, base + fm.descent + 0.8f * u),
                0.5f * u, StreamPalette.withAlpha(StreamPalette.INK, StreamPalette.CHAT_PLATE_ALPHA))
        }
        c.drawText(shown, x, base, p)
    }

    private fun capHeight(p: TextPaint, sample: String): Float {
        val r = Rect()
        p.getTextBounds(sample, 0, sample.length, r)
        return -r.top.toFloat()
    }

    /**
     * The chat layer. Each line is an Ink 88% plate (0.4u corners, at most 60u wide) with the name in Bone
     * Archivo SC 600 and the message in #C2C8CF SC 400, both 3.4u. The chatter's own colour is a 0.6u tick at the
     * plate's left instead of the name's colour: a dark blue name measured 4.1:1 on the plate, while Bone holds 12.7:1
     * and the message 8.5:1 for every chatter, so nothing has to lighten anyone's colour.
     */
    private fun drawChat(c: Canvas, corner: Corner, lines: List<ChatLine>, width: Int, height: Int, u: Float, used: Float, gap: Float): Float? {
        val plateMax = minOf(CHAT_MAX_W_U * u, width - 2 * OverlayLayout.margin(width, height))
        val rows = chatRows(lines, u, plateMax)
        val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = StreamPalette.withAlpha(StreamPalette.INK, StreamPalette.CHAT_PLATE_ALPHA) }
        val tickPaint = Paint().apply { style = Paint.Style.FILL }
        // Newest nearest the corner's edge: from a bottom corner the newest line is lowest.
        val order = if (corner.bottom) rows.asReversed() else rows
        var taken = used
        var first = true
        var end: Float? = null
        for (row in order) {
            // The usual gap from the layers before chat; chat's own lines sit closer, as one block.
            val g = if (first) gap else CHAT_LINE_GAP_U * u
            val box = OverlayLayout.place(corner, row.w, row.h, width, height, taken, g)
            drawChatRow(c, row, box.x, box.y, u, platePaint, tickPaint)
            taken = (if (taken > 0f) taken + g else 0f) + row.h
            first = false
            end = if (corner.bottom) box.y else box.y + box.h
        }
        return end
    }

    /** A one-line chat plate's height (see [chatRows]: one line of text, no font padding, plus the plate's padding). */
    private fun chatOneLineHeight(u: Float): Float {
        val fm = paint(BrandType.sc400, 3.4f * u, StreamPalette.TEXT2).fontMetricsInt
        return (fm.descent - fm.ascent) + 2 * CHAT_PAD_TOP_U * u
    }

    /** One chat line laid out: its plate is [w] x [h] px. */
    private class ChatRow(val line: ChatLine, val layout: StaticLayout, val w: Float, val h: Float)

    /** [lines] laid out as chat plates no wider than [plateMax] px, each at most two lines of text. */
    private fun chatRows(lines: List<ChatLine>, u: Float, plateMax: Float): List<ChatRow> {
        val p = paint(BrandType.sc400, 3.4f * u, StreamPalette.TEXT2)
        val chrome = (CHAT_PAD_LEFT_U + CHAT_TICK_W_U + CHAT_TICK_GAP_U + CHAT_PAD_RIGHT_U) * u
        val textMax = (plateMax - chrome).toInt().coerceAtLeast(1)
        return lines.map { l ->
            val sb = SpannableStringBuilder().apply {
                append(l.name)
                setSpan(TypefaceSpan(BrandType.sc600), 0, l.name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(StreamPalette.BONE), 0, l.name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append(" ")
                // A notice (a watch streak) reads as part of the name, in the same weight, then the viewer's words.
                l.notice?.let { n ->
                    val start = length
                    append(n)
                    setSpan(TypefaceSpan(BrandType.sc600), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(ForegroundColorSpan(StreamPalette.BONE), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (l.text.isNotBlank()) append(CHAT_NOTICE_SEPARATOR)
                }
                if (l.notice == null || l.text.isNotBlank()) appendMessage(this, l, p.textSize)
            }
            val lay = StaticLayout.Builder.obtain(sb, 0, sb.length, p, textMax)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            val textW = (0 until lay.lineCount).maxOf { lay.getLineWidth(it) }
            ChatRow(l, lay, chrome + textW, lay.height + 2 * CHAT_PAD_TOP_U * u)
        }
    }

    /** Draws [row]'s plate, the chatter's colour tick and the text with the plate's top left at ([x], [y]). */
    private fun drawChatRow(c: Canvas, row: ChatRow, x: Float, y: Float, u: Float, platePaint: Paint, tickPaint: Paint) {
        val padTop = CHAT_PAD_TOP_U * u
        val padLeft = CHAT_PAD_LEFT_U * u
        val tickW = CHAT_TICK_W_U * u
        c.drawRoundRect(RectF(x, y, x + row.w, y + row.h), 0.4f * u, 0.4f * u, platePaint)
        tickPaint.color = tickColor(row.line.color)
        c.drawRect(x + padLeft, y + padTop, x + padLeft + tickW, y + row.h - padTop, tickPaint)
        c.withTranslation(x + padLeft + tickW + CHAT_TICK_GAP_U * u, y + padTop) { row.layout.draw(this) }
    }

    // The chat line's chrome, in u.
    private const val CHAT_PAD_TOP_U = 0.9f
    private const val CHAT_PAD_LEFT_U = 1.2f
    private const val CHAT_PAD_RIGHT_U = 1.6f
    private const val CHAT_TICK_W_U = 0.6f
    private const val CHAT_TICK_GAP_U = 1.2f
    private const val CHAT_LINE_GAP_U = 0.8f

    /**
     * The message, with each Twitch emote whose image is ready drawn as that image at the text's height (centred on the
     * line); emotes still loading, and messages without emotes, are plain text.
     */
    private fun appendMessage(sb: SpannableStringBuilder, l: ChatLine, textSize: Float) {
        if (l.fragments.isEmpty()) { sb.append(l.text); return }
        for (f in l.fragments) {
            val bmp = (f as? app.irlsupreme.twitch.ChatFragment.Emote)?.let { EmoteCache.get(it.id) }
            if (bmp == null || bmp.height <= 0) { sb.append(f.text); continue }
            val h = textSize * EMOTE_HEIGHT
            val w = h * bmp.width / bmp.height
            val d = android.graphics.drawable.BitmapDrawable(null, bmp).apply { setBounds(0, 0, w.toInt(), h.toInt()) }
            val start = sb.length
            sb.append(f.text.ifEmpty { "\uFFFC" })
            sb.setSpan(android.text.style.ImageSpan(d, android.text.style.DynamicDrawableSpan.ALIGN_CENTER), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** An emote's height as a multiple of the chat text size: a touch taller than the letters, like on Twitch. */
    private const val EMOTE_HEIGHT = 1.3f

    /** The chatter's Twitch colour, as given; one who never chose a colour gets the neutral tick grey. */
    private fun tickColor(hex: String?): Int =
        hex?.let { runCatching { it.toColorInt() }.getOrNull() } ?: StreamPalette.TICK

    /**
     * One alert, drawn once into its own bitmap, no bigger than [OverlayLayout.ALERT_MAX_W] x
     * [OverlayLayout.ALERT_MAX_H] of the frame. Loudness shows by size and inversion, never hue:
     * - QUIET (a follow): an Ink 92% plate, one line, `FOLLOW` in mono then the name.
     * - MEDIUM (subs, gifts, cheers, tips): the same plate, two lines, the amount in mono on the right.
     * - LOUD (a raid): a Bone slab with a 0.35u Ink keyline (19.4:1 against a white sky) and the mark.
     * The foot of each keeps room for the engine's 12-step time-left rule, which it draws over this bitmap.
     */
    fun alertBitmap(card: AlertCard, frameW: Int, frameH: Int): Pair<Bitmap, AlertTier> {
        val tier = card.tier
        val maxW = frameW * OverlayLayout.ALERT_MAX_W
        val maxH = frameH * OverlayLayout.ALERT_MAX_H
        var u = OverlayLayout.unit(frameW, frameH)
        var size = drawAlert(null, card, tier, u, maxW)
        // In landscape a raid's slab is close to a quarter of the height: shrink the whole thing rather than clip it.
        if (size.second > maxH) {
            u *= maxH / size.second
            size = drawAlert(null, card, tier, u, maxW)
        }
        val bmp = createBitmap(ceil(size.first).toInt().coerceAtLeast(1), ceil(size.second).toInt().coerceAtLeast(1))
        drawAlert(Canvas(bmp), card, tier, u, maxW)
        return bmp to tier
    }

    /**
     * Where an alert of [size] goes on a [frameW] x [frameH] stream ([OverlayLayout.alertBox]): left on the title-safe
     * margin, 58% down on camera and 30% on a full-frame card ([onCard]), mirrored right when [occupied] has the
     * lower-left. Pixels, top-left.
     */
    fun alertPosition(frameW: Int, frameH: Int, onCard: Boolean, occupied: Set<Corner>, size: Size): Point {
        val b = OverlayLayout.alertBox(frameW, frameH, onCard, occupied, size.width.toFloat(), size.height.toFloat())
        return Point(Math.round(b.x), Math.round(b.y))
    }

    /** Measures (with [c] null) or draws one alert; returns its width and height in px. */
    private fun drawAlert(c: Canvas?, a: AlertCard, tier: AlertTier, u: Float, maxW: Float): Pair<Float, Float> = when (tier) {
        AlertTier.QUIET -> quiet(c, a, u, maxW)
        AlertTier.MEDIUM -> medium(c, a, u, maxW)
        AlertTier.LOUD -> loud(c, a, u, maxW)
    }

    private val plateInk get() = StreamPalette.withAlpha(StreamPalette.INK, StreamPalette.ALERT_PLATE_ALPHA)

    /** `FOLLOW` (mono 2.5u, text2) then the name (SC 600 4.2u, Bone) on one baseline, 3u padding. */
    private fun quiet(c: Canvas?, a: AlertCard, u: Float, maxW: Float): Pair<Float, Float> {
        val pad = 3f * u
        val labelP = label(2.5f * u, StreamPalette.TEXT2)
        val nameP = paint(BrandType.sc600, 4.2f * u, StreamPalette.BONE)
        val gap = 2f * u
        val baseline = pad + ascender(nameP)
        val h = baseline + nameP.fontMetrics.descent + pad
        val lead = pictureLead(a, h - 2 * pad, u)
        val labelW = labelP.measureText(a.label)
        val line = a.message ?: a.name
        val nameW = maxW - 2 * pad - lead - labelW - gap
        val name = fitShrink(line, nameP, nameW, a.message != null)
        val w = pad + lead + labelW + gap + nameP.measureText(name) + pad
        if (c != null) {
            plate(c, RectF(0f, 0f, w, h), 0.5f * u, plateInk)
            drawPicture(c, a, pad, pad, h - 2 * pad, u)
            c.drawText(a.label, pad + lead, baseline, labelP)
            c.drawText(name, pad + lead + labelW + gap, baseline, nameP)
        }
        return w to h
    }

    /**
     * Label (mono 2.5u, text2) with the amount (mono 5.2u, Bone) right-aligned on the same baseline, then the name
     * (Archivo C 800 6.4u, Bone). At least 44u wide so the amount has somewhere to sit right.
     */
    private fun medium(c: Canvas?, a: AlertCard, u: Float, maxW: Float): Pair<Float, Float> {
        val pad = 3f * u
        val labelP = label(2.5f * u, StreamPalette.TEXT2)
        val amountP = paint(BrandType.mono500, 5.2f * u, StreamPalette.BONE)
        val nameP = paint(BrandType.c800, 6.4f * u, StreamPalette.BONE)
        val row1 = pad + (if (a.amount != null) ascender(amountP) else ascender(labelP))
        val row2 = row1 + 1.6f * u + ascender(nameP)
        val h = row2 + nameP.fontMetrics.descent + pad
        val lead = pictureLead(a, h - 2 * pad, u)
        val inner = maxW - 2 * pad - lead
        val labelW = labelP.measureText(a.label)
        val gap = 3f * u
        val amount = a.amount?.let { fit(it, amountP, inner - labelW - gap) }
        val amountW = amount?.let { amountP.measureText(it) } ?: 0f
        val name = fitShrink(a.message ?: a.name, nameP, inner, a.message != null)
        val contentW = maxOf(44f * u - 2 * pad, labelW + (if (amount != null) gap + amountW else 0f), nameP.measureText(name))
        val w = minOf(maxW, contentW + 2 * pad + lead)
        if (c != null) {
            plate(c, RectF(0f, 0f, w, h), 0.5f * u, plateInk)
            drawPicture(c, a, pad, pad, h - 2 * pad, u)
            c.drawText(a.label, pad + lead, row1, labelP)
            if (amount != null) c.drawText(amount, w - pad - amountW, row1, amountP)
            c.drawText(name, pad + lead, row2, nameP)
        }
        return w to h
    }

    /**
     * The raid slab: `RAID` (Archivo C 700 3.2u, tracked) with the mark (4u, all bars full) on the right, the name
     * (Archivo XC 800 10u), then the count (mono 4.6u) and "viewers" (SC 500). All Ink on Bone, inside a 0.35u Ink
     * keyline so the slab's edge holds against a white sky. At least 56u wide.
     */
    private fun loud(c: Canvas?, a: AlertCard, u: Float, maxW: Float): Pair<Float, Float> {
        val padT = 2.4f * u
        val padR = 3.4f * u
        val padB = 3.2f * u
        val padL = 3f * u
        val labelP = paint(BrandType.c700, 3.2f * u, StreamPalette.INK).apply { letterSpacing = 0.2f }
        val nameP = paint(BrandType.xc800, 10f * u, StreamPalette.INK)
        val countP = paint(BrandType.mono500, 4.6f * u, StreamPalette.INK)
        val unitP = paint(BrandType.sc500, 3.4f * u, StreamPalette.INK)
        val markSize = 4f * u
        // Heights first (they don't depend on the text's width), so a picture's width is known before the text fits.
        val labelCap = ascender(labelP)
        val row1Top = padT
        val row2Pre = row1Top + maxOf(labelCap, markSize) + 1.6f * u + ascender(nameP)
        val hasRow3 = a.amount != null || a.amountUnit != null
        val row3Pre = if (hasRow3) row2Pre + 0.22f * nameP.textSize + 1.4f * u + ascender(countP) else row2Pre
        val hPre = row3Pre + (if (row3Pre == row2Pre) nameP.fontMetrics.descent else 0f) + padB
        val lead = pictureLead(a, hPre - padT - padB, u)
        val inner = maxW - padL - padR - lead
        val name = fitShrink(a.message ?: a.name, nameP, inner, a.message != null)
        val count = a.amount
        val countW = count?.let { countP.measureText(it) } ?: 0f
        val unitGap = 1.4f * u
        val unitW = a.amountUnit?.let { unitP.measureText(it) } ?: 0f
        val row3W = countW + (if (a.amountUnit != null) unitGap + unitW else 0f)
        val row1W = labelP.measureText(a.label) + 3f * u + markSize
        val contentW = maxOf(56f * u - padL - padR, row1W, nameP.measureText(name), row3W)
        val w = minOf(maxW, contentW + padL + padR + lead)
        val row1Base = row1Top + maxOf(labelCap, markSize) / 2 + labelCap / 2
        val row2 = row1Top + maxOf(labelCap, markSize) + 1.6f * u + ascender(nameP)
        // Room for the name's descenders before the count line, as the name's tight line height allows.
        val row3 = if (count != null || a.amountUnit != null) row2 + 0.22f * nameP.textSize + 1.4f * u + ascender(countP) else row2
        val h = row3 + (if (row3 == row2) nameP.fontMetrics.descent else 0f) + padB
        if (c != null) {
            val keyline = StreamPalette.SLAB_KEYLINE_U * u
            val r = 0.5f * u
            plate(c, RectF(0f, 0f, w, h), r, StreamPalette.INK)
            plate(c, RectF(keyline, keyline, w - keyline, h - keyline), (r - keyline).coerceAtLeast(0f), StreamPalette.BONE)
            drawPicture(c, a, padL, padT, h - padT - padB, u)
            c.drawText(a.label, padL + lead, row1Base, labelP)
            val markTop = row1Top + (maxOf(labelCap, markSize) - markSize) / 2
            BrandDraw.mark(c, w - padR - markSize, markTop, markSize, intArrayOf(BrandDraw.BAR_FULL, BrandDraw.BAR_FULL, BrandDraw.BAR_FULL), StreamPalette.INK)
            c.drawText(name, padL + lead, row2, nameP)
            if (count != null) c.drawText(count, padL + lead, row3, countP)
            a.amountUnit?.let { c.drawText(it, padL + lead + countW + (if (count != null) unitGap else 0f), row3, unitP) }
        }
        return w to h
    }

    private fun paint(face: Typeface, size: Float, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face
        textSize = size
        this.color = color
        // Figures that change keep their width (tabular), as everywhere else in the brand.
        fontFeatureSettings = "'tnum' 1, 'kern' 1"
    }

    /** A tier label: JetBrains Mono 500, caps, tracked +0.12em like every stream tag. */
    private fun label(size: Float, color: Int) = paint(BrandType.mono500, size, color).apply { letterSpacing = 0.12f }

    /** Height above the baseline of tall letters, so tight rows line up on what's actually drawn, not the line box. */
    private fun ascender(p: TextPaint): Float {
        val r = Rect()
        p.getTextBounds("Hdlk0", 0, 5, r)
        return -r.top.toFloat()
    }

    /**
     * The streamer's picture's width plus its gap, for a picture [height] px tall (wider pictures are capped at 1.6x
     * their height so the words keep their room); 0 without one.
     */
    private fun pictureLead(a: AlertCard, height: Float, u: Float): Float {
        val p = a.picture ?: return 0f
        if (p.height <= 0 || height <= 0f) return 0f
        return minOf(height * p.width / p.height, height * 1.6f) + 2f * u
    }

    /** Draws the streamer's picture at ([x], [y]), [height] tall, its shape kept, with the brand's small corners. */
    private fun drawPicture(c: Canvas, a: AlertCard, x: Float, y: Float, height: Float, u: Float) {
        val p = a.picture ?: return
        if (p.height <= 0 || height <= 0f) return
        val w = minOf(height * p.width / p.height, height * 1.6f)
        val r = RectF(x, y, x + w, y + height)
        val save = c.save()
        c.clipPath(android.graphics.Path().apply { addRoundRect(r, 0.5f * u, 0.5f * u, android.graphics.Path.Direction.CW) })
        c.drawBitmap(p, null, r, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        c.restoreToCount(save)
    }

    /**
     * Fits [text] into [width]: a custom message ([shrink]) first gets smaller, down to 60% of its size, so a whole
     * sentence shows; then anything still too long is cut with an ellipsis. Changes [p]'s size when it shrinks.
     */
    private fun fitShrink(text: String, p: TextPaint, width: Float, shrink: Boolean): String {
        if (shrink) {
            val full = p.textSize
            while (p.measureText(text) > width && p.textSize > full * 0.6f) p.textSize *= 0.94f
        }
        return fit(text, p, width)
    }

    private fun fit(text: String, p: TextPaint, width: Float): String =
        TextUtils.ellipsize(text, p, width.coerceAtLeast(0f), TextUtils.TruncateAt.END).toString()

    private fun plate(c: Canvas, r: RectF, radius: Float, color: Int) =
        c.drawRoundRect(r, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })

    private fun plate(c: Canvas, b: OverlayLayout.Box, radius: Float, color: Int) =
        plate(c, RectF(b.x, b.y, b.x + b.w, b.y + b.h), radius, color)

    /** A chat line is never wider than this many u, so a long message wraps instead of crossing the picture. */
    const val CHAT_MAX_W_U = 60f

    /** Sample chat for the settings preview. */
    val sampleChat = listOf(
        ChatLine("s1", "Sam", "#1E90FF", "this view is unreal", false, 0),
        ChatLine("s2", "kit_irl", "#FF4500", "where are you right now?", false, 0),
        ChatLine("s3", "Moe", null, "hi chat", false, 0),
    )

    /** A sample follow for the settings preview, drawn with [alertBitmap] like the real thing. */
    val sampleAlert = AlertCard("sample", "FOLLOW", "sam_rides", null, AlertSounds.Style.SOFT)
}
