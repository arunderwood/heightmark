package com.bizzarosn.heightmark

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * A [SensorEventListener] from a single lambda, sparing callers the
 * mandatory empty onAccuracyChanged stub.
 */
inline fun sensorListener(crossinline onChanged: (SensorEvent) -> Unit): SensorEventListener =
    object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) = onChanged(event)

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

/**
 * Registers [onPressure] against the barometer at [samplingPeriodUs], returning
 * the listener to hand back to [SensorManager.unregisterListener], or null when
 * the device has no barometer or the registration was refused. Callers keep the
 * returned listener as their armed/disarmed flag.
 *
 * [onPressure] receives hPa and the sample's timestamp in nanoseconds. The
 * period is only a hint, and some sensors deliver many times faster, so
 * anything time-based must go by the timestamp rather than count samples.
 */
fun SensorManager.registerPressureListener(
    samplingPeriodUs: Int,
    onPressure: (pressureHpa: Float, atNanos: Long) -> Unit
): SensorEventListener? {
    val sensor = getDefaultSensor(Sensor.TYPE_PRESSURE) ?: return null
    val listener = sensorListener { event -> onPressure(event.values[0], event.timestamp) }
    return if (registerListener(listener, sensor, samplingPeriodUs)) listener else null
}
