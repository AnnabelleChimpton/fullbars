package app.irlsupreme.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.irlsupreme.engine.SessionSummary
import app.irlsupreme.service.UnexpectedEnd
import app.irlsupreme.ui.theme.FbMotion
import app.irlsupreme.ui.theme.FbType
import app.irlsupreme.ui.theme.JetMono
import app.irlsupreme.ui.theme.LocalFbColors
import app.irlsupreme.ui.theme.LocalHealthColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// --- Preflight readings and the verdict ----------------------------------------------------------------------------

internal enum class Level { GOOD, WARN, BAD }

/** [suggest720]: the fix is to stream at 720p, offered as one tap while the quality is 1080p. */
internal data class Check(val level: Level, val text: String, val suggest720: Boolean = false)

/**
 * What the phone measured, gathered off the main thread by [runPreflight]. Kept apart from the judgement
 * ([assessPreflight]) so the rules are tested without a phone.
 * [upKbps] is Android's own estimate for the active network (null when it gives none); [ingestMs] the connect time to
 * Twitch's ingest or the BRB server (null when unreachable); [battery] is −1 when unknown; [thermal] is a
 * PowerManager.THERMAL_STATUS_* value; [brbPort] is set when the target is the paired BRB server.
 */
internal data class PreflightReadings(
    val online: Boolean,
    val kind: String,
    val upKbps: Int?,
    val ingestMs: Long?,
    val udpBlocked: Boolean,
    val battery: Int,
    val charging: Boolean,
    val thermal: Int,
    val brbPort: Int?,
)

/**
 * One cell of a readout row: a caps [label] over a mono [figure] and its [unit]. [alert] gives the value its health
 * colour (hot, or a battery under 15%), the only hue Home's readout may carry. [spoken] is what TalkBack reads, since
 * "UPLINK 9.4 Mb/s" spelled out letter by letter helps nobody.
 */
internal data class ReadoutCell(
    val label: String,
    val figure: String,
    val unit: String? = null,
    val alert: Boolean = false,
    val spoken: String,
)

/** The assessed preflight: the worst finding as [verdict], the 4-cell readout, and when it was taken. */
internal data class Preflight(
    val verdict: Check,
    val readings: PreflightReadings,
    val readout: List<ReadoutCell>,
    val checkedAtMs: Long,
) {
    /** The connection itself works: online, and the target answered (on UDP too, for a BRB server). */
    val connectionOk: Boolean get() = readings.online && readings.ingestMs != null && !readings.udpBlocked
}

/** A battery under this share takes the health colour in the readout. */
internal const val LOW_BATTERY_ALERT = 15

/** Rung 1 of the engine's thermal ladder starts at MODERATE (StreamEngine.rungFor). */
private const val THERMAL_RUNG_1 = PowerManager.THERMAL_STATUS_MODERATE

/**
 * Judges [r] for [quality]: the verdict (worst finding first, in plain words) and the UPLINK · SERVER · BATTERY · TEMP
 * readout. Pure; [locale] only picks the decimal mark.
 */
