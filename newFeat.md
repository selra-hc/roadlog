Implementation Plan — 3 New Features for RoadLog
This plan adds three features to the RoadLog dashcam app at /home/cbastien/repos/local/01_prod/car-dashcam:
1. Cut/Uncut video feed button — a 4th button in the recording control row that toggles display of the live preview without affecting recording.
2. PIP overlay window — a SYSTEM_ALERT_WINDOW-based floating thumbnail (160×90dp, draggable) shown when the app is backgrounded while recording, containing the live preview and a single Pause/Resume button.
3. PIP settings toggle — a new PipSettings sub-object on AppSettings, default OFF, placed in the General settings section.
Implementation order is Feature 1 → Feature 3 → Feature 2, because Feature 2's gating depends on Feature 1's cut state and Feature 3's setting.
---
Feature 1 — Cut/Uncut Video Feed Button
Goal: add a 4th button at position 3 of the recording control row that toggles display of the live camera preview without affecting recording. Recording info (elapsed time + storage ring) stays visible when cut.
1.1 State ownership
The cut state must be observable by both the UI (to black out the preview) and the recording service (to gate PIP in Feature 2). Lift it onto VideoRecorderService, mirroring the existing pendingImpactSave / onPendingImpactSaveChange pattern on IntervalRecorderService.kt:50-53.
services/VideoRecorderService.kt — add:
var feedCut: Boolean = false
    private set
var onFeedCutChange: (Boolean) -> Unit = {}
fun setFeedCut(cut: Boolean) { feedCut = cut; onFeedCutChange(cut) }
ui/models/BaseRecorderModel.kt — mirror it as a Compose-observable, the same way pendingImpactSave is mirrored (VideoRecorderModel.kt:46-49):
var feedCut by mutableStateOf(false)
    private set
