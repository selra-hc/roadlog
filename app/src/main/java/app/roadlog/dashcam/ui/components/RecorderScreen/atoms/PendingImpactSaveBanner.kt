package app.roadlog.dashcam.ui.components.RecorderScreen.atoms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.roadlog.dashcam.R
import app.roadlog.dashcam.impact.PendingImpactSave
import app.roadlog.dashcam.ui.theme.CardCornerRadius
import app.roadlog.dashcam.ui.theme.RoadLogTheme
import app.roadlog.dashcam.ui.utils.formatDuration
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect

// An impact-triggered auto-save counting down (§7.2/§7.3) is an urgent, attention-grabbing
// state (footage of a potential incident is being protected) — deliberately not styled as a
// subtle/dismissible-by-default info card, hence the solid warning-orange fill rather than a
// translucent backing plate like the rest of the recording-status overlay.
@Composable
fun PendingImpactSaveBanner(
    pending: PendingImpactSave,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var remainingMillis by remember(pending) {
        mutableLongStateOf(pending.finalizeAtEpochMillis - System.currentTimeMillis())
    }

    LaunchedEffect(pending) {
        while (remainingMillis > 0) {
            delay(1000)
            remainingMillis = pending.finalizeAtEpochMillis - System.currentTimeMillis()
        }
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(CardCornerRadius))
            .background(RoadLogTheme.colors.warningOrange)
            .border(1.dp, RoadLogTheme.colors.dangerRed, RoundedCornerShape(CardCornerRadius))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = Color.Black,
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.ui_recorder_impactBanner_title),
                style = MaterialTheme.typography.labelLarge,
                color = Color.Black,
            )
            Text(
                text = stringResource(
                    R.string.ui_recorder_impactBanner_countdown,
                    formatDuration(remainingMillis.coerceAtLeast(0)),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Black,
            )
        }

        TextButton(
            onClick = onCancel,
            colors = ButtonDefaults.textButtonColors(contentColor = Color.Black),
        ) {
            Text(stringResource(R.string.ui_recorder_impactBanner_cancel_label))
        }
    }
}
