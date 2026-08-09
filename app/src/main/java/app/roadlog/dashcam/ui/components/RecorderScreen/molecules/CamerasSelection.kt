package app.roadlog.dashcam.ui.components.RecorderScreen.molecules

import CAMERA_LENS_ICON_MAP
import CameraSelectionButton
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalLensFacing
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.roadlog.dashcam.R
import app.roadlog.dashcam.ui.models.VideoRecorderModel
import app.roadlog.dashcam.ui.utils.CameraInfo

@Composable
fun CamerasSelection(
    cameras: Iterable<CameraInfo>,
    videoSettings: VideoRecorderModel,
    supportsDualRecording: Boolean = false,
) {
    val CAMERA_LENS_TEXT_MAP = mapOf(
        CameraInfo.Lens.BACK to stringResource(R.string.ui_videoRecorder_action_start_settings_cameraLens_back_label),
        CameraInfo.Lens.FRONT to stringResource(R.string.ui_videoRecorder_action_start_settings_cameraLens_front_label),
        CameraInfo.Lens.EXTERNAL to stringResource(R.string.ui_videoRecorder_action_start_settings_cameraLens_external_label),
        CameraInfo.Lens.UNKNOWN to stringResource(R.string.ui_videoRecorder_action_start_settings_cameraLens_unknown_label),
    )

    Column {
        if (CameraInfo.checkHasNormalCameras(cameras)) {
            CameraSelectionButton(
                icon = CAMERA_LENS_ICON_MAP[CameraInfo.Lens.BACK]!!,
                label = stringResource(R.string.ui_videoRecorder_action_start_settings_cameraLens_back_label),
                selected = !videoSettings.dualRecordingEnabled && videoSettings.cameraID == CameraInfo.Lens.BACK.androidValue,
                onSelected = {
                    videoSettings.cameraID = CameraInfo.Lens.BACK.androidValue
                    videoSettings.dualRecordingEnabled = false
                },
            )
            CameraSelectionButton(
                icon = CAMERA_LENS_ICON_MAP[CameraInfo.Lens.FRONT]!!,
                label = stringResource(R.string.ui_videoRecorder_action_start_settings_cameraLens_front_label),
                selected = !videoSettings.dualRecordingEnabled && videoSettings.cameraID == CameraInfo.Lens.FRONT.androidValue,
                onSelected = {
                    videoSettings.cameraID = CameraInfo.Lens.FRONT.androidValue
                    videoSettings.dualRecordingEnabled = false
                },
            )
        } else {
            cameras.forEach { camera ->
                CameraSelectionButton(
                    icon = CAMERA_LENS_ICON_MAP[camera.lens]!!,
                    selected = !videoSettings.dualRecordingEnabled && videoSettings.cameraID == camera.id,
                    onSelected = {
                        videoSettings.cameraID = camera.id
                        videoSettings.dualRecordingEnabled = false
                    },
                    label = stringResource(
                        R.string.ui_videoRecorder_action_start_settings_cameraLens_label,
                        camera.id
                    ),
                    description = CAMERA_LENS_TEXT_MAP[camera.lens]!!,
                )
            }
        }

        // Deliberately outside the `checkHasNormalCameras` branching above — that check
        // only asks "does this device have exactly 2 cameras total," which is false on
        // most modern phones (extra ultra-wide/telephoto lenses on the back alone push the
        // count past 2), even when the device's HAL can perfectly well run front+back
        // concurrently. `supportsDualRecording` (from CameraX's own concurrent-camera
        // capability query, §9.3) is the only thing that should gate this option.
        if (supportsDualRecording) {
            CameraSelectionButton(
                icon = Icons.Default.FlipCameraAndroid,
                label = stringResource(R.string.ui_videoRecorder_action_start_settings_cameraLens_dual_label),
                selected = videoSettings.dualRecordingEnabled,
                onSelected = {
                    videoSettings.dualRecordingEnabled = true
                },
            )
        }
    }
}