package app.roadlog.dashcam.db

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import app.roadlog.dashcam.helpers.SpeedUnit
import app.roadlog.dashcam.helpers.VideoBatchesFolder
import app.roadlog.dashcam.ui.RECORDER_MEDIA_SELECTED_VALUE
import app.roadlog.dashcam.ui.SUPPORTS_SCOPED_STORAGE
import app.roadlog.dashcam.ui.components.RecorderScreen.organisms.RecorderModel
import app.roadlog.dashcam.ui.utils.PermissionHelper
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDateTime

@Serializable
data class AppSettings(
    val videoRecorderSettings: VideoRecorderSettings = VideoRecorderSettings.getDefaultInstance(),

    val hasSeenOnboarding: Boolean = false,
    val showAdvancedSettings: Boolean = false,
    val theme: Theme = Theme.DARK,
    // Matches the green preset in AccentColorPicker.kt (0xFF81C784) — kept as a plain
    // ARGB Int here since this db layer otherwise has no dependency on Compose's Color
    // type.
    val accentColorArgb: Int = 0xFF81C784.toInt(),
    val lastRecording: RecordingInformation? = null,

    val filenameFormat: FilenameFormat = FilenameFormat.DATETIME_RELATIVE_START,

    /// Recording information
    // 30 minutes
    val maxDuration: Long = 15 * 60 * 1000L,
    // 60 seconds
    val intervalDuration: Long = 60 * 1000L,

    val deleteRecordingsImmediately: Boolean = false,
    val saveFolder: String? = null,

    val watermark: WatermarkSettings = WatermarkSettings(),
    val impactDetection: ImpactDetectionSettings = ImpactDetectionSettings(),
    val location: LocationSettings = LocationSettings(),
    val pip: PipSettings = PipSettings(),

    // Dashcam convention: the app opens and immediately begins recording without
    // requiring a manual tap (§9.1) — default on, since that's the expected behavior
    // for a mounted, always-on device.
    val autoStartOnLaunch: Boolean = true,

    // Fades the Record screen's backlight to a minimal brightness after a period of no
    // touch input while recording, restoring instantly on the next touch (§9.6's
    // energy-efficiency pass — `ui/utils/views.kt`'s `dimWhileRecording`). Off by
    // default: a real, visible behavior change to a screen users may rely on glancing
    // at, worth defaulting on only after real-device validation.
    val dimScreenWhileRecording: Boolean = false,

    // Set by `VideoRecorderService` after `EncoderCapabilityChecker` runs at camera-open
    // time (§9.6's energy-efficiency pass, hardware-encoder guardrail) — true only when
    // the resolved encoder for the currently-selected quality was confirmed NOT
    // hardware-accelerated. Advisory-only: surfaces a one-time Settings banner, never
    // changes recording behavior on its own. Re-evaluated (and can clear itself) on
    // every recording start, so this always reflects the most recent check, not a
    // permanently-sticky flag.
    val softwareEncoderDetected: Boolean = false,
) {
    fun setShowAdvancedSettings(showAdvancedSettings: Boolean): AppSettings {
        return copy(showAdvancedSettings = showAdvancedSettings)
    }

    fun setVideoRecorderSettings(videoRecorderSettings: VideoRecorderSettings): AppSettings {
        return copy(videoRecorderSettings = videoRecorderSettings)
    }

    fun setHasSeenOnboarding(hasSeenOnboarding: Boolean): AppSettings {
        return copy(hasSeenOnboarding = hasSeenOnboarding)
    }

    fun setTheme(theme: Theme): AppSettings {
        return copy(theme = theme)
    }

    fun setAccentColor(accentColorArgb: Int): AppSettings {
        return copy(accentColorArgb = accentColorArgb)
    }

    fun setLastRecording(lastRecording: RecordingInformation?): AppSettings {
        return copy(lastRecording = lastRecording)
    }

    fun setFilenameFormat(filenameFormat: FilenameFormat): AppSettings {
        return copy(filenameFormat = filenameFormat)
    }

    fun setMaxDuration(duration: Long): AppSettings {
        if (duration < 60 * 1000L || duration > 10 * 24 * 60 * 60 * 1000L) {
            throw Exception("Max duration must be between 1 minute and 10 days")
        }

        if (duration < intervalDuration) {
            throw Exception("Max duration must be greater than interval duration")
        }

        return copy(maxDuration = duration)
    }

    fun setIntervalDuration(duration: Long): AppSettings {
        if (duration < 10 * 1000L || duration > 60 * 60 * 1000L) {
            throw Exception("Interval duration must be between 10 seconds and 1 hour")
        }

        if (duration > maxDuration) {
            throw Exception("Interval duration must be less than max duration")
        }

        return copy(intervalDuration = duration)
    }

    fun setDeleteRecordingsImmediately(deleteRecordingsImmediately: Boolean): AppSettings {
        return copy(deleteRecordingsImmediately = deleteRecordingsImmediately)
    }

    fun setSaveFolder(saveFolder: String?): AppSettings {
        return copy(saveFolder = saveFolder)
    }

    fun setWatermarkSettings(watermark: WatermarkSettings): AppSettings {
        return copy(watermark = watermark)
    }

    fun setImpactDetectionSettings(impactDetection: ImpactDetectionSettings): AppSettings {
        return copy(impactDetection = impactDetection)
    }

    fun setLocationSettings(location: LocationSettings): AppSettings {
        return copy(location = location)
    }

    fun setPipSettings(pip: PipSettings): AppSettings {
        return copy(pip = pip)
    }

    fun setAutoStartOnLaunch(autoStartOnLaunch: Boolean): AppSettings {
        return copy(autoStartOnLaunch = autoStartOnLaunch)
    }

    fun setDimScreenWhileRecording(dimScreenWhileRecording: Boolean): AppSettings {
        return copy(dimScreenWhileRecording = dimScreenWhileRecording)
    }

    fun setSoftwareEncoderDetected(softwareEncoderDetected: Boolean): AppSettings {
        return copy(softwareEncoderDetected = softwareEncoderDetected)
    }

    fun saveLastRecording(recorder: RecorderModel): AppSettings {
        return if (deleteRecordingsImmediately) {
            this
        } else {
            setLastRecording(
                recorder.recorderService!!.getRecordingInformation()
            )
        }
    }

    fun requiresExternalStoragePermission(context: Context): Boolean {
        return !SUPPORTS_SCOPED_STORAGE && (saveFolder == RECORDER_MEDIA_SELECTED_VALUE && !PermissionHelper.hasGranted(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ))
    }

    fun exportToString(): String {
        return Json.encodeToString(serializer(), this)
    }

    // RoadLog is dark-first (§8.2) — no light theme, no system-following. AMOLED is a
    // pure-black variant of the same dark theme, not a separate light/dark axis.
    enum class Theme {
        DARK,
        AMOLED,
    }

    enum class FilenameFormat {
        DATETIME_ABSOLUTE_START,
        DATETIME_RELATIVE_START,
        DATETIME_NOW,
    }

    companion object {
        fun getDefaultInstance(): AppSettings = AppSettings()

        fun fromExportedString(data: String): AppSettings {
            return Json.decodeFromString(
                serializer(),
                data,
            )
        }

        // Example values used by the max/interval duration pickers.
        val EXAMPLE_MAX_DURATIONS = listOf(
            1 * 60 * 1000L,
            5 * 60 * 1000L,
            15 * 60 * 1000L,
            30 * 60 * 1000L,
            60 * 60 * 1000L,
        )
        val EXAMPLE_DURATION_TIMES = listOf(
            60 * 1000L,
            60 * 2 * 1000L,
            60 * 5 * 1000L,
            60 * 10 * 1000L,
            60 * 15 * 1000L,
        )
    }
}

