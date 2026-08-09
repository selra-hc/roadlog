package app.roadlog.dashcam.helpers

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CompletableDeferred
import java.io.File

class MediaConverter {
    companion object {
        // Batch segments are addressed either as plain absolute file paths (internal storage,
        // legacy pre-scoped-storage media folder) or as content:// URIs (SAF tree, scoped-storage
        // MediaStore). Media3's `MediaItem.fromUri` needs a proper URI either way.
        private fun toMediaUri(path: String): Uri {
            return if (path.contains("://")) Uri.parse(path) else File(path).toUri()
        }

        // Concatenates a set of video files into a single output file using AndroidX Media3
        // Transformer. All input segments come from the same CameraX/MediaCodec encoder
        // configuration recorded back-to-back on this device, so their formats always match —
        // this means Transformer automatically "transmuxes" (copies compressed samples without
        // re-encoding) rather than transcoding, the same stream-copy guarantee the previous
        // FFmpeg `-c copy` based implementation relied on, just decided automatically instead
        // of via an explicit codec flag.
        //
        // `outputFile` must be a local absolute file path — Transformer cannot write directly to
        // a SAF/MediaStore `Uri` (see BatchesFolder.concatenate for how callers work around this
        // for those destinations via a temp file + ContentResolver copy).
        //
        // Transformer must be created/started on a thread with a `Looper` (its callbacks fire on
        // that same thread), so the actual work is dispatched onto the main looper regardless of
        // which thread this function is called from.
        fun concatenateVideoFiles(
            context: Context,
            inputFiles: Iterable<String>,
            outputFile: String,
            onProgress: (Int) -> Unit = { },
        ): CompletableDeferred<Unit> {
            val completer = CompletableDeferred<Unit>()
            val mainHandler = Handler(Looper.getMainLooper())

            mainHandler.post {
                // Everything here up through `transformer.start()` runs synchronously on
                // the main looper and can throw directly (e.g. Media3's own
                // `EditedMediaItemSequence` requires at least one item and throws
                // `IllegalArgumentException` otherwise) — unlike `Transformer.Listener`'s
                // `onError` (below), an uncaught exception here would escape this
                // `Runnable` as an uncaught exception on the main thread, killing the
                // whole app rather than reaching `completer`/whichever coroutine is
                // awaiting it. Catching and routing through `completer` instead makes
                // this call site's own `try`/`catch` (e.g. `BatchesFolder.concatenate()`)
                // the single place that has to handle failure.
                try {
                    val editedItems = inputFiles.map { path ->
                        EditedMediaItem.Builder(MediaItem.fromUri(toMediaUri(path))).build()
                    }

                    val composition = Composition.Builder(
                        EditedMediaItemSequence.withAudioAndVideoFrom(editedItems)
                    ).build()

                    lateinit var transformer: Transformer
                    val progressHolder = ProgressHolder()
                    val progressRunnable = object : Runnable {
                        override fun run() {
                            if (transformer.getProgress(progressHolder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                                onProgress(progressHolder.progress)
                            }
                            mainHandler.postDelayed(this, 300)
                        }
                    }

                    transformer = Transformer.Builder(context)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(
                                composition: Composition,
                                exportResult: ExportResult,
                            ) {
                                mainHandler.removeCallbacks(progressRunnable)
                                onProgress(100)
                                completer.complete(Unit)
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException,
                            ) {
                                mainHandler.removeCallbacks(progressRunnable)
                                Log.e(
                                    "Video Concatenation",
                                    "Failed to concatenate videos",
                                    exportException,
                                )
                                completer.completeExceptionally(
                                    Exception("Failed to concatenate videos", exportException)
                                )
                            }
                        })
                        .build()

                    transformer.start(composition, outputFile)
                    mainHandler.post(progressRunnable)
                } catch (error: Exception) {
                    Log.e("Video Concatenation", "Failed to start concatenation", error)
                    completer.completeExceptionally(error)
                }
            }

            return completer
        }
    }
}
