package app.roadlog.dashcam.ui.utils

import java.text.DateFormat
import java.util.Date
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

// For the Recordings/Gallery screen's row label (§9.2) — a human-friendly
// "Jul 16, 2026, 2:32 PM"-style rendering of a saved recording's last-modified time.
fun formatRecordingDate(epochMillis: Long): String {
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))
}

// For the Recordings/Gallery screen's file-size label (§9.2), e.g. "42.3 MB".
fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) {
        return "$bytes B"
    }

    val units = arrayOf("KB", "MB", "GB", "TB")
    val exponent = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(1, units.size)
    val value = bytes / 1024.0.pow(exponent)

    return "%.1f %s".format(value, units[exponent - 1])
}


fun formatDuration(
    durationInMilliseconds: Long,
    formatFull: Boolean = false,
): String {
    val totalSeconds = durationInMilliseconds / 1000

    val hours = floor(totalSeconds / 3600.0).toInt()
    val minutes = floor(totalSeconds / 60.0).toInt() % 60
    val seconds = totalSeconds - (minutes * 60) - (hours * 3600)

    if (formatFull) {
        return "" +
            hours.toString().padStart(2, '0') +
            ":" + minutes.toString().padStart(2, '0') +
            ":" + seconds.toString().padStart(2, '0') +
            "." + (durationInMilliseconds % 1000).toString()
    }

    if (durationInMilliseconds < 1000) {
        return "00:00.$durationInMilliseconds"
    }

    if (totalSeconds < 60) {
        return "00:${totalSeconds.toString().padStart(2, '0')}"
    }

    if (hours <= 0) {
        return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }

    return "" +
        hours.toString().padStart(2, '0') +
        ":" + minutes.toString().padStart(2, '0') +
        ":" + seconds.toString().padStart(2, '0')
}
