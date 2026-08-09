package app.roadlog.dashcam.ui.components.RecorderScreen.atoms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.roadlog.dashcam.R
import app.roadlog.dashcam.ui.theme.ButtonCornerRadius

// Same 48dp square footprint/shape as SaveButton/DeleteButton (consistent control-row
// sizing), tinted with the app's accent color like PauseResumeButton/SaveButton (§8.2's
// design direction for this row) — the icon itself communicates which action tapping it
// will perform, so no traffic-light semantic color is needed here.
@Composable
fun CutFeedButton(
    modifier: Modifier = Modifier,
    isCut: Boolean,
    onCutFeed: () -> Unit,
) {
    val cutLabel = stringResource(R.string.ui_recorder_action_cutFeed_label)
    val uncutLabel = stringResource(R.string.ui_recorder_action_uncutFeed_label)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(ButtonCornerRadius))
            .background(MaterialTheme.colorScheme.primary)
            .semantics {
                contentDescription = if (isCut) uncutLabel else cutLabel
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = MaterialTheme.colorScheme.onPrimary),
                onClick = onCutFeed,
            )
            .then(modifier)
    ) {
        Icon(
            if (isCut) Icons.Default.Visibility else Icons.Default.VisibilityOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
        )
    }
}
