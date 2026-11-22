package app.irlsupreme.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import app.irlsupreme.engine.CaptionPosition
import app.irlsupreme.engine.CaptionSize
import app.irlsupreme.engine.captions.CaptionLanguageSupport
import app.irlsupreme.engine.captions.CaptionLanguages
import app.irlsupreme.service.CaptionPrefs
import app.irlsupreme.service.CaptionSettings
import kotlinx.coroutines.delay
import java.util.Locale

/** A language tag as people say it, in the phone's language: "English (United States)". */
internal fun languageLabel(tag: String): String =
    Locale.forLanguageTag(tag).getDisplayName(Locale.getDefault()).ifBlank { tag }

/** The Captions row on Settings: off, or on in which language. */
internal fun captionsSettingValue(p: CaptionPrefs): String = if (!p.on) "Off" else "On · ${languageLabel(p.languageTag)}"

/** Whether [tag] works now ([installed]), is on its way, can be fetched, or isn't offered at all. */
internal enum class LanguageState { INSTALLED, DOWNLOADING, DOWNLOADABLE, NOT_OFFERED }

/** Where [tag] stands. A tag counts as installed when an installed one matches it, ignoring case ("en-us"). */
internal fun languageState(tag: String, s: CaptionLanguageSupport): LanguageState {
    fun List<String>.has() = any { it.equals(tag, ignoreCase = true) }
    return when {
        s.installed.has() -> LanguageState.INSTALLED
        s.pending.has() -> LanguageState.DOWNLOADING
        s.downloadable.has() -> LanguageState.DOWNLOADABLE
        else -> LanguageState.NOT_OFFERED
    }
}

internal fun captionSizeTitle(s: CaptionSize) = when (s) {
    CaptionSize.SMALL -> "Small"
    CaptionSize.MEDIUM -> "Medium"
    CaptionSize.LARGE -> "Large"
}

/**
 * Settings › Captions: live captions of what viewers hear you say, recognised on this phone (nothing is sent
 * anywhere) and written on the stream. The language list is what the phone's speech service has, or can fetch.
 */
@Composable
fun CaptionsPage(nav: Navigator) = PageScaffold("Captions", nav::back) {
    val ctx = LocalContext.current
    val p by CaptionSettings.state(ctx).collectAsState()
    var support by remember { mutableStateOf<CaptionLanguageSupport?>(null) }
    var checks by remember { mutableIntStateOf(0) }
    var moreLanguages by remember { mutableStateOf(false) }
    var downloadShown = false
    LaunchedEffect(checks) { CaptionLanguages.check(ctx) { support = it } }
    // While a language downloads, look again every few seconds so it turns choosable once it's there.
    val s = support
    LaunchedEffect(s) { if (s != null && s.pending.isNotEmpty()) { delay(4_000); checks++ } }

    SwitchRow(
        "Live captions",
        supporting = "What viewers hear you say, written on the stream. Recognised on this phone: nothing is sent anywhere.",
        checked = p.on,
        enabled = s?.onDevice != false,
        onCheckedChange = { CaptionSettings.setOn(ctx, it) },
    )
    if (s != null && !s.onDevice) {
        HelpText(
            if (!CaptionLanguages.supported) "Live captions need Android 13 or later."
            else "This phone can't recognise speech on the device, so captions aren't available on it.",
        )
        return@PageScaffold
    }
    // Off means folded: the rest only matters once captions are on (the choices are kept meanwhile).
    if (!p.on) return@PageScaffold

    SectionHeader("Language")
    val phoneTag = LocalConfiguration.current.locales[0].toLanguageTag()
    Column(Modifier.selectableGroup()) {
        fun note(tag: String): String? = when (s?.let { languageState(tag, it) }) {
            LanguageState.DOWNLOADING -> "Downloading"
            LanguageState.DOWNLOADABLE -> "Download"
            LanguageState.NOT_OFFERED -> "Not available on this phone"
            else -> null
        }
        fun choose(tag: String?) {
            val t = tag ?: phoneTag
            if (s != null && languageState(t, s) == LanguageState.DOWNLOADABLE) { CaptionLanguages.download(ctx, t); checks++ }
            CaptionSettings.setLanguage(ctx, tag)
        }
        RadioRow("Phone's language", supporting = listOfNotNull(languageLabel(phoneTag), note(phoneTag)).joinToString(" · "), selected = p.language == null) { choose(null) }
        val installed = s?.installed.orEmpty().filterNot { it.equals(phoneTag, ignoreCase = true) }
        installed.forEach { t -> RadioRow(languageLabel(t), selected = p.language == t) { choose(t) } }
        val others = (s?.pending.orEmpty() + s?.downloadable.orEmpty()).distinct()
            .filterNot { it.equals(phoneTag, ignoreCase = true) || installed.contains(it) }
            .sortedBy { languageLabel(it) }
        // The chosen one stays in view even when the list is folded.
        val shown = if (moreLanguages) others else others.filter { it == p.language }
        shown.forEach { t -> RadioRow(languageLabel(t), supporting = note(t), selected = p.language == t) { choose(t) } }
        if (others.isNotEmpty() && !moreLanguages) {
            ValueRow("More languages", "${others.size}", valueIsFigure = true, onClick = { moreLanguages = true })
        }
        downloadShown = note(phoneTag) == "Download" || shown.any { note(it) == "Download" }
    }
    if (downloadShown) HelpText("Download fetches the language the first time you choose it.")

    SectionHeader("Look")
    ChoiceLabel("Text size")
    KeyChoice("Text size", CaptionSize.entries, p.size, ::captionSizeTitle) { CaptionSettings.setSize(ctx, it) }
    ChoiceLabel("Position")
    KeyChoice("Position", CaptionPosition.entries, p.position, { if (it == CaptionPosition.TOP) "Top" else "Bottom" }) { CaptionSettings.setPosition(ctx, it) }
    SwitchRow(
        "Hide swear words",
        supporting = "Stars them out. People near you are captioned too.",
        checked = p.maskOffensive,
        onCheckedChange = { CaptionSettings.setMask(ctx, it) },
    )
    HelpText("Captions stop while you're muted or on Privacy, and pause if the phone gets hot. Turn them on or off live from Scenes.")
}
