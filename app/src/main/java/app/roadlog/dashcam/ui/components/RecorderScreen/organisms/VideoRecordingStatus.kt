package app.roadlog.dashcam.ui.components.RecorderScreen.organisms

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.roadlog.dashcam.R
import app.roadlog.dashcam.dataStore
import app.roadlog.dashcam.ui.components.RecorderScreen.atoms.PendingImpactSaveBanner
import app.roadlog.dashcam.ui.components.RecorderScreen.atoms.RecordingPreview
import app.roadlog.dashcam.ui.components.RecorderScreen.atoms.SaveCurrentNowModal
import app.roadlog.dashcam.ui.components.RecorderScreen.atoms.StorageIndicator
import app.roadlog.dashcam.ui.components.RecorderScreen.molecules.RecordingControl
import app.roadlog.dashcam.ui.components.RecorderScreen.molecules.RecordingStatus
import app.roadlog.dashcam.ui.models.VideoRecorderModel
import app.roadlog.dashcam.ui.theme.CardCornerRadius
import app.roadlog.dashcam.ui.theme.RoadLogTheme
import app.roadlog.dashcam.ui.utils.KeepScreenOn
import app.roadlog.dashcam.ui.utils.dimWhileRecording
import app.roadlog.dashcam.ui.utils.rememberInitialRecordingAnimation
import kotlinx.coroutines.launch

// Hoisted out of `VideoRecorderModel` (an unstable `ViewModel`, see its own file) so
// `_PrimitiveControls`/`RecordingControl` receive small, `@Immutable` parameters instead of
// the whole model — letting them skip recomposition on ticks that don't actually change
// these fields, rather than fully re-executing every second alongside `recordingTime`.
@Immutable
data class RecordingUiState(
    val isPaused: Boolean,
    val isCut: Boolean,
    val animateIn: Boolean,
    // "Process Video" (§9.1/§11) for this recording session. Drives two things in
    // `_PrimitiveControls` below: whether the Delete button shows at all (discarding an
    // unsaved recording wholesale doesn't fit once saving no longer produces one
    // combined file the user could otherwise lose), and which gesture on the Save
    // button stops the recording — see that composable's own comment for why they swap.
    val processVideo: Boolean,
)

@Immutable
data class RecordingControlActions(
    val onDelete: () -> Unit,
    val onPauseResume: () -> Unit,
    val onSaveAndStop: () -> Unit,
    val onConfirmSaveCurrent: () -> Unit,
    val onCutFeed: () -> Unit,
)

