package app.roadlog.dashcam.ui.components.RecorderScreen.atoms

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.roadlog.dashcam.R
import app.roadlog.dashcam.ui.theme.ButtonCornerRadius
import app.roadlog.dashcam.ui.theme.RoadLogTheme

// Secondary 48dp rounded-square icon button (§8.2/§9.1) — a destructive action, so tinted
// with `dangerRed` at low-alpha (tonal, not filled solid) to read as "available but not the
// row's primary action" next to the pill-shaped Save/Stop button.
@Composable
fun DeleteButton(
    modifier: Modifier = Modifier,
    onDelete: () -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        ConfirmDeletionDialog(
            onDismiss = {
                showDeleteDialog = false
            },
            onConfirm = {
                showDeleteDialog = false
                onDelete()
            },
        )
    }
    val label = stringResource(R.string.ui_recorder_action_delete_label)

    FilledTonalIconButton(
        onClick = {
            showDeleteDialog = true
        },
        modifier = Modifier
            .size(48.dp)
            .semantics {
                contentDescription = label
            }
            .then(modifier),
        shape = RoundedCornerShape(ButtonCornerRadius),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = RoadLogTheme.colors.dangerRed.copy(alpha = 0.18f),
            contentColor = RoadLogTheme.colors.dangerRed,
        ),
    ) {
        Icon(
            Icons.Default.Delete,
            contentDescription = null,
        )
    }
}
