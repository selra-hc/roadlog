package app.roadlog.dashcam.ui.utils

import android.app.Activity
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay

@Composable
fun KeepScreenOn() {
    val currentView = LocalView.current
    DisposableEffect(Unit) {
        currentView.keepScreenOn = true
        onDispose {
            currentView.keepScreenOn = false
        }
    }
}

// Fades the hosting Activity's window to a minimal brightness after a period of no
// touch input, restoring full brightness instantly on the next one (§9.6's
// energy-efficiency pass) — independent of `KeepScreenOn()` above, which only defeats
// the screen-off timeout and has no brightness control of its own. A windshield-mounted
// phone recording for hours doesn't need its backlight at full brightness the whole
// time; backlight power is often the single largest component of total device draw
// during that time. Uses a per-window `screenBrightness` override
// (`WindowManager.LayoutParams`), not the system-wide `Settings.System.SCREEN_BRIGHTNESS`
// (which needs the `WRITE_SETTINGS` permission and would affect every other app, not
// just this one). A no-op outside an Activity-hosted composable — never applies to the
// PIP overlay, which has no window of its own to dim and should stay legible regardless.
//
// Attach the returned `Modifier` to the screen's outermost surface — its `pointerInput`
// observes touches at `PointerEventPass.Initial` (before any sibling gets a chance to
// consume them), so it detects every touch without stealing or blocking input meant for
// buttons/preview gestures underneath. The dim timer itself doubles as the "don't dim
// immediately after recording starts" grace period: `lastActivityAt` starts at
// composition time, so dimming can't kick in until `idleTimeoutMs` after the screen
// first appears, same as after any other touch.
@Composable
fun Modifier.dimWhileRecording(
    enabled: Boolean,
    idleTimeoutMs: Long,
    dimmedBrightness: Float = MIN_SCREEN_BRIGHTNESS,
): Modifier {
    val activity = LocalView.current.context as? Activity
    var lastActivityAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    DisposableEffect(activity) {
        onDispose {
            activity?.let {
                setWindowBrightness(it, WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            }
        }
    }

    LaunchedEffect(activity, enabled, lastActivityAt) {
        if (activity == null) {
            return@LaunchedEffect
        }

        setWindowBrightness(activity, WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)

        if (!enabled) {
            return@LaunchedEffect
        }

        delay(idleTimeoutMs)
        setWindowBrightness(activity, dimmedBrightness)
    }

    if (activity == null || !enabled) {
        return this
    }

    return this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial)
                lastActivityAt = SystemClock.elapsedRealtime()
            }
        }
    }
}

private fun setWindowBrightness(activity: Activity, brightness: Float) {
    val window = activity.window
    window.attributes = window.attributes.apply {
        screenBrightness = brightness
    }
}

// Not fully off (0f can render as a black/unreadable screen on some panels) — dim
// enough to meaningfully cut backlight power while staying glanceable if the driver
// looks over mid-drive before the next touch restores full brightness.
const val MIN_SCREEN_BRIGHTNESS = 0.02f
