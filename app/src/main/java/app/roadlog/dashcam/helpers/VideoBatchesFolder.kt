package app.roadlog.dashcam.helpers

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import app.roadlog.dashcam.ui.MEDIA_SUBFOLDER_NAME
import app.roadlog.dashcam.ui.RECORDER_INTERNAL_SELECTED_VALUE
import app.roadlog.dashcam.ui.RECORDER_MEDIA_SELECTED_VALUE
import app.roadlog.dashcam.ui.VIDEO_RECORDING_BATCHES_SUBFOLDER_NAME
import java.io.File
import java.time.LocalDateTime

class VideoBatchesFolder(
    override val context: Context,
    override val type: BatchType,
    override val customFolder: DocumentFile? = null,
    override val subfolderName: String = VIDEO_RECORDING_BATCHES_SUBFOLDER_NAME,
) : BatchesFolder(
    context,
    type,
    customFolder,
    subfolderName,
) {
    override val scopedMediaContentUri: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    override val legacyMediaFolder = File(
        Environment.getExternalStoragePublicDirectory(BASE_LEGACY_STORAGE_FOLDER),
        MEDIA_RECORDINGS_SUBFOLDER,
    )

    // Keyed by stream tag (null for the primary/back stream) so a dual (front+back)
    // recording (§9.3) can hold one open descriptor per stream without either one's
    // `asCustomGetParcelFileDescriptor` call closing the other's.
    private val customParcelFileDescriptors = mutableMapOf<String?, ParcelFileDescriptor>()

    override fun getConcatenationDestination(
        date: LocalDateTime,
        extension: String,
        fileName: String,
    ): ConcatenationDestination {
        return when (type) {
            BatchType.INTERNAL -> ConcatenationDestination.LocalFile(
                asInternalGetOutputFile(fileName).absolutePath
            )

            BatchType.CUSTOM -> {
                ConcatenationDestination.ContentUri(
                    (customFolder!!.findFile(fileName) ?: customFolder.createFile(
                        "video/${extension}",
                        fileName,
                    )!!).uri
                )
            }

            BatchType.MEDIA -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val mediaUri = getOrCreateMediaFile(
                        name = fileName,
                        mimeType = "video/$extension",
                        relativePath = BASE_SCOPED_STORAGE_RELATIVE_PATH + "/" + MEDIA_SUBFOLDER_NAME,
                    )

                    ConcatenationDestination.ContentUri(mediaUri)
                } else {
                    val path = arrayOf(
                        Environment.getExternalStoragePublicDirectory(BASE_LEGACY_STORAGE_FOLDER),
                        MEDIA_SUBFOLDER_NAME,
                        fileName,
                    ).joinToString("/")

                    ConcatenationDestination.LocalFile(
                        File(path).apply { createNewFile() }.absolutePath
                    )
                }
            }
        }
    }

    override fun cleanup() {
        customParcelFileDescriptors.values.forEach {
            runCatching { it.close() }
        }
        customParcelFileDescriptors.clear()
    }

    fun asCustomGetParcelFileDescriptor(
        counter: Long,
        fileExtension: String,
        tag: String? = null,
    ): ParcelFileDescriptor {
        runCatching {
            customParcelFileDescriptors[tag]?.close()
        }

        val suffix = if (tag != null) "-$tag" else ""
        val file =
            getCustomDefinedFolder().createFile(
                "video/$fileExtension",
                "$counter$suffix.$fileExtension"
            )!!
        val resolver = context.contentResolver.acquireContentProviderClient(file.uri)!!

        resolver.use {
            val descriptor = it.openFile(file.uri, "w")!!
            customParcelFileDescriptors[tag] = descriptor

            return descriptor
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun asMediaGetScopedStorageContentValues(name: String) = ContentValues().apply {
        put(
            MediaStore.Video.Media.IS_PENDING,
            1
        )
        put(
            MediaStore.Video.Media.RELATIVE_PATH,
            SCOPED_STORAGE_RELATIVE_PATH,
        )

        put(
            MediaStore.Video.Media.DISPLAY_NAME,
            name
        )
    }

    companion object {
        fun viaInternalFolder(context: Context) = VideoBatchesFolder(context, BatchType.INTERNAL)

        fun viaCustomFolder(context: Context, folder: DocumentFile) =
            VideoBatchesFolder(context, BatchType.CUSTOM, folder)

        fun viaMediaFolder(context: Context) = VideoBatchesFolder(context, BatchType.MEDIA)

        fun importFromFolder(folder: String?, context: Context) = when (folder) {
            null -> viaInternalFolder(context)
            RECORDER_INTERNAL_SELECTED_VALUE -> viaInternalFolder(context)
            RECORDER_MEDIA_SELECTED_VALUE -> viaMediaFolder(context)
            else -> viaCustomFolder(
                context,
                // `DocumentFile.fromTreeUri` returns null (rather than throwing) if the
                // tree URI's persisted permission was revoked — e.g. the SD card/custom
                // folder the user picked was removed, or the OS revoked the grant. That
                // used to surface as a bare, contextless NullPointerException from `!!`
                // wherever this was called from (e.g. the Recordings screen's listing
                // coroutine); throw something diagnosable instead.
                DocumentFile.fromTreeUri(context, Uri.parse(folder))
                    ?: throw IllegalStateException(
                        "DocumentFile.fromTreeUri() returned null for saveFolder='$folder' " +
                            "— its persisted URI permission was likely revoked or the " +
                            "folder is otherwise no longer accessible (e.g. removed SD card)"
                    )
            )
        }

        val BASE_LEGACY_STORAGE_FOLDER = Environment.DIRECTORY_DCIM
        val MEDIA_RECORDINGS_SUBFOLDER = MEDIA_SUBFOLDER_NAME + "/.video_recordings"
        val BASE_SCOPED_STORAGE_RELATIVE_PATH = Environment.DIRECTORY_DCIM
        val SCOPED_STORAGE_RELATIVE_PATH =
            BASE_SCOPED_STORAGE_RELATIVE_PATH + "/" + MEDIA_RECORDINGS_SUBFOLDER
    }
}