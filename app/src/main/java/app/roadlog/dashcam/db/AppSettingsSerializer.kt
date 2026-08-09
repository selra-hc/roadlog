package app.roadlog.dashcam.db

import androidx.datastore.core.Serializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDateTime

class AppSettingsSerializer : Serializer<AppSettings> {
    override val defaultValue: AppSettings = AppSettings.getDefaultInstance()

    // `ignoreUnknownKeys = true` is load-bearing, not cosmetic: kotlinx.serialization's
    // default (`false`) throws `SerializationException` on any key the current schema no
    // longer declares — e.g. `appLockSettings`, present in settings.json written before
    // that field was removed from `AppSettings`. Without this, every schema removal
    // silently resets the ENTIRE settings file (saveFolder, filenameFormat, everything)
    // back to defaultValue on next launch, for any device that already has a settings
    // file from before the removal.
    private val json = Json {
        ignoreUnknownKeys = true
    }

    override suspend fun readFrom(input: InputStream): AppSettings {
        return try {
            json.decodeFromString(
                AppSettings.serializer(),
                input.readBytes().decodeToString()
            )
        } catch (error: SerializationException) {
            error.printStackTrace()
            defaultValue
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        output.write(
            json.encodeToString(
                AppSettings.serializer(),
                t
            ).encodeToByteArray()
        )
    }
}

class LocalDateTimeSerializer : KSerializer<LocalDateTime> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LocalDateTime", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): LocalDateTime {
        return LocalDateTime.parse(decoder.decodeString())
    }

    override fun serialize(encoder: Encoder, value: LocalDateTime) {
        encoder.encodeString(value.toString())
    }
}
