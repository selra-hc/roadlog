package app.roadlog.dashcam.ui.components.SettingsScreen.Tiles

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.roadlog.dashcam.R
import app.roadlog.dashcam.dataStore
import app.roadlog.dashcam.db.AppSettings
import app.roadlog.dashcam.ui.components.atoms.SettingsTile
import app.roadlog.dashcam.ui.utils.IconResource
import com.maxkeppeker.sheets.core.models.base.Header
import com.maxkeppeker.sheets.core.models.base.IconSource
import com.maxkeppeker.sheets.core.models.base.rememberUseCaseState
import com.maxkeppeler.sheets.list.ListDialog
import com.maxkeppeler.sheets.list.models.ListOption
import com.maxkeppeler.sheets.list.models.ListSelection
import kotlinx.coroutines.launch

// A short `ListDialog` over a handful of example minute values is used here rather than
// `IntervalDurationTile`/`MaxDurationTile`'s `DurationDialog` (built for
// minutes:seconds-precision millisecond durations) or an `InputDialog` free-text field
// (see `VideoRecorderBitrateTile`) — "1 to 30 whole minutes" is a small, bounded range
// where a plain pick-a-value list is the least code and simplest UX.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImpactDetectionDurationTile(
    settings: AppSettings,
) {
    val scope = rememberCoroutineScope()
    val showDialog = rememberUseCaseState()
    val dataStore = LocalContext.current.dataStore

    val DURATIONS_MINUTES = listOf(1, 2, 5, 10, 15, 30)

    fun updateValue(minutes: Int) {
        scope.launch {
            dataStore.updateData {
                it.setImpactDetectionSettings(
                    it.impactDetection.copy(postImpactDurationMinutes = minutes)
                )
            }
        }
    }

    ListDialog(
        state = showDialog,
        header = Header.Default(
            title = stringResource(R.string.ui_settings_option_impactDetectionDuration_title),
            icon = IconSource(
                painter = IconResource.fromImageVector(Icons.Default.Timer)
                    .asPainterResource(),
                contentDescription = null,
            ),
        ),
        selection = ListSelection.Single(
            showRadioButtons = true,
            options = DURATIONS_MINUTES.map { minutes ->
                ListOption(
                    titleText = stringResource(R.string.format_minutes, minutes),
                    selected = settings.impactDetection.postImpactDurationMinutes == minutes,
                )
            }
        ) { index, _ ->
            updateValue(DURATIONS_MINUTES[index])
        },
    )
    SettingsTile(
        title = stringResource(R.string.ui_settings_option_impactDetectionDuration_title),
        description = stringResource(R.string.ui_settings_option_impactDetectionDuration_description),
        leading = {
            Icon(
                Icons.Default.Timer,
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
                Text(
                    stringResource(
                        R.string.format_minutes,
                        settings.impactDetection.postImpactDurationMinutes
                    )
                )
            }
        },
    )
}
