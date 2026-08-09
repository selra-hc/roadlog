package app.roadlog.dashcam.ui.components.SettingsScreen.Tiles

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MovieFilter
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

@Composable
fun ProcessVideoTile(
    settings: AppSettings,
) {
    val scope = rememberCoroutineScope()
    val dataStore = LocalContext.current.dataStore

    SettingsTile(
        title = stringResource(R.string.ui_settings_option_processVideo_title),
        description = stringResource(R.string.ui_settings_option_processVideo_description),
        leading = {
            Icon(
                Icons.Default.MovieFilter,
                contentDescription = null,
            )
        },
        trailing = {
            Switch(
                checked = settings.videoRecorderSettings.processVideo,
                onCheckedChange = { checked ->
                    scope.launch {
                        dataStore.updateData {
                            it.setVideoRecorderSettings(
                                it.videoRecorderSettings.setProcessVideo(checked)
                            )
                        }
                    }
                }
            )
        }
    )
}
