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
 */
fun SensorManager.registerPressureListener(
    samplingPeriodUs: Int,
    onPressure: (Float) -> Unit
): SensorEventListener? {
    val sensor = getDefaultSensor(Sensor.TYPE_PRESSURE) ?: return null
    val listener = sensorListener { event -> onPressure(event.values[0]) }
    return if (registerListener(listener, sensor, samplingPeriodUs)) listener else null
}
