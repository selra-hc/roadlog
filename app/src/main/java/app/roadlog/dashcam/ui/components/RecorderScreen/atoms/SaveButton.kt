package app.roadlog.dashcam.ui.components.RecorderScreen.atoms

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
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

// Clicking this is the app's actual stop-triggering action (it stops recording and saves
// the clip — there's no separate literal "Stop" button, `onLongClick`/`onSaveCurrent` is a
// distinct "save current buffer without stopping" action). Same 48dp square footprint/shape
// as DeleteButton (consistent control-row sizing) and tinted with the app's accent color
// rather than a semantic traffic-light color, per explicit design direction for this button.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SaveButton(
    modifier: Modifier = Modifier,
    onSave: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val label = stringResource(R.string.ui_recorder_action_save_label)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(ButtonCornerRadius))
            .background(MaterialTheme.colorScheme.primary)
            .semantics {
                contentDescription = label
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = MaterialTheme.colorScheme.onPrimary),
                onClick = onSave,
                onLongClick = onLongClick,
            )
            .then(modifier)
    ) {
        Icon(
            Icons.Default.Save,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
        )
    }
}
