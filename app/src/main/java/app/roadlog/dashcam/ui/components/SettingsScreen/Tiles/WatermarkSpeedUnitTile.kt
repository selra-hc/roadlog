package app.roadlog.dashcam.ui.components.SettingsScreen.Tiles

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.roadlog.dashcam.R
import app.roadlog.dashcam.helpers.SpeedUnit
import app.roadlog.dashcam.ui.components.atoms.SettingsTile
import app.roadlog.dashcam.ui.utils.IconResource
import com.maxkeppeker.sheets.core.models.base.Header
import com.maxkeppeker.sheets.core.models.base.IconSource
import com.maxkeppeker.sheets.core.models.base.rememberUseCaseState
import com.maxkeppeler.sheets.list.ListDialog
import com.maxkeppeler.sheets.list.models.ListOption
import com.maxkeppeler.sheets.list.models.ListSelection

// Same "reads/writes the caller's draft" pattern as `WatermarkEnabledTile` — see that
// file's doc comment. The picker's option labels use `SpeedUnit.label` directly (a raw
// value the enum already owns, e.g. "km/h"/"mph") rather than a string resource — only
// the surrounding tile/dialog chrome (title/description) goes through `stringResource`.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatermarkSpeedUnitTile(
    speedUnit: SpeedUnit,
    onChange: (SpeedUnit) -> Unit,
) {
    val UNITS = listOf(SpeedUnit.KMH, SpeedUnit.MPH)

    val showDialog = rememberUseCaseState()

    ListDialog(
        state = showDialog,
        header = Header.Default(
            title = stringResource(R.string.ui_settings_option_watermarkSpeedUnit_title),
            icon = IconSource(
                painter = IconResource.fromImageVector(Icons.Default.Speed)
                    .asPainterResource(),
                contentDescription = null,
            ),
        ),
        selection = ListSelection.Single(
            showRadioButtons = true,
            options = UNITS.map { option ->
                ListOption(
                    titleText = option.label,
                    selected = speedUnit == option,
                )
            }
        ) { index, _ ->
            onChange(UNITS[index])
        },
    )
    SettingsTile(
        title = stringResource(R.string.ui_settings_option_watermarkSpeedUnit_title),
        description = stringResource(R.string.ui_settings_option_watermarkSpeedUnit_description),
        leading = {
            Icon(
                Icons.Default.Speed,
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
                Text(speedUnit.label)
            }
        },
    )
}
