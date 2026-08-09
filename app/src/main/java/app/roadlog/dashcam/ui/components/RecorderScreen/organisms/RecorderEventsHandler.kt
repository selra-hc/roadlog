package app.roadlog.dashcam.ui.components.RecorderScreen.organisms

import android.net.Uri
import android.util.Log
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.roadlog.dashcam.R
import app.roadlog.dashcam.dataStore
import app.roadlog.dashcam.db.AppSettings
import app.roadlog.dashcam.db.RecordingInformation
import app.roadlog.dashcam.helpers.BatchesFolder
import app.roadlog.dashcam.helpers.VideoBatchesFolder
import app.roadlog.dashcam.services.IntervalRecorderService
import app.roadlog.dashcam.ui.components.RecorderScreen.atoms.BatchesInaccessibleDialog
import app.roadlog.dashcam.ui.components.RecorderScreen.atoms.RecorderErrorDialog
import app.roadlog.dashcam.ui.components.RecorderScreen.atoms.RecorderProcessingDialog
import app.roadlog.dashcam.ui.effects.rememberOpenUri
import app.roadlog.dashcam.ui.models.BaseRecorderModel
import app.roadlog.dashcam.ui.models.VideoRecorderModel
import app.roadlog.dashcam.ui.utils.rememberFileSaverDialog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Timer
import kotlin.concurrent.schedule
import kotlin.concurrent.thread

typealias RecorderModel = BaseRecorderModel<
        RecordingInformation,
        BatchesFolder,
        IntervalRecorderService<RecordingInformation, BatchesFolder>,
        >