@Serializable
data class RecordingInformation(
    val folderPath: String,
    @Serializable(with = LocalDateTimeSerializer::class)
    val recordingStart: LocalDateTime,
    val batchesAmount: Int,
    val maxDuration: Long,
    val intervalDuration: Long,
    val fileExtension: String,
    val type: Type,
    // True when this recording also has a secondary (front-camera) stream saved alongside
    // the primary — discoverable purely by its "-front"/"-front-auto" filename tag
    // (`BatchesFolder.FRONT_STREAM_FILENAME_TAG`, §9.3). Defaults false so already-persisted
    // (pre-dual-recording) `RecordingInformation` deserializes unaffected.
    val hasSecondaryStream: Boolean = false,
) {
    fun hasRecordingsAvailable(context: Context): Boolean =
        VideoBatchesFolder.importFromFolder(folderPath, context).hasRecordingsAvailable()

    fun getStartDateForFilename(filenameFormat: AppSettings.FilenameFormat): LocalDateTime {
        return when (filenameFormat) {
            AppSettings.FilenameFormat.DATETIME_ABSOLUTE_START -> recordingStart
            AppSettings.FilenameFormat.DATETIME_RELATIVE_START -> LocalDateTime.now().minusSeconds(
                getFullDuration() / 1000
            )

            AppSettings.FilenameFormat.DATETIME_NOW -> LocalDateTime.now()
        }
    }

    fun getFullDuration(): Long {
        // This is not accurate, since the last batch may be shorter than the others
        // but it's good enough
        return intervalDuration * batchesAmount - (intervalDuration * 0.5).toLong()
    }

    enum class Type {
        VIDEO,
    }
}

