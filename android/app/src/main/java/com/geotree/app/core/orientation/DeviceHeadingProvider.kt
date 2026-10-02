package com.geotree.app.core.orientation

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow

/** Raw compass readings. Abstracted so heading logic can be tested without phone hardware. */
interface HeadingSource {
    val isSupported: Boolean

    /** Sensors are registered while this flow is collected and unregistered when collection stops. */
    fun readings(): Flow<RawHeading>

    companion object {
        val None: HeadingSource = object : HeadingSource {
            override val isSupported = false
            override fun readings(): Flow<RawHeading> = emptyFlow()
        }
    }
}

/** East-positive magnetic declination in degrees, or null when it cannot be computed safely. */
fun interface DeclinationSource {
    fun declinationDegrees(latitude: Double, longitude: Double, altitudeMeters: Double, timeMillis: Long): Double?

    companion object {
        val None = DeclinationSource { _, _, _, _ -> null }

        /** Android's World Magnetic Model; works offline. */
        val Android = DeclinationSource { lat, lon, alt, time ->
            runCatching { GeomagneticField(lat.toFloat(), lon.toFloat(), alt.toFloat(), time).declination.toDouble() }
                .getOrNull()
                ?.takeIf { it.isFinite() && it in -90.0..90.0 }
        }
    }
}

/**
 * Phone heading from Android sensors, offline.
 *
 * Preferred: TYPE_ROTATION_VECTOR (sensor-fused, drift-corrected). Fallback: accelerometer +
 * magnetometer through SensorManager.getRotationMatrix, which is noisier but sound; the
 * [HeadingSmoother] damps it. Both report magnetic north. The app is portrait-only, so no
 * display-rotation remapping is needed.
 */
class DeviceHeadingProvider(context: Context) : HeadingSource {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    override val isSupported: Boolean = rotationVector != null || (accelerometer != null && magnetometer != null)

    override fun readings(): Flow<RawHeading> = callbackFlow {
        val matrix = FloatArray(9)
        val gravity = FloatArray(3)
        val geomagnetic = FloatArray(3)
        var haveGravity = false
        var haveGeomagnetic = false
        var accuracy = HeadingAccuracy.MEDIUM

        fun emit() {
            headingFromRotationMatrix(matrix)?.let { trySend(RawHeading(it, accuracy)) }
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(matrix, event.values)
                        emit()
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        event.values.copyInto(gravity, endIndex = 3)
                        haveGravity = true
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        event.values.copyInto(geomagnetic, endIndex = 3)
                        haveGeomagnetic = true
                        if (haveGravity && SensorManager.getRotationMatrix(matrix, null, gravity, geomagnetic)) emit()
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, status: Int) {
                accuracy = when (status) {
                    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> HeadingAccuracy.HIGH
                    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> HeadingAccuracy.MEDIUM
                    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> HeadingAccuracy.LOW
                    else -> HeadingAccuracy.UNRELIABLE
                }
            }
        }

        val rotation = rotationVector
        if (rotation != null) {
            sensorManager.registerListener(listener, rotation, SensorManager.SENSOR_DELAY_UI)
        } else if (accelerometer != null && magnetometer != null) {
            sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
            sensorManager.registerListener(listener, magnetometer, SensorManager.SENSOR_DELAY_UI)
        } else {
            close()
        }
        awaitClose { sensorManager.unregisterListener(listener) }
    }
}
