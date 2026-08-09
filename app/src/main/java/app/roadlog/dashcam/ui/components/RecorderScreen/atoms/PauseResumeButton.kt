package app.roadlog.dashcam.ui.components.RecorderScreen.atoms

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.roadlog.dashcam.R

// Tinted with the app's accent color rather than the `recordPause` traffic-light color
// (per explicit design direction for this button) — the button's own icon (pause vs.
// play) already communicates which action tapping it will perform.
@Composable
fun PauseResumeButton(
    modifier: Modifier = Modifier,
    isPaused: Boolean,
    onChange: () -> Unit,
) {
    val pauseLabel = stringResource(R.string.ui_recorder_action_pause_label)
    val resumeLabel = stringResource(R.string.ui_recorder_action_resume_label)

    FloatingActionButton(
        modifier = Modifier
            .semantics {
                contentDescription = if (isPaused) resumeLabel else pauseLabel
            }
            .then(modifier),
        onClick = onChange,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Icon(
            if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
            contentDescription = null,
        )
    }
}
