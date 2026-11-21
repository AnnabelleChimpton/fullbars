package app.irlsupreme.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.irlsupreme.engine.EngineState
import app.irlsupreme.engine.audio.InputClass
import app.irlsupreme.engine.audio.MicLevel
import app.irlsupreme.service.MicSettings
import app.irlsupreme.ui.Hairline
import app.irlsupreme.ui.LocalStreamEngine
import app.irlsupreme.ui.MicPanel
import app.irlsupreme.ui.ValueRow
import app.irlsupreme.ui.VoiceSource
import app.irlsupreme.ui.theme.FbShape
import app.irlsupreme.ui.theme.FbType
import app.irlsupreme.ui.theme.LocalFbColors
import app.irlsupreme.ui.voiceSourceOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The live screen's mic sheet (opened from the MIC column): which mic viewers hear, the live meter, the level fader
 * and the phone's auto level, applied to the stream at once and kept for the next one. Same pieces as Settings ›
 * Microphone.
 *
 * Which mic: with the Pocket 3 or another RTMP camera, the stream switches to the camera's audio by itself, and then
 * the fader and the auto level don't change what viewers hear (the camera's sound goes out at 0 dB). Saying so keeps
 * the controls from looking broken, and points a too-loud camera at the camera's own level.
 */
@Composable
internal fun MicSheet(
    level: StateFlow<MicLevel>,
    muted: Boolean,
    onGain: (Int) -> Unit,
    onRaw: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalFbColors.current
    val prefs by MicSettings.state(LocalContext.current).collectAsState()
    val lvl by level.collectAsState()
    val engine = LocalStreamEngine.current
    val engineState = remember(engine) { engine?.state ?: MutableStateFlow(EngineState()) }
    val state by engineState.collectAsState()
    val source = voiceSourceOf(state.externalCamera)
    Column(
        modifier
            .background(c.s1, RoundedCornerShape(topStart = FbShape.rM, topEnd = FbShape.rM))
            .semantics { paneTitle = "Mic" },
    ) {
        Hairline(color = c.rule)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
                Text("MIC", style = FbType.label, color = c.text2, modifier = Modifier.semantics { heading() })
                Text("Changes are live now and kept for your next stream.", style = FbType.help, color = c.text2)
            }
            ValueRow("Viewers hear", if (source == VoiceSource.CAMERA) "The camera's audio" else "The phone mic", valueIsFigure = false)
            if (source == VoiceSource.CAMERA) {
                Text(
                    "The level and auto level below are for the phone mic, which takes over if the camera's audio drops. " +
                        "Set the camera's own level on the camera.",
                    style = FbType.help, color = c.text2,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                )
            } else {
                Spacer(Modifier.height(12.dp))
            }
            // A USB or wired mic in use has its own level, as on Settings › Microphone.
            val external = state.micInput?.inputClass == InputClass.EXTERNAL
            MicPanel(lvl, muted, prefs.gainFor(external), onGain, prefs.raw, onRaw, source)
            Spacer(Modifier.height(8.dp))
        }
    }
}
