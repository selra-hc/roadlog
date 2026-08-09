package app.roadlog.dashcam.impact

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File

// Persisted state for an in-progress impact-triggered auto-save (§7.4) — a hard-braking
// event was detected at `triggeredAtEpochMillis` and the pre-impact batches were locked
// at `lockedIndex`; the save should finalize at `finalizeAtEpochMillis`.
//
// This survives the recording service's own process being killed and restarted mid-
// countdown: `IntervalRecorderService` writes this on trigger and reads it back on every
// `start()`, so a resumed service can tell "now >= finalizeAtEpochMillis" apart from
// "still waiting" — the primary mechanism (an in-service coroutine `delay()`) doesn't
// survive a process death, this file is the safety net that does.
@Serializable
data class PendingImpactSave(
    val triggeredAtEpochMillis: Long,
    val lockedIndex: Long,
    val postImpactDurationMinutes: Int,
) {
    val finalizeAtEpochMillis: Long
        get() = triggeredAtEpochMillis + postImpactDurationMinutes * 60_000L
}

// A dedicated pending-action file next to the batches folder (§7.4), not a DataStore
// field alongside the rest of AppSettings — this is service-internal bookkeeping read
// and written synchronously from the service, not a user-facing setting that needs
// DataStore's async Flow machinery.
class PendingImpactSaveStore(private val context: Context) {
    private val file: File
        get() = File(context.filesDir, FILE_NAME)

    fun read(): PendingImpactSave? {
        val file = file
        if (!file.exists()) {
            return null
        }

        return try {
            Json.decodeFromString(PendingImpactSave.serializer(), file.readText())
        } catch (error: SerializationException) {
            null
        }
    }

    fun write(pending: PendingImpactSave) {
        file.writeText(Json.encodeToString(PendingImpactSave.serializer(), pending))
    }

    fun clear() {
        file.delete()
    }

    companion object {
        private const val FILE_NAME = "pending_impact_save.json"
    }
}
