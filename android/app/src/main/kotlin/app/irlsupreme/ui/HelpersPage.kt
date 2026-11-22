package app.irlsupreme.ui

import android.graphics.Bitmap
import android.net.wifi.WifiManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.irlsupreme.service.HelperState
import app.irlsupreme.service.HelperStatus
import app.irlsupreme.ui.theme.FbShape
import app.irlsupreme.ui.theme.LocalFbColors
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder

/**
 * Helper phones. One page for both sides: this phone accepting helpers while it streams, and this phone helping
 * another. Helpers lend their own mobile data, so a stream gets a carrier it didn't have. Same grammar as the other
 * settings pages: one help line per row, anything longer in a numbered footnote at the foot.
 */
@Composable
fun HelpersPage(c: SetupController, nav: Navigator) = PageScaffold("Helper phones", nav::back) {
    val helping by HelperStatus.state.collectAsState()
    var confirmNewCode by remember { mutableStateOf(false) }
    // Not saved: leaving the page closes the pairing code again, and it never opens by itself.
    var adding by remember { mutableStateOf(false) }
    var moblinCode by remember { mutableStateOf(false) }

    if (helping != HelperState.Off) {
        HelpingStatus(helping, c)
        return@PageScaffold
    }

    HelpText("A spare phone with its own mobile data adds its connection to your stream.")

    SectionHeader("When this phone streams")
    SwitchRow(
        "Accept helper phones",
        supporting = if (c.combine) "Helpers join while you're live. Some phones ask you to tap Connect on the helper each time."
        else "Turn on Combine connections first; helpers add to it.",
        checked = c.helpersOn,
        onCheckedChange = c::changeHelpersOn,
    )
    if (!c.combine) {
        FbButton("Set up Combine connections", { nav.open(Screen.CONNECTIONS) }, Modifier.padding(horizontal = 16.dp), kind = FbButtonKind.Text)
    }
    // Without STA+AP concurrency the hotspot would cost this phone its own Wi‑Fi link, so StreamService skips it
    // while on Wi‑Fi. A switch that looks on but does nothing is worse than a disabled one that says why. Read here,
    // outside the helpers block, because footnote 2 at the page foot depends on it too.
    val ctx = LocalContext.current
    val staAp = remember { runCatching { ctx.getSystemService(WifiManager::class.java)?.isStaApConcurrencySupported == true }.getOrDefault(false) }
    if (c.helpersOn) {
        SwitchRow(
            "Make a hotspot for helpers",
            supporting = if (staAp) "Helpers get its details over Bluetooth and join by themselves."
            else "This phone can't run a hotspot while on Wi‑Fi. Use the same Wi‑Fi or a travel router.",
            checked = c.helperHotspot,
            enabled = staAp,
            onCheckedChange = c::changeHelperHotspot,
        )
        // The pairing code is a password (the QR carries it too), so it's behind a button, closed again when the page is.
        if (!adding) {
            FbButton(
                "Add a helper phone", { adding = true },
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), kind = FbButtonKind.Secondary,
            )
        } else {
            HelpText(
                "On the helper: Fullbars › Settings › Helper phones › Use this phone as a helper › Scan. " +
                    if (c.helperHotspot && staAp) "Keep Bluetooth on for both phones." else "Then connect it to this phone's hotspot or the same Wi‑Fi.",
            )
            QrCode(c.helperLink)
            if (!moblinCode) {
                FbButton("Using Moblin?", { moblinCode = true }, Modifier.padding(horizontal = 16.dp), kind = FbButtonKind.Text)
            } else {
                HelpText("Use this password in Moblin:")
                CodeBlock(c.helperPassword, sensitive = true, label = "Helper password")
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FbButton("Make a new code", { confirmNewCode = true }, kind = FbButtonKind.Text)
                FbButton("Hide the code", { adding = false; moblinCode = false }, kind = FbButtonKind.Text)
            }
        }
    }

    SectionHeader("Help another phone")
    NavRow(
        "Use this phone as a helper", supporting = "Lend its mobile data to a phone that's streaming.",
        onClick = { nav.open(Screen.HELP_ANOTHER) },
    )

    if (confirmNewCode) {
        ConfirmDialog(
            title = "Make a new code?",
            text = "Helper phones, including Moblin, will need the new code or password.",
            confirmLabel = "Make new code",
            onConfirm = { c.newHelperCode(); confirmNewCode = false },
            onDismiss = { confirmNewCode = false },
        )
    }
}

/** What a helper state says: the status word, its square, one help line, and an optional footnote for the rest. */
internal data class HelpingText(val status: String, val mark: StateMark, val line: String, val note: String? = null)

/**
 * The helper side in words (pure, so HelpersTextTest pins it). Only states that are verified problems are FAILED (red);
 * waiting and searching are UNCHECKED, never red.
 */
