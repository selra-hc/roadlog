package app.roadlog.dashcam.ui

import android.os.Build
import androidx.compose.ui.unit.dp

val BIG_PRIMARY_BUTTON_SIZE = 64.dp
val BIG_PRIMARY_BUTTON_MAX_WIDTH = 450.dp

val SHEET_BOTTOM_OFFSET = 24.dp
val MAX_AMPLITUDE = 20000

val MEDIA_SUBFOLDER_NAME = "roadlog"

val SUPPORTS_SCOPED_STORAGE = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
val SUPPORTS_SAVING_VIDEOS_IN_CUSTOM_FOLDERS = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
val MEDIA_RECORDINGS_PREFIX = "roadlog-recording-"
val RECORDER_MEDIA_SELECTED_VALUE = "_'media"
val RECORDER_INTERNAL_SELECTED_VALUE = "_'internal"

val VIDEO_RECORDING_BATCHES_SUBFOLDER_NAME = ".video_recordings"
val AUDIO_RECORDING_BATCHES_SUBFOLDER_NAME = ".audio_recordings"

// RoadLog's recording engine and app architecture are forked from Alibi (GPLv3) — this
// is the upstream attribution link shown on the About screen. Alibi's own donation
// links/contact info/PGP key/crypto addresses belonged to that project's author
// personally and were dropped entirely, not carried forward into RoadLog.
const val ALIBI_REPO_URL = "https://github.com/Myzel394/Alibi"