@Composable
fun VideoRecordingStatus(
    videoRecorder: VideoRecorderModel,
) {
    KeepScreenOn()

    // Full-bleed live preview (§9.1) — the exact composited (camera + watermark) frames
    // being encoded to disk, not a separate copy (see `RecordingPreview`'s own doc comment
    // for why). Only rendered once the service's camera is actually open; everything else
    // in this composable renders as an overlay on top of it via a translucent backing
    // plate (`_StatusPanel` below) so text/controls stay legible over unpredictable video
    // content. There's no camera to show at all while paused — `VideoRecorderService`
    // closes the camera on pause (§9.1: no camera use when paused), so `recorderService`
    // stays non-null but simply has nothing bound for `RecordingPreview` to display.
    val context = LocalContext.current
    val density = LocalDensity.current
    val dataStore = context.dataStore
    val scope = rememberCoroutineScope()

    // Built once per recording session — `videoRecorder`/`context`/`scope`/`dataStore`
    // never change identity mid-recording — so `_PrimitiveControls` (and, transitively,
    // `RecordingControl`) receive a stable `actions` reference instead of 5 freshly
    // allocated lambdas every time `recordingTime` ticks (every second while recording).
    val actions = remember(videoRecorder, context, scope, dataStore) {
        RecordingControlActions(
            onDelete = {
                scope.launch {
                    runCatching {
                        videoRecorder.stopRecording(context)
                    }
                    runCatching {
                        videoRecorder.destroyService(context)
                    }
                    videoRecorder.batchesFolder!!.deleteRecordings()
                }
            },
            onPauseResume = {
                if (videoRecorder.isPaused) {
                    videoRecorder.resumeRecording()
                } else {
                    videoRecorder.pauseRecording()
                }
            },
            onSaveAndStop = {
                scope.launch {
                    Log.i("RoadLog", "====== Asking to stop recording...")
                    videoRecorder.stopRecording(context)
                    Log.i("RoadLog", "====== Asking to stop recording... done")

                    Log.i("RoadLog", "====== Updating data store...")
                    dataStore.updateData {
                        it.saveLastRecording(videoRecorder as RecorderModel)
                    }
                    Log.i("RoadLog", "====== Updating data store... done")

                    Log.i("RoadLog", "===== Asking to save recording...")
                    videoRecorder.onRecordingSave(false).join()
                    Log.i("RoadLog", "===== Asking to save recording... done")

                    Log.i("RoadLog", "===== Destroying service...")
                    runCatching {
                        videoRecorder.destroyService(context)
                    }
                    Log.i("RoadLog", "===== Destroying service... done")
                }
            },
            onConfirmSaveCurrent = {
                scope.launch {
                    videoRecorder.recorderService!!.startNewCycle()
                    videoRecorder.onRecordingSave(false).join()
                }
            },
            onCutFeed = {
                videoRecorder.recorderService?.setFeedCut(!videoRecorder.feedCut)
            },
        )
    }

    // `batchesFolder`/`settings` are plain `var`s (not Compose state), set once when the
    // recording session starts and stable thereafter — memoizing `trailing` on them keeps
    // the same lambda instance across every per-second tick, so `RecordingStatus`'s call to
    // it can skip re-entering `StorageIndicator`'s body (whose own data only refreshes
    // every 15s) instead of re-running it every second alongside the elapsed-time digits.
    val batchesFolder = videoRecorder.batchesFolder
    val settings = videoRecorder.settings
    val trailing: @Composable () -> Unit = remember(batchesFolder, settings) {
        {
            if (batchesFolder != null && settings != null) {
                StorageIndicator(batchesFolder = batchesFolder, settings = settings)
            }
        }
    }

    // The device's actual physical screen size — deliberately NOT the current
    // `BoxWithConstraints` pane size, which in split-screen/multi-window IS the shrinking
    // window itself. Using the pane's own width/height to decide anything here would mean
    // that quantity chases its own tail as the window is resized (e.g. a freeform resize
    // that shrinks both dimensions can flip which one is larger independently of the
    // device's real orientation, flipping the controls' axis for no reason the user
    // caused). `getRealMetrics` (unlike `Configuration.screenWidthDp`/`LocalConfiguration`,
    // both of which already reflect the CURRENT, possibly-split window) reports the true
    // full-display size regardless of how much of it this app currently occupies.
    // Keyed on `LocalConfiguration.current`, not just `context` — `MainActivity` declares
    // `configChanges` (§10.1) so it's never recreated on rotation, meaning `context` alone
    // never changes identity across a rotation and a plain `remember(context)` would cache
    // this forever from first composition. `LocalConfiguration.current` DOES change on
    // every config change (rotation included), giving this the recompute trigger it needs.
    val fullScreenSize = remember(context, LocalConfiguration.current) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay
            .getRealMetrics(metrics)
        metrics
    }
    val fullScreenWidthDp = with(density) { fullScreenSize.widthPixels.toDp() }
    val fullScreenHeightDp = with(density) { fullScreenSize.heightPixels.toDp() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .dimWhileRecording(
                enabled = videoRecorder.settings?.dimScreenWhileRecording ?: false,
                idleTimeoutMs = DIM_IDLE_TIMEOUT_MS,
            ),
    ) {
        // Split-screen/multi-window compactness is relative to the device's actual
        // screen, not a fixed dp value (which would trigger at a different fraction of
        // the screen on a small phone than on a tablet) — hide time/free-space once the
        // window drops below a third of the full screen on BOTH axes. Requiring both
        // (rather than either) axis avoids misfiring on an ordinary full-screen phone,
        // which always has exactly one dimension close to the device's own short axis.
        val isCompact = maxWidth < fullScreenWidthDp / COMPACT_SIZE_FRACTION_DIVISOR &&
            maxHeight < fullScreenHeightDp / COMPACT_SIZE_FRACTION_DIVISOR

        // Controls stack based on the DEVICE's actual screen shape — deliberately the
        // same `fullScreenWidthDp`/`fullScreenHeightDp` (real, un-split display size)
        // used for `isCompact` above, NOT `maxWidth`/`maxHeight` (the current pane) and
        // NOT `LocalConfiguration.current.orientation` either: `Configuration.orientation`
        // is ITSELF derived from the app's current window dimensions, so in split-screen
        // it's just as pane-shape-dependent as `maxWidth`/`maxHeight` was — a landscape
        // device split into a narrower-than-wide pane reports `ORIENTATION_PORTRAIT` for
        // that window despite the physical screen being landscape, which is exactly why
        // the controls' axis kept flipping as the split was resized. Comparing the real
        // full-screen dimensions is stable regardless of how much of it this app currently
        // occupies. (LANDSCAPE input -> vertical Column output in `RecordingControl`, so a
        // horizontal/landscape screen gets vertically-stacked, always-visible controls;
        // PORTRAIT input -> Row.)
        val controlsOrientation = if (fullScreenWidthDp > fullScreenHeightDp)
            Configuration.ORIENTATION_LANDSCAPE
        else
            Configuration.ORIENTATION_PORTRAIT

        videoRecorder.recorderService?.let { service ->
            Box(Modifier.fillMaxSize()) {
                // Cut/Uncut Video Feed (newFeat.md Feature 1) — when cut, `RecordingPreview`
                // is removed from composition entirely rather than merely painted over: its
                // `DisposableEffect` (see RecordingPreview.kt) then calls
                // `clearPreviewSurfaceProvider(...)` on the service, actually detaching the
                // camera's `Preview` use case from a surface instead of just hiding a still-
                // live one behind a black box — real-device testing found the latter still
                // showed camera content bleeding through at the screen edges, and cutting
                // the feed is explicitly meant to reduce GPU/CPU work, not just hide it
                // visually. Recording itself is unaffected either way: `videoCapture` is
                // bound independently of `preview` in the same `UseCaseGroup` (§5.1) and
                // keeps encoding regardless of whether anything consumes the Preview use
                // case's frames. The status panel below (elapsed time, storage ring,
                // pending-auto-save banner) is a sibling of this Box, layered above it, so
                // it stays visible while cut.
                if (videoRecorder.feedCut) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(
                                if (videoRecorder.isPaused) {
                                    R.string.ui_recorder_feedCut_pausedLabel
                                } else {
                                    R.string.ui_recorder_feedCut_activeLabel
                                }
                            ),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                } else {
                    RecordingPreview(
                        recorderService = service,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box {}

            _StatusPanel {
                if (!isCompact) {
                    RecordingStatus(
                        recordingTime = videoRecorder.recordingTime,
                        progress = videoRecorder.progress,
                        trailing = trailing,
                    )
                    HorizontalDivider()
                }

                // `animateIn`/`uiState` are recomputed every time this content lambda
                // re-executes (every second, from the `recordingTime` read above), but
                // `remember` with these explicit keys returns the SAME `RecordingUiState`
                // instance whenever `isPaused`/`isCut`/`animateIn` haven't actually
                // changed — which is what lets `_PrimitiveControls` skip recomposition on
                // ticks where only `recordingTime` moved.
                val animateIn = rememberInitialRecordingAnimation(videoRecorder.recordingTime)
                // `settings` is a plain (non-Compose-observable) snapshot fixed for the
                // whole recording session (§16's accepted limitation, same as
                // quality/bitrate/frame rate/fixed-focus) — reading it here doesn't need
                // its own remember key beyond `videoRecorder` itself.
                val processVideo = videoRecorder.settings?.videoRecorderSettings?.processVideo ?: true
                val uiState = remember(videoRecorder.isPaused, videoRecorder.feedCut, animateIn, processVideo) {
                    RecordingUiState(
                        isPaused = videoRecorder.isPaused,
                        isCut = videoRecorder.feedCut,
                        animateIn = animateIn,
                        processVideo = processVideo,
                    )
                }
                _PrimitiveControls(uiState, actions, orientation = controlsOrientation)
            }
        }

        videoRecorder.pendingImpactSave?.let { pending ->
            PendingImpactSaveBanner(
                pending = pending,
                onCancel = {
                    videoRecorder.recorderService?.cancelPendingImpactSave()
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
                    .fillMaxWidth(0.92f),
            )
        }
    }
}

// Below 1/this-many of the full screen's size on BOTH axes simultaneously = compact/
// split-screen mode (see isCompact above) — "a third of screen" per the reported bug.
private const val COMPACT_SIZE_FRACTION_DIVISOR = 3

// §9.6's energy-efficiency pass — how long the Record screen waits with no touch input
// before dimming (`dimWhileRecording`), when the setting is on.
private const val DIM_IDLE_TIMEOUT_MS = 15_000L

// Translucent backing plate (§9.1) behind status text/controls so they stay legible over
// live, unpredictable video content — a card-rail-style panel using the same
// border/surface convention as the rest of the app (§8.2's flat-card-with-1dp-stroke
// convention), just with a partially transparent surface instead of an opaque one.
@Composable
private fun _StatusPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        // `IntrinsicSize.Min` makes this Column measure its width as the minimum its
        // content actually needs — without it, a `fillMaxWidth()` descendant (the
        // progress bar / divider inside `RecordingStatus`) would resolve against the
        // full incoming screen width instead, stretching this panel far wider than the
        // (often narrow, e.g. a vertically-stacked button column in landscape) content
        // it's wrapping actually requires. This is what keeps the panel "as small as
        // possible" rather than enlarging to fill a wide/landscape pane.
        modifier = modifier
            .width(IntrinsicSize.Min)
            .clip(RoundedCornerShape(CardCornerRadius))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            .border(1.dp, RoadLogTheme.colors.cardBorder, RoundedCornerShape(CardCornerRadius))
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}

@Composable
fun _PrimitiveControls(
    uiState: RecordingUiState,
    actions: RecordingControlActions,
    orientation: Int = Configuration.ORIENTATION_PORTRAIT,
) {
    var showConfirmSaveNow by remember { mutableStateOf(false) }

    if (showConfirmSaveNow) {
        SaveCurrentNowModal(
            onDismiss = {
                showConfirmSaveNow = false
            },
            onConfirm = {
                showConfirmSaveNow = false
                actions.onConfirmSaveCurrent()
            },
        )
    }

    RecordingControl(
        orientation = orientation,
        // There may be some edge cases where the app may crash if the
        // user stops or pauses the recording too soon, so we simply add a
        // small delay to prevent that
        initialDelay = 1000L,
        isPaused = uiState.isPaused,
        animateIn = uiState.animateIn,
        showDeleteButton = uiState.processVideo,
        onDelete = actions.onDelete,
        onPauseResume = actions.onPauseResume,
        // With "Process Video" on (default), tapping Save stops the recording (the
        // common/expected action for this button, no confirmation needed) and a
        // long-press — an easy-to-trigger-by-accident gesture — goes through a
        // confirmation before saving the current buffer without stopping. With it off,
        // these swap: recording is meant to keep running so the rolling buffer keeps
        // producing chunks to move out (a real reported bug — tapping Save used to stop
        // recording here too, the same as the default case), so the tap now saves the
        // current buffer directly with no confirmation (the expected action in this
        // mode), and stopping is pushed behind the long-press instead — still reachable,
        // just no longer the easy gesture, matching how the confirmation-worthy action
        // is always the one bound to long-press.
        onSaveAndStop = if (uiState.processVideo) {
            actions.onSaveAndStop
        } else {
            actions.onConfirmSaveCurrent
        },
        onSaveCurrent = {
            if (uiState.processVideo) {
                showConfirmSaveNow = true
            } else {
                actions.onSaveAndStop()
            }
        },
        isCut = uiState.isCut,
        onCutFeed = actions.onCutFeed,
    )
}