@Serializable
data class VideoRecorderSettings(
    val targetedVideoBitRate: Int? = null,
    val quality: String? = null,
    val targetFrameRate: Int? = null,
    // Locks the primary (rear) camera's focus to the far distance and turns off
    // continuous autofocus (§9.1) — a windshield-mounted dashcam's own dashboard/hood
    // sits much closer to the lens than the road it's actually meant to capture, and
    // autofocus can lock onto that near surface instead. Applied via Camera2 interop at
    // camera-open time (`VideoRecorderService`), like the rest of this class's settings —
    // not live-updatable mid-recording, same limitation as quality/bitrate/frame rate.
    val disableAutofocus: Boolean = false,
    // On (default): saved recordings are concatenated into a single file, same as
    // always. Off: chunks are never merged — saving just moves the rolling buffer's
    // currently-available chunk files, as-is, into the save folder, and the user relies
    // on those individual chunks rather than one combined video (§9.1). Read directly
    // from live settings by the Record screen's own Save button
    // (`RecorderEventsHandler.saveRecording()`, so a mid-recording toggle takes effect
    // on the very next save there); read from the service's frozen settings snapshot —
    // same "next recording session" limitation as the rest of this class — for the PIP
    // overlay's Save button and impact auto-save (both via `IntervalRecorderService
    // .concatenateCurrentBuffer()`).
    val processVideo: Boolean = true,
    // Dual (front+back) recording (§9.3) previously bound both cameras at the SAME
    // quality/bitrate/frame-rate as the primary — two concurrent hardware-encoder
    // instances at full quality, often two simultaneous 4K encodes (§9.6's
    // energy-efficiency pass). On (default): the secondary/front stream — driver-facing
    // verification footage, not the primary evidentiary road footage — instead records
    // at a fixed, deliberately lower profile (`VideoRecorderService
    // .SECONDARY_STREAM_QUALITY`/`_BITRATE`/`_FRAME_RATE`), roughly halving dual
    // recording's total encode cost. Off restores the original symmetric-quality
    // behavior. Only has any effect while dual recording is active; a no-op for
    // single-camera recording.
    val useLightweightSecondaryStream: Boolean = true,
) {
    fun setTargetedVideoBitRate(bitRate: Int?): VideoRecorderSettings {
        return copy(targetedVideoBitRate = bitRate)
    }

    fun setQuality(quality: Quality?): VideoRecorderSettings {
        val invertedMap = QUALITY_NAME_QUALITY_MAP.entries.associateBy({ it.value }, { it.key })

        return copy(quality = quality?.let { invertedMap[it] })
    }

    fun setTargetFrameRate(frameRate: Int?): VideoRecorderSettings {
        return copy(targetFrameRate = frameRate)
    }

    fun setDisableAutofocus(disableAutofocus: Boolean): VideoRecorderSettings {
        return copy(disableAutofocus = disableAutofocus)
    }

    fun setProcessVideo(processVideo: Boolean): VideoRecorderSettings {
        return copy(processVideo = processVideo)
    }

    fun setUseLightweightSecondaryStream(useLightweightSecondaryStream: Boolean): VideoRecorderSettings {
        return copy(useLightweightSecondaryStream = useLightweightSecondaryStream)
    }

    fun getQuality(): Quality? =
        quality?.let {
            QUALITY_NAME_QUALITY_MAP[it]!!
        }

    fun getQualitySelector(): QualitySelector? =
        quality?.let {
            QualitySelector.from(
                QUALITY_NAME_QUALITY_MAP[it]!!
            )
        }

    fun getMimeType() = "video/$fileExtension"

    val fileExtension
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "mp4" else "3gp"

    companion object {
        fun getDefaultInstance() = VideoRecorderSettings()

        val QUALITY_NAME_QUALITY_MAP: Map<String, Quality> = mapOf(
            "LOWEST" to Quality.LOWEST,
            "HIGHEST" to Quality.HIGHEST,
            "SD" to Quality.SD,
            "HD" to Quality.HD,
            "FHD" to Quality.FHD,
            "UHD" to Quality.UHD,
        )

        val EXAMPLE_BITRATE_VALUES = listOf(
            null,
            500 * 1000,
            // 1 Mbps
            1 * 1000 * 1000,
            2 * 1000 * 1000,
            4 * 1000 * 1000,
            8 * 1000 * 1000,
            16 * 1000 * 1000,
            32 * 1000 * 1000,
            50 * 1000 * 1000,
            100 * 1000 * 1000,
        )

        val EXAMPLE_FRAME_RATE_VALUES = listOf(
            null,
            24,
            30,
            60,
            120,
            240,
        )

        val AVAILABLE_QUALITIES = listOf(
            Quality.HIGHEST,
            Quality.UHD,
            Quality.FHD,
            Quality.HD,
            Quality.SD,
            Quality.LOWEST,
        )

        val EXAMPLE_QUALITY_VALUES = listOf(
            null,
        ) + AVAILABLE_QUALITIES
    }
}