internal fun assessPreflight(r: PreflightReadings, quality: Quality, checkedAtMs: Long, locale: Locale = Locale.getDefault()): Preflight {
    val needKbps = quality.bitrate / 1000 + 128
    val target = if (r.brbPort != null) "your BRB server" else "Twitch"
    val lowBattery = r.battery in 0..19 && !r.charging
    val verdict = when {
        !r.online -> Check(Level.BAD, "No internet connection")
        r.ingestMs == null -> Check(Level.BAD, "Can't reach $target right now")
        r.udpBlocked -> Check(Level.BAD, "UDP ${r.brbPort} is blocked on your BRB server")
        r.thermal >= PowerManager.THERMAL_STATUS_SEVERE -> Check(Level.WARN, "Ready, but the phone is hot", suggest720 = true)
        lowBattery -> Check(Level.WARN, "Ready, but the battery is low")
        r.upKbps != null && r.upKbps < needKbps * 1.3 && quality == Quality.HD1080 ->
            Check(Level.WARN, "Weak upload · 720p recommended", suggest720 = true)
        else -> Check(Level.GOOD, "Ready for ${quality.title}")
    }
    val up = r.upKbps?.takeIf { r.online }
    val readout = listOf(
        ReadoutCell(
            "Uplink", up?.let { String.format(locale, "%.1f", it / 1000.0) } ?: "–", if (up != null) "Mb/s" else null,
            spoken = "Uplink, " + (up?.let { String.format(locale, "%.1f", it / 1000.0) + " megabits per second" } ?: "unknown"),
        ),
        ReadoutCell(
            "Server", r.ingestMs?.takeIf { r.online }?.toString() ?: "–", if (r.online && r.ingestMs != null) "ms" else null,
            spoken = "Server, " + (r.ingestMs?.takeIf { r.online }?.let { "$it milliseconds" } ?: "not reached"),
        ),
        ReadoutCell(
            "Battery", if (r.battery >= 0) "${r.battery}" else "–", if (r.battery >= 0) "%" else null,
            alert = r.battery in 0 until LOW_BATTERY_ALERT,
            spoken = "Battery, " + (if (r.battery >= 0) "${r.battery} percent" else "unknown") + if (r.charging) ", charging" else "",
        ),
        when {
            r.thermal >= PowerManager.THERMAL_STATUS_SEVERE -> ReadoutCell("Temp", "HOT", alert = true, spoken = "Temperature, hot")
            r.thermal >= THERMAL_RUNG_1 -> ReadoutCell("Temp", "WARM", alert = true, spoken = "Temperature, warm")
            else -> ReadoutCell("Temp", "OK", spoken = "Temperature, OK")
        },
    )
    return Preflight(verdict, r, readout, checkedAtMs)
}

/**
 * How many readout cells share a row. Four cells of caps labels and mono figures don't fit a 360dp phone at large
 * font scales (BATTERY alone outgrows a quarter), so from 150% they go two by two instead of truncating.
 */
internal fun readoutColumns(cells: Int, fontScale: Float): Int = if (fontScale >= 1.5f && cells > 2) 2 else cells

/** "checked 12 s ago", the Preflight stamp; minutes after the first one. */
internal fun checkedAgo(checkedAtMs: Long, nowMs: Long): String {
    val s = ((nowMs - checkedAtMs) / 1000).coerceAtLeast(0)
    return if (s < 60) "checked $s s ago" else "checked ${s / 60} min ago"
}

/**
 * Runs the preflight every 30 s while Home is on screen (not in the background) and when
 * the quality or target changes. With Combine connections on it reports the paired BRB server ([brb]), including a
 * blocked UDP port, from SetupController's own probe so the Connection row and the verdict agree; otherwise it opens a
 * TCP connection to Twitch's ingest ([host]:[port]). Null until the first result.
 */
@Composable
internal fun rememberPreflight(quality: Quality, host: String, port: Int, brb: BrbCheck?): Preflight? {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var result by remember { mutableStateOf<Preflight?>(null) }
    LaunchedEffect(quality, host, port, brb) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                result = withContext(Dispatchers.IO) { runPreflight(ctx, quality, host, port, brb) }
                delay(30_000)
            }
        }
    }
    return result
}

/**
 * The paired BRB server's health, from SetupController's own probe (TLS control API, then SRTLA on UDP), so the
 * preflight and the Home Connection row read the same answer. [checked] is false until the first probe for this pairing.
 */
data class BrbCheck(val checked: Boolean, val reachable: Boolean, val udpBlocked: Boolean, val ms: Long?, val port: Int)