internal fun helpingText(s: HelperState, lastHost: String?): HelpingText {
    fun name(n: String, fallback: String = "the streaming phone") = n.ifEmpty { fallback }
    return when (s) {
        HelperState.Off -> HelpingText("Off", StateMark.NONE, "")
        HelperState.NoWifi -> HelpingText(
            "Join the streaming phone's Wi‑Fi", StateMark.FAILED,
            "Its hotspot or the same Wi‑Fi. With Bluetooth on, it joins the hotspot by itself.",
        )
        HelperState.WifiOff -> HelpingText(
            "Turn on Wi‑Fi", StateMark.FAILED,
            "It only needs to be on: this phone joins ${name(lastHost.orEmpty())}'s hotspot by itself.",
        )
        is HelperState.WaitingToGoLive -> HelpingText(
            "Waiting for ${name(s.hostName)} to go live", StateMark.UNCHECKED,
            "This phone joins its hotspot over Bluetooth when it does.",
            "You can lock it once it's helping; it may ask you to tap Connect when the stream starts. No Fullbars " +
                "hotspot? Connect both phones to the same Wi‑Fi. If nobody goes live for 2 hours, it stops by itself to save battery.",
        )
        is HelperState.JoiningHotspot -> HelpingText(
            "Joining ${name(s.hostName)}'s hotspot", StateMark.UNCHECKED,
            "If it asks to connect, tap Connect. Some phones ask every stream.",
        )
        HelperState.NoMobileData -> HelpingText(
            "Needs its own mobile data", StateMark.FAILED,
            "Turn on mobile data. Helping over the same Wi‑Fi adds nothing.",
        )
        is HelperState.Searching -> HelpingText(
            "Looking for ${name(s.hostName)}", StateMark.UNCHECKED, "Both phones need the same Wi‑Fi or hotspot.",
        )
        is HelperState.Connecting -> HelpingText("Connecting to ${name(s.hostName)}", StateMark.UNCHECKED, "")
        HelperState.WrongPassword -> HelpingText(
            "The code didn't match", StateMark.FAILED, "The streaming phone may have a new code. Scan it again.",
        )
        is HelperState.Helping -> if (s.forwarding) {
            HelpingText("Helping ${name(s.hostName, "the stream")}", StateMark.VERIFIED, "You can lock this phone.")
        } else {
            HelpingText(
                "Ready", StateMark.VERIFIED,
                "${name(s.hostName, "The streaming phone")} isn't live with Combine connections yet.",
                "This phone joins in when it is, and stops by itself after 2 hours without a stream.",
            )
        }
    }
}

/**
 * Settings › Helper phones › Use this phone as a helper: lend this phone's mobile data to a phone that's streaming,
 * by scanning its code (or to a phone running Moblin). While it helps, the status shows here, as on Helper phones.
 */
@Composable
fun HelpAnotherPage(c: SetupController, nav: Navigator) = PageScaffold("Use this phone as a helper", nav::back) {
    val helping by HelperStatus.state.collectAsState()
    var showMoblin by rememberSaveable { mutableStateOf(false) }
    var moblinPassword by rememberSaveable { mutableStateOf("") }
    var moblinAddress by rememberSaveable { mutableStateOf("") }
    if (helping != HelperState.Off) {
        HelpingStatus(helping, c)
        return@PageScaffold
    }
    HelpText("Lend this phone's mobile data to a phone that's streaming. Keep it charged; it uses data fast.")
    // The phone this one helped last: a value row, with the one-tap way back to it.
    c.helpingHost?.let { h ->
        ValueRow("Streaming phone", h.name.ifEmpty { "The last phone" }, valueIsFigure = false)
    }
    FbButton(
        "Scan the streaming phone's code", c::scanHelperCode,
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
    c.helpingHost?.let { h ->
        FbButton(
            "Help ${h.name.ifEmpty { "the last phone" }} again", c::startHelping,
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), kind = FbButtonKind.Secondary,
        )
    }
    if (!showMoblin) {
        FbButton("Help a phone running Moblin", { showMoblin = true }, Modifier.padding(horizontal = 16.dp), kind = FbButtonKind.Text)
    } else {
        HelpText("In Moblin: Settings › Moblink › Streamer. Use its password here.")
        SecretField(moblinPassword, { moblinPassword = it }, "Moblink password")
        PlainField(moblinAddress, { moblinAddress = it }, "Its address (optional)", supporting = "Leave empty to find it automatically")
        FbButton(
            "Start helping", { c.useMoblinStreamer(moblinPassword, moblinAddress) },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), kind = FbButtonKind.Secondary,
            enabled = moblinPassword.isNotBlank(),
        )
    }
}

/** "4.2 Mb/s · 318 MB": what this phone has sent for the stream, figures in mono. */
internal fun helpingSent(upKbps: Long, totalMb: Long): String = "%.1f Mb/s · %d MB".format(upKbps / 1000.0, totalMb)

/** This phone helping another: value rows (status, what it's sending), reported in place in a polite live region. */
@Composable
private fun HelpingStatus(s: HelperState, c: SetupController) {
    val t = helpingText(s, c.helpingHost?.name)
    SectionHeader("Helping")
    ValueRow(
        "Status", t.status, valueIsFigure = false, state = t.mark,
        valueColor = if (t.mark == StateMark.FAILED) LocalFbColors.current.error else Color.Unspecified,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    if (s is HelperState.Helping && s.forwarding) ValueRow("Sending", helpingSent(s.upKbps, s.totalMb), valueIsFigure = true)
    if (t.line.isNotEmpty()) HelpText(t.line, modifier = Modifier.padding(top = 4.dp))
    t.note?.let { HelpText(it) }
    if (s == HelperState.WifiOff) {
        val ctx = LocalContext.current
        FbButton(
            "Turn on Wi‑Fi", { runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.Panel.ACTION_WIFI)) } },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    if (s == HelperState.WrongPassword) {
        FbButton("Scan the code again", c::scanHelperCode, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
    }
    FbButton(
        "Stop helping", c::stopHelping,
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), kind = FbButtonKind.Secondary,
    )
}

/** The pairing code, black on a white rS plate: scanners need the quiet zone whatever the theme. */
@Composable
private fun QrCode(text: String) {
    val bitmap: Bitmap = remember(text) { BarcodeEncoder().encodeBitmap(text, BarcodeFormat.QR_CODE, 640, 640) }
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.CenterStart) {
        Image(
            bitmap.asImageBitmap(),
            contentDescription = "Helper pairing code",
            modifier = Modifier.size(240.dp).background(Color.White, FbShape.small).padding(8.dp),
        )
    }
}
