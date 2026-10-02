package app.roadlog.dashcam.ui.components.PipOverlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.roadlog.dashcam.R
import app.roadlog.dashcam.db.AppSettings
import app.roadlog.dashcam.services.VideoRecorderService
import app.roadlog.dashcam.ui.components.RecorderScreen.atoms.RecordingPreview
import app.roadlog.dashcam.ui.theme.RoadLogTheme

// Fixed 160x90dp content — no elapsed time, no Stop/Return button, just the live preview
// (or a paused/cut placeholder) and Pause/Resume + Cut/Uncut + Save buttons. `RecordingPreview`
// stays mounted across the paused<->recording swap (rather than being conditionally
// composed) so its surface provider only ever attaches once, when the overlay window
// itself is shown, and detaches once, when it's hidden — not on every pause/resume
// toggle. Camera bind/unbind on pause is handled entirely by `VideoRecorderService
// .pause()`/`resume()` regardless of what's drawn here (§9.1: no camera use while
// paused). The cut<->uncut swap is different: `RecordingPreview` IS conditionally
// composed there, deliberately — cutting the feed needs to actually detach the surface
// provider (§9.1's real-device fix for the main Record screen), not just paint over a
// still-live preview underneath.
//
// Wrapped in `RoadLogTheme` here, unlike the Record screen's own composables — this
// `ComposeView` is hosted directly on `WindowManager` (§9.5), with no Activity ancestor
// to provide the app's theme/`MaterialTheme` the normal way, so without this wrapper
// `MaterialTheme.colorScheme` below would silently fall back to Compose's default
// (unbranded) color scheme instead of the user's configured accent color. Reads
// `recorderService.settings` directly rather than a live DataStore `Flow` — that field
// is already a plain settings snapshot the rest of the service relies on the same way
// (e.g. `WatermarkOverlay`'s `getSettings` lambda), refreshed only when a recording
// (re)starts, not on every settings change — consistent with that existing limitation
// rather than a new one introduced here.
@Composable
fun PipOverlayContent(
    recorderService: VideoRecorderService,
    onDrag: (dx: Int, dy: Int) -> Unit,
    onOpenApp: () -> Unit,
    onCutFeed: () -> Unit,
    onSave: () -> Unit,
) {
    val settings = recorderService.settings

    RoadLogTheme(
        amoled = settings.theme == AppSettings.Theme.AMOLED,
        accentColor = Color(settings.accentColorArgb),
    ) {
        val isPaused = recorderService.overlayIsPaused
        val isCut = recorderService.feedCut
        // Idle-timeout suspension (§9.6's energy-efficiency pass) — a separate flag from
        // the user-driven `isCut`, ORed together at render time below so either one alone
        // is enough to show the placeholder instead of live video.
        val isIdleSuspended = recorderService.pipIdleSuspended
        val showPlaceholder = isCut || isIdleSuspended
        val pauseLabel = stringResource(R.string.ui_recorder_action_pause_label)
        val resumeLabel = stringResource(R.string.ui_recorder_action_resume_label)
        val cutLabel = stringResource(R.string.ui_recorder_action_cutFeed_label)
        val uncutLabel = stringResource(R.string.ui_recorder_action_uncutFeed_label)
        val saveLabel = stringResource(R.string.ui_recorder_action_save_label)

        Box(
            modifier = Modifier
                .size(width = PIP_WIDTH, height = PIP_HEIGHT)
                .background(Color.Black)
                // A thin frame around the whole window while cut — otherwise the PIP
                // (already just a small black rectangle floating over whatever's behind it)
                // has no visible edge of its own to distinguish it from the background it's
                // floating over. Uses the user's configured accent color (via `RoadLogTheme`
                // above), matching the buttons, rather than a fixed grey.
                .then(
                    if (isCut) {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        Modifier
                    }
                )
                // The entire window background is both the drag handle (§2.4) and the "open
                // the app" tap target — every pixel matters at this size, so there's no
                // dedicated drag-handle strip or separate tap-target overlay. Two sibling
                // `pointerInput` blocks rather than one: `detectDragGestures` never consumes
                // a pointer event unless the touch-slop threshold is actually exceeded (a
                // plain tap's down/up passes through untouched), so `detectTapGestures` in
                // the second block still sees and registers a genuine tap; a real drag
                // instead consumes the movement, which cancels the pending tap detection
                // automatically. The button row below is a further sibling drawn on top and
                // consumes its own pointer events first, so tapping a button never starts a
                // drag or opens the app. Both also reset the idle-suspend timer (§9.6) —
                // resetPipActivity() below.
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        recorderService.resetPipActivity()
                        onOpenApp()
                    })
                }
                .pointerInput(Unit) {
                    detectDragGestures { _, dragAmount ->
                        recorderService.resetPipActivity()
                        onDrag(dragAmount.x.toInt(), dragAmount.y.toInt())
                    }
                },
        ) {
            if (showPlaceholder) {
                // Mirrors the Record screen's own Cut/Uncut placeholder (§9.1) — same black
                // screen + label, same reason: `RecordingPreview` isn't composed at all in
                // this branch, so it's not just visually hidden, its surface provider is
                // actually detached (§9.1's real-device fix) — the overlay still shows (it's
                // deliberately not gated on `feedCut`, §9.5) so both buttons and
                // tap-to-reopen stay available, just without live video.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(
                            if (isPaused) {
                                R.string.ui_recorder_feedCut_pausedLabel
                            } else {
                                R.string.ui_recorder_feedCut_activeLabel
                            }
                        ),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            } else {
                RecordingPreview(
                    recorderService = recorderService,
                    modifier = Modifier.fillMaxSize(),
                )

                if (isPaused) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                    )
                    Icon(
                        Icons.Default.Pause,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(28.dp)
                            .alpha(0.5f),
                    )
                }
            }

            // Bottom-centered, evenly spaced — same relative order as the Record screen's
            // control row (§9.1: Pause/Resume, then Cut/Uncut, then Save last) — rather
            // than the previous single button pinned to a corner, now that there are three.
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
            ) {
                PipButton(
                    icon = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    label = if (isPaused) resumeLabel else pauseLabel,
                    onClick = {
                        recorderService.resetPipActivity()
                        if (isPaused) {
                            recorderService.resumeRecording()
                        } else {
                            recorderService.pauseRecording()
                        }
                    },
                )
                PipButton(
                    icon = if (isCut) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    label = if (isCut) uncutLabel else cutLabel,
                    onClick = {
                        recorderService.resetPipActivity()
                        onCutFeed()
                    },
                )
                PipButton(
                    icon = Icons.Default.Save,
                    label = saveLabel,
                    onClick = {
                        recorderService.resetPipActivity()
                        onSave()
                    },
                )
            }
        }
    }
}