/** Null while the BRB server's first probe is still out: Home keeps saying "Checking your setup…". */
private fun runPreflight(ctx: Context, quality: Quality, host: String, port: Int, brb: BrbCheck?): Preflight? {
    if (brb != null && !brb.checked) return null
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
    val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    val kind = when {
        caps == null -> "No connection"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi‑Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Connected"
    }
    val ingestMs: Long? = when {
        !online -> null
        brb != null -> if (brb.reachable) brb.ms ?: 0 else null
        else -> runCatching {
            val t0 = System.nanoTime()
            Socket().use { it.connect(InetSocketAddress(host, port), 4_000) }
            (System.nanoTime() - t0) / 1_000_000
        }.getOrNull()
    }
    val battery = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = battery?.let {
        it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
    } ?: -1
    val readings = PreflightReadings(
        online = online, kind = kind,
        upKbps = caps?.linkUpstreamBandwidthKbps?.takeIf { it > 0 },
        ingestMs = ingestMs,
        udpBlocked = online && brb != null && brb.reachable && brb.udpBlocked,
        battery = level,
        charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
        thermal = ctx.getSystemService(PowerManager::class.java).currentThermalStatus,
        brbPort = brb?.port,
    )
    return assessPreflight(readings, quality, System.currentTimeMillis())
}

/**
 * "Ready for 1080p": Archivo Condensed 700 at 19sp, between a row and a CTA. A getter, so this file's pure
 * functions load in JVM unit tests without initialising the font roles.
 */
private val VerdictStyle get() = FbType.cta.copy(fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 24.sp)

/**
 * The verdict line and the readout under it. The filled square shows only for [ReadyVerdict.ready], which
 * readyVerdict grants only when nothing blocks going live and every reading is good; otherwise the line is the reason,
 * with no mark. [onUse720] is the one-tap fix for a weak upload or a hot phone at 1080p.
 */
@Composable
internal fun VerdictBlock(verdict: ReadyVerdict, preflight: Preflight?, onUse720: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalFbColors.current
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.Top,
        ) {
            if (verdict.ready) {
                StateSquare(StateMark.VERIFIED, Modifier.padding(top = 5.dp), size = 14.dp)
                Spacer(Modifier.width(12.dp))
            }
            Text(verdict.text, style = VerdictStyle, color = c.text)
        }
        if (verdict.suggest720) {
            FbButton("Use 720p", onClick = onUse720, kind = FbButtonKind.Text, modifier = Modifier.padding(start = 16.dp))
        }
        if (preflight != null) Readout(preflight.readout, Modifier.padding(horizontal = 16.dp))
    }
}

/**
 * A row of instrument cells split by hairlines: the preflight readout and the recap stats. Labels are caps in text2,
 * figures mono; only an [ReadoutCell.alert] value takes [alertColor]. Goes two by two at large font scales.
 */
