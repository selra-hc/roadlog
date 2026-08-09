package app.roadlog.dashcam.ui.components.SettingsScreen.Tiles

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.roadlog.dashcam.R
import app.roadlog.dashcam.db.WatermarkCorner
import androidx.compose.ui.res.stringResource
import app.roadlog.dashcam.ui.components.atoms.SettingsTile
import app.roadlog.dashcam.ui.utils.IconResource
import com.maxkeppeker.sheets.core.models.base.Header
import com.maxkeppeker.sheets.core.models.base.IconSource
import com.maxkeppeker.sheets.core.models.base.rememberUseCaseState
import com.maxkeppeler.sheets.list.ListDialog
import com.maxkeppeler.sheets.list.models.ListOption
import com.maxkeppeler.sheets.list.models.ListSelection

// Same "reads/writes the caller's draft" pattern as `WatermarkEnabledTile` — see that
// file's doc comment.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatermarkCornerTile(
    corner: WatermarkCorner,
    onChange: (WatermarkCorner) -> Unit,
) {
    val CORNERS = listOf(
        WatermarkCorner.TOP_LEFT,
        WatermarkCorner.TOP_RIGHT,
        WatermarkCorner.BOTTOM_LEFT,
        WatermarkCorner.BOTTOM_RIGHT,
    )
    val CORNER_LABELS = mapOf(
        WatermarkCorner.TOP_LEFT to stringResource(R.string.ui_settings_value_watermarkCorner_topLeft),
        WatermarkCorner.TOP_RIGHT to stringResource(R.string.ui_settings_value_watermarkCorner_topRight),
        WatermarkCorner.BOTTOM_LEFT to stringResource(R.string.ui_settings_value_watermarkCorner_bottomLeft),
        WatermarkCorner.BOTTOM_RIGHT to stringResource(R.string.ui_settings_value_watermarkCorner_bottomRight),
    )

    val showDialog = rememberUseCaseState()

    ListDialog(
        state = showDialog,
        header = Header.Default(
            title = stringResource(R.string.ui_settings_option_watermarkCorner_title),
            icon = IconSource(
                painter = IconResource.fromImageVector(Icons.Default.CropFree)
                    .asPainterResource(),
                contentDescription = null,
            ),
        ),
        selection = ListSelection.Single(
            showRadioButtons = true,
            options = CORNERS.map { option ->
                ListOption(
                    titleText = CORNER_LABELS[option]!!,
                    selected = corner == option,
                )
            }
        ) { index, _ ->
            onChange(CORNERS[index])
        },
    )
    SettingsTile(
        title = stringResource(R.string.ui_settings_option_watermarkCorner_title),
        description = stringResource(R.string.ui_settings_option_watermarkCorner_description),
        leading = {
            Icon(
                Icons.Default.CropFree,
                contentDescription = null,
            )
        },
        trailing = {
            Button(
                onClick = showDialog::show,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(CORNER_LABELS[corner]!!)
            }
        },
    )
}
