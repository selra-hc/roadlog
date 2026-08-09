package app.roadlog.dashcam.impact

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

// Detects a hard-braking/collision-like deceleration event via the accelerometer (§7.1).
class ImpactDetector(context: Context) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // Gravity-compensated — more reliable for isolating a deceleration spike than raw
    // TYPE_ACCELEROMETER, which also carries the gravity vector (§7.1).
    private val linearAccelerationSensor =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    private var consecutiveHighMagnitudeSamples = 0

    var onImpactDetected: () -> Unit = {}

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val (x, y, z) = event.values
            val magnitudeInGs =
                sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH

            if (magnitudeInGs >= THRESHOLD_G) {
                consecutiveHighMagnitudeSamples += 1

                // Require a short sustained spike, not a single-sample one, to reduce
                // false positives from potholes/speed bumps (§7.1) — a real hard-braking
                // or collision event keeps the vehicle decelerating hard across several
                // consecutive ~20ms samples, unlike a single sharp jolt.
                if (consecutiveHighMagnitudeSamples >= REQUIRED_CONSECUTIVE_SAMPLES) {
                    consecutiveHighMagnitudeSamples = 0
                    onImpactDetected()
                }
            } else {
                consecutiveHighMagnitudeSamples = 0
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // SENSOR_DELAY_GAME (~20ms/50Hz) needs no special runtime permission — the
    // HIGH_SAMPLING_RATE_SENSORS permission is only required above 200Hz (§7.1, §12).
    fun start() {
        val sensor = linearAccelerationSensor ?: return // no such sensor on this device
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
        consecutiveHighMagnitudeSamples = 0
    }

    companion object {
        // Starting point per §7.1 (2.5-3g) and §16's flagged risk: expect a real-world
        // road-test iteration cycle to tune this, not a one-shot correct value.
        const val THRESHOLD_G = 2.5f

        // ~60ms sustained at SENSOR_DELAY_GAME's ~20ms sample period — "a couple of
        // consecutive samples," per §7.1, not a single-sample spike.
        const val REQUIRED_CONSECUTIVE_SAMPLES = 3
    }
}