// §11's schema for this — corner/speedUnit are read every frame by WatermarkTextProvider
// (§5.2), so they're defined now (Phase 4) rather than waiting for the rest of §11's
// settings additions (LocationSettings, autoStartOnLaunch), which land later alongside
// their own settings-screen UI.
enum class WatermarkCorner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
}

@Serializable
data class WatermarkSettings(
    val enabled: Boolean = true,
    val corner: WatermarkCorner = WatermarkCorner.TOP_LEFT,
    val speedUnit: SpeedUnit = SpeedUnit.KMH,
) {
    companion object {
        fun getDefaultInstance() = WatermarkSettings()
    }
}

// Added now (alongside ImpactDetector, §7) rather than waiting for the rest of §11's
// schema additions — same reasoning as WatermarkSettings above: ImpactDetector needs a
// real, user-configurable postImpactDurationMinutes to be genuinely wired, not stubbed.
@Serializable
data class ImpactDetectionSettings(
    val enabled: Boolean = true,
    val postImpactDurationMinutes: Int = 5,
) {
    companion object {
        fun getDefaultInstance() = ImpactDetectionSettings()
    }
}

// §11's schema for this — no dedicated settings-screen card is specified for it (§9.3
// lists Recording/Watermark/Impact Detection/Storage/Privacy/Appearance/About, no
// "Location" section), so this is an internal tuning knob `LocationTracker` reads,
// not something exposed in the UI.
@Serializable
data class LocationSettings(
    val updateIntervalMs: Long = 1000L,
) {
    companion object {
        fun getDefaultInstance() = LocationSettings()
    }
}

// Floating preview window shown when the app is backgrounded while actively recording
// (newFeat.md Feature 2) — default OFF, since it requires the special
// `SYSTEM_ALERT_WINDOW` ("draw over other apps") permission and shouldn't surprise a user
// who never opted in. Forward-compat is automatic: `AppSettingsSerializer` already uses
// `ignoreUnknownKeys = true`, so existing settings.json files without a `pip` key
// deserialize with this default.
@Serializable
data class PipSettings(
    val enabled: Boolean = false,
    // Fades the overlay's live preview to the same placeholder the manual Cut/Uncut
    // button already produces after a period of no interaction (drag/tap/button press)
    // — a small, floating, always-backgrounded window keeping a full GPU-composited
    // camera preview running indefinitely with nobody watching it is pure waste
    // (§9.6's energy-efficiency pass). Defaults on: only takes effect when the PIP
    // itself is already enabled, and is purely cosmetic/reversible (any interaction
    // restores it instantly).
    val idleSuspendEnabled: Boolean = true,
) {
    fun setIdleSuspendEnabled(idleSuspendEnabled: Boolean): PipSettings {
        return copy(idleSuspendEnabled = idleSuspendEnabled)
    }

    companion object {
        fun getDefaultInstance() = PipSettings()
    }
}
