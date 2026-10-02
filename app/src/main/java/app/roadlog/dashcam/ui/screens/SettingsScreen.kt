package app.roadlog.dashcam.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.roadlog.dashcam.R
import app.roadlog.dashcam.dataStore
import app.roadlog.dashcam.ui.components.SettingsScreen.Tiles.AboutTile
import app.roadlog.dashcam.ui.components.SettingsScreen.Tiles.AutoStartOnLaunchTile
import app.roadlog.dashcam.ui.components.SettingsScreen.Tiles.DimScreenWhileRecordingTile
import app.roadlog.dashcam.ui.components.SettingsScreen.Tiles.DeleteRecordingsImmediatelyTile
import app.roadlog.dashcam.ui.components.SettingsScreen.Tiles.DividerTitle
import app.roadlog.dashcam.ui.components.SettingsScreen.Tiles.FilenameFormatTile
import app.roadlog.dashcam.ui.components.SettingsScreen.Tiles.ImpactDetectionDurationTile
import app.roadlog.dashcam.ui.components.SettingsScreen.Tiles.ImpactDetectionEnabledTile
import app.roadlog.dashcam.ui.components.SettingsScreen.Tiles.ImportExport
import app.roadlog.dashcam.ui.components.SettingsScreen.Tiles.IntervalDurationTile
import app.roadlog.dashcam.ui.components.SettingsScreen.Tiles.MaxDurationTile
import app.roadlog.dashcam.ui.components.SettingsScreen.Tiles.PipEnabledTile
import app.roadlog.dashcam.ui.components.SettingsScreen.Tiles.PipIdleSuspendTile
import app.roadlog.dashcam.ui.components.SettingsScreen.Tiles.ProcessVideoTile
import app.roadlog.dashcam.ui.components.SettingsScreen.Tiles.SaveFolderTile
import app.roadlog.dashcam.ui.components.SettingsScreen.Tiles.WatermarkSettingsSection
import app.roadlog.dashcam.ui.components.SettingsScreen.Tiles.VideoRecorderBitrateTile
import app.roadlog.dashcam.ui.components.SettingsScreen.Tiles.VideoRecorderDisableAutofocusTile
import app.roadlog.dashcam.ui.components.SettingsScreen.Tiles.VideoRecorderFrameRateTile
import app.roadlog.dashcam.ui.components.SettingsScreen.Tiles.VideoRecorderLightweightSecondaryStreamTile
import app.roadlog.dashcam.ui.components.SettingsScreen.Tiles.VideoRecorderQualityTile
import app.roadlog.dashcam.ui.components.SettingsScreen.atoms.AccentColorPicker
import app.roadlog.dashcam.ui.components.SettingsScreen.atoms.InAppLanguagePicker
import app.roadlog.dashcam.ui.components.SettingsScreen.atoms.ThemeSelector
import app.roadlog.dashcam.ui.components.atoms.GlobalSwitch
import app.roadlog.dashcam.ui.components.atoms.MessageBox
import app.roadlog.dashcam.ui.components.atoms.MessageType
import app.roadlog.dashcam.ui.effects.rememberSettings
import app.roadlog.dashcam.ui.models.VideoRecorderModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToAboutScreen: () -> Unit,
    videoRecorder: VideoRecorderModel,
) {
    val snackbarHostState = remember { SnackbarHostState() }

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val scope = rememberCoroutineScope()
            val dataStore = LocalContext.current.dataStore
            val settings = rememberSettings()

            // Show alert
            if (videoRecorder.isInRecording) {
                Box(
                    modifier = Modifier
                        .padding(16.dp)
                ) {
                    MessageBox(
                        type = MessageType.WARNING,
                        title = stringResource(R.string.ui_settings_hint_recordingActive_title),
                        message = stringResource(R.string.ui_settings_hint_recordingActive_message),
                    )
                }
            }

            // Hardware-encoder guardrail (§9.6's energy-efficiency pass) — advisory only,
            // set by `VideoRecorderService.checkEncoderCapability()` at camera-open time.
            // Dismissing just clears the flag client-side; it re-sets itself on the next
            // recording if the check still finds a software encoder, so this never
            // permanently silences a real, ongoing condition.
            if (settings.softwareEncoderDetected) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                ) {
                    Column {
                        MessageBox(
                            type = MessageType.WARNING,
                            title = stringResource(R.string.ui_settings_hint_softwareEncoder_title),
                            message = stringResource(R.string.ui_settings_hint_softwareEncoder_message),
                        )
                        TextButton(
                            onClick = {
                                scope.launch {
                                    dataStore.updateData {
                                        it.setSoftwareEncoderDetected(false)
                                    }
                                }
                            },
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text(stringResource(R.string.ui_settings_hint_softwareEncoder_dismiss))
                        }
                    }
                }
            }

            // General settings (appearance + core recording basics) — grouped under one
            // header, ordered before the dashcam-specific Watermark/Impact Detection
            // sections and the Advanced Settings toggle below.
            Column {
                DividerTitle(
                    title = stringResource(R.string.ui_settings_sections_general_title),
                    description = stringResource(R.string.ui_settings_sections_general_description),
                )
                ThemeSelector(settings = settings)
                AccentColorPicker(settings = settings)
                AutoStartOnLaunchTile(settings = settings)
                DimScreenWhileRecordingTile(settings = settings)
                PipEnabledTile(settings = settings)
                PipIdleSuspendTile(settings = settings)
                MaxDurationTile(settings = settings)
                IntervalDurationTile(settings = settings)
                InAppLanguagePicker()
                DeleteRecordingsImmediatelyTile(settings = settings)
                FilenameFormatTile(settings = settings, snackbarHostState = snackbarHostState)
                SaveFolderTile(
                    settings = settings,
                    snackbarHostState = snackbarHostState,
                )
            }

            // Watermark / Impact Detection (§9.3) — primary dashcam features, kept just
            // before the Advanced Settings toggle.
            WatermarkSettingsSection(settings = settings)
            Column {
                DividerTitle(
                    title = stringResource(R.string.ui_settings_sections_impactDetection_title),
                    description = stringResource(R.string.ui_settings_sections_impactDetection_description),
                )
                ImpactDetectionEnabledTile(settings = settings)
                ImpactDetectionDurationTile(settings = settings)
            }

            GlobalSwitch(
                label = stringResource(R.string.ui_settings_advancedSettings_label),
                checked = settings.showAdvancedSettings,
                onCheckedChange = {
                    scope.launch {
                        dataStore.updateData {
                            it.setShowAdvancedSettings(it.showAdvancedSettings.not())
                        }
                    }
                }
            )
            AnimatedVisibility(visible = settings.showAdvancedSettings) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(32.dp),
                ) {
                    Column {
                        DividerTitle(
                            title = stringResource(R.string.ui_settings_sections_video_title),
                            description = stringResource(R.string.ui_settings_sections_video_description),
                        )
                        VideoRecorderQualityTile(settings = settings)
                        VideoRecorderBitrateTile(settings = settings)
                        VideoRecorderFrameRateTile(settings = settings)
                        VideoRecorderDisableAutofocusTile(settings = settings)
                        ProcessVideoTile(settings = settings)
                        VideoRecorderLightweightSecondaryStreamTile(settings = settings)
                    }
                    HorizontalDivider(
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                    )
                    ImportExport(settings = settings, snackbarHostState = snackbarHostState)
                }
            }
            AboutTile(onNavigateToAboutScreen)
        }
    }
}
