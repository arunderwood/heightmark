package com.bizzarosn.heightmark

import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.LocationManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

/**
 * The extra data feeds the diagnostic panel needs and nothing else does:
 * satellite counts, ambient pressure, and a ticker that keeps the fix age
 * moving between fixes. [onUpdate] fires whenever a value changes.
 *
 * Each source arms independently, so a source that could not start — most
 * often the GNSS callback, which needs fine location — is retried on the next
 * [start] rather than being written off for the life of the panel.
 */
class DetailsSourcesController(
    private val locationManager: LocationManager,
    private val sensorManager: SensorManager,
    private val onUpdate: () -> Unit
) {
    var satellitesUsed: Int = 0
        private set
    var satellitesVisible: Int = 0
        private set
    var pressureHpa: Float? = null
        private set

    private var gnssStatusCallback: GnssStatus.Callback? = null
    private var pressureListener: SensorEventListener? = null
    private var tickerJob: Job? = null

    fun start(executor: Executor, scope: CoroutineScope, hasFinePermission: Boolean) {
        if (gnssStatusCallback == null && hasFinePermission) {
            val callback = object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    satellitesVisible = status.satelliteCount
                    satellitesUsed = (0 until status.satelliteCount).count { status.usedInFix(it) }
                    onUpdate()
                }
            }
            try {
                locationManager.registerGnssStatusCallback(executor, callback)
                gnssStatusCallback = callback
            } catch (e: SecurityException) {
                Log.e(TAG, "Cannot register GnssStatus callback", e)
            }
        }

        if (pressureListener == null) {
            sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { sensor ->
                val listener = sensorListener { event -> pressureHpa = event.values[0] }
                if (sensorManager.registerListener(
                        listener, sensor, SensorManager.SENSOR_DELAY_UI
                    )
                ) {
                    pressureListener = listener
                }
            }
        }

        if (tickerJob == null) {
            tickerJob = scope.launch {
                while (true) {
                    delay(TICK_INTERVAL_MS)
                    onUpdate()
                }
            }
        }
    }

    /**
     * Releases every source. The last-known values are deliberately kept, so
     * reopening the panel shows them again instead of flashing empty counts
     * while the first callback is still in flight.
     */
    fun stop() {
        gnssStatusCallback?.let { locationManager.unregisterGnssStatusCallback(it) }
        gnssStatusCallback = null
        pressureListener?.let { sensorManager.unregisterListener(it) }
        pressureListener = null
        tickerJob?.cancel()
        tickerJob = null
    }

    companion object {
        private const val TAG = "DetailsSources"
        private const val TICK_INTERVAL_MS = 1_000L
    }
}
