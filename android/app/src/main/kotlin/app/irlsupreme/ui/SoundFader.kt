package app.irlsupreme.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.irlsupreme.service.AlertSoundRules
import app.irlsupreme.ui.theme.FbHaptics
import app.irlsupreme.ui.theme.FbType
import app.irlsupreme.ui.theme.HapticPattern
import app.irlsupreme.ui.theme.LocalFbColors
import kotlin.math.roundToInt

/**
 * A sound's volume in whole dB from [min] to [max]: the value in figures, a slider, and − and + keys for one-dB steps,
 * like the mic's level.
 */
@Composable
internal fun SoundFader(label: String, db: Int, min: Int, max: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalFbColors.current
    val view = LocalView.current
    val current by rememberUpdatedState(db)
    val set: (Int) -> Unit = { v ->
        val n = v.coerceIn(min, max)
        if (n != current) { FbHaptics.play(view, HapticPattern.KEY); onChange(n) }
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(label, style = FbType.row, color = c.text, modifier = Modifier.weight(1f).clearAndSetSemantics {})
            Text(AlertSoundRules.gainText(db), style = FbType.value, color = c.text, modifier = Modifier.clearAndSetSemantics {})
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepKey("−", "$label down") { set(current - 1) }
            Spacer(Modifier.width(8.dp))
            Slider(
                value = db.toFloat(),
                onValueChange = { set(it.roundToInt()) },
                valueRange = min.toFloat()..max.toFloat(),
                steps = max - min - 1,
                colors = SliderDefaults.colors(
                    thumbColor = c.text, activeTrackColor = c.text, inactiveTrackColor = c.rule,
                    activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f).semantics { contentDescription = label; stateDescription = gainSpoken(db) },
            )
            Spacer(Modifier.width(8.dp))
            StepKey("+", "$label up") { set(current + 1) }
        }
    }
}
