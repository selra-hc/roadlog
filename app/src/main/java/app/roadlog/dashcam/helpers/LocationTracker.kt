package app.roadlog.dashcam.helpers

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import app.roadlog.dashcam.ui.utils.PermissionHelper
import kotlin.math.roundToInt

enum class SpeedUnit(val label: String) {
    KMH("km/h"),
    MPH("mph"),
}

// Tracks live speed via plain `LocationManager` — deliberately not
// `FusedLocationProviderClient`, since this app has a hard requirement to avoid Google Play
// Services entirely (§6, §16). Fed into `WatermarkTextProvider` for the burned-in speed line,
// and started/stopped alongside the rolling-buffer recording service's lifecycle so location
// updates keep flowing even when the app is backgrounded/split-screen.
class LocationTracker(private val context: Context) {
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // Written from the main looper (LocationListener callbacks below run there) but read
    // from whatever thread calls currentSpeedText() — the watermark subsystem reads it
    // once per rendered frame from its own background thread (§5). @Volatile ensures
    // that thread sees an up-to-date value instead of a stale cached read.
    @Volatile
    private var lastLocation: Location? = null

    @Volatile
    private var previousLocation: Location? = null

    // `LocationListener`'s onStatusChanged/onProviderEnabled/onProviderDisabled only became
    // default (optional-to-override) methods in API 30. Overriding all four explicitly
    // (instead of a SAM lambda for just onLocationChanged) keeps this binary-compatible
    // with this app's minSdk 24 — a lambda here would crash with AbstractMethodError on
    // API 24-29 devices at runtime despite compiling fine against a newer compileSdk.
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            previousLocation = lastLocation
            lastLocation = location
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    // ~1Hz updates, matching typical dashcam speed-refresh expectations (§6).
    fun start(updateIntervalMs: Long = DEFAULT_UPDATE_INTERVAL_MS) {
        if (!PermissionHelper.hasGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
            // No permission (yet) — currentSpeedText() already degrades to "-- km/h" with
            // no location fix, so there's nothing more to do here. Whatever future
            // permission-request flow grants this permission is expected to call start()
            // again afterwards.
            return
        }

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                updateIntervalMs,
                0f,
                listener,
                Looper.getMainLooper(),
            )
        } catch (error: IllegalArgumentException) {
            // Device has no GPS provider at all — stay in the "-- km/h" fallback state.
        }
    }

    fun stop() {
        locationManager.removeUpdates(listener)
        lastLocation = null
        previousLocation = null
    }

    // Returns e.g. "58 km/h", or "-- km/h" if there's no fix, the last fix is stale (>5s
    // old), or the app doesn't have location permission — never blocks or throws (§5.2, §6).
    fun currentSpeedText(unit: SpeedUnit): String {
        val speedMetersPerSecond = currentSpeedMetersPerSecond()
            ?: return "-- ${unit.label}"

        val converted = when (unit) {
            SpeedUnit.KMH -> speedMetersPerSecond * 3.6f
            SpeedUnit.MPH -> speedMetersPerSecond * 2.23694f
        }

        return "${converted.roundToInt()} ${unit.label}"
    }

    private fun currentSpeedMetersPerSecond(): Float? {
        val location = lastLocation ?: return null

        if (isStale(location)) {
            return null
        }

        // Prefer the device-reported speed (itself GPS-Doppler-derived on most chipsets —
        // accurate and low-jitter, §6) unless it's missing or flagged as low-accuracy, in
        // which case fall back to a manual delta-distance/delta-time calculation between
        // the two most recent fixes.
        if (location.hasSpeed() && !hasPoorSpeedAccuracy(location)) {
            return location.speed
        }

        return manualSpeedFromDelta(location) ?: location.speed.takeIf { location.hasSpeed() }
    }

    private fun manualSpeedFromDelta(location: Location): Float? {
        val previous = previousLocation ?: return null

        val elapsedSeconds =
            (location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000_000f
        if (elapsedSeconds <= 0f) {
            return null
        }

        val distanceMeters = previous.distanceTo(location)
        return distanceMeters / elapsedSeconds
    }

    private fun hasPoorSpeedAccuracy(location: Location): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false
        }

        return location.hasSpeedAccuracy() &&
                location.speedAccuracyMetersPerSecond > POOR_SPEED_ACCURACY_THRESHOLD_MPS
    }

    private fun isStale(location: Location): Boolean {
        val ageMs =
            (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        return ageMs > STALE_FIX_THRESHOLD_MS
    }

    companion object {
        const val DEFAULT_UPDATE_INTERVAL_MS = 1000L
        private const val STALE_FIX_THRESHOLD_MS = 5_000L
        private const val POOR_SPEED_ACCURACY_THRESHOLD_MPS = 5f
    }
}