// in the ServiceConnection.onServiceConnected block:
recorder.onFeedCutChange = { cut -> feedCut = cut }
// also read synchronously on reconnect: feedCut = recorder.feedCut
1.2 UI blackout
ui/components/RecorderScreen/organisms/VideoRecordingStatus.kt:118-123 — wrap the existing RecordingPreview call site:
videoRecorder.recorderService?.let { service ->
    Box(Modifier.fillMaxSize()) {
        RecordingPreview(recorderService = service, modifier = Modifier.fillMaxSize())
        if (videoRecorder.feedCut) {
            Box(Modifier.fillMaxSize().background(Color.Black))
        }
    }
}
The status cards (elapsed time, storage ring, pending-auto-save banner) are siblings of this Box, layered above it, so they remain visible per the requirement. Recording is completely unaffected — RecordingPreview only consumes the service's Preview use case, while VideoCapture is bound independently in the same UseCaseGroup (VideoRecorderService.kt:398-412) and keeps encoding regardless of what's drawn on the preview surface.
1.3 New button atom
New file ui/components/RecorderScreen/atoms/CutFeedButton.kt — modeled on SaveButton.kt (48dp RoundedCornerShape(ButtonCornerRadius), combinedClickable, semantics{contentDescription}):
- Icon: Icons.Default.VisibilityOff when feed is currently visible (cut action), Icons.Default.Visibility when cut (uncut action).
- Container: MaterialTheme.colorScheme.primary (matches PauseResumeButton/SaveButton per the design-direction comment at PauseResumeButton.kt:16-18).
1.4 Control row extension
ui/components/RecorderScreen/molecules/RecordingControl.kt:
- Add isCut: Boolean and onCutFeed: () -> Unit parameters (line 29-40 signature).
- Add a 4th cutFeedButtonAlpha/cutFeedButtonAlphaIsIn animated-state pair (copy the pattern at lines 61-68).
- Change RandomStack.of(arrayOf(1, 2, 3)...) at line 74 → arrayOf(1, 2, 3, 4) and add a 4 -> cutFeedButtonAlphaIsIn = true branch.
- Portrait branch (lines 142-182): insert the new CutFeedButton Box between PauseResumeButton and SaveButton (so portrait order becomes Delete → PauseResume → CutFeed → Save).
- Landscape branch (lines 96-140): insert it between PauseResume and Delete (so landscape order top-to-bottom becomes Save → PauseResume → CutFeed → Delete).
1.5 Wiring
VideoRecordingStatus.kt _PrimitiveControls (lines 224-261) — add to the RecordingControl(...) call:
isCut = videoRecorder.feedCut,
onCutFeed = { videoRecorder.recorderService?.setFeedCut(!videoRecorder.feedCut) },
1.6 Strings
res/values/strings.xml — add ui_recorder_action_cutFeed_label ("Hide preview"), ui_recorder_action_uncutFeed_label ("Show preview").
1.7 Risk
Low — pure UI/state-lifting, no new permissions, recording unaffected by construction (the VideoCapture use case is bound independently of the Preview use case in the same UseCaseGroup).
---
Feature 3 — PIP Settings Toggle
Goal: new PipSettings sub-object on AppSettings, default OFF, toggle placed in the General settings section right after AutoStartOnLaunchTile. Done before Feature 2 since Feature 2's gating reads this setting.
3.1 Schema
db/AppSettings.kt — add a new @Serializable data class PipSettings(val enabled: Boolean = false) (default OFF), mirroring ImpactDetectionSettings at AppSettings.kt:342-350. Add to AppSettings root: val pip: PipSettings = PipSettings(), plus a fluent setter setPipSettings(pip: PipSettings): AppSettings = copy(pip = pip). Forward-compat is automatic — AppSettingsSerializer uses ignoreUnknownKeys = true (AppSettingsSerializer.kt:19-28), so existing settings.json files without a pip key deserialize with the default.
3.2 Tile
New file ui/components/SettingsScreen/Tiles/PipEnabledTile.kt — clone of ImpactDetectionEnabledTile.kt (the closest analog: a boolean inside a nested sub-object):
- leading = { Icon(Icons.Default.PictureInPicture, ...) } (or Icons.Default.Web if PictureInPicture isn't in the bundled Material Icons version — fall back to Icons.Default.PlayCircle like AutoStartOnLaunchTile.kt).
- trailing = Switch(checked = settings.pip.enabled, onCheckedChange = { checked -> ... }).
The onCheckedChange logic handles the SYSTEM_ALERT_WINDOW special-permission flow (per §3.5 below): if the user is toggling ON and !Settings.canDrawOverlays(context), show a rationale dialog and launch Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")) instead of persisting. The tile visually stays OFF until the user returns from the system settings with permission granted (re-checked via a Lifecycle.ON_RESUME observer on the tile). If toggling OFF or permission already granted, persist normally:
scope.launch { dataStore.updateData { it.setPipSettings(it.pip.copy(enabled = checked)) } }
3.3 Placement
ui/screens/SettingsScreen.kt:107-124 — add PipEnabledTile(settings = settings) immediately after AutoStartOnLaunchTile(settings = settings) (both are recording-behavior toggles; this keeps them grouped logically).
3.4 Strings
res/values/strings.xml — add ui_settings_option_pipEnabled_title ("Floating preview window"), ui_settings_option_pipEnabled_description ("Show a floating camera preview when the app is sent to the background while recording").
3.5 Manifest permission
AndroidManifest.xml — add <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" /> near the other <uses-permission> declarations (lines 23-44). Required because the overlay mechanism uses WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY. This is a special permission that can't be granted via the normal runtime-permission dialog — the user must grant it via the system "draw over other apps" settings screen, which is why §3.2's tile handles the canDrawOverlays() check and ACTION_MANAGE_OVERLAY_PERMISSION intent.
3.6 Risk
Low — schema extension is forward-compat by design; tile is a clone of ImpactDetectionEnabledTile. The SYSTEM_ALERT_WINDOW permission flow is the only new UX surface and is a well-known Android pattern.
---
Feature 2 — PIP Overlay Window When Backgrounded
Goal: when the app is backgrounded while actively recording, PIP is enabled in settings, and the feed is not cut on the Record screen, show a 160×90dp draggable floating window containing the live camera preview and a single Pause/Resume button. Recording continues in the foreground service regardless.
2.1 Permission flow
Handled by Feature 3's PipEnabledTile (§3.2): toggling PIP on without SYSTEM_ALERT_WINDOW permission prompts the user via ACTION_MANAGE_OVERLAY_PERMISSION. Feature 2's show logic re-checks Settings.canDrawOverlays(this) as a gate (§2.3) so it never crashes if permission was later revoked.
2.2 Background detection
The overlay must be hosted from the foreground service, not the Activity (the Activity can be destroyed when the user swipes away, but the service keeps running). Add androidx.lifecycle:lifecycle-process:2.8.4 to app/build.gradle (matching the version of the existing lifecycle-runtime-ktx:2.8.4/lifecycle-service:2.8.4), and observe ProcessLifecycleOwner from VideoRecorderService.
services/VideoRecorderService.kt — in startForegroundService() (lines 281-307) or onCreate, register a DefaultLifecycleObserver on ProcessLifecycleOwner.get().lifecycle:
- onStop (app went background) → maybeShowPipOverlay()
- onStart (app came foreground) → hidePipOverlay()
- Unregister in destroy().
2.3 Show/hide gating
maybeShowPipOverlay() show conditions (all must be true):
1. state == RecorderState.RECORDING (actively recording when backgrounded; skip if PAUSED)
2. settings.pip.enabled == true (read from the in-memory settings field the service already holds)
3. !feedCut (the Feature 1 cut state, exposed on the service per §1.1) — "don't enter PiP while cut"
4. Settings.canDrawOverlays(this) (permission granted)
5. Overlay not already shown
If all true → PipOverlayWindow.show(context, recorderService = this, recordingTimeProvider = { recordingTime }). If any becomes false → hidePipOverlay().
Hide conditions:
- ProcessLifecycleOwner.onStart (app returns to foreground) → hidePipOverlay()
- state transitions to STOPPED / IDLE (recording fully stopped) → hidePipOverlay()
- feedCut becomes true → hidePipOverlay() (moot in practice — cut is only toggleable from the main screen, which requires foreground — but kept as a safety net)
Does NOT hide on pause. When the user taps Pause from the overlay, the window stays mounted and swaps its content to a paused placeholder (§2.5). This works because the overlay's ComposeView stays in composition across the pause/resume transition, and the service's preview use case object survives closeCamera() (confirmed: VideoRecorderService.kt:86 preview field is NOT nulled in closeCamera()), so when the camera reopens on resume the same PreviewView surface provider re-receives frames without re-binding.
2.4 The overlay window (draggable, 160×90dp)
New file ui/components/PipOverlay/PipOverlayWindow.kt — owns the WindowManager view lifecycle and drag handling:
class PipOverlayWindow(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var composeView: ComposeView? = null
    private lateinit var params: WindowManager.LayoutParams  // held for drag updates
    fun show(recorderService: VideoRecorderService, recordingTimeProvider: () -> Long) {
        if (composeView != null) return
        val view = ComposeView(context).apply {
            // Manual lifecycle/viewmodel-store wiring (service as owner)
            ViewTreeLifecycleOwner.set(this, serviceLifecycleOwner)
            ViewTreeViewModelStoreOwner.set(this, serviceViewModelStoreOwner)
            setContent { PipOverlayContent(recorderService, recordingTimeProvider, ::updateLayout) }
        }
        params = WindowManager.LayoutParams(
            WRAP_CONTENT, WRAP_CONTENT,
            TYPE_APPLICATION_OVERLAY,
            FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            width = dp(160); height = dp(90)   // 16:9 thumbnail
            x = 0; y = 0                       // initial position
        }
        windowManager.addView(view, params)
        composeView = view
    }
    // Called from the Composable's drag gesture (detectDragGestures on the background)
    private fun updateLayout(dx: Int, dy: Int) {
        params.x += dx; params.y += dy
        windowManager.updateViewLayout(composeView, params)
    }
    fun hide() { composeView?.let { windowManager.removeView(it) }; composeView = null }
}
Drag implementation: the outer Box of PipOverlayContent gets Modifier.pointerInput { detectDragGestures { _, dragAmount -> updateLayout(dragAmount.x.toInt(), dragAmount.y.toInt()) } }. The Pause/Resume button is laid out on top of this Box and consumes its own pointer events (Compose's pointer-input system dispatches to the topmost consumer — the button — and the drag gesture on the parent only fires for pointer events that aren't consumed by the button). The entire window background is the drag handle — no dedicated drag-handle strip, since at 160×90dp every pixel matters. updateViewLayout is cheap and can be called per-drag-delta frame. No visible drag affordance for MVP; a subtle drag icon or hint border can be added in a polish pass if discoverability feels lacking on-device.
Compose-in-WindowManager lifecycle wiring is the primary risk area (unchanged): the ComposeView needs ViewTreeLifecycleOwner/ViewTreeViewModelStoreOwner/ViewTreeSavedStateRegistryOwner set explicitly since it's not attached to an Activity. The cleanest host is the service itself — VideoRecorderService implements LifecycleOwner (using ServiceLifecycleDispatcher from lifecycle-service, already a dependency) and ViewModelStoreOwner. Flagging this as the most novel piece of the plan.
2.5 PIP window contents (pause-aware, no elapsed time)
New file ui/components/PipOverlay/PipOverlayContent.kt renders state-dependently inside the fixed 160×90dp Box:
While state == RECORDING:
- RecordingPreview(recorderService = ..., modifier = Modifier.fillMaxSize()) — reuses the existing atom (RecordingPreview.kt:21), binding a new PreviewView to the service's Preview use case. The service's setPreviewSurfaceProvider(...) is called with the new PreviewView's surface provider; the existing RecordingPreview on the main Record screen detaches via its DisposableEffect when the Activity stops.
- Pause button (Icons.Default.Pause, ~32dp touch target) overlaid bottom-right. onClick = { recorderService.pauseRecording() } — direct call, no broadcast/receiver indirection needed since the overlay is hosted from the service context.
While state == PAUSED:
- Dark surface: Box(Modifier.fillMaxSize().background(Color.Black)) — the PreviewView is still mounted underneath but shows nothing (camera unbound), so this overlay guarantees a clean dark background rather than whatever the empty PreviewView renders.
- Small "Paused" label or Icons.Default.Pause centered, dimmed — purely visual indicator.
- Resume button (Icons.Default.PlayArrow, ~32dp) overlaid bottom-right, replacing the Pause button. onClick = { recorderService.resumeRecording() }.
No elapsed time, no Stop button, no Return button per the consolidated answers. The state is observed reactively — PipOverlayContent reads recorderService.state (the RecorderState enum, Compose-observable via the service's onStateChange callback) and switches between the two layouts via recomposition. No manual show/hide coordination needed for the placeholder swap.
2.6 Surface-provider hand-off
The service's Preview use case has one surface provider at a time (setPreviewSurfaceProvider). When the app backgrounds: ProcessLifecycleOwner.onStop → overlay's PreviewView binds via setPreviewSurfaceProvider(...). When the app foregrounds: ProcessLifecycleOwner.onStart → hidePipOverlay() removes the overlay view (which triggers RecordingPreview's DisposableEffect calling setPreviewSurfaceProvider(null)), then the Activity-side RecordingPreview re-binds when its composition resumes. Safe ordering is hide() first, then Activity rebind — enforced in the lifecycle observer wiring. Worth verifying no race window where both try to attach simultaneously.
2.7 Camera reopen on resume
When the user taps Resume from the paused placeholder:
- resumeRecording() → changeState(RECORDING) → VideoRecorderService.resume() reopens the camera, rebinds the same UseCaseGroup (Preview + VideoCapture + watermark effect).
- The overlay's PreviewView surface provider is still attached to the surviving preview use case object → frames resume flowing into the overlay automatically once the camera rebinds.
- Content swaps back from placeholder to live preview + Pause button via recomposition.
Timing risk to verify: the camera reopen is async (cameraProvider.bindToLifecycle returns a ListenableFuture-style callback). There may be a brief window where the placeholder has already swapped out (because state flipped to RECORDING immediately) but no frame is yet available. Acceptable — the PreviewView simply shows its last frame/black for a fraction of a second. Worth confirming on-device it's not a visible glitch.
2.8 Removed: StopRecordingReceiver
No Stop button → no StopRecordingReceiver → no service→Activity save-trigger path. This was the highest-risk item I flagged in the original draft — eliminated entirely. The pause/resume buttons are direct in-process calls on recorderService. Returning to the full app is via the existing foreground notification's setContentIntent (already wired at RecorderNotificationHelper.kt:43-50 to open MainActivity).
2.9 Risk
Medium — Compose-in-WindowManager lifecycle wiring (ViewTreeLifecycleOwner/ViewModelStoreOwner on service) is still novel for this codebase; surface-provider hand-off on background/foreground transitions needs careful ordering. The save-from-service complication is eliminated since there's no Stop button. Drag implementation is standard updateViewLayout-per-delta.
---
Summary of all file changes
New files
ui/components/RecorderScreen/atoms/CutFeedButton.kt, purpose: Feature 1 — cut/uncut button atom
ui/components/SettingsScreen/Tiles/PipEnabledTile.kt, purpose: Feature 3 — PIP toggle tile
ui/components/PipOverlay/PipOverlayWindow.kt, purpose: Feature 2 — WindowManager overlay host + drag
ui/components/PipOverlay/PipOverlayContent.kt, purpose: Feature 2 — Composable content (recording vs paused states)
Modified files
File
services/VideoRecorderService.kt, purpose: feedCut state + onFeedCutChange + setFeedCut(); ProcessLifecycleOwner observer; maybeShowPipOverlay()/hidePipOverlay(); implement LifecycleOwner/ViewModelStoreOwner for Compose-in-overlay
ui/models/BaseRecorderModel.kt, purpose: Mirror feedCut as Compose-observable; wire onFeedCutChange in ServiceConnection
ui/components/RecorderScreen/molecules/RecordingControl.kt, purpose: Add isCut/onCutFeed params; 4th alpha state; RandomStack.of(arrayOf(1,2,3,4)); insert CutFeedButton in portrait + landscape branches
ui/components/RecorderScreen/organisms/VideoRecordingStatus.kt, purpose: Wrap RecordingPreview in Box with black overlay when feedCut; pass isCut/onCutFeed to RecordingControl
db/AppSettings.kt, purpose: PipSettings data class; pip field on AppSettings root; setPipSettings() setter
ui/screens/SettingsScreen.kt, purpose: Mount PipEnabledTile after AutoStartOnLaunchTile in General section
app/build.gradle, purpose: Add androidx.lifecycle:lifecycle-process:2.8.4
app/src/main/AndroidManifest.xml, purpose: Add SYSTEM_ALERT_WINDOW permission
app/src/main/res/values/strings.xml, purpose: ui_recorder_action_cutFeed_label, ui_recorder_action_uncutFeed_label, ui_settings_option_pipEnabled_title, ui_settings_option_pipEnabled_description
---
## Verification plan
- **Build:** `./gradlew assembleDebug` after each feature.
- **Lint:** `./gradlew lint` (will catch the `SYSTEM_ALERT_WINDOW` permission declaration review).
- **Typecheck:** covered by `assembleDebug` (Kotlin compilation); no separate `typecheck` task exists.
- **Manual on-device** (per PLAN.md §15, no camera-equipped emulator available in this environment):
**Feature 1:**
- Cut while recording → preview goes black, elapsed time + storage ring still visible, saved file plays back normally (recording was never interrupted).
- Uncut → preview returns. Recording time continuous across the cut/uncut.
**Feature 3:**
- Toggle persists across app restart.
- Toggling PIP on without overlay permission → rationale dialog → `ACTION_MANAGE_OVERLAY_PERMISSION` intent → return with permission granted → tile reflects ON.
- Toggling off → persists immediately, no permission prompt.
**Feature 2:**
- PIP enabled + permission granted + recording + not cut → press Home → 160×90dp draggable thumbnail appears with live preview + Pause button (bottom-right), no elapsed time.
- Drag the window background → window repositions smoothly; Pause button still works (doesn't trigger drag).
- Tap Pause → window stays, content swaps to dark "Paused" placeholder + Resume button.
- Drag while paused → window still repositions.
- Tap Resume → camera reopens, live feed returns, content swaps back to live preview + Pause button.
- Press Home from the main app while paused (already paused before backgrounding) → no PIP appears (show condition #1 requires RECORDING at background time). The foreground notification's paused variant with Resume action remains the signal. This is a deliberate, consistent boundary.
- Cut the feed first, then background → no thumbnail (gate condition #3).
- Tap the foreground notification → app returns to foreground, thumbnail gone.
---
Risk summary
#	Feature
1	Cut/Uncut button
3	PIP settings toggle
2	PIP overlay window
---
