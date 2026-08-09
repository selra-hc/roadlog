package app.roadlog.dashcam.ui.components.PipOverlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.roadlog.dashcam.MainActivity
import app.roadlog.dashcam.services.VideoRecorderService

// Owns the WindowManager view lifecycle + drag handling for the floating preview window
// shown while the app is backgrounded during recording (newFeat.md Feature 2). Hosted
// from the recording *service*, not an Activity — the service is what survives the app
// being swiped away, and it already implements LifecycleOwner (via LifecycleService)
// plus ViewModelStoreOwner/SavedStateRegistryOwner (see VideoRecorderService) so a bare
// ComposeView can be attached directly to the WindowManager, with no Activity involved.
class PipOverlayWindow(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var composeView: ComposeView? = null

    // Held so drag updates can mutate x/y and re-apply via `updateViewLayout` without
    // reconstructing the params object every frame.
    private var params: WindowManager.LayoutParams? = null

    val isShowing: Boolean
        get() = composeView != null

    fun show(recorderService: VideoRecorderService) {
        if (composeView != null) return

        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(recorderService)
            setViewTreeViewModelStoreOwner(recorderService)
            setViewTreeSavedStateRegistryOwner(recorderService)
            setContent {
                PipOverlayContent(
                    recorderService = recorderService,
                    onDrag = ::updateLayout,
                    onOpenApp = ::openApp,
                    onCutFeed = { recorderService.setFeedCut(!recorderService.feedCut) },
                    onSave = { recorderService.saveCurrentBufferNow() },
                )
            }
        }

        val density = context.resources.displayMetrics.density
        val layoutParams = WindowManager.LayoutParams(
            (PIP_WIDTH_DP * density).toInt(),
            (PIP_HEIGHT_DP * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        // `addView` can still fail here despite the caller (`VideoRecorderService
        // .maybeShowPipOverlay`) having already checked `Settings.canDrawOverlays` —
        // e.g. a OEM-specific overlay restriction — so this never risks the actual
        // recording, just the (optional) floating window.
        runCatching {
            windowManager.addView(view, layoutParams)
            composeView = view
            params = layoutParams
        }
    }

    // Called from `PipOverlayContent`'s tap gesture on the background (not the
    // Pause/Resume button, which consumes its own taps first). `FLAG_ACTIVITY_NEW_TASK`
    // is required to start an Activity from a Service context; `FLAG_ACTIVITY_
    // REORDER_TO_FRONT` brings the existing `MainActivity` instance already in its task
    // to the foreground instead of creating a stacked duplicate on top of it (`MainActivity`
    // uses the default `standard` launch mode, so without this flag a plain `NEW_TASK`
    // start would pile up a fresh instance every time). Hiding the overlay itself is left
    // to the existing `ProcessLifecycleOwner.onStart` → `hidePipOverlay()` path (§2.2/§9.5)
    // once the app actually reaches the foreground, rather than hidden here too, to avoid
    // racing that same teardown from two places.
    private fun openApp() {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        runCatching {
            context.startActivity(intent)
        }
    }

    // Called from `PipOverlayContent`'s drag gesture (`detectDragGestures` on the
    // background) — cheap enough to call per-drag-delta frame.
    private fun updateLayout(dx: Int, dy: Int) {
        val view = composeView ?: return
        val layoutParams = params ?: return

        layoutParams.x += dx
        layoutParams.y += dy

        runCatching {
            windowManager.updateViewLayout(view, layoutParams)
        }
    }

    fun hide() {
        val view = composeView ?: return

        runCatching {
            windowManager.removeView(view)
        }
        composeView = null
        params = null
    }

    companion object {
        // 16:9 thumbnail (§2.4 of newFeat.md).
        private const val PIP_WIDTH_DP = 160
        private const val PIP_HEIGHT_DP = 90
    }
}