// Small circular icon button matching the PIP window's own scale (§9.5) — the accent
// color now comes from `RoadLogTheme` above rather than Compose's unbranded default.
// A plain `Box` + `clickable`, NOT Material3's `IconButton` — `IconButton` pads its
// content out to a minimum 48dp touch target via `minimumInteractiveComponentSize()`,
// invisibly, regardless of the smaller visual size an explicit `Modifier.size()` draws.
// At a smaller `BUTTON_SIZE` tried earlier (down to 18dp), that inflated-but-invisible
// touch area was large enough for the two buttons' actual tap targets to overlap even
// when their drawn circles looked clearly separated. A raw `Box` (the same pattern the
// Record screen's own small buttons already use — `CutFeedButton`/`DeleteButton`/
// `SaveButton`) has no such hidden inflation: its layout bounds are exactly
// `BUTTON_SIZE`, so the `Row`'s explicit gap between children is the ENTIRE gap
// between their tap targets, not just between their visible pixels — true at any
// `BUTTON_SIZE`, including the current one.
@Composable
private fun PipButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(BUTTON_SIZE)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
            .semantics {
                contentDescription = label
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = MaterialTheme.colorScheme.onPrimary),
                onClick = onClick,
            ),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(BUTTON_ICON_SIZE),
        )
    }
}

// Back to the original 32dp/24dp button/glyph sizing — the smaller passes (down to
// 18dp) were compensating for `IconButton`'s invisible 48dp touch-target inflation
// making the two buttons' tap targets overlap; now that `PipButton` is a raw `Box`
// with exact, non-inflated bounds (see the doc comment above), that pressure is gone
// and the buttons can go back to their original, easier-to-tap size.
private val BUTTON_SIZE = 32.dp
private val BUTTON_ICON_SIZE = 24.dp

private val PIP_WIDTH = 160.dp
private val PIP_HEIGHT = 90.dp
