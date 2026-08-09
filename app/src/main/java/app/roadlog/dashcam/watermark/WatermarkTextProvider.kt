package app.roadlog.dashcam.watermark

import app.roadlog.dashcam.db.WatermarkSettings
import app.roadlog.dashcam.helpers.LocationTracker
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Simplified to this app's scope: no reverse-geocoding, no UTM, no weather/noise/compass
// lines — just timestamp + speed (§5.2).
class WatermarkTextProvider(
    private val locationTracker: LocationTracker,
) {
    // Resource-consumption optimization, not a behavior change: the displayed timestamp
    // only has one-second granularity ("HH:mm:ss") and `LocationTracker`'s own GPS fixes
    // only arrive at ~1Hz (`LocationSettings.updateIntervalMs`) — so within any given
    // second, every one of this method's callers (the watermark draw callback, called
    // once per rendered camera frame, §5.1 — up to 30-60x/sec while recording, the only
    // truly hot path in this app) was recomputing and reallocating the exact same string
    // via `DateTimeFormatter.format()` (itself a moderately expensive call — internal
    // `StringBuilder`, locale-aware field formatting) purely because nothing throttled
    // it. Caching by epoch-second (plus the speed text, which can change independently
    // of the second boundary if a fresher GPS fix arrives) means the expensive
    // formatting/allocation work now happens at most once per second instead of once per
    // frame, while the returned text is bit-for-bit identical to what the uncached
    // version would have produced at the same instant.
    private var cachedText: String? = null
    private var cachedEpochSecond: Long = Long.MIN_VALUE
    private var cachedSpeedText: String? = null

    fun currentText(settings: WatermarkSettings): String {
        val epochSecond = Instant.now().epochSecond
        val speedText = locationTracker.currentSpeedText(settings.speedUnit)

        val cached = cachedText
        if (cached != null && epochSecond == cachedEpochSecond && speedText == cachedSpeedText) {
            return cached
        }

        val timestamp = FORMATTER.format(
            Instant.ofEpochSecond(epochSecond).atZone(ZoneId.systemDefault())
        )
        val text = "$timestamp\n$speedText"

        cachedText = text
        cachedEpochSecond = epochSecond
        cachedSpeedText = speedText

        return text
    }

    companion object {
        // "2026-07-16 14:32:07"
        private val FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
