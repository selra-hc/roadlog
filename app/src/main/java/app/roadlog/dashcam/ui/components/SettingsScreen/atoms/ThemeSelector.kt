package app.roadlog.dashcam.ui.components.SettingsScreen.atoms

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.roadlog.dashcam.R
import app.roadlog.dashcam.dataStore
import app.roadlog.dashcam.db.AppSettings
import kotlinx.coroutines.launch

private val THEME_OPTIONS = listOf(
    AppSettings.Theme.DARK,
    AppSettings.Theme.AMOLED,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSelector(settings: AppSettings) {
    val scope = rememberCoroutineScope()

    val dataStore = LocalContext.current.dataStore

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.ui_settings_option_theme_title),
            style = MaterialTheme.typography.bodyLarge,
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            THEME_OPTIONS.forEachIndexed { index, theme ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = THEME_OPTIONS.size),
                    selected = settings.theme == theme,
                    onClick = {
                        scope.launch {
                            dataStore.updateData {
                                it.setTheme(theme)
                            }
                        }
                    },
                ) {
                    Text(
                        text = stringResource(
                            when (theme) {
                                AppSettings.Theme.DARK -> R.string.ui_settings_option_theme_value_dark
                                AppSettings.Theme.AMOLED -> R.string.ui_settings_option_theme_value_amoled
                            }
                        ),
                    )
                }
            }
        }
    }
}
