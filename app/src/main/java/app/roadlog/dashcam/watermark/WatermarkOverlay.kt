package app.roadlog.dashcam.watermark

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import androidx.camera.core.CameraEffect
import androidx.camera.effects.OverlayEffect
import app.roadlog.dashcam.db.WatermarkSettings
import app.roadlog.dashcam.helpers.LocationTracker

// Ties `WatermarkTextProvider` (§5.2) and `WatermarkRenderer` (§5.1 step 3) to a CameraX
// `OverlayEffect` — the officially-supported CameraX Effects API helper for compositing a
// `Canvas`-drawn overlay onto camera frames (§5.1). Preferred over hand-rolling a raw
// `SurfaceProcessor` with manual EGL/GLSL passthrough+quad shaders, which is what §5.1
// originally described building: `OverlayEffect` already provides that same "draw a
// Canvas overlay, get it composited onto every output surface" guarantee, and is less
// code and less risk to maintain — see the note added to PLAN.md §5.1 about this.
//
// Owns a background `HandlerThread` since drawing must happen off the caller's thread and
// `OverlayEffect` requires a `Handler` tied to a `Looper`; call `close()` when the
// recording service stops to release it.
class WatermarkOverlay(
    context: Context,
    locationTracker: LocationTracker,
    private val getSettings: () -> WatermarkSettings,
) {
    private val textProvider = WatermarkTextProvider(locationTracker)
    private val renderer = WatermarkRenderer(context)

    // Runs `OverlayEffect`'s per-frame draw callback for the whole recording session, with
    // `queueDepth = 0` below (zero buffering tolerance) — a late callback here is a
    // dropped/backpressured frame, not just a delayed one, so this thread is elevated above
    // default priority to reduce the chance of it losing the CPU under contention.
    private val handlerThread = HandlerThread(
        "WatermarkOverlay",
        Process.THREAD_PRIORITY_URGENT_DISPLAY,
    ).apply { start() }
    private val handler = Handler(handlerThread.looper)

    // Attach this to a `UseCaseGroup` alongside both `VideoCapture` and `Preview` —
    // covering both targets is what makes the live preview a truthful WYSIWYG
    // representation of the saved file (§4.5, §9.1): the exact same composited frame
    // (camera + watermark) fans out to both outputs, not two independently-rendered
    // copies that could drift apart.
    val cameraEffect: CameraEffect = OverlayEffect(
        CameraEffect.VIDEO_CAPTURE or CameraEffect.PREVIEW,
        /* queueDepth = */ 0,
        handler,
    ) { error ->
        Log.e("WatermarkOverlay", "Error compositing watermark", error)
    }.apply {
        setOnDrawListener { frame ->
            val settings = getSettings()
            val text = if (settings.enabled) textProvider.currentText(settings) else null
            renderer.draw(frame, text, settings.corner)
        }
    }

    fun close() {
        (cameraEffect as OverlayEffect).close()
        handlerThread.quitSafely()
    }
}
