package app.roadlog.dashcam.ui.components.RecorderScreen.molecules

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.roadlog.dashcam.ui.components.atoms.Pulsating
import app.roadlog.dashcam.ui.theme.InterFontFamily
import app.roadlog.dashcam.ui.theme.TabularFigures
import app.roadlog.dashcam.ui.utils.formatDuration
import app.roadlog.dashcam.ui.utils.rememberInitialRecordingAnimation

@Composable
fun RecordingStatus(
    recordingTime: Long,
    progress: Float,
    // Fills its container rather than a fixed width (§10.3) — this renders inside a
    // split-screen status panel that can be narrower than 300dp.
    progressModifier: Modifier = Modifier.fillMaxWidth(),
    // Rendered on the same line as the elapsed time (e.g. free-space indicator) —
    // a slot rather than a hardcoded composable so this molecule stays decoupled from
    // what the caller wants next to the timer.
    trailing: @Composable () -> Unit = {},
) {
    val animateIn = rememberInitialRecordingAnimation(recordingTime)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pulsating {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color.Red)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                ElapsedTimeRoll(
                    text = formatDuration(recordingTime * 1000),
                    style = MaterialTheme.typography.headlineLarge,
                )
            }

            trailing()
        }

        AnimatedVisibility(
            visible = animateIn,
            enter = expandHorizontally(
                tween(1000)
            )
        ) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = progressModifier,
                drawStopIndicator = { },
                gapSize = 0.dp,
            )
        }
    }
}

// Odometer-style animated digit display (§8.2/§9.1) — each character in the
// elapsed-time string gets its own `AnimatedContent`,
// sliding/fading independently when it changes, so e.g. "12:3_9_" -> "12:4_0_" only animates
// the digits that actually changed. Uses Inter's tabular (fixed-width) figures so digit
// width never shifts as values change, keeping the whole string visually stable even while
// individual characters animate. `key(index)` keeps each position's animation state stable
// even if the string's length changes (e.g. crossing the 1-hour mark adds an "HH:" prefix).
@Composable
private fun ElapsedTimeRoll(
    text: String,
    style: TextStyle,
) {
    val digitStyle = remember(style) {
        style.copy(
            fontFamily = InterFontFamily,
            fontFeatureSettings = TabularFigures,
        )
    }

    Row {
        text.forEachIndexed { index, character ->
            key(index) {
                AnimatedContent(
                    targetState = character,
                    transitionSpec = {
                        (slideInVertically { height -> height } + fadeIn()) togetherWith
                            (slideOutVertically { height -> -height } + fadeOut())
                    },
                    label = "elapsedTimeDigit",
                ) { targetChar ->
                    Text(
                        text = targetChar.toString(),
                        style = digitStyle,
                    )
                }
            }
        }
    }
}