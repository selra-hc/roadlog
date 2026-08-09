package app.roadlog.dashcam.ui.components.SettingsScreen.Tiles

import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.roadlog.dashcam.R
import app.roadlog.dashcam.dataStore
import app.roadlog.dashcam.db.AppSettings
import app.roadlog.dashcam.ui.components.atoms.SettingsTile
import app.roadlog.dashcam.ui.utils.openOverlaySettings
import kotlinx.coroutines.launch

// §3.2 of newFeat.md — `SYSTEM_ALERT_WINDOW` is a special permission that can't be granted
// through the normal runtime-permission dialog, so toggling this ON without it already
// granted shows a rationale first, then routes the user to the system "draw over other
// apps" screen instead of persisting immediately. The tile stays OFF until the user comes
// back with permission actually granted — re-checked on every `ON_RESUME` since there's no
// callback for "user granted an overlay permission."
@Composable
fun PipEnabledTile(
    settings: AppSettings,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val dataStore = context.dataStore

    var showPermissionRationale by remember { mutableStateOf(false) }
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun persist(checked: Boolean) {
        scope.launch {
            dataStore.updateData {
                it.setPipSettings(it.pip.copy(enabled = checked))
            }
        }
    }

    if (showPermissionRationale) {
        AlertDialog(
            onDismissRequest = { showPermissionRationale = false },
            title = {
                Text(stringResource(R.string.ui_settings_option_pipEnabled_permissionRationale_title))
            },
            text = {
                Text(stringResource(R.string.ui_settings_option_pipEnabled_permissionRationale_message))
            },
            icon = {
                Icon(Icons.Default.PictureInPicture, contentDescription = null)
            },
            confirmButton = {
                val label = stringResource(R.string.ui_settings_option_pipEnabled_permissionRationale_confirm)
                Button(
                    onClick = {
                        showPermissionRationale = false
                        context.openOverlaySettings()
                    },
                ) {
                    Text(label)
                }
            },
            dismissButton = {
                val label = stringResource(R.string.dialog_close_cancel_label)
                TextButton(
                    onClick = { showPermissionRationale = false },
                ) {
                    Icon(
                        Icons.Default.Cancel,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                    Text(label)
                }
            },
        )
    }

    SettingsTile(
        title = stringResource(R.string.ui_settings_option_pipEnabled_title),
        description = stringResource(R.string.ui_settings_option_pipEnabled_description),
        leading = {
            Icon(
                Icons.Default.PictureInPicture,
                contentDescription = null,
            )
        },
        trailing = {
            val label = stringResource(R.string.ui_settings_option_pipEnabled_title)
            Switch(
                modifier = Modifier.semantics {
                    contentDescription = label
                },
                // Visually stays OFF until permission is actually granted, even though
                // `settings.pip.enabled` may already be true from a previous grant —
                // `hasOverlayPermission` is the source of truth for whether the overlay can
                // actually show, so this switch's checked state reflects "will this really
                // work right now," not just the persisted intent.
                checked = settings.pip.enabled && hasOverlayPermission,
                onCheckedChange = { checked ->
                    if (checked && !hasOverlayPermission) {
                        showPermissionRationale = true
                    } else {
                        persist(checked)
                    }
                }
            )
        }
    )
}
