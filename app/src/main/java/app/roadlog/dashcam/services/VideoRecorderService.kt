package app.roadlog.dashcam.services

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.util.Range
import android.util.Rational
import android.view.Display
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileDescriptorOutputOptions
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import app.roadlog.dashcam.NotificationHelper
import app.roadlog.dashcam.db.RecordingInformation
import app.roadlog.dashcam.enums.RecorderState
import app.roadlog.dashcam.helpers.BatchesFolder
import app.roadlog.dashcam.helpers.VideoBatchesFolder
import app.roadlog.dashcam.ui.SUPPORTS_SAVING_VIDEOS_IN_CUSTOM_FOLDERS
import app.roadlog.dashcam.ui.SUPPORTS_SCOPED_STORAGE
import app.roadlog.dashcam.ui.components.PipOverlay.PipOverlayWindow
import app.roadlog.dashcam.ui.utils.PermissionHelper
import app.roadlog.dashcam.watermark.WatermarkOverlay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.properties.Delegates

// `ViewModelStoreOwner`/`SavedStateRegistryOwner` (on top of the `LifecycleOwner` already
// provided by `LifecycleService`, via `IntervalRecorderService`/`RecorderService`) exist
// solely so the PIP overlay's bare `ComposeView` (newFeat.md Feature 2, `PipOverlayWindow`)
// has a full set of `ViewTree*Owner`s to attach to — it's not hosted inside an Activity,
// so nothing else provides these. Neither the ViewModelStore nor the SavedStateRegistry is
// actually used for anything (the overlay has no ViewModels and nothing worth restoring
// across process death) — this is the minimal wiring Compose's `ComposeView` requires to
// attach outside an Activity/Fragment at all.
class VideoRecorderService :
    IntervalRecorderService<RecordingInformation, VideoBatchesFolder>(),
    ViewModelStoreOwner,
    SavedStateRegistryOwner {
    override var batchesFolder = VideoBatchesFolder.viaInternalFolder(this)

    private val _viewModelStore = ViewModelStore()
    override val viewModelStore: ViewModelStore
        get() = _viewModelStore

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    // Guards `openCamera()`/`closeCamera()` ordering across `pause()`/`resume()`. Both are
    // launched as independent, unordered coroutines on `scope` — without this, resuming
    // before a pending pause's `closeCamera()` actually runs let the stale close fire AFTER
    // the fresh `openCamera()`, unbinding the just-resumed camera and nulling its fields.
    // A generation counter alone wouldn't fix this: skipping a stale close would leave the
    // old camera/watermark `HandlerThread` bound and leaked underneath the new session, since
    // CameraX doesn't implicitly unbind a previous `UseCaseGroup`. The mutex enforces the
    // close-before-reopen ordering the code actually needs.
    private val cameraLifecycleMutex = Mutex()

    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var watermarkOverlay: WatermarkOverlay? = null

    // Dual (front+back) recording (§9.3) — the secondary (front) camera pipeline,
    // mirroring the primary (back) fields above; all null/absent whenever
    // `dualRecordingEnabled` is false, or when the device can't actually bind front+back
    // concurrently (checked fresh in `openCamera()`, since availability can change between
    // the prep sheet's own check and now). No secondary `Preview`/live-preview surface —
    // the visible feed and torch control stay back-camera-only (§9.3).
    private var secondaryVideoCapture: VideoCapture<Recorder>? = null
    private var secondaryActiveRecording: Recording? = null
    private var secondaryWatermarkOverlay: WatermarkOverlay? = null
    private var secondaryVideoFinalizerListener: CompletableDeferred<Unit>? = null

    // Whether the current/most recent recording actually bound a secondary camera —
    // unlike `secondaryVideoCapture` (which `closeCamera()` nulls out during teardown),
    // this deliberately survives `closeCamera()` so `getRecordingInformation()` (called
    // from the save flow around the same time the service is stopping/tearing down, §9.3)
    // reads a stable answer regardless of exactly when it races against `closeCamera()`.
    private var secondaryStreamActive = false

    // Constructed eagerly (not inside `openCamera()`) so `setPreviewSurfaceProvider` can
    // be called at any time — e.g. the UI binds its `PreviewView` before the camera has
    // actually finished opening — without needing to coordinate with `openCamera()`'s
    // timing. Bound into the same `UseCaseGroup`/watermark effect as `videoCapture` once
    // `openCamera()` runs, giving the live preview the same WYSIWYG guarantee as the
    // saved file (§9.1).
    private val preview: Preview = Preview.Builder().build()

    // The `Preview` use case only ever has one active surface provider at a time, but
    // TWO different `RecordingPreview` instances can legitimately want it across a
    // background/foreground transition — the Activity's own (§9.1), and the PIP
    // overlay's (§9.5) — with no guaranteed order between "the old one detaching" and
    // "the new one attaching." Real-device testing found exactly this: resuming
    // playback from the PIP and then switching to full-screen left the Activity's own
    // preview frozen on its last frame, because the PIP's teardown nulled the surface
    // provider without anything re-attaching the Activity's (which had already run its
    // one-time `AndroidView` factory long before and had no other trigger to re-attach).
    // Tracked by identity so a stale/late clear can never wipe out a provider some OTHER
    // caller has since legitimately attached — see `clearPreviewSurfaceProvider` below.
    private var activePreviewSurfaceProvider: Preview.SurfaceProvider? = null

    // Called by a live-preview composable (the Activity's own, §9.1, or the PIP
    // overlay's, §9.5) once it has a `PreviewView` ready.
    fun setPreviewSurfaceProvider(surfaceProvider: Preview.SurfaceProvider) {
        activePreviewSurfaceProvider = surfaceProvider
        preview.setSurfaceProvider(surfaceProvider)
    }

    // Called when a live-preview composable leaves composition — a no-op unless
    // `surfaceProvider` is still the currently-active one, so a composable that's since
    // been superseded by a different one (see the field doc above) can't tear down a
    // provider it no longer owns.
    fun clearPreviewSurfaceProvider(surfaceProvider: Preview.SurfaceProvider) {
        if (activePreviewSurfaceProvider !== surfaceProvider) {
            return
        }

        activePreviewSurfaceProvider = null
        preview.setSurfaceProvider(null)
    }

    // Cut/Uncut Video Feed (newFeat.md Feature 1) — hides the live preview on the Record
    // screen without touching recording at all: `RecordingPreview` only ever consumes this
    // service's `Preview` use case, while `videoCapture` is bound independently in the same
    // `UseCaseGroup` (see `openCamera()` below) and keeps encoding regardless of what's
    // drawn on the preview surface. Mirrors the `onFeedCutChange` callback pattern already
    // used for `IntervalRecorderService.onPendingImpactSaveChange`.
    //
    // Compose-observable (`by mutableStateOf`, not a plain field) so the PIP overlay's
    // content (§9.5, read directly off this service) can reactively swap to its own
    // "Dashcam active" placeholder the moment the feed is cut, the same way
    // `overlayIsPaused` below drives its paused placeholder. Split into a private
    // `_feedCut` delegate + a public getter-only `feedCut`, rather than `var feedCut by
    // mutableStateOf(...) private set` directly: a delegated property's private setter
    // still needs a real JVM accessor method (unlike a plain `var`'s, which the compiler
    // can elide), and that synthetic `setFeedCut(Boolean)` collides with — "platform
    // declaration clash" — the hand-written `setFeedCut` below.
    private var _feedCut by mutableStateOf(false)
    val feedCut: Boolean
        get() = _feedCut
    var onFeedCutChange: (Boolean) -> Unit = {}
    fun setFeedCut(cut: Boolean) {
        _feedCut = cut
        onFeedCutChange(cut)
    }

    // PIP overlay window (newFeat.md Feature 2) — a small draggable floating preview shown
    // via `WindowManager` while the app is backgrounded during an active recording. Hosted
    // from this service (not `MainActivity`) since the service is what survives the app
    // being swiped away.
    //
    // Must be `by lazy`, not a plain field initializer: `PipOverlayWindow`'s constructor
    // calls `context.getSystemService(...)`, but field initializers run inside the Kotlin
    // constructor, which Android invokes via `newInstance()` *before* `attachBaseContext()`
    // gives the Service a real `Context` — calling `getSystemService()` on `this` at that
    // point throws `NullPointerException` on the unattached base context, crashing the
    // service on every single creation (i.e. every tap of Start, independent of the PIP
    // setting). `displayManager` below already sidesteps this exact trap with `by lazy`;
    // this needs the same treatment.
    private val pipOverlay by lazy { PipOverlayWindow(this) }

    // Compose-observable so `PipOverlayContent` (read directly off this service, with no
    // ViewModel indirection since the overlay isn't part of the normal Activity-hosted UI
    // tree) can reactively swap between the live preview and the paused placeholder.
    // Updated directly in `pause()`/`resume()`/`start()` below rather than through
    // `onStateChange` — that callback slot is already claimed by `VideoRecorderModel` for
    // the Activity-side UI, and a service-internal concern like this shouldn't compete for
    // it.
    var overlayIsPaused: Boolean by mutableStateOf(false)
        private set

    // `ProcessLifecycleOwner` (unlike this service's own `LifecycleOwner`, whose lifecycle
    // follows the *service's* create/destroy) reports the whole app's foreground/background
    // state — exactly what §2.2 of newFeat.md needs: show the overlay when the app (not
    // just this service) goes to the background, hide it when the app returns.
    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            maybeShowPipOverlay()
        }

        override fun onStart(owner: LifecycleOwner) {
            hidePipOverlay()
        }
    }

    // Show conditions (all must be true) — deliberately re-checked every time this runs
    // (on every app-background transition) rather than cached, since any of them can
    // change between one backgrounding and the next (the user can toggle the setting or
    // revoke the overlay permission without stopping the service). Deliberately does
    // NOT gate on `feedCut` — the overlay shows regardless, falling back to its own
    // "Dashcam active" placeholder (mirroring the Record screen's own cut placeholder,
    // §9.1) instead of live video when cut, rather than not appearing at all. This
    // matches explicit product direction: cutting the feed is about not showing camera
    // content, not about losing access to the overlay's Pause/Resume + tap-to-reopen
    // while backgrounded.
    private fun maybeShowPipOverlay() {
        if (pipOverlay.isShowing) return
        // Only when actively recording, not paused, at the moment of backgrounding —
        // resuming from the overlay itself is handled separately (§2.5) and does NOT
        // re-trigger this check.
        if (state != RecorderState.RECORDING) return
        if (!settings.pip.enabled) return
        if (!Settings.canDrawOverlays(this)) return

        pipOverlay.show(this)
    }

    private fun hidePipOverlay() {
        pipOverlay.hide()
    }

    // `MainActivity` declares `configChanges` (§10.1) so it's never recreated on
    // rotation — which normally is *also* how a CameraX use case would learn about a
    // new device rotation (a fresh `Preview`/`VideoCapture` gets built with the current
    // rotation baked in). Without an Activity recreation to piggyback on, CameraX's own
    // docs are explicit that the app must call `setTargetRotation()` on each use case
    // itself whenever rotation changes — this is exactly that, done here (not in the
    // Activity) since the camera itself is entirely service-owned. Confirmed on-device
    // this was missing: the live preview/recording never rotated to match the physical
    // device orientation, and — since `WatermarkRenderer` derives its own rotation
    // compensation from this same CameraX-reported value — the watermark's corner
    // placement was wrong for exactly the same underlying reason.
    private val displayManager: DisplayManager by lazy {
        getSystemService(DISPLAY_SERVICE) as DisplayManager
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                updateTargetRotation()
            }
        }
    }

    private fun updateTargetRotation() {
        val rotation = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: return
        preview.targetRotation = rotation
        videoCapture?.targetRotation = rotation
        secondaryVideoCapture?.targetRotation = rotation
    }

    // Known limitation (§9.3): while actively recording, the back camera's saved-video
    // watermark stays frozen at whatever rotation was current when recording started —
    // `updateTargetRotation()` above doesn't fix it, since the frozen value isn't coming
    // from `VideoCapture.targetRotation` itself. Two on-device-tested rebind-based fixes
    // were tried and reverted:
    //   1. Rebinding with `preview` dropped then restored, reusing the same
    //      `WatermarkOverlay`/`OverlayEffect` — recording stayed intact (safe), but the
    //      watermark still didn't rotate. Rules out "shared ViewPort with `preview`."
    //   2. Rebinding with a BRAND NEW `WatermarkOverlay`/`OverlayEffect` each time —
    //      broke live preview AND froze the active recording on rotation. Reverted
    //      immediately; a cosmetic watermark bug must never risk the actual recording.
    // The dual-recording front stream (bound with `VideoCapture` alone, no `Preview`)
    // does NOT have this problem — only the back camera, which always shares its
    // `UseCaseGroup` with the live `preview`, is affected. Root cause still unconfirmed;
    // further investigation needs actual device logcat (e.g. logging `frame.rotationDegrees`
    // from `WatermarkRenderer.draw()` across a rotation event) rather than another blind
    // rebind attempt, since both plausible static-analysis hypotheses have now been ruled
    // out or shown to be unsafe.

    // With no `ViewPort`, CameraX lets `Preview` and `VideoCapture` each pick their own
    // resolution/crop independently — the CameraX docs are explicit that a shared
    // `ViewPort` on the `UseCaseGroup` is what "guarantees the crop rects of all the use
    // cases in the group point to the same area in the camera sensor," which is exactly
    // the WYSIWYG guarantee `WatermarkOverlay`'s doc comment already claims but this
    // service didn't actually establish. Rebuilt fresh in `openCamera()` on every bind
    // (start/resume), so it always reflects whatever rotation is current at bind time;
    // per-frame rotation after that is still `setTargetRotation()`'s job
    // (`updateTargetRotation` above), not this.
    //
    // Real-device bug: this used to pair `rotation` with `resources.displayMetrics`.
    // `resources` here comes from the bare `Service` context, which — unlike an
    // `Activity`'s — does NOT reliably swap `widthPixels`/`heightPixels` to match the
    // display's *current* rotation (it's known to report the default/natural-orientation
    // size regardless of how the device is actually rotated, since a `Service` has no
    // window of its own for the framework to key off of). That meant the `ViewPort`'s
    // aspect ratio and its `rotation` argument disagreed with each other in landscape —
    // `WatermarkRenderer`, which derives its logical width/height from that same
    // `ViewPort`-influenced `frame.cropRect`, ended up computing portrait-shaped
    // dimensions even while the reported rotation said landscape (watermark rendered as
    // if the screen were still vertical), and in portrait the resulting crop rect could
    // end up degenerate enough to place the watermark plate off-frame entirely
    // (invisible). `Display.getRealMetrics()` is the standard fix for exactly this
    // no-window-context gap — it's keyed to the `Display` object itself, so it correctly
    // reports already-rotated dimensions.
    private fun buildViewPort(): ViewPort {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val rotation = display?.rotation ?: android.view.Surface.ROTATION_0

        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        if (display != null) {
            display.getRealMetrics(metrics)
        } else {
            resources.displayMetrics.let {
                metrics.widthPixels = it.widthPixels
                metrics.heightPixels = it.heightPixels
            }
        }

        return ViewPort.Builder(Rational(metrics.widthPixels, metrics.heightPixels), rotation)
            .build()
    }

    // Used to listen and check if the camera is available
    private var _cameraAvailableListener = CompletableDeferred<Unit>()
    private lateinit var _videoFinalizerListener: CompletableDeferred<Unit>;

    // Absolute last completer that can be awaited to ensure that the camera is closed
    private var _cameraCloserListener = CompletableDeferred<Unit>()

    private lateinit var selectedCamera: CameraSelector
    private var enableAudio by Delegates.notNull<Boolean>()
    private var dualRecordingEnabled = false

    var onCameraControlAvailable = {}

    override fun onCreate() {
        // Must run BEFORE `super.onCreate()`: `SavedStateRegistryController.performAttach()`/
        // `performRestore()` both assert the owner's `Lifecycle` is still `INITIALIZED` and
        // throw `IllegalStateException` otherwise. `super.onCreate()` (LifecycleService)
        // dispatches `ON_CREATE`, which moves the lifecycle to `CREATED` — calling these
        // after that point crashes the service on every creation, i.e. every time the user
        // taps Start (regardless of the PIP setting, since this has nothing to do with it).
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)

        super.onCreate()

        // Registered here (service creation), not `start()`/`startForegroundService()` —
        // the observer itself is cheap and stateless; `maybeShowPipOverlay()`'s own gating
        // (state == RECORDING, etc.) is what actually decides whether anything happens on
        // a given background transition.
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        hidePipOverlay()
        viewModelStore.clear()

        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "init") {
            selectedCamera = CameraSelector.Builder().requireLensFacing(
                intent.getIntExtra("cameraID", CameraSelector.LENS_FACING_BACK)
            ).build()
            enableAudio = intent.getBooleanExtra("enableAudio", true)
            dualRecordingEnabled = intent.getBooleanExtra("dualRecording", false)
        }

        return super.onStartCommand(intent, flags, startId)
    }

    override fun start() {
        super.start()

        overlayIsPaused = false

        scope.launch {
            openCamera()
        }
    }

    override suspend fun stop() {
        super.stop()

        stopActiveRecording()

        // Camera can only be closed after the recording has been finalized
        withTimeoutOrNull(CAMERA_CLOSE_TIMEOUT) {
            _videoFinalizerListener.await()
            secondaryVideoFinalizerListener?.await()
        }

        // Only now is the last actively-recording batch guaranteed flushed to disk —
        // safe to concatenate a still-pending impact auto-save, if any (§7.2/§7.4).
        finalizePendingImpactSaveOnStop()

        closeCamera()

        withTimeoutOrNull(CAMERA_CLOSE_TIMEOUT) {
            _cameraCloserListener.await()
        }

        // Recording has fully stopped (§2.3's hide condition) — unlike pause, there's no
        // "resume from the overlay" path back from here, so the overlay (if shown) must
        // come down now rather than staying mounted with nothing left to show.
        hidePipOverlay()
    }

    override fun pause() {
        super.pause()

        overlayIsPaused = true

        stopActiveRecording()

        // No camera use while paused (§9.1) — closes the camera (and, with it, the live
        // preview) the same way `stop()` does, waiting for the just-stopped segment to
        // actually finish flushing first so `closeCamera()` doesn't unbind mid-write.
        // Runs under `cameraLifecycleMutex` so a quick resume can't reopen the camera before
        // this close actually runs (see the mutex's declaration comment).
        scope.launch {
            cameraLifecycleMutex.withLock {
                withTimeoutOrNull(CAMERA_CLOSE_TIMEOUT) {
                    _videoFinalizerListener.await()
                    secondaryVideoFinalizerListener?.await()
                }
                closeCamera()
            }
        }
    }

    override fun resume() {
        overlayIsPaused = false

        // Reset before kicking off the reopen below, so `startNewCycle`'s existing
        // "wait for the camera" gate (see its `_cameraAvailableListener.isCompleted`
        // check) correctly waits for THIS reopen rather than seeing the previous,
        // already-completed one from before pause and proceeding immediately with no
        // camera bound yet.
        _cameraAvailableListener = CompletableDeferred()

        // Runs under `cameraLifecycleMutex` so this waits for a pending pause's
        // `closeCamera()` to finish first, instead of racing ahead of it (see the mutex's
        // declaration comment).
        scope.launch {
            cameraLifecycleMutex.withLock { openCamera() }
        }

        // Recreates the cycle timer, which triggers `startNewCycle()` — gated on the
        // fresh `_cameraAvailableListener` above until `openCamera()` completes.
        super.resume()
    }

    override fun startForegroundService() {
        ServiceCompat.startForeground(
            this,
            NotificationHelper.RECORDER_CHANNEL_NOTIFICATION_ID,
            getNotificationHelper().buildStartingNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA

                if (enableAudio) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }

                // Only declare the location type if the permission is actually granted
                // right now — Android 14+ throws if startForeground() declares a type
                // whose runtime permission isn't held, and unlike camera/mic, location
                // (§6.1) is optional/best-effort here: LocationTracker already degrades
                // to "-- km/h" with no fix, recording must never be blocked by it.
                if (PermissionHelper.hasGranted(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }

                types
            } else {
                0
            },
        )
    }

    @SuppressLint("MissingPermission")
    override fun startNewCycle() {
        super.startNewCycle()

        fun action() {
            stopActiveRecording()
            val newRecording = prepareVideoRecording(videoCapture!!, withAudio = enableAudio)

            _videoFinalizerListener = CompletableDeferred()

            activeRecording = newRecording.start(ContextCompat.getMainExecutor(this)) { event ->
                if (event is VideoRecordEvent.Finalize && (this@VideoRecorderService.state == RecorderState.STOPPED || this@VideoRecorderService.state == RecorderState.PAUSED)) {
                    _videoFinalizerListener.complete(Unit)
                }
            }

            val secondaryCapture = secondaryVideoCapture
            if (secondaryCapture != null) {
                val newSecondaryRecording = prepareVideoRecording(
                    secondaryCapture,
                    tag = BatchesFolder.FRONT_STREAM_FILENAME_TAG,
                    withAudio = false,
                )

                secondaryVideoFinalizerListener = CompletableDeferred()

                secondaryActiveRecording =
                    newSecondaryRecording.start(ContextCompat.getMainExecutor(this)) { event ->
                        if (event is VideoRecordEvent.Finalize && (this@VideoRecorderService.state == RecorderState.STOPPED || this@VideoRecorderService.state == RecorderState.PAUSED)) {
                            secondaryVideoFinalizerListener?.complete(Unit)
                        }
                    }
            }
        }

        if (_cameraAvailableListener.isCompleted) {
            action()
        } else {
            // Race condition of `startNewCycle` being called before `invokeOnCompletion`
            // has been called can be ignored, as the camera usually opens within 5 seconds
            // and the interval can't be set shorter than 10 seconds.
            _cameraAvailableListener.invokeOnCompletion {
                action()
            }
        }
    }


    // Runs a function in the main thread
    private fun runOnMain(callback: () -> Unit) {
        val mainHandler = ContextCompat.getMainExecutor(this)

        mainHandler.execute(callback)
    }

    private fun buildRecorder() = Recorder.Builder()
        .setQualitySelector(
            settings.videoRecorderSettings.getQualitySelector()
                ?: QualitySelector.from(Quality.HIGHEST)
        )
        .apply {
            if (settings.videoRecorderSettings.targetedVideoBitRate != null) {
                setTargetVideoEncodingBitRate(settings.videoRecorderSettings.targetedVideoBitRate!!)
            }
        }
        .build()

    private fun buildVideoCapture(recorder: Recorder) = VideoCapture.Builder(recorder)
        .apply {
            val frameRate = settings.videoRecorderSettings.targetFrameRate
            if (frameRate != null) {
                setTargetFrameRate(Range(frameRate, frameRate))
            }
        }
        .build()

    // Opens the camera — called both when a recording session starts and every time it
    // resumes from a pause (§9.1: the camera is fully closed while paused, so resuming
    // has to reopen it, not just resume writing to an already-open one).
    private suspend fun openCamera() {
        cameraProvider = withContext(Dispatchers.IO) {
            ProcessCameraProvider.getInstance(this@VideoRecorderService).get()
        }

        val recorder = buildRecorder()
        videoCapture = buildVideoCapture(recorder)
        updateTargetRotation()
        displayManager.registerDisplayListener(displayListener, null)

        // Burns the watermark into every frame both VideoCapture's encoder AND the live
        // preview receive (§5, §9.1) — a CameraEffect attached via UseCaseGroup,
        // CameraX's officially-supported way to composite a GL/Canvas layer onto camera
        // frames before they reach either output.
        watermarkOverlay = WatermarkOverlay(
            context = this,
            locationTracker = locationTracker,
            getSettings = { settings.watermark },
        )
        // Computed once and reused for both use case groups below (primary + dual-recording
        // secondary) — it doesn't depend on which camera it's bound to, so there's no need
        // to re-query `Display.getRealMetrics()` a second time in the same `openCamera()` call.
        val viewPort = buildViewPort()

        val useCaseGroup = UseCaseGroup.Builder()
            .addUseCase(videoCapture!!)
            .addUseCase(preview)
            .addEffect(watermarkOverlay!!.cameraEffect)
            .setViewPort(viewPort)
            .build()

        // Dual (front+back) recording (§9.3): resolve a concurrently-bindable front+back
        // pair straight from CameraX's own capability query, independent of
        // `selectedCamera` (only meaningful for the single-camera path below). Left null —
        // silently falling back to single-camera with the original `selectedCamera` — if
        // the device can't actually bind front+back right now, e.g. a race against the
        // prep sheet's own capability check.
        var secondarySelector: CameraSelector? = null
        secondaryStreamActive = false
        if (dualRecordingEnabled) {
            for (concurrentSet in cameraProvider!!.availableConcurrentCameraInfos) {
                val back = concurrentSet.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK }
                val front = concurrentSet.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_FRONT }

                if (back != null && front != null) {
                    selectedCamera = back.cameraSelector
                    secondarySelector = front.cameraSelector
                    break
                }
            }
        }

        var secondaryUseCaseGroup: UseCaseGroup? = null
        if (secondarySelector != null) {
            val secondaryRecorder = buildRecorder()
            secondaryVideoCapture = buildVideoCapture(secondaryRecorder)
            // `updateTargetRotation()` already ran above (line ~376), before this field
            // existed — its `secondaryVideoCapture?.targetRotation = rotation` line was a
            // no-op that call. Without this, the secondary/front stream's initial
            // targetRotation was left to whatever CameraX's own construction-time default
            // resolves to, rather than the explicitly-read current rotation the primary
            // stream got — re-running it now that the field is non-null closes that gap.
            updateTargetRotation()
            secondaryWatermarkOverlay = WatermarkOverlay(
                context = this,
                locationTracker = locationTracker,
                getSettings = { settings.watermark },
            )
            secondaryUseCaseGroup = UseCaseGroup.Builder()
                .addUseCase(secondaryVideoCapture!!)
                .addEffect(secondaryWatermarkOverlay!!.cameraEffect)
                .setViewPort(viewPort)
                .build()
        }

        runOnMain {
            try {
                camera = if (secondarySelector != null && secondaryUseCaseGroup != null) {
                    // `ConcurrentCamera.getCameras()` preserves the order of the
                    // `SingleCameraConfig` list passed in — the back camera (index 0) is
                    // what `CameraControl`/torch below wrap; unverified on real hardware,
                    // flag if torch ends up controlling the wrong physical camera.
                    val bound = cameraProvider!!.bindToLifecycle(
                        listOf(
                            ConcurrentCamera.SingleCameraConfig(selectedCamera, useCaseGroup, this),
                            ConcurrentCamera.SingleCameraConfig(secondarySelector, secondaryUseCaseGroup, this),
                        )
                    )
                    secondaryStreamActive = true
                    bound.cameras.first()
                } else {
                    cameraProvider!!.bindToLifecycle(
                        this,
                        selectedCamera,
                        useCaseGroup
                    )
                }

                onCameraControlAvailable()

                applyFocusSettings(camera!!)

                _cameraAvailableListener.complete(Unit)
            } catch (error: IllegalArgumentException) {
                onError()
            }
        }
    }

    // Locks focus to the far distance and disables continuous autofocus on the primary
    // (rear) camera when enabled in settings (§9.1) — a windshield-mounted dashcam's own
    // dashboard/hood sits much closer to the lens than the road it's meant to capture,
    // and autofocus can otherwise lock onto that near surface instead. Only applied to
    // the primary camera: a dual-recording secondary stream is a driver-facing camera
    // (its subject IS close), so forcing far-distance focus there would be wrong.
    // `LENS_FOCUS_DISTANCE = 0.0f` is the Camera2 convention for infinity focus. Applied
    // post-bind via `Camera2CameraControl` rather than at use-case-builder time, since
    // `preview`/`videoCapture` are already built by the time settings are read here —
    // wrapped in `runCatching` since a fixed-focus lens (no `CONTROL_AF_MODE_OFF`/manual
    // focus support at all) would otherwise throw here instead of just no-op'ing.
    private fun applyFocusSettings(camera: Camera) {
        if (!settings.videoRecorderSettings.disableAutofocus) {
            return
        }

        runCatching {
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)
                .build()

            Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(options)
        }.onFailure { error ->
            Log.e("VideoRecorderService", "Failed to apply fixed far-distance focus", error)
        }
    }

    // Closes the camera — also called on pause now (§9.1: no camera use while paused),
    // in addition to its original "recording has fully stopped" use.
    private fun closeCamera() {
        runCatching {
            displayManager.unregisterDisplayListener(displayListener)
        }

        runOnMain {
            // Each step isolated in its own `runCatching` — previously only `unbindAll()`
            // was guarded, so an exception from either overlay's `close()` skipped
            // everything after it: the other overlay's close (leaking its `HandlerThread`,
            // a fresh one per `openCamera()` call), `_cameraCloserListener.complete(Unit)`
            // (blocking `stop()`'s await on it for up to `CAMERA_CLOSE_TIMEOUT`), and the
            // field-nulling below.
            runCatching {
                cameraProvider?.unbindAll()
            }
            runCatching {
                watermarkOverlay?.close()
            }
            runCatching {
                secondaryWatermarkOverlay?.close()
            }
            _cameraCloserListener.complete(Unit)

            // Doesn't need to run on main thread, but
            // if it runs outside `runOnMain`, `cameraProvider` is already null
            // before it's unbound
            cameraProvider = null
            videoCapture = null
            camera = null
            watermarkOverlay = null
            secondaryVideoCapture = null
            secondaryWatermarkOverlay = null
        }
    }

    private fun stopActiveRecording() {
        runCatching {
            activeRecording?.stop()
        }
        runCatching {
            secondaryActiveRecording?.stop()
        }
    }

    private fun getNameForMediaFile(tag: String? = null): String {
        val suffix = if (tag != null) "-$tag" else ""
        return "${batchesFolder.mediaPrefix}$counter$suffix.${settings.videoRecorderSettings.fileExtension}"
    }

    // `tag` distinguishes the secondary (front) stream's batch files from the primary's
    // (§9.3) — null for the primary. `withAudio` is always false for the secondary stream
    // regardless of the `enableAudio` setting: there's one physical microphone, and two
    // concurrent `Recorder`s both capturing it is an unsupported/unverified combination
    // this app doesn't attempt — audio only ever goes on the primary (back) stream's file.
    @SuppressLint("MissingPermission", "NewApi")
    private fun prepareVideoRecording(
        videoCapture: VideoCapture<Recorder>,
        tag: String? = null,
        withAudio: Boolean = enableAudio,
    ) =
        videoCapture.output
            .let {
                if (batchesFolder.type == BatchesFolder.BatchType.CUSTOM && SUPPORTS_SAVING_VIDEOS_IN_CUSTOM_FOLDERS) {
                    it.prepareRecording(
                        this,
                        FileDescriptorOutputOptions.Builder(
                            batchesFolder.asCustomGetParcelFileDescriptor(
                                counter,
                                settings.videoRecorderSettings.fileExtension,
                                tag,
                            )
                        ).build()
                    )
                } else if (batchesFolder.type == BatchesFolder.BatchType.MEDIA) {
                    if (SUPPORTS_SCOPED_STORAGE) {
                        val name = getNameForMediaFile(tag)

                        it.prepareRecording(
                            this,
                            MediaStoreOutputOptions
                                .Builder(
                                    contentResolver,
                                    batchesFolder.scopedMediaContentUri,
                                )
                                .setContentValues(
                                    batchesFolder.asMediaGetScopedStorageContentValues(
                                        name
                                    )
                                )
                                .build()
                        )
                    } else {
                        val name = getNameForMediaFile(tag)

                        it.prepareRecording(
                            this,
                            FileOutputOptions
                                .Builder(batchesFolder.asMediaGetLegacyFile(name))
                                .build()
                        )
                    }
                } else {
                    it.prepareRecording(
                        this,
                        FileOutputOptions.Builder(
                            batchesFolder.asInternalGetFile(
                                counter,
                                settings.videoRecorderSettings.fileExtension,
                                tag,
                            ).apply {
                                createNewFile()
                            }
                        ).build()
                    )
                }
            }
            .run {
                if (withAudio) {
                    return@run withAudioEnabled()
                }

                this
            }

    override fun getRecordingInformation() =
        RecordingInformation(
            folderPath = batchesFolder.exportFolderForSettings(),
            recordingStart = recordingStart,
            maxDuration = settings.maxDuration,
            // Deliberately untagged (primary/back stream only) — the secondary stream's
            // batch count is irrelevant here since both streams share the same counter
            // per cycle (§9.3), so counting either one gives the same `batchesAmount`.
            batchesAmount = batchesFolder.getBatchesForConcatenation().size,
            fileExtension = settings.videoRecorderSettings.fileExtension,
            intervalDuration = settings.intervalDuration,
            type = RecordingInformation.Type.VIDEO,
            hasSecondaryStream = secondaryStreamActive,
        )

    companion object {
        const val CAMERA_CLOSE_TIMEOUT = 20000L
    }
}
