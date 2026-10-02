package app.roadlog.dashcam.helpers

import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import androidx.camera.core.CameraInfo
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector

// §9.6's energy-efficiency pass — a software (CPU-bound) video encoder is dramatically
// more power-hungry than the dedicated hardware codec silicon virtually every real
// device registers as its preferred H.264/HEVC encoder; this app never explicitly picks
// an encoder (`VideoRecorderService.buildRecorder()` only configures CameraX's
// `Recorder.Builder()`, which resolves an actual `MediaCodec` via the same OS mechanism
// `MediaCodec.createEncoderByType()` uses), so a software fallback would currently be
// silent and invisible. This is a read-only, advisory-only check — it never changes
// recording behavior itself, just lets the Settings screen surface a one-time banner if
// the resolved encoder for the user's selected quality turns out not to be hardware.
object EncoderCapabilityChecker {
    // CameraX's `Recorder` defaults to H.264 (AVC) unless a specific codec is requested
    // via a `VideoEncoderConfig` — this app never does, so this is the actual encoder
    // MIME being resolved for every recording, regardless of the unrelated container/
    // file-extension MIME `VideoRecorderSettings.getMimeType()` returns (e.g. "video/mp4").
    private const val VIDEO_CODEC_MIME = MediaFormat.MIMETYPE_VIDEO_AVC

    // Returns null if the check itself couldn't be completed (no resolution available
    // for this quality/camera, or no encoder found at all) — callers should treat null
    // as "inconclusive," not "software," since asserting a false positive here would be
    // worse than saying nothing.
    fun isHardwareEncoder(cameraInfo: CameraInfo, quality: Quality): Boolean? {
        val resolution = QualitySelector.getResolution(cameraInfo, quality) ?: return null

        val format = MediaFormat.createVideoFormat(VIDEO_CODEC_MIME, resolution.width, resolution.height)
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val encoderName = runCatching { codecList.findEncoderForFormat(format) }.getOrNull()
            ?: return null

        return runCatching {
            val info = codecList.codecInfos.first { it.name == encoderName }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isHardwareAccelerated
            } else {
                // Pre-Q heuristic: these are the conventional naming prefixes for
                // Android's own bundled software codecs; anything else is presumed to
                // be a vendor-registered (hardware) implementation.
                !(encoderName.startsWith("OMX.google.") || encoderName.startsWith("c2.android."))
            }
        }.getOrNull()
    }
}