@Composable
fun RecorderEventsHandler(
    settings: AppSettings,
    snackbarHostState: SnackbarHostState,
    videoRecorder: VideoRecorderModel,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val dataStore = context.dataStore

    var isProcessing by remember { mutableStateOf(false) }
    // Set synchronously the instant `saveRecording()` starts, unlike the 250ms-delayed
    // `isProcessing` above (which exists purely to avoid flashing a dialog for very fast
    // saves) — guards against a second save starting while one is still running, which
    // `isProcessing` alone can't catch during that first 250ms window. Real bug found and
    // fixed: with "Process Video" off, a save deletes its source chunks right after moving
    // them (`BatchesFolder.moveChunksToDestination`), so a quick tap-then-long-press on
    // Save (short tap = save-current, long-press = save-and-stop in this mode, §9.1) could
    // start a second save before the first had finished — both read an overlapping/
    // identical `getBatchesForConcatenation()` snapshot, and the second's attempt to open
    // a chunk the first had already deleted threw a `FileNotFoundException`, surfacing as
    // the recorder error dialog. "Process Video" on doesn't delete source batches after
    // concatenating, so the same race there was silently wasteful rather than erroring —
    // but two concurrent saves were never actually intended either way.
    var isSaving by remember { mutableStateOf(false) }
    var showRecorderError by remember { mutableStateOf(false) }
    var showBatchesInaccessibleError by remember { mutableStateOf(false) }

    var processingProgress by remember { mutableStateOf<Float?>(null) }

    val saveVideoFile = rememberFileSaverDialog(settings.videoRecorderSettings.getMimeType()) {
        if (settings.deleteRecordingsImmediately) {
            runCatching {
                videoRecorder.batchesFolder?.deleteRecordings()
            }
        }

        if (videoRecorder.batchesFolder?.hasRecordingsAvailable() == false) {
            scope.launch {
                dataStore.updateData {
                    it.setLastRecording(null)
                }
            }
        }
    }

    suspend fun saveAsLastRecording(
        recorder: RecorderModel
    ) {
        if (!settings.deleteRecordingsImmediately) {
            val information = recorder.recorderService?.getRecordingInformation()

            if (information == null) {
                Log.e("RecorderEventsHandler", "Recording information is null")
                return
            }

            dataStore.updateData {
                it.setLastRecording(
                    information
                )
            }
        }
    }

    val successMessage = stringResource(R.string.ui_recorder_action_save_success)
    val openMessage = stringResource(R.string.ui_recorder_action_save_openFolder)

    val openFolder = rememberOpenUri()

    fun showSnackbar() {
        scope.launch {
            snackbarHostState.showSnackbar(
                message = successMessage,
                duration = SnackbarDuration.Short,
            )
        }
    }

    fun showSnackbar(uri: Uri) {
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = successMessage,
                actionLabel = openMessage,
                duration = SnackbarDuration.Short,
            )

            if (result == SnackbarResult.ActionPerformed) {
                openFolder(uri)
            }
        }
    }

    fun saveRecording(
        recorder: RecorderModel,
        cleanupOldFiles: Boolean = false
    ): CompletableDeferred<Unit> {
        if (isSaving) {
            // A save is already running — ignore this second trigger instead of racing
            // it against the first (see `isSaving`'s own declaration comment above).
            return CompletableDeferred(Unit)
        }
        isSaving = true

        val completer = CompletableDeferred<Unit>()

        // If processing takes this short, don't show the processing dialog.
        // `Timer.schedule` (the `kotlin.concurrent` extension) returns the `TimerTask`, not
        // the `Timer` — keep the `Timer` reference too so `timer.cancel()` below actually
        // stops its background thread instead of just cancelling the one task.
        val timer = Timer()
        timer.schedule(250L) {
            isProcessing = true
        }

        thread {
            runBlocking {
                try {
                    // Captured once so the "is this a Save & Stop, or a Save Current"
                    // decision below stays consistent with the lockFiles gate here, even
                    // though the underlying state could in principle change mid-function.
                    val isActivelyRecording = recorder.isCurrentlyActivelyRecording

                    if (isActivelyRecording) {
                        recorder.recorderService?.lockFiles()
                    }

                    val recording =
                        // When new recording created
                        recorder.recorderService?.getRecordingInformation()
                        // When recording is loaded from lastRecording
                            ?: settings.lastRecording
                            ?: throw Exception("No recording information available")

                    val batchesFolder = VideoBatchesFolder.importFromFolder(
                        recording.folderPath,
                        context
                    )

                    val fileName = batchesFolder.getName(
                        recording.recordingStart,
                        recording.fileExtension,
                    )

                    // "Process Video" (§9.1/§11) — when disabled, move the rolling
                    // buffer's currently-available chunks to the save folder as-is
                    // instead of merging them into `fileName`. Only meaningful for
                    // CUSTOM/MEDIA, where "the save folder" is somewhere the user can
                    // actually browse — INTERNAL always concatenates regardless, since
                    // its only exit path (the `saveVideoFile` SAF "save as" picker below)
                    // is built around exactly one output file, not N chunk files.
                    val moveChunksInstead = !settings.videoRecorderSettings.processVideo &&
                        batchesFolder.type != BatchesFolder.BatchType.INTERNAL

                    if (moveChunksInstead) {
                        // Excludes the currently-active chunk only for "Save Current"
                        // (`isActivelyRecording` true — recording continues after this),
                        // not "Save & Stop" (already stopped, so `stopRecording()` has
                        // already finalized every segment and none is "active" anymore).
                        batchesFolder.moveChunksToDestination(
                            recording,
                            filenameFormat = settings.filenameFormat,
                            excludeActiveChunk = isActivelyRecording,
                        )
                    } else {
                        batchesFolder.concatenate(
                            recording,
                            filenameFormat = settings.filenameFormat,
                            fileName = fileName,
                            onProgress = { percentage ->
                                processingProgress = percentage
                            }
                        )
                    }

                    // Dual (front+back) recording (§9.3): concatenate (or move, per
                    // `moveChunksInstead` above) the secondary stream's batches into its
                    // own output right alongside the primary's. For INTERNAL storage
                    // this is deliberately NOT routed through `saveVideoFile` below —
                    // prompting a second SAF file-picker dialog immediately after the
                    // primary's would be a jarring double-prompt, so it's simply left in
                    // the batches folder as a second saved recording, identifiable by
                    // its "-front" filename tag. CUSTOM/MEDIA already write straight to
                    // their final location inside `concatenate()`/`moveChunksToDestination()`.
                    if (recording.hasSecondaryStream) {
                        if (moveChunksInstead) {
                            batchesFolder.moveChunksToDestination(
                                recording,
                                filenameFormat = settings.filenameFormat,
                                outputTag = BatchesFolder.FRONT_STREAM_FILENAME_TAG,
                                streamTag = BatchesFolder.FRONT_STREAM_FILENAME_TAG,
                                excludeActiveChunk = isActivelyRecording,
                            )
                        } else {
                            val secondaryFileName = batchesFolder.getName(
                                recording.recordingStart,
                                recording.fileExtension,
                                tag = BatchesFolder.FRONT_STREAM_FILENAME_TAG,
                            )

                            batchesFolder.concatenate(
                                recording,
                                filenameFormat = settings.filenameFormat,
                                fileName = secondaryFileName,
                                tag = BatchesFolder.FRONT_STREAM_FILENAME_TAG,
                            )
                        }
                    }

                    // Save file
                    when (batchesFolder.type) {
                        BatchesFolder.BatchType.INTERNAL -> {
                            saveVideoFile(
                                batchesFolder.asInternalGetOutputFile(fileName), fileName
                            )
                        }

                        BatchesFolder.BatchType.CUSTOM -> {
                            showSnackbar(batchesFolder.customFolder!!.uri)

                            if (settings.deleteRecordingsImmediately && !moveChunksInstead) {
                                batchesFolder.deleteRecordings()
                            }
                        }

                        BatchesFolder.BatchType.MEDIA -> {
                            showSnackbar()

                            if (settings.deleteRecordingsImmediately && !moveChunksInstead) {
                                batchesFolder.deleteRecordings()
                            }
                        }
                    }
                } catch (error: Exception) {
                    // `Log.getStackTraceString` just returns a formatted string — it doesn't
                    // itself log anything, so a bare call here silently swallowed every
                    // concatenation/export failure (e.g. an empty batch list, a Transformer
                    // error) with no trace in logcat and no feedback to the user, making a
                    // failed save look identical to a successful one.
                    Log.e("RecorderEventsHandler", "Failed to save recording", error)
                    showRecorderError = true
                } finally {
                    if (recorder.isCurrentlyActivelyRecording) {
                        recorder.recorderService?.unlockFiles(cleanupOldFiles)
                    }
                    timer.cancel()
                    isProcessing = false
                    isSaving = false
                    processingProgress = null
                    completer.complete(Unit)
                }
            }
        }

        return completer
    }

    // Register video recorder events
    // Absolutely no idea, but somehow on some devices the `DisposableEffect`
    // is registered twice, and THEN disposed once (AFTER being called twice),
    // which then causes the `onRecordingSave` to be in a weird state.
    // This variable is a workaround to prevent this from happening.
    var previousVideoSettings: AppSettings? = null
    DisposableEffect(settings) {
        if (previousVideoSettings == settings) {
            onDispose { }
        } else {
            previousVideoSettings = settings
            Log.i("RoadLog", "===== Registering videoRecorder events $videoRecorder")
            videoRecorder.onRecordingSave = { cleanupOldFiles ->
                saveRecording(videoRecorder as RecorderModel, cleanupOldFiles)
            }
            videoRecorder.onRecordingStart = {
                snackbarHostState.currentSnackbarData?.dismiss()
            }
            videoRecorder.onError = {
                scope.launch {
                    saveAsLastRecording(videoRecorder as RecorderModel)

                    runCatching {
                        videoRecorder.stopRecording(context)
                    }
                    runCatching {
                        videoRecorder.destroyService(context)
                    }

                    showRecorderError = true
                }
            }
            videoRecorder.onBatchesFolderNotAccessible = {
                scope.launch {
                    showBatchesInaccessibleError = true

                    runCatching {
                        videoRecorder.stopRecording(context)
                    }
                    runCatching {
                        videoRecorder.destroyService(context)
                    }
                }
            }

            onDispose {
                Log.i("RoadLog", "===== Disposing videoRecorder events")
                videoRecorder.onRecordingSave = {
                    throw NotImplementedError("onRecordingSave should not be called now")
                }
                videoRecorder.onError = {}
            }
        }
    }

    if (isProcessing)
        RecorderProcessingDialog(
            progress = processingProgress,
        )

    if (showBatchesInaccessibleError)
        BatchesInaccessibleDialog(
            onClose = {
                showBatchesInaccessibleError = false
            },
        )
    else if (showRecorderError)
        RecorderErrorDialog(
            onClose = {
                showRecorderError = false
            },
        )
}