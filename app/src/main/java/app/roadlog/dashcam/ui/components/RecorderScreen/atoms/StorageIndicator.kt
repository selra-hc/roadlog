package app.roadlog.dashcam.ui.components.RecorderScreen.atoms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.roadlog.dashcam.R
import app.roadlog.dashcam.db.AppSettings
import app.roadlog.dashcam.helpers.BatchesFolder
import app.roadlog.dashcam.ui.theme.RoadLogTheme
import app.roadlog.dashcam.ui.utils.formatFileSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val POLL_INTERVAL_MILLIS = 15_000L

// The ring's fill fraction is deliberately NOT "bytes used vs. total device capacity" —
// that's nearly meaningless on a modern phone with hundreds of GB free, the ring would
// just always read ~full. Instead it reuses the exact same reference point
// `LowStorageInfo.kt` already warns against falling under: `requiredBytes`, the storage
// needed to hold one full rolling-buffer window at the current `maxDuration` setting. The
// ring is full (1f) whenever available space is at or above that, and drains toward 0 as
// available space approaches (and then warns at, via `LowStorageInfo`) that floor — i.e.
// it reads as "reserve capacity for the currently configured recording window," not "how
// full is my phone."
@Composable
fun StorageIndicator(
    batchesFolder: BatchesFolder,
    settings: AppSettings,
    modifier: Modifier = Modifier,
) {
    // Initial value computed synchronously on first composition, same as the existing
    // `LowStorageInfo.kt` precedent for this exact call — kept consistent with that rather
    // than introducing a different (async-only) convention for the same underlying I/O.
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
    val bytesPerMinute = BatchesFolder.requiredBytesForOneMinuteOfRecording(settings)
    val requiredBytes = (settings.maxDuration / 1000 / 60) * bytesPerMinute
    val ratio = if (requiredBytes <= 0) {
        1f
    } else {
        (available.toFloat() / requiredBytes.toFloat()).coerceIn(0f, 1f)
    }

    val ringColor = when {
        ratio < 0.15f -> RoadLogTheme.colors.dangerRed
        ratio < 0.5f -> RoadLogTheme.colors.warningOrange
        else -> RoadLogTheme.colors.recordStart
    }

    val label = stringResource(R.string.ui_recorder_storage_available_label, formatFileSize(available))

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.semantics { contentDescription = label },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(20.dp),
        ) {
            CircularProgressIndicator(
                progress = { ratio },
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.5.dp,
                color = ringColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
