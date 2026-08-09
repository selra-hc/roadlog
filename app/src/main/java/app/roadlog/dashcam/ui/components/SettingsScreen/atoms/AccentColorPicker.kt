package app.roadlog.dashcam.ui.components.SettingsScreen.atoms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.roadlog.dashcam.R
import app.roadlog.dashcam.dataStore
import app.roadlog.dashcam.db.AppSettings
import app.roadlog.dashcam.ui.theme.AccentPrimary
import app.roadlog.dashcam.ui.theme.DefaultAccentColor
import kotlinx.coroutines.launch

// Preset swatches rather than a full HSV wheel/picker — a smaller, lower-risk surface
// that still lets users personalize the single Material accent used by every
// Switch/Button/toggle app-wide, without pulling in a new custom-picker dependency.
private val ACCENT_PRESETS = listOf(
    DefaultAccentColor, // green — the default
    AccentPrimary, // purple
    Color(0xFF64B5F6), // blue
    Color(0xFFFFB74D), // amber
    Color(0xFFE57373), // red
    Color(0xFF4DB6AC), // teal
    Color(0xFFF06292), // pink
    Color(0xFFFFF176), // yellow
)

@Composable
fun AccentColorPicker(settings: AppSettings) {
    val scope = rememberCoroutineScope()
    val dataStore = LocalContext.current.dataStore

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.ui_settings_option_accentColor_title),
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            ACCENT_PRESETS.forEach { color ->
                val isSelected = settings.accentColorArgb == color.toArgb()
                val onColor = if (color.luminance() > 0.5f) Color.Black else Color.White

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            width = if (isSelected) 2.dp else 0.dp,
                            color = MaterialTheme.colorScheme.onSurface,
                            shape = CircleShape,
                        )
                        .clickable {
                            scope.launch {
                                dataStore.updateData {
                                    it.setAccentColor(color.toArgb())
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = onColor,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}
