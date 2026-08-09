package app.roadlog.dashcam.ui.components.RecorderScreen.atoms

import android.view.ViewGroup
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.roadlog.dashcam.services.VideoRecorderService

// Unlike `CameraPreview` (its own independent `Preview` use case + `ProcessCameraProvider`
// bind, Activity-lifecycle-owned — used only pre-recording, in the preparation sheet),
// this binds to the recording *service's* already-open camera by handing it a
// `PreviewView`'s surface provider (`VideoRecorderService.setPreviewSurfaceProvider`).
// That service-side `Preview` use case shares the same `UseCaseGroup`/watermark
// `CameraEffect` as `VideoCapture` (§5, §9.1) — so what's shown here is composited from
// the exact same frames being encoded to disk, not a separately-rendered copy that could
// drift out of sync with what actually gets saved.
@Composable
fun RecordingPreview(
    modifier: Modifier = Modifier,
    recorderService: VideoRecorderService,
) {
    // Captured once, at the exact `PreviewView` instance this composable's own
    // `AndroidView` created — reused for both the initial attach and every later
    // re-attach/detach, so identity comparisons against `VideoRecorderService`'s
    // `activePreviewSurfaceProvider` always refer to the SAME object.
    var capturedProvider by remember { mutableStateOf<Preview.SurfaceProvider?>(null) }

    Box(modifier = modifier) {
        AndroidView(
            factory = { context ->
                PreviewView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    // COMPATIBLE (TextureView-backed) is required here: the default
                    // PERFORMANCE mode lets the system back this view with a SurfaceView
                    // that can bypass the GPU compositor via a hardware overlay, which
                    // skips the watermark CameraEffect's output entirely on some devices.
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    capturedProvider = surfaceProvider
                    recorderService.setPreviewSurfaceProvider(surfaceProvider)
                }
            },
        )
    }

    // Re-claims the `Preview` use case's single surface-provider slot every time this
    // composable's own Activity/window returns to the foreground (`ON_RESUME`) — not
    // just once at creation. Needed because a *different* `RecordingPreview` instance
    // (the PIP overlay's, §9.5) can take over that slot while this one's Activity is
    // backgrounded; when the PIP later relinquishes it, this composable's `AndroidView`
    // factory above has already run once and has no other trigger to run again, so
    // without this it would never re-attach — real-device testing confirmed this showed
    // as the preview freezing on its last frame after returning from the PIP. Harmless
    // on first mount too: `Lifecycle.addObserver` synchronously replays catch-up events
    // for the observer's current state, so an already-RESUMED lifecycle re-fires this
    // once immediately, which is a no-op re-attach of the same provider just set above.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, recorderService) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                capturedProvider?.let(recorderService::setPreviewSurfaceProvider)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Detach whenever this leaves composition (screen navigated away from, or
            // recording stops and the service is torn down) — a no-op if some other
            // `RecordingPreview` has since taken over the slot (see
            // `clearPreviewSurfaceProvider`'s own doc comment).
            capturedProvider?.let(recorderService::clearPreviewSurfaceProvider)
        }
    }
}
