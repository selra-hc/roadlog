package app.roadlog.dashcam.ui.components.RecorderScreen.atoms

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.roadlog.dashcam.R
import app.roadlog.dashcam.db.AppSettings
import app.roadlog.dashcam.helpers.BatchesFolder
import app.roadlog.dashcam.helpers.VideoBatchesFolder
import app.roadlog.dashcam.ui.components.atoms.MessageBox
import app.roadlog.dashcam.ui.components.atoms.MessageType
import app.roadlog.dashcam.ui.components.atoms.VisualDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val POLL_INTERVAL_MILLIS = 15_000L

@Composable
fun LowStorageInfo(
    modifier: Modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    appSettings: AppSettings,
) {
    val context = LocalContext.current

    // Resource-consumption fix: this used to call `getAvailableBytes()` (a `StatFs`
    // filesystem call) directly in the composable body, with no caching — meaning it
    // ran again on every single recomposition of this composable, not just
    // periodically. Matched to `StorageIndicator`'s own established pattern for this
    // exact same underlying I/O (whose own doc comment cites this function as the
    // precedent for "compute once, refresh periodically" — a precedent this function
    // itself never actually followed): computed once via `remember`, then refreshed on
    // a fixed interval via `LaunchedEffect`, off the main thread.
    val batchesFolder = remember(appSettings.saveFolder) {
        VideoBatchesFolder.importFromFolder(appSettings.saveFolder, context)
    }
    var availableBytes by remember(batchesFolder) {
        mutableStateOf(batchesFolder.getAvailableBytes())
    }

    LaunchedEffect(batchesFolder) {
        while (true) {
            delay(POLL_INTERVAL_MILLIS)
            availableBytes = withContext(Dispatchers.IO) { batchesFolder.getAvailableBytes() }
        }
    }

    val available = availableBytes ?: return

    val bytesPerMinute = BatchesFolder.requiredBytesForOneMinuteOfRecording(appSettings)
    val requiredBytes = appSettings.maxDuration / 1000 / 60 * bytesPerMinute

    // Allow for a 10% margin of error
    val isLowOnStorage = available < requiredBytes * 1.1

    if (isLowOnStorage)
        Box(modifier = modifier) {
            BoxWithConstraints {
                val isLarge = maxHeight > 600.dp;

                MessageBox(
                    type = MessageType.WARNING,
                    message = if (appSettings.saveFolder == null)
                        stringResource(R.string.ui_recorder_lowOnStorage_hintANDswitchSaveFolder)
                    else stringResource(R.string.ui_recorder_lowOnStorage_hint),
                    density = if (isLarge) VisualDensity.COMFORTABLE else VisualDensity.COMPACT
                )
            }
        }
}
