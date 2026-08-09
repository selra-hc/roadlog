package app.roadlog.dashcam.ui.utils

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

@Composable
fun rememberIsInDarkMode(): Boolean {
    return isSystemInDarkTheme()
}