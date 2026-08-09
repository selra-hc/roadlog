package app.roadlog.dashcam

import android.content.Context
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.datastore.dataStore
import app.roadlog.dashcam.db.AppSettings
import app.roadlog.dashcam.db.AppSettingsSerializer
import app.roadlog.dashcam.ui.LockedAppHandlers
import app.roadlog.dashcam.ui.Navigation
import app.roadlog.dashcam.ui.theme.DefaultAccentColor
import app.roadlog.dashcam.ui.theme.RoadLogTheme

const val SETTINGS_FILE = "settings.json"
val Context.dataStore by dataStore(
    fileName = SETTINGS_FILE,
    serializer = AppSettingsSerializer()
)

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val settings = LocalContext.current
                .dataStore
                .data
                .collectAsState(initial = null)
                .value

            RoadLogTheme(
                amoled = settings?.theme == AppSettings.Theme.AMOLED,
                accentColor = settings?.accentColorArgb?.let { Color(it) } ?: DefaultAccentColor,
            ) {
                LockedAppHandlers()

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            MaterialTheme.colorScheme.background
                        )
                ) {
                    Navigation()
                }
            }
        }
    }
}
