package app.roadlog.dashcam.ui.components.SettingsScreen.Tiles

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.roadlog.dashcam.R
import app.roadlog.dashcam.dataStore
import app.roadlog.dashcam.db.AppSettings
import app.roadlog.dashcam.ui.components.atoms.SettingsTile
import kotlinx.coroutines.launch

// §9.1's auto-start-on-launch setting — default on, since a mounted dashcam is expected
// to start recording the instant the app opens, without a manual tap.
@Composable
fun AutoStartOnLaunchTile(
    settings: AppSettings,
) {
    val scope = rememberCoroutineScope()
    val dataStore = LocalContext.current.dataStore

    SettingsTile(
        title = stringResource(R.string.ui_settings_option_autoStartOnLaunch_title),
        description = stringResource(R.string.ui_settings_option_autoStartOnLaunch_description),
        leading = {
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = null,
            )
        },
        trailing = {
            Switch(
                checked = settings.autoStartOnLaunch,
                onCheckedChange = {
                    scope.launch {
                        dataStore.updateData {
                            it.setAutoStartOnLaunch(it.autoStartOnLaunch.not())
                        }
                    }
                }
            )
        }
    )
}
