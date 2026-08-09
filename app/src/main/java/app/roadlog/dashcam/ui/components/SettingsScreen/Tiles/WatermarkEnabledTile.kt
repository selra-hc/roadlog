package app.roadlog.dashcam.ui.components.SettingsScreen.Tiles

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.roadlog.dashcam.R
import app.roadlog.dashcam.ui.components.atoms.SettingsTile

// Reads/writes the caller's draft `WatermarkSettings` (see `WatermarkSettingsSection`) —
// unlike most other tiles in this package, it does not read `AppSettings`/write
// `dataStore` directly, since its value must also drive the live preview above it (§9.4)
// before being persisted.
@Composable
fun WatermarkEnabledTile(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SettingsTile(
        title = stringResource(R.string.ui_settings_option_watermarkEnabled_title),
        description = stringResource(R.string.ui_settings_option_watermarkEnabled_description),
        leading = {
            Icon(
                Icons.Default.WaterDrop,
                contentDescription = null,
            )
        },
        trailing = {
            Switch(
                checked = enabled,
                onCheckedChange = onChange,
            )
        }
    )
}
