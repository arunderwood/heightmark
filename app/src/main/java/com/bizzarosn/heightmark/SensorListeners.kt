package com.bizzarosn.heightmark

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener

/**
 * A [SensorEventListener] from a single lambda, sparing callers the
 * mandatory empty onAccuracyChanged stub.
 */
inline fun sensorListener(crossinline onChanged: (SensorEvent) -> Unit): SensorEventListener =
    object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) = onChanged(event)

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
