package app.roadlog.dashcam.ui.screens

import android.Manifest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.roadlog.dashcam.db.AppSettings
import app.roadlog.dashcam.ui.components.RecorderScreen.organisms.RecorderEventsHandler
import app.roadlog.dashcam.ui.components.RecorderScreen.organisms.StartRecording
import app.roadlog.dashcam.ui.components.RecorderScreen.organisms.VideoRecordingStatus
import app.roadlog.dashcam.ui.models.VideoRecorderModel
import app.roadlog.dashcam.ui.utils.PermissionHelper
import kotlinx.coroutines.launch

// Navigation to Recordings/Settings now happens through the bottom nav bar (§8.3), wired
// in `Navigation.kt` — this screen no longer owns its own nav icon buttons for them.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecorderScreen(
    videoRecorder: VideoRecorderModel,
    settings: AppSettings,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    RecorderEventsHandler(
        settings = settings,
        snackbarHostState = snackbarHostState,
        videoRecorder = videoRecorder,
    )

    // Auto-start-on-launch (§9.1) — a dashcam is expected to start recording the moment
    // the app opens, without a manual tap. Only fires once per app session (guarded by
    // `hasAttemptedAutoStart`) and only when recording isn't already active — e.g. the
    // service reconnected mid-recording after the app was backgrounded and reopened.
    // Silently falls through to the normal manual-start UI if a required permission
    // isn't granted yet, mirroring `VideoRecordingStart`'s own permission check exactly
    // — there's no way to auto-grant a runtime permission without user interaction, so
    // this is the graceful degradation path, not a bug.
    LaunchedEffect(Unit) {
        if (settings.autoStartOnLaunch &&
            !videoRecorder.hasAttemptedAutoStart &&
            !videoRecorder.isInRecording
        ) {
            videoRecorder.hasAttemptedAutoStart = true

            val hasRequiredPermissions = !settings.requiresExternalStoragePermission(context) &&
                    PermissionHelper.hasGranted(context, Manifest.permission.CAMERA) &&
                    PermissionHelper.hasGranted(context, Manifest.permission.RECORD_AUDIO)

            if (hasRequiredPermissions) {
                videoRecorder.startRecording(context, settings)
            }
        }
    }

    // Drives the press-and-hold camera-preview-peek gesture (StartRecording/
    // VideoRecordingStart) — `false` while the user is holding the button down to peek
    // at the live camera feed.
    var topBarVisible by remember { mutableStateOf(true) }

    Scaffold(
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                snackbar = {
                    Snackbar(
                        snackbarData = it,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        actionColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        actionContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        dismissActionContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (videoRecorder.isInRecording)
                VideoRecordingStatus(videoRecorder = videoRecorder)
            else
                StartRecording(
                    videoRecorder = videoRecorder,
                    appSettings = settings,
                    onSaveLastRecording = {
                        scope.launch {
                            videoRecorder.onRecordingSave(false)
                        }
                    },
                    showTopBar = topBarVisible,
                    onHideTopBar = {
                        topBarVisible = false
                    },
                    onShowTopBar = {
                        topBarVisible = true
                    },
                )
        }
    }
}
