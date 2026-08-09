package app.roadlog.dashcam.ui.models

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.camera.core.CameraSelector
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.roadlog.dashcam.db.AppSettings
import app.roadlog.dashcam.db.RecordingInformation
import app.roadlog.dashcam.enums.RecorderState
import app.roadlog.dashcam.helpers.Doctor
import app.roadlog.dashcam.helpers.VideoBatchesFolder
import app.roadlog.dashcam.impact.PendingImpactSave
import app.roadlog.dashcam.services.VideoRecorderService
import app.roadlog.dashcam.ui.utils.CameraInfo
import app.roadlog.dashcam.ui.utils.PermissionHelper

class VideoRecorderModel :
    BaseRecorderModel<RecordingInformation, VideoBatchesFolder, VideoRecorderService>() {
    override var batchesFolder: VideoBatchesFolder? = null
    override val intentClass = VideoRecorderService::class.java

    var enableAudio by mutableStateOf(true)
    var cameraID by mutableIntStateOf(CameraInfo.Lens.BACK.androidValue)

    // Mutually exclusive with `cameraID` being the "single active selection" — when true,
    // the service binds both front and back concurrently instead of just `cameraID`'s lens.
    var dualRecordingEnabled by mutableStateOf(false)

    override val isInRecording: Boolean
        get() = super.isInRecording

    // Guards `autoStartOnLaunch` (§9.1) so it fires once per app session rather than
    // every time the user navigates back to the Record tab after manually stopping —
    // this `ViewModel` is created once per `Navigation()` call and survives tab
    // switches, so a plain one-shot flag here matches "on launch," not "on every visit."
    var hasAttemptedAutoStart = false

    // Mirrors `IntervalRecorderService.pendingImpactSave` (§7.3's banner) — read
    // synchronously from the service on (re)connect below, then kept in sync via
    // `onPendingImpactSaveChange` for every subsequent trigger/finalize/cancel.
    var pendingImpactSave by mutableStateOf<PendingImpactSave?>(null)
        private set

    // Mirrors `VideoRecorderService.feedCut` (newFeat.md Feature 1) — read synchronously
    // on (re)connect below, then kept in sync via `onFeedCutChange` for every subsequent
    // toggle from the Record screen's cut/uncut button.
    var feedCut by mutableStateOf(false)
        private set

    val cameraSelector: CameraSelector
        get() = CameraSelector.Builder().requireLensFacing(cameraID).build()

    fun init(context: Context) {
        enableAudio = PermissionHelper.hasGranted(context, Manifest.permission.RECORD_AUDIO)
        cameraID = CameraInfo.Lens.BACK.androidValue
        dualRecordingEnabled = false
    }

    override fun startRecording(context: Context, settings: AppSettings) {
        // Was a hand-rolled `when` here that only matched `null`/`RECORDER_MEDIA_SELECTED_VALUE`
        // and fell through to the CUSTOM/SAF branch (with an unguarded `!!`) for anything
        // else — including, in principle, the literal `RECORDER_INTERNAL_SELECTED_VALUE`
        // string, which it didn't special-case. `importFromFolder` is the single already-
        // correct implementation of this same resolution (used by the Recordings listing
        // and by save/concatenation), so route through it instead of duplicating (and
        // subtly diverging from) that logic here.
        batchesFolder = VideoBatchesFolder.importFromFolder(settings.saveFolder, context)

        super.startRecording(context, settings)
    }

    override fun onServiceConnected(service: VideoRecorderService) {
        // `onServiceConnected` may be called when reconnecting to the service,
        // so we only want to actually start the recording if the service is idle and thus
        // not already recording
        if (service.state == RecorderState.IDLE) {
            service.clearAllRecordings()
            service.startRecording()
            onRecordingStart()
        }

        service.onPendingImpactSaveChange = { pending ->
            pendingImpactSave = pending
        }
        // Read synchronously in case a save was already counting down before this UI
        // (re)connected — e.g. app was backgrounded mid-countdown and reopened.
        pendingImpactSave = service.pendingImpactSave

        service.onFeedCutChange = { cut ->
            feedCut = cut
        }
        // Read synchronously in case the feed was already cut before this UI (re)connected.
        feedCut = service.feedCut

        recorderState = service.state
        recordingTime = service.recordingTime
    }

    override fun reset() {
        super.reset()
        pendingImpactSave = null
        feedCut = false
    }

    override fun handleIntent(intent: Intent) =
        intent.apply {
            putExtra("cameraID", cameraID)
            putExtra("enableAudio", enableAudio)
            putExtra("dualRecording", dualRecordingEnabled)
        }
}