package app.roadlog.dashcam.helpers

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.MediaStore.Video.Media
import android.system.Os
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import app.roadlog.dashcam.db.AppSettings
import app.roadlog.dashcam.db.RecordingInformation
import app.roadlog.dashcam.ui.MEDIA_RECORDINGS_PREFIX
import app.roadlog.dashcam.ui.RECORDER_INTERNAL_SELECTED_VALUE
import app.roadlog.dashcam.ui.RECORDER_MEDIA_SELECTED_VALUE
import app.roadlog.dashcam.ui.SUPPORTS_SCOPED_STORAGE
import app.roadlog.dashcam.ui.utils.PermissionHelper
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

abstract class BatchesFolder(
    open val context: Context,
    open val type: BatchType,
    open val customFolder: DocumentFile? = null,
    open val subfolderName: String = ".recordings",
) {
    abstract val scopedMediaContentUri: Uri
    abstract val legacyMediaFolder: File

    val mediaPrefix
        get() = MEDIA_RECORDINGS_PREFIX + subfolderName.substring(1) + "-"

    fun initFolders() {
        when (type) {
            BatchType.INTERNAL -> getInternalFolder().mkdirs()

            BatchType.CUSTOM -> {
                if (customFolder!!.findFile(subfolderName) == null) {
                    customFolder!!.createDirectory(subfolderName)
                }
            }

            BatchType.MEDIA -> {
                // Scoped storage works fine on new Android versions,
                // we need to manually manage the folder on older versions
                if (!SUPPORTS_SCOPED_STORAGE) {
                    legacyMediaFolder.mkdirs()
                }
            }
        }
    }

    fun getInternalFolder(): File {
        return File(context.filesDir, subfolderName)
    }

    fun getCustomDefinedFolder(): DocumentFile {
        return customFolder!!.findFile(subfolderName)!!
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    protected fun queryMediaContent(
        callback: (rawName: String, counter: Int, uri: Uri, cursor: Cursor) -> Any?,
    ) {
        context.contentResolver.query(
            scopedMediaContentUri,
            null,
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '$mediaPrefix%'",
            null,
            null,
        )!!.use { cursor ->
            while (cursor.moveToNext()) {
                val rawName = cursor.getColumnIndex(Media.DISPLAY_NAME).let { id ->
                    if (id == -1) null else cursor.getString(id)
                }

                if (rawName.isNullOrBlank() || !rawName.startsWith(mediaPrefix)) {
                    continue
                }

                val counter =
                    parseBatchName(rawName.substringAfter(mediaPrefix).substringBeforeLast("."))?.first
                        ?: continue

                val id = cursor.getColumnIndex(Media._ID).let { id ->
                    if (id == -1) null else cursor.getString(id)
                }

                if (id.isNullOrBlank()) {
                    continue
                }

                val uri = Uri.withAppendedPath(scopedMediaContentUri, id)

                val result = callback(rawName, counter, uri, cursor)

                if (result == false) {
                    return
                }
            }
        }
    }

    // `tag` selects which stream's batches to enumerate — null (the default) for the
    // primary/back stream, `FRONT_STREAM_FILENAME_TAG` for a dual recording's secondary
    // stream (§9.3). Entries whose parsed tag doesn't match are excluded entirely, so each
    // stream concatenates into its own independent output file. Sorted ascending by
    // counter, kept alongside each path/Uri — needed by `moveChunksToDestination()` below
    // to both derive a session-unique chunk filename (see its own doc comment) and to
    // identify the currently-active segment (the highest counter) when the recording is
    // still ongoing.
    private fun getBatchesForConcatenationWithCounter(tag: String? = null): List<Pair<Int, String>> {
        return when (type) {
            BatchType.INTERNAL ->
                (getInternalFolder()
                    .listFiles()
                    ?.filter {
                        parseBatchName(it.nameWithoutExtension)?.second == tag
                    }
                    ?: emptyList())
                    .sortedBy {
                        parseBatchName(it.nameWithoutExtension)!!.first
                    }
                    .map { parseBatchName(it.nameWithoutExtension)!!.first to it.absolutePath }

            BatchType.CUSTOM -> getCustomDefinedFolder()
                .listFiles()
                .filter {
                    it.name?.substringBeforeLast(".")?.let { name -> parseBatchName(name)?.second == tag } ?: false
                }
                .sortedBy {
                    parseBatchName(it.name!!.substringBeforeLast("."))!!.first
                }
                .map {
                    parseBatchName(it.name!!.substringBeforeLast("."))!!.first to it.uri.toString()
                }

            BatchType.MEDIA -> {
                val fileUris = mutableListOf<Pair<String, Uri>>()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    queryMediaContent { rawName, _, uri, _ ->
                        fileUris.add(Pair(rawName, uri))
                    }
                } else {
                    legacyMediaFolder.listFiles()?.forEach {
                        fileUris.add(Pair(it.name, it.toUri()))
                    }
                }

                fileUris
                    .filter {
                        val name = it.first.substring(mediaPrefix.length).substringBeforeLast(".")
                        parseBatchName(name)?.second == tag
                    }
                    .sortedBy {
                        val name = it.first.substring(mediaPrefix.length).substringBeforeLast(".")
                        parseBatchName(name)!!.first
                    }
                    .map { pair ->
                        val name = pair.first.substring(mediaPrefix.length).substringBeforeLast(".")
                        parseBatchName(name)!!.first to pair.second.toString()
                    }
            }
        }
    }

    fun getBatchesForConcatenation(tag: String? = null): List<String> =
        getBatchesForConcatenationWithCounter(tag).map { it.second }

    // `tag`, when given, is appended to the filename (e.g. "auto" for impact-triggered
    // saves, §7.3) so a Recordings/Gallery screen can tell auto-saved clips apart from
    // manually-saved ones just by filename, without needing separate sidecar metadata.
    fun getName(date: LocalDateTime, extension: String, tag: String? = null): String {
        val name = date
            .format(DateTimeFormatter.ISO_DATE_TIME)
            .toString()
            .replace(":", "-")
            .replace(".", "_")
        val suffix = if (tag != null) "-$tag" else ""

        return "$name$suffix.$extension"
    }

    fun asInternalGetOutputFile(fileName: String): File {
        return File(getInternalFolder(), fileName)
    }

    fun asMediaGetLegacyFile(name: String): File = File(
        legacyMediaFolder,
        name
    ).apply {
        createNewFile()
    }

    fun checkIfOutputAlreadyExists(
        fileName: String,
    ): Boolean {
        return when (type) {
            BatchType.INTERNAL -> File(getInternalFolder(), fileName).exists()

            BatchType.CUSTOM ->
                getCustomDefinedFolder().findFile(fileName)?.exists() ?: false

            BatchType.MEDIA -> {
                var exists = false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    queryMediaContent { rawName, _, _, _ ->
                        if (rawName == fileName) {
                            exists = true
                            return@queryMediaContent true
                        } else {
                        }
                    }

                    return exists
                } else {
                    return File(
                        legacyMediaFolder,
                        fileName,
                    ).exists()
                }
            }
        }
    }

    abstract fun getConcatenationDestination(
        date: LocalDateTime,
        extension: String,
        fileName: String,
    ): ConcatenationDestination

    abstract fun cleanup()

    suspend fun concatenate(
        recording: RecordingInformation,
        filenameFormat: AppSettings.FilenameFormat,
        disableCache: Boolean? = null,
        onProgress: (Float?) -> Unit = {},
        fileName: String,
        // Which stream's in-progress batches to gather — null for the primary/back
        // stream, `FRONT_STREAM_FILENAME_TAG` for a dual recording's secondary stream
        // (§9.3). Independent of `fileName`'s own tag, which the caller builds separately
        // (e.g. "front-auto" for an impact-triggered secondary-stream save).
        tag: String? = null,
    ): String {
        val disableCache = disableCache ?: (type != BatchType.INTERNAL)
        val date = recording.getStartDateForFilename(filenameFormat)

        val destination = getConcatenationDestination(
            date = date,
            extension = recording.fileExtension,
            fileName = fileName,
        )

        if (!disableCache && checkIfOutputAlreadyExists(fileName)) {
            return destination.asResultString()
        }

        val filePaths = getBatchesForConcatenation(tag)

        // Media3's `EditedMediaItemSequence` requires at least one item and otherwise
        // throws deep inside `MediaConverter` — checking here instead gives a clear,
        // diagnosable message on the SAME call stack the caller already catches (e.g.
        // `RecorderEventsHandler.saveRecording()`'s `try`/`catch` → `showRecorderError`),
        // rather than depending on `MediaConverter`'s own defense-in-depth catch to
        // surface something meaningful.
        if (filePaths.isEmpty()) {
            throw IllegalStateException(
                "No batches available to concatenate (tag=$tag) — the recording may have " +
                    "been stopped before its first segment finished writing."
            )
        }

        // Media3's Transformer can only write to a local absolute file path, it cannot write
        // directly to a SAF tree/MediaStore `Uri`. For those destinations, export to a temp
        // file in the cache dir first, then stream-copy it into the real destination below.
        val exportPath = when (destination) {
            is ConcatenationDestination.LocalFile -> destination.path
            is ConcatenationDestination.ContentUri ->
                File.createTempFile(
                    "concat-",
                    ".${recording.fileExtension}",
                    context.cacheDir,
                ).absolutePath
        }

        try {
            Log.i("Concatenation", "Concatenating ${filePaths.size} batches into $exportPath")

            MediaConverter.concatenateVideoFiles(
                context,
                filePaths,
                exportPath,
            ) { percentage ->
                onProgress(percentage / 100f)
            }.await()
        } catch (error: Exception) {
            if (destination is ConcatenationDestination.ContentUri) {
                runCatching { File(exportPath).delete() }
            }
            throw error
        }

        if (destination is ConcatenationDestination.ContentUri) {
            val tempFile = File(exportPath)

            context.contentResolver.openOutputStream(destination.uri)!!.use { output ->
                tempFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            }

            runCatching { tempFile.delete() }
        }

        return destination.asResultString()
    }

    // "Process Video" disabled (§9.1/§11's `VideoRecorderSettings.processVideo`) — the
    // individual rolling-buffer chunks ARE the saved output, no merge step. Reuses the
    // exact same file selection (`getBatchesForConcatenationWithCounter`) and per-
    // storage-backend destination resolution (`getConcatenationDestination`)
    // `concatenate()` already relies on above, generating one destination per chunk
    // instead of merging them all into a single file — so no separate retention/
    // selection logic is needed: whatever `deleteOldRecordings()`'s rolling window
    // (`maxDuration`/`intervalDuration`) has left available IS what gets moved.
    // `streamTag` selects which stream's in-progress chunks to gather (same meaning as
    // `concatenate`'s own `tag` param); `outputTag` is baked into each chunk's own
    // destination filename instead (e.g. "auto" for an impact-triggered save) — the two
    // are independent, mirroring how `concatenate`'s callers already separate "which
    // batches to read" from "what to name the result." Each destination filename is
    // tagged with the chunk's OWN (session-unique, ever-increasing) counter rather than
    // a call-local index — a call-local index restarts at 1 on every save, which
    // silently overwrote an earlier save's chunk files with a later save's on any
    // second save within the same recording session (a real bug: this mode is meant for
    // exactly that — repeatedly saving chunks out while recording keeps running).
    suspend fun moveChunksToDestination(
        recording: RecordingInformation,
        filenameFormat: AppSettings.FilenameFormat,
        outputTag: String? = null,
        streamTag: String? = null,
        // True whenever the recording continues after this call returns — "Save
        // Current," impact auto-save, and the PIP overlay's Save button all keep
        // recording, unlike "Save & Stop." In that case the highest-counter chunk is
        // still open for writing, and must never be swept up: a real crash repro'd as
        // "tap Save, then later long-press Save" — the first save's `startNewCycle()`
        // had just opened a new segment when `getBatchesForConcatenationWithCounter` ran,
        // moving-and-deleting it out from under the still-recording camera pipeline,
        // which then failed to finalize it properly once recording genuinely stopped
        // later. False for "Save & Stop," where `stopRecording()` has already finalized
        // every segment before this runs, so there's no active one left to exclude.
        excludeActiveChunk: Boolean = false,
    ): List<String> {
        val date = recording.getStartDateForFilename(filenameFormat)
        var chunks = getBatchesForConcatenationWithCounter(streamTag)

        if (excludeActiveChunk && chunks.isNotEmpty()) {
            chunks = chunks.dropLast(1)
        }

        if (chunks.isEmpty()) {
            throw IllegalStateException(
                "No batches available to move (streamTag=$streamTag) — the recording may " +
                    "have been stopped before its first segment finished writing."
            )
        }

        return chunks.map { (counter, sourcePath) ->
            val chunkTag = listOfNotNull(outputTag, "chunk%05d".format(counter))
                .joinToString("-")
            val fileName = getName(date, recording.fileExtension, tag = chunkTag)

            val destination = getConcatenationDestination(
                date = date,
                extension = recording.fileExtension,
                fileName = fileName,
            )

            moveChunkFile(sourcePath, destination)

            destination.asResultString()
        }
    }

    // Copies one source batch file/Uri to its resolved destination, then deletes the
    // source — a "move," not a "copy": unlike `concatenate()`'s inputs (which stay put
    // for a possible future save), a moved chunk no longer serves any purpose once
    // relocated out of the rolling buffer, so it's removed immediately rather than left
    // for `deleteOldRecordings()` to eventually prune.
    private fun moveChunkFile(sourcePath: String, destination: ConcatenationDestination) {
        val inputStream = when (type) {
            BatchType.INTERNAL -> File(sourcePath).inputStream()
            BatchType.CUSTOM -> context.contentResolver.openInputStream(Uri.parse(sourcePath))
            BatchType.MEDIA ->
                if (SUPPORTS_SCOPED_STORAGE) {
                    context.contentResolver.openInputStream(Uri.parse(sourcePath))
                } else {
                    File(Uri.parse(sourcePath).path!!).inputStream()
                }
        } ?: throw IllegalStateException("Failed to open source chunk for reading: $sourcePath")

        inputStream.use { input ->
            val outputStream = when (destination) {
                is ConcatenationDestination.LocalFile -> File(destination.path).outputStream()
                is ConcatenationDestination.ContentUri ->
                    context.contentResolver.openOutputStream(destination.uri)
                        ?: throw IllegalStateException(
                            "Failed to open destination for writing: ${destination.uri}"
                        )
            }

            outputStream.use { output ->
                input.copyTo(output)
            }
        }

        when (type) {
            BatchType.INTERNAL -> File(sourcePath).delete()
            BatchType.CUSTOM -> runCatching {
                DocumentFile.fromSingleUri(context, Uri.parse(sourcePath))?.delete()
            }
            BatchType.MEDIA ->
                if (SUPPORTS_SCOPED_STORAGE) {
                    runCatching { context.contentResolver.delete(Uri.parse(sourcePath), null, null) }
                } else {
                    File(Uri.parse(sourcePath).path!!).delete()
                }
        }
    }

    // Destination to write the concatenated output to: either a local absolute file path
    // (internal storage, legacy pre-scoped-storage media folder), or a content:// `Uri`
    // (SAF tree, scoped-storage MediaStore) that Transformer cannot write to directly and
    // that `concatenate` above copies the exported temp file into.
    sealed class ConcatenationDestination {
        data class LocalFile(val path: String) : ConcatenationDestination()
        data class ContentUri(val uri: Uri) : ConcatenationDestination()

        fun asResultString(): String = when (this) {
            is LocalFile -> path
            is ContentUri -> uri.toString()
        }
    }

    fun exportFolderForSettings(): String {
        return when (type) {
            BatchType.INTERNAL -> RECORDER_INTERNAL_SELECTED_VALUE
            BatchType.MEDIA -> RECORDER_MEDIA_SELECTED_VALUE
            BatchType.CUSTOM -> customFolder!!.uri.toString()
        }
    }

    fun deleteRecordings() {
        // Currently deletes all recordings.
        // This is fine, because we are saving the recordings
        // in a dedicated subfolder
        when (type) {
            BatchType.INTERNAL -> getInternalFolder().deleteRecursively()

            BatchType.CUSTOM -> customFolder?.findFile(subfolderName)?.delete()
                ?: customFolder?.findFile(subfolderName)?.listFiles()?.forEach {
                    it.delete()
                }

            BatchType.MEDIA -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // TODO: Also delete pending recordings
                    // --> Doesn't seem to be possible :/
                    context.contentResolver.delete(
                        scopedMediaContentUri,
                        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '$mediaPrefix%'",
                        null,
                    )

                } else {
                    legacyMediaFolder.deleteRecursively()
                }
            }
        }
    }

    fun hasRecordingsAvailable(): Boolean {
        return when (type) {
            BatchType.INTERNAL -> getInternalFolder().listFiles()?.isNotEmpty() ?: false

            BatchType.CUSTOM -> customFolder?.findFile(subfolderName)?.listFiles()?.isNotEmpty()
                ?: false

            BatchType.MEDIA -> {
                var hasRecordings = false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.query(
                        scopedMediaContentUri,
                        arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '$mediaPrefix%'",
                        null,
                        null,
                    )!!.use { cursor ->
                        if (cursor.moveToFirst()) {
                            hasRecordings = true
                        }
                    }

                    return hasRecordings
                } else {
                    return legacyMediaFolder.listFiles()?.isNotEmpty() ?: false
                }
            }
        }
    }

    fun deleteRecordings(range: LongRange) {
        when (type) {
            BatchType.INTERNAL -> getInternalFolder().listFiles()?.forEach {
                val fileCounter = parseBatchName(it.nameWithoutExtension)?.first ?: return@forEach

                if (fileCounter in range) {
                    it.delete()
                }
            }

            BatchType.CUSTOM -> getCustomDefinedFolder().listFiles().forEach {
                val fileCounter = it.name?.substringBeforeLast(".")?.let { name -> parseBatchName(name)?.first } ?: return@forEach

                if (fileCounter in range) {
                    it.delete()
                }
            }

            BatchType.MEDIA -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val deletableNames = mutableListOf<String>()

                    queryMediaContent { rawName, counter, _, _ ->
                        if (counter in range) {
                            deletableNames.add(rawName)
                        }
                    }

                    try {
                        context.contentResolver.delete(
                            scopedMediaContentUri,
                            "${MediaStore.MediaColumns.DISPLAY_NAME} IN (${
                                deletableNames.joinToString(
                                    ","
                                ) { "'$it'" }
                            })",
                            null,
                        )
                        // This is unfortunate if the files can't be deleted, but let's just
                        // ignore it since we can't do anything about it
                    } catch (e: RuntimeException) {
                        // Probably file not found
                        e.printStackTrace()
                    } catch (e: IllegalArgumentException) {
                        // Strange filename, should not happen
                        e.printStackTrace()
                    }
                } else {
                    // TODO: Fix "would you like to try saving" -> Save button
                    legacyMediaFolder.listFiles()?.forEach {
                        val fileCounter =
                            parseBatchName(it.nameWithoutExtension.substring(mediaPrefix.length))?.first
                                ?: return@forEach

                        if (fileCounter in range) {
                            it.delete()
                        }
                    }
                }
            }
        }
    }

    fun checkIfFolderIsAccessible(): Boolean {
        try {
            return when (type) {
                BatchType.INTERNAL -> true
                BatchType.CUSTOM -> getCustomDefinedFolder().canWrite() && getCustomDefinedFolder().canRead()
                BatchType.MEDIA -> {
                    if (SUPPORTS_SCOPED_STORAGE) {
                        return true
                    }

                    return PermissionHelper.hasGranted(
                        context,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    ) &&
                            PermissionHelper.hasGranted(
                                context,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            )
                }
            }
        } catch (error: NullPointerException) {
            error.printStackTrace()
            return false
        }
    }

    fun asInternalGetFile(counter: Long, fileExtension: String, tag: String? = null): File {
        val suffix = if (tag != null) "-$tag" else ""
        return File(getInternalFolder(), "$counter$suffix.$fileExtension")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun getOrCreateMediaFile(
        name: String,
        mimeType: String,
        relativePath: String,
    ): Uri {
        // Check if already exists
        var uri: Uri? = null

        context.contentResolver.query(
            scopedMediaContentUri,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = '$name'",
            null,
            null,
        )!!.use { cursor ->
            if (cursor.moveToFirst()) {
                // No need to check for the name since the query already did that
                val id = cursor.getColumnIndex(MediaStore.MediaColumns._ID)

                if (id == -1) {
                    return@use
                }

                uri = ContentUris.withAppendedId(
                    scopedMediaContentUri,
                    cursor.getLong(id)
                )
            }
        }

        if (uri == null) {
            // Previously this caught+logged any failure here but then fell through to
            // `return uri!!` anyway — so a real, diagnosable `insert()` failure (bad
            // RELATIVE_PATH, provider `SecurityException`, etc., logged with its real
            // message/stack below) got masked by an unrelated, message-less NPE one line
            // down instead of propagating with its original context to the save flow's
            // error handling (RecorderEventsHandler.saveRecording()). Log for
            // diagnosability, then let it actually propagate.
            uri = try {
                // Create empty output file to be able to write to it
                context.contentResolver.insert(
                    scopedMediaContentUri,
                    ContentValues().apply {
                        put(
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            name
                        )
                        put(
                            MediaStore.MediaColumns.MIME_TYPE,
                            mimeType
                        )

                        put(
                            Media.RELATIVE_PATH,
                            relativePath,
                        )
                    }
                )
            } catch (e: Exception) {
                Log.e("Media", "Failed to create file (name=$name, relativePath=$relativePath)", e)
                throw e
            }

            if (uri == null) {
                Log.e(
                    "Media",
                    "contentResolver.insert() returned null for name=$name, relativePath=$relativePath",
                )
            }
        }

        return uri ?: throw IllegalStateException(
            "Failed to create or find media file for name=$name, relativePath=$relativePath"
        )
    }

    fun getAvailableBytes(): Long? {
        if (type == BatchType.CUSTOM) {
            var fileDescriptor: ParcelFileDescriptor? = null

            try {
                fileDescriptor =
                    context.contentResolver.openFileDescriptor(customFolder!!.uri, "r")!!
                val stats = Os.fstatvfs(fileDescriptor.fileDescriptor)

                val available = stats.f_bavail * stats.f_bsize

                runCatching {
                    fileDescriptor.close()
                }

                return available
            } catch (e: Exception) {
                runCatching {
                    fileDescriptor?.close();
                }

                return null
            }
        }

        val storageManager = context.getSystemService(StorageManager::class.java) ?: return null
        val file = when (type) {
            BatchType.INTERNAL -> context.filesDir
            BatchType.MEDIA ->
                if (SUPPORTS_SCOPED_STORAGE)
                    File(
                        Environment.getExternalStoragePublicDirectory(VideoBatchesFolder.BASE_SCOPED_STORAGE_RELATIVE_PATH),
                        Media.EXTERNAL_CONTENT_URI.toString(),
                    )
                else
                    File(
                        Environment.getExternalStoragePublicDirectory(VideoBatchesFolder.BASE_LEGACY_STORAGE_FOLDER),
                        VideoBatchesFolder.MEDIA_RECORDINGS_SUBFOLDER,
                    )

            BatchType.CUSTOM -> throw IllegalArgumentException("This code should not be reachable")
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            storageManager.getAllocatableBytes(storageManager.getUuidForPath(file))
        } else {
            file.usableSpace;
        }
    }

    enum class BatchType {
        INTERNAL,
        CUSTOM,
        MEDIA,
    }

    companion object {
        // Passed to `getName(..., tag = ...)` for impact-triggered saves (§7.3).
        const val AUTO_SAVED_FILENAME_TAG = "auto"

        // Passed as the batch/saved-output `tag` for the secondary camera stream in a dual
        // (front+back) recording (§9.3) — the primary/back stream keeps the untagged name.
        const val FRONT_STREAM_FILENAME_TAG = "front"

        // In-progress batch filenames are "$counter.$ext" (untagged/primary stream) or
        // "$counter-$tag.$ext" (e.g. "5-front.mp4" for the secondary stream, §9.3) — this
        // parses either shape back into (counter, tag), tag null for the untagged case.
        val BATCH_NAME_REGEX = Regex("^(\\d+)(?:-([a-zA-Z0-9]+))?$")

        fun parseBatchName(nameWithoutExtension: String): Pair<Int, String?>? {
            val match = BATCH_NAME_REGEX.matchEntire(nameWithoutExtension) ?: return null
            val counter = match.groupValues[1].toIntOrNull() ?: return null
            return counter to match.groupValues[2].ifEmpty { null }
        }

        fun requiredBytesForOneMinuteOfRecording(appSettings: AppSettings): Long {
            // 350 MiB sounds like a good default
            return 350 * 1024 * 1024
        }
    }
}

