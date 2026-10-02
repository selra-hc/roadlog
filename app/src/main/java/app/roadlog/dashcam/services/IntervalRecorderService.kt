package app.roadlog.dashcam.services

import android.util.Log
import androidx.lifecycle.lifecycleScope
import app.roadlog.dashcam.db.AppSettings
import app.roadlog.dashcam.db.RecordingInformation
import app.roadlog.dashcam.helpers.BatchesFolder
import app.roadlog.dashcam.helpers.LocationTracker
import app.roadlog.dashcam.impact.ImpactDetector
import app.roadlog.dashcam.impact.PendingImpactSave
import app.roadlog.dashcam.impact.PendingImpactSaveStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

// `I` is bound to `RecordingInformation` (rather than left fully generic) so the
// impact-triggered auto-save logic below can call `getStartDateForFilename`/
// `fileExtension` on whatever `getRecordingInformation()` returns — safe now that
// audio-only recording (the only other former `I` type) was removed in the initial
// port (§2.1), leaving `RecordingInformation` as this class's only real instantiation.
abstract class IntervalRecorderService<I : RecordingInformation, B : BatchesFolder> :
    RecorderService() {
    protected var counter = 0L
        private set

    // Tracks the index of the currently locked file
    private var lockedIndex: Long? = null

    lateinit var settings: AppSettings

    // Started/stopped alongside recording (not pause/resume, since there's no cost to
    // keeping a location fix warm through a pause) — §6's "service-driven, not
    // activity-driven" lifecycle. Exposed so `VideoRecorderService` can feed it into
    // `WatermarkTextProvider`.
    val locationTracker: LocationTracker by lazy { LocationTracker(this) }

    // Same service-driven lifecycle as `locationTracker` above (§7.1).
    private val impactDetector: ImpactDetector by lazy { ImpactDetector(this) }
    private val pendingImpactSaveStore: PendingImpactSaveStore by lazy {
        PendingImpactSaveStore(this)
    }
    private var pendingImpactSaveJob: Job? = null

    // Current pending-save state, for a freshly-(re)bound UI to read immediately rather
    // than wait for the next `onPendingImpactSaveChange` invocation (§7.3's banner).
    var pendingImpactSave: PendingImpactSave? = null
        private set

    var onPendingImpactSaveChange: (PendingImpactSave?) -> Unit = {}

    private lateinit var cycleTimer: ScheduledExecutorService

    abstract var batchesFolder: B

    var onBatchesFolderNotAccessible: () -> Unit = {}

    abstract fun getRecordingInformation(): I

    // When saving the recording, the files should be locked.
    // This prevents the service from deleting the currently available files, so that
    // they can be safely used to save the recording.
    // Once finished, make sure to unlock the files using `unlockFiles`.
    fun lockFiles() {
        lockedIndex = counter
    }

    // Restores a lock directly to a specific index, rather than deriving it from the
    // current `counter` the way `lockFiles()` does. Needed when resuming a pending
    // impact-triggered save after the service restarts (§7.4): `counter` resets to 0 on
    // restart, so re-deriving the lock from it would protect the wrong (new) batches
    // instead of the original pre-impact ones recorded before the restart.
    fun restoreLock(index: Long) {
        lockedIndex = index
    }

    // Unlocks and deletes the files that were locked using `lockFiles`.
    fun unlockFiles(cleanupFiles: Boolean = false) {
        if (cleanupFiles) {
            batchesFolder.deleteRecordings(0..<lockedIndex!!)
        }

        lockedIndex = null
    }

    // Make overrideable
    open fun startNewCycle() {
        counter += 1
        deleteOldRecordings()
    }

    private fun createTimer() {
        cycleTimer = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleAtFixedRate(
                ::startNewCycle,
                0,
                settings.intervalDuration,
                TimeUnit.MILLISECONDS
            )
        }
    }

    override fun start() {
        super.start()

        // GPS's only consumer anywhere in the app is `WatermarkTextProvider` (the speed
        // readout burned into the watermark) — no point keeping the GPS radio warm at
        // `settings.location.updateIntervalMs` for a value nothing reads when watermarking
        // itself is turned off (§9.6's energy-efficiency pass).
        if (settings.watermark.enabled) {
            locationTracker.start(updateIntervalMs = settings.location.updateIntervalMs)
        }
        impactDetector.onImpactDetected = ::onImpactDetected
        impactDetector.start()
        resumePendingImpactSaveIfAny()

        batchesFolder.initFolders()

        if (!batchesFolder.checkIfFolderIsAccessible()) {
            onBatchesFolderNotAccessible()

            throw AvoidErrorDialogError()
        }

        createTimer()
    }

    override fun pause() {
        super.pause()
        cycleTimer.shutdown()
        // Auto-save-on-impact is moot while paused (no footage being recorded), unlike
        // `locationTracker`'s cheap ~1Hz GPS fix (left running through pause, see its own
        // start() call site) — stop the ~50Hz accelerometer listener rather than paying its
        // continuous cost for as long as the user leaves the recording paused.
        impactDetector.stop()
    }

    override fun resume() {
        super.resume()
        createTimer()
        impactDetector.start()
    }

    override suspend fun stop() {
        locationTracker.stop()
        impactDetector.stop()
        cycleTimer.shutdown()
        batchesFolder.cleanup()
        super.stop()
    }

    // If the user manually stops recording while an impact auto-save is still counting
    // down, there's no more "after" footage coming — finalize immediately with whatever
    // was captured rather than losing the pending save entirely. (A fresh recording
    // session calls `clearAllRecordings()` on start, which would delete the very batches
    // a lingering pending-save record still points at.)
    //
    // Deliberately NOT called from `stop()` above: subclasses (`VideoRecorderService`)
    // call `super.stop()` before they've actually finished flushing the last actively-
    // recording segment to disk, so finalizing this early would risk concatenating
    // while that last batch file is still being written. Call this explicitly instead,
    // once the subclass's own stop sequence has confirmed the last segment is flushed.
    protected suspend fun finalizePendingImpactSaveOnStop() {
        if (pendingImpactSaveJob == null) {
            return
        }

        pendingImpactSaveJob?.cancel()
        finalizePendingImpactSave()
    }

    fun clearAllRecordings() {
        batchesFolder.deleteRecordings()
    }

    private fun onImpactDetected() {
        if (!settings.impactDetection.enabled) {
            return
        }

        // A second impact during an active countdown doesn't restart/extend it — the
        // existing pending save already covers "from before the first impact onward,"
        // which already includes this second event too.
        if (pendingImpactSaveJob?.isActive == true) {
            return
        }

        lockFiles()

        val pending = PendingImpactSave(
            triggeredAtEpochMillis = System.currentTimeMillis(),
            lockedIndex = counter,
            postImpactDurationMinutes = settings.impactDetection.postImpactDurationMinutes,
        )
        // `onImpactDetected` runs on whichever thread registered the sensor listener (main),
        // so the file write is dispatched to IO rather than blocking it right when an
        // impact was just detected — the banner/countdown below doesn't wait on this.
        lifecycleScope.launch(Dispatchers.IO) { pendingImpactSaveStore.write(pending) }
        schedulePendingImpactSave(pending)
    }

    private fun schedulePendingImpactSave(pending: PendingImpactSave) {
        pendingImpactSave = pending
        onPendingImpactSaveChange(pending)

        pendingImpactSaveJob = lifecycleScope.launch(Dispatchers.IO) {
            val remaining = pending.finalizeAtEpochMillis - System.currentTimeMillis()
            if (remaining > 0) {
                delay(remaining)
            }

            finalizePendingImpactSave()
        }
    }

    // Shared by `finalizePendingImpactSave()` (impact auto-save) and `saveCurrentBufferNow()`
    // (manual save from the PIP overlay, §9.5) — both just concatenate whatever's currently
    // in the rolling buffer into a new file, differing only in the filename tag applied to
    // mark it as auto-saved.
    private suspend fun concatenateCurrentBuffer(tag: String? = null) {
        val recording = getRecordingInformation()

        // "Process Video" disabled (§9.1/§11) — move the raw chunks to the save folder
        // individually instead of merging them, mirroring what `RecorderEventsHandler
        // .saveRecording()` does for the Record screen's own Save button. Only meaningful
        // for CUSTOM/MEDIA, where "the save folder" is a real, user-browsable location —
        // INTERNAL always concatenates regardless of this setting (see that function's
        // own comment for why: its only exit path is a single-file SAF "save as" picker,
        // not built for N chunks).
        if (!settings.videoRecorderSettings.processVideo && batchesFolder.type != BatchesFolder.BatchType.INTERNAL) {
            // Recording is always still ongoing at this point — `concatenateCurrentBuffer`
            // is only ever called mid-recording (impact auto-save, the PIP overlay's Save
            // button), never as part of a stop flow — so the highest-counter chunk is
            // always the one currently open for writing and must be excluded.
            batchesFolder.moveChunksToDestination(
                recording = recording,
                filenameFormat = settings.filenameFormat,
                outputTag = tag,
                excludeActiveChunk = true,
            )

            if (recording.hasSecondaryStream) {
                val secondaryOutputTag = if (tag != null) {
                    "${BatchesFolder.FRONT_STREAM_FILENAME_TAG}-$tag"
                } else {
                    BatchesFolder.FRONT_STREAM_FILENAME_TAG
                }

                batchesFolder.moveChunksToDestination(
                    recording = recording,
                    filenameFormat = settings.filenameFormat,
                    outputTag = secondaryOutputTag,
                    streamTag = BatchesFolder.FRONT_STREAM_FILENAME_TAG,
                    excludeActiveChunk = true,
                )
            }

            return
        }

        val fileName = batchesFolder.getName(
            recording.getStartDateForFilename(settings.filenameFormat),
            recording.fileExtension,
            tag = tag,
        )

        batchesFolder.concatenate(
            recording = recording,
            filenameFormat = settings.filenameFormat,
            fileName = fileName,
        )

        if (recording.hasSecondaryStream) {
            val secondaryTag = if (tag != null) {
                "${BatchesFolder.FRONT_STREAM_FILENAME_TAG}-$tag"
            } else {
                BatchesFolder.FRONT_STREAM_FILENAME_TAG
            }
            val secondaryFileName = batchesFolder.getName(
                recording.getStartDateForFilename(settings.filenameFormat),
                recording.fileExtension,
                tag = secondaryTag,
            )

            batchesFolder.concatenate(
                recording = recording,
                filenameFormat = settings.filenameFormat,
                fileName = secondaryFileName,
                tag = BatchesFolder.FRONT_STREAM_FILENAME_TAG,
            )
        }
    }

    private suspend fun finalizePendingImpactSave() {
        try {
            concatenateCurrentBuffer(tag = BatchesFolder.AUTO_SAVED_FILENAME_TAG)
        } catch (error: Exception) {
            Log.e("ImpactAutoSave", "Failed to finalize impact-triggered save", error)
        } finally {
            unlockFiles(false)
            pendingImpactSaveStore.clear()
            pendingImpactSaveJob = null
            pendingImpactSave = null
            onPendingImpactSaveChange(null)
        }
    }

    // Manual "save current buffer" trigger for the PIP overlay's Save button (§9.5) — the
    // overlay is hosted directly from this service, with no Activity/ViewModel/
    // `RecorderEventsHandler` in the loop the way the Record screen's own Save button is
    // (§9.1), so this reuses the same self-contained concatenate logic
    // `finalizePendingImpactSave()` already relies on instead. `startNewCycle()` runs first
    // so the segment still actively recording gets flushed to its own file before
    // `getRecordingInformation()` looks for what to concatenate — without it, the last
    // (still in-progress) interval of footage would be missed.
    fun saveCurrentBufferNow(): Job {
        startNewCycle()

        return lifecycleScope.launch(Dispatchers.IO) {
            lockFiles()
            try {
                concatenateCurrentBuffer()
            } catch (error: Exception) {
                Log.e("PipManualSave", "Failed to save current buffer from PIP", error)
            } finally {
                unlockFiles(false)
            }
        }
    }

    // Exposed for the (future) pending-auto-save banner's Cancel action (§7.2 step 5) —
    // releases the lock without finalizing a save, letting normal rolling-buffer expiry
    // resume as if nothing had happened.
    fun cancelPendingImpactSave() {
        pendingImpactSaveJob?.cancel()
        pendingImpactSaveJob = null
        unlockFiles(false)
        lifecycleScope.launch(Dispatchers.IO) { pendingImpactSaveStore.clear() }
        pendingImpactSave = null
        onPendingImpactSaveChange(null)
    }

    // Resumes a save interrupted by process death (§7.4's safety net) — the primary
    // mechanism (the coroutine `delay()` in `schedulePendingImpactSave`) doesn't survive
    // the service's process being killed, but this file-backed record does. Called once
    // on every `start()`; a no-op if there's nothing pending.
    private fun resumePendingImpactSaveIfAny() {
        val pending = pendingImpactSaveStore.read() ?: return

        restoreLock(pending.lockedIndex)
        schedulePendingImpactSave(pending)
    }

    private fun deleteOldRecordings() {
        val timeMultiplier = settings.maxDuration / settings.intervalDuration
        val rollingWindowEarliest = counter - timeMultiplier

        // A lock must only ever make this MORE conservative (protect more), never less —
        // `Math.min` caps deletion at the locked index so it's never exceeded, no matter
        // how long the lock is held. (This used to be `Math.max`, which only "protected"
        // batches for the few seconds a manual save's own concatenation took to run —
        // once enough cycles passed for the normal rolling window to reach past the
        // locked index, `Math.max` would win and delete straight through it. Manual saves
        // never held the lock long enough to hit that. A held lock like impact
        // detection's multi-minute one, §7.2, definitely does — this was the bug that
        // would have silently deleted the very "before" footage impact detection exists
        // to protect.)
        val earliestCounter = lockedIndex?.let { minOf(rollingWindowEarliest, it) }
            ?: rollingWindowEarliest

        if (earliestCounter <= 0) {
            return
        }

        batchesFolder.deleteRecordings(0..earliestCounter)
    }
}