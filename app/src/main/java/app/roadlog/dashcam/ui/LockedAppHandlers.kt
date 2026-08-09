package app.roadlog.dashcam.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.roadlog.dashcam.R
import app.roadlog.dashcam.dataStore
import app.roadlog.dashcam.helpers.Doctor
import kotlinx.coroutines.flow.map

// App-wide startup handlers, run once regardless of which screen/tab is active.
@Composable
fun LockedAppHandlers() {
    val context = LocalContext.current
    // Gate everything below on settings having loaded at least once. Mapped to a constant
    // before collecting so this only recomposes on the one real false->true transition,
    // instead of subscribing to (and recomposing on) every single settings write app-wide
    // for a value that's discarded immediately after this check.
    val hasLoaded by context
        .dataStore
        .data
        .map { true }
        .collectAsState(initial = false)
    if (!hasLoaded) return

    // RoadLog is always dark (§8.2) — force AppCompat's night mode once so any
    // AppCompat-native chrome matches, regardless of the device's system-wide
    // day/night setting.
    LaunchedEffect(Unit) {
        if (AppCompatDelegate.getDefaultNightMode() != AppCompatDelegate.MODE_NIGHT_YES) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
    }

    var showFileSaverUnavailableDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val doctor = Doctor(context)

        if (!doctor.checkIfFileSaverDialogIsAvailable()) {
            showFileSaverUnavailableDialog = true
        }
    }

    if (showFileSaverUnavailableDialog) {
        AlertDialog(
            icon = {
                Icon(
                    Icons.Default.Error,
                    contentDescription = null
                )
            },
            onDismissRequest = {
                showFileSaverUnavailableDialog = false
            },
            title = {
                Text(stringResource(R.string.ui_severeError_fileSaverUnavailable_title))
            },
            text = {
                Text(stringResource(R.string.ui_severeError_fileSaverUnavailable_text))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFileSaverUnavailableDialog = false
                    }
                ) {
                    Text(text = stringResource(R.string.dialog_close_neutral_label))
                }
            }
        )
    }
}