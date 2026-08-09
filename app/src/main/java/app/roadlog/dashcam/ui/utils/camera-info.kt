package app.roadlog.dashcam.ui.utils

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalLensFacing
import androidx.camera.lifecycle.ProcessCameraProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLensFacing::class)
data class CameraInfo(
    val id: Int,
) {
    enum class Lens(val androidValue: Int) {
        BACK(CameraSelector.LENS_FACING_BACK),
        FRONT(CameraSelector.LENS_FACING_FRONT),
        EXTERNAL(CameraSelector.LENS_FACING_EXTERNAL),
        UNKNOWN(999),
    }

    val lens: Lens
        get() = CAMERA_INT_TO_LENS_MAP[id] ?: Lens.UNKNOWN

    companion object {
        val CAMERA_INT_TO_LENS_MAP = mapOf(
            CameraSelector.LENS_FACING_BACK to Lens.BACK,
            CameraSelector.LENS_FACING_FRONT to Lens.FRONT,
            CameraSelector.LENS_FACING_EXTERNAL to Lens.EXTERNAL,
        )

        fun queryAvailableCameras(context: Context): List<CameraInfo> {
            val camera = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            return camera.cameraIdList.map { id ->
                val lensFacing =
                    camera.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                        ?: return@map null

                fromCameraId(id, lensFacing)
            }.filterNotNull()
        }

        fun fromCameraId(cameraId: String, lensFacing: Int): CameraInfo {
            return CameraInfo(
                id = cameraId.toInt(),
            )
        }

        // "normal cameras" means the device has a front and back camera
        fun checkHasNormalCameras(cameras: Iterable<CameraInfo>) =
            cameras.count() == 2 && cameras.elementAt(0).id == 0 && cameras.elementAt(1).id == 1

        // Whether this device's camera HAL can actually run front+back concurrently —
        // CameraX's `availableConcurrentCameraInfos` is the only authoritative source for
        // this (having 2 physical cameras doesn't imply they can be bound at the same time).
        suspend fun checkSupportsDualRecording(context: Context): Boolean {
            val cameraProvider = withContext(Dispatchers.IO) {
                ProcessCameraProvider.getInstance(context).get()
            }

            return cameraProvider.availableConcurrentCameraInfos.any { concurrentSet ->
                concurrentSet.any { it.lensFacing == CameraSelector.LENS_FACING_FRONT } &&
                    concurrentSet.any { it.lensFacing == CameraSelector.LENS_FACING_BACK }
            }
        }
    }
}