@Composable
internal fun Readout(cells: List<ReadoutCell>, modifier: Modifier = Modifier, alertColor: Color = LocalHealthColors.current.weak) {
    val c = LocalFbColors.current
    val cols = readoutColumns(cells.size, LocalDensity.current.fontScale)
    Column(modifier.fillMaxWidth()) {
        Hairline()
        cells.chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                for (i in 0 until cols) {
                    if (i > 0) Box(Modifier.fillMaxHeight().width(1.dp).background(c.rule))
                    val cell = row.getOrNull(i)
                    if (cell == null) {
                        Spacer(Modifier.weight(1f))
                        continue
                    }
                    Column(
                        Modifier.weight(1f).padding(start = if (i == 0) 0.dp else 12.dp, end = 8.dp, top = 10.dp, bottom = 12.dp)
                            .clearAndSetSemantics { contentDescription = cell.spoken },
                    ) {
                        Text(cell.label.uppercase(), style = FbType.label, color = c.text2)
                        val valueColor = if (cell.alert) alertColor else c.text
                        Text(
                            buildAnnotatedString {
                                append(cell.figure)
                                if (cell.unit != null) withStyle(SpanStyle(color = c.text2, fontSize = 12.sp)) { append(" " + cell.unit) }
                            },
                            style = FbType.value, color = valueColor, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            Hairline()
        }
    }
}

// --- Recap ---------------------------------------------------------------------------------------------------------

/**
 * What the drops meant for viewers. Through the BRB server they saw the BRB screen and the stream stayed up; on a
 * direct stream each drop was a real disconnect from Twitch (a pause, or the stream ending for viewers), so the recap
 * doesn't take credit for it.
 */
internal fun recapDropLines(drops: Int, bonded: Boolean): List<String> = when {
    drops == 0 -> listOf("No connection drops")
    bonded -> listOf(if (drops == 1) "We kept you live through 1 connection drop" else "We kept you live through $drops connection drops")
    else -> listOf(
        (if (drops == 1) "Reconnected 1 time" else "Reconnected $drops times") + ". Viewers saw a pause each time.",
        "With a BRB server, viewers see your BRB screen instead.",
    )
}

/**
 * The recap verdict, led by the mark: "Held the whole way." when nothing dropped; otherwise the honest
 * first line of [recapDropLines], as a sentence. The second line of a direct stream's drops stays as help under it.
 */
internal fun recapVerdict(drops: Int, bonded: Boolean): String =
    if (drops == 0) "Held the whole way." else recapDropLines(drops, bonded).first().let { if (it.endsWith(".")) it else "$it." }

/**
 * The engine's last error in plain words for the recap, worded like the live screen's ([plainFailure]) so both screens
 * say the same thing, as the end of a sentence ("The stream didn't start: can't reach your BRB server."). Falls back to
 * the recap's own wording where the live screen would say "Tap for details" (there's nothing to tap here).
 */
internal fun recapFailure(lastError: String, bonded: Boolean): String {
    val e = lastError.lowercase()
    if ("rejected" !in e) plainFailure(lastError, bonded)?.takeIf { it != "Tap for details" }?.let { p ->
        return p.replaceFirstChar { it.lowercase() } + "."
    }
    return when {
        "rejected" in e && bonded -> "your BRB server refused the stream. Pair it again."
        "rejected" in e || "auth" in e || "key" in e -> "Twitch refused the stream key."
        "brb" in e || "no connection" in e -> "can't reach your BRB server."
        "timeout" in e || "timed out" in e || "refused" in e || "unreachable" in e || "resolve" in e || "host" in e ->
            if (bonded) "can't reach your BRB server. Check the signal." else "can't reach Twitch. Check the signal."
        "microphone" in e || "audiorecord" in e -> "the microphone wasn't available."
        else -> "something went wrong. Try again."
    }
}

/**
 * Data sent, as a streamer thinks of it: whole MB under 1 GB ("48 MB"), then GB to one place ("1.4 GB"). Never
 * "0.00 GB" for a short test. Decimal units, as phone data plans count them.
 */
internal fun recapBytes(bytes: Long, locale: Locale = Locale.getDefault()): String = when {
    bytes <= 0 -> "0 MB"
    bytes < 1_000_000 -> "<1 MB"
    bytes < 1_000_000_000 -> "${(bytes / 1e6).roundToLong().coerceAtMost(999)} MB"
    else -> String.format(locale, "%.1f GB", bytes / 1e9)
}

/** "0:01:22": the live timer's own format, so the recap reads as the same clock. */
internal fun formatDuration(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60)
}

private fun plural(n: Long, one: String) = if (n == 1L) "1 $one" else "$n ${one}s"

/** "1 hour 12 minutes", "1 minute 22 seconds": the duration as TalkBack should say it. */
internal fun spokenDuration(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    val h = t / 3600
    val m = t / 60 % 60
    val s = t % 60
    return when {
        h > 0 -> plural(h, "hour") + if (m > 0) " " + plural(m, "minute") else ""
        m > 0 -> plural(m, "minute") + if (s > 0) " " + plural(s, "second") else ""
        else -> plural(s, "second")
    }
}

/** The video pipeline restarted itself mid-stream: said once, in words, with no "time(s)". */
internal fun encoderLine(recoveries: Int): String? = when {
    recoveries <= 0 -> null
    recoveries == 1 -> "The video stopped once and recovered by itself."
    else -> "The video stopped $recoveries times and recovered by itself."
}

/**
 * DROPS · SENT · LINKS, plus TEMP only when the phone reached the first thermal rung. [links] is null when
 * this app session didn't see the stream (the cell is left out rather than guessed). [progress] 0→1 drives the
 * count-up: the figures grow with it and are exact at 1.
 */
internal fun recapCells(s: SessionSummary, links: Int?, progress: Float = 1f, locale: Locale = Locale.getDefault()): List<ReadoutCell> {
    val p = progress.coerceIn(0f, 1f)
    val drops = (s.recoveredDrops * p).roundToInt()
    val sent = recapBytes((s.bytesSent * p.toDouble()).roundToLong(), locale)
    val (sentFigure, sentUnit) = sent.substringBeforeLast(' ') to sent.substringAfterLast(' ')
    return buildList {
        add(ReadoutCell("Drops", "$drops", spoken = "Drops, ${s.recoveredDrops}"))
        add(ReadoutCell("Sent", sentFigure, sentUnit, spoken = "Sent, " + recapBytes(s.bytesSent, locale)))
        if (links != null) add(ReadoutCell("Links", "$links", spoken = "Links, $links"))
        when {
            s.peakThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> add(ReadoutCell("Temp", "hot", spoken = "Temperature, got hot"))
            s.peakThermalStatus >= THERMAL_RUNG_1 -> add(ReadoutCell("Temp", "warm", spoken = "Temperature, got warm"))
        }
    }
}

/**
 * The one polite announcement when the recap lands: "Held the whole way. Live for 1 hour 12 minutes. 0
 * drops, 1.4 GB sent."
 */
internal fun recapAnnouncement(s: SessionSummary, wasTest: Boolean, bonded: Boolean): String {
    if (s.liveDurationMs == 0L) return "The stream didn't start" + (s.lastError?.let { ": " + recapFailure(it, bonded) } ?: ".")
    val drops = if (s.recoveredDrops == 1) "1 drop" else "${s.recoveredDrops} drops"
    return recapVerdict(s.recoveredDrops, bonded) + " " + (if (wasTest) "Tested for " else "Live for ") +
        spokenDuration(s.liveDurationMs) + ". " + drops + ", " + recapBytes(s.bytesSent) + " sent."
}

/** The recap folded by DONE: "0:01:22 · held", "0:31:40 · 2 drops", or "didn't start". */
internal fun lastStreamValue(s: SessionSummary): String = when {
    s.liveDurationMs == 0L -> "didn't start"
    s.recoveredDrops == 0 -> formatDuration(s.liveDurationMs) + " · held"
    s.recoveredDrops == 1 -> formatDuration(s.liveDurationMs) + " · 1 drop"
    else -> formatDuration(s.liveDurationMs) + " · ${s.recoveredDrops} drops"
}

/** How long the recap figures take to count up once, after a 120ms beat. */
private const val COUNT_UP_MS = 600
private const val COUNT_UP_DELAY_MS = 120L

/**
 * End-of-stream recap: prove the app's value, say what happened, and no more
 * than that. Verdict first, with the mark (one hollow bar per drop); then the duration; then one stat row.
 * [bonded]: the stream went through the BRB server, the only case where viewers saw the BRB screen through a drop
 * instead of a pause. [links]: the bonding links, when known. [countUp]: the first showing of this recap, so the
 * figures count up once (reduce motion shows them final). [onOpenInspector]: a test can't be watched on Twitch, so
 * offer the place where it can be checked. [onDone] folds the recap into one line.
 */
@Composable
fun RecapCard(
    s: SessionSummary,
    wasTest: Boolean,
    bonded: Boolean,
    links: Int?,
    countUp: Boolean,
    onOpenInspector: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalFbColors.current
    val reduced = FbMotion.rememberReduced()
    val progress = remember(s.endedAtMs) { Animatable(if (countUp && !reduced) 0f else 1f) }
    LaunchedEffect(s.endedAtMs) {
        if (progress.value < 1f) {
            delay(COUNT_UP_DELAY_MS)
            progress.animateTo(1f, tween(COUNT_UP_MS, easing = FbMotion.Enter))
        }
    }
    val p = progress.value
    val ended = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.endedAtMs))
    Column(modifier.fillMaxWidth()) {
        SectionHeader(if (wasTest) "Test stream ended" else "Stream ended", trailing = ended, top = 8.dp)
        if (s.liveDurationMs == 0L) {
            // A failed start: the reason leads, and there's no verdict to give.
            InlineError("The stream didn't start" + (s.lastError?.let { ": ${recapFailure(it, bonded)}" } ?: "."))
        } else {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.Top) {
                MarkGlyph(MarkBar.forDrops(s.recoveredDrops), c.text, Modifier.padding(top = 2.dp), size = 28.dp)
                Spacer(Modifier.width(12.dp))
                Text(recapVerdict(s.recoveredDrops, bonded), style = FbType.displayS, color = c.text)
            }
            recapDropLines(s.recoveredDrops, bonded).getOrNull(1)?.let { HelpText(it, Modifier.padding(top = 4.dp)) }
            encoderLine(s.encoderRecoveries)?.let { HelpText(it) }
            Text(
                "DURATION", style = FbType.label, color = c.text2,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp).clearAndSetSemantics {},
            )
            BasicText(
                formatDuration((s.liveDurationMs * p.toDouble()).roundToLong()),
                style = FbType.duration.copy(color = c.text),
                maxLines = 1,
                // 60sp mono outgrows a 360dp phone at 200% font scale: shrink to fit rather than clip the time.
                autoSize = TextAutoSize.StepBased(minFontSize = 28.sp, maxFontSize = FbType.duration.fontSize, stepSize = 2.sp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .clearAndSetSemantics { contentDescription = "Duration, " + spokenDuration(s.liveDurationMs) },
            )
            Readout(recapCells(s, links, p), Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (wasTest && s.liveDurationMs > 0) {
                FbButton("Check it arrived in Twitch Inspector", onClick = onOpenInspector, kind = FbButtonKind.Text, modifier = Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            FbButton(
                "DONE", onClick = onDone, kind = FbButtonKind.Text,
                textStyle = FbType.label.copy(fontSize = 14.sp, lineHeight = 18.sp),
                modifier = Modifier.padding(horizontal = 8.dp).semantics { contentDescription = "Done" },
            )
        }
    }
}

/** The recap after DONE: one hairline row, the duration in mono and the outcome in words. */
@Composable
fun LastStreamRow(s: SessionSummary, modifier: Modifier = Modifier) {
    val v = lastStreamValue(s)
    val fig = if (s.liveDurationMs > 0) formatDuration(s.liveDurationMs) else ""
    val value = buildAnnotatedString {
        if (fig.isNotEmpty()) withStyle(SpanStyle(fontFamily = JetMono, fontWeight = FontWeight.Medium, fontSize = 14.sp)) { append(fig) }
        append(v.removePrefix(fig))
    }
    Column(modifier.fillMaxWidth().padding(top = 8.dp)) {
        Hairline()
        ValueRow("Last stream", value, valueIsFigure = false)
    }
}

/** Shown once after a stream ended without the user ending it (e.g. OxygenOS's background killer). */
@Composable
fun UnexpectedEndCard(e: UnexpectedEnd, onFix: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalFbColors.current
    val at = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.atMs))
    Column(modifier.fillMaxWidth()) {
        SectionHeader("Stream ended", trailing = at, top = 8.dp)
        Text(
            e.title, style = FbType.row, color = c.text,
            modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
        )
        HelpText(e.body)
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (e.offerBackgroundFix) FbButton(e.fixLabel, onClick = onFix, kind = FbButtonKind.Secondary)
            FbButton("Got it", onClick = onDismiss, kind = FbButtonKind.Text)
        }
    }
}

/**
 * OxygenOS/ColorOS kill streaming apps ~5 min after screen-off unless "Allow background activity" is on, and
 * apps cannot read that setting. Send the user there with a plain explanation.
 */
const val SCREEN_OFF_TEXT = "Your phone stops background apps about 5 minutes after the screen turns off. " +
    "Open settings › Battery usage › turn on “Allow background activity”."

/**
 * The screen-off reminder on phones that need it, as a block with no container: the title, the one explanation and the
 * fix. Home now links to the Screen off page instead; this stays for any page that wants the whole explanation inline.
 */
@Composable
fun BackgroundActivityCard(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalFbColors.current
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            "Keep streaming with the screen off", style = FbType.row, color = c.text,
            modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
        )
        HelpText(SCREEN_OFF_TEXT)
        FbButton("Open settings", onClick = onOpenSettings, kind = FbButtonKind.Secondary, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
    }
}
