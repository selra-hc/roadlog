package app.roadlog.dashcam.ui.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import app.roadlog.dashcam.dataStore
import app.roadlog.dashcam.db.AppSettings

@Composable
fun rememberSettings(): AppSettings {
    return LocalContext.current.dataStore.data.collectAsState(initial = AppSettings.getDefaultInstance()).value
}
