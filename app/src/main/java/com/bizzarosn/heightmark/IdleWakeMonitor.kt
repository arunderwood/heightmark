package com.bizzarosn.heightmark

import android.Manifest
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.CancellationSignal
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

/**
 * Watches for the device leaving a stationary position while the GPS radio is
 * off, and fires [onWake] (once) when it should be turned back on.
 *
 * Wake triggers, each skipped gracefully when the hardware lacks it:
 *  - significant-motion sensor: horizontal movement (walking or driving away).
 *    Its platform contract only covers horizontal travel, so it can miss
 *    elevators — hence the barometer.
 *  - barometer: sustained pressure change means vertical movement.
 *  - passive provider: free fixes other apps request; wake if one shows we moved.
 *  - fallback GPS poll every few minutes, only on devices with no barometer,
 *    to catch vertical movement the other triggers can't see.
 */
class IdleWakeMonitor(
    private val locationManager: LocationManager,
    private val sensorManager: SensorManager
) {
    private val pressureDetector = PressureDeltaDetector()
    private var onWake: (() -> Unit)? = null
    private var anchor: Location? = null

    private var triggerListener: TriggerEventListener? = null
    private var pressureListener: SensorEventListener? = null
    private var passiveListener: LocationListener? = null
    private var pollJob: Job? = null
    private var pollCancellation: CancellationSignal? = null

    val isRunning: Boolean
        get() = onWake != null

    /**
     * Starts watching. [anchor] is the fix the device went idle at; [executor]
     * receives location callbacks and [scope] hosts the fallback poll.
     */
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun start(anchor: Location, executor: Executor, scope: CoroutineScope, onWake: () -> Unit) {
        stop()
        this.anchor = anchor
        this.onWake = onWake

        armSignificantMotion()
        val hasBarometer = armBarometer()
        armPassiveProvider(executor)
        if (!hasBarometer) {
            startFallbackPoll(executor, scope)
        }
    }

    fun stop() {
        onWake = null
        anchor = null

        triggerListener?.let { listener ->
            sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)?.let { sensor ->
                sensorManager.cancelTriggerSensor(listener, sensor)
            }
        }
        triggerListener = null

        pressureListener?.let { sensorManager.unregisterListener(it) }
        pressureListener = null
        pressureDetector.reset()

        passiveListener?.let { locationManager.removeUpdates(it) }
        passiveListener = null

        pollJob?.cancel()
        pollJob = null
        pollCancellation?.cancel()
        pollCancellation = null
    }

    private fun wake() {
        val callback = onWake ?: return
        stop()
        callback()
    }

    private fun armSignificantMotion() {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
        val listener = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                Log.d(TAG, "Significant motion detected")
                wake()
            }
        }
        if (sensorManager.requestTriggerSensor(listener, sensor)) {
            triggerListener = listener
        }
    }

    /** Returns true if a barometer is present and armed. */
    private fun armBarometer(): Boolean {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) ?: return false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (pressureDetector.feed(event.values[0])) {
                    Log.d(TAG, "Sustained pressure change detected")
                    wake()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        val registered = sensorManager.registerListener(
            listener, sensor, PRESSURE_SAMPLING_PERIOD_US
        )
        if (registered) {
            pressureListener = listener
        }
        return registered
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun armPassiveProvider(executor: Executor) {
        if (!locationManager.hasProvider(LocationManager.PASSIVE_PROVIDER)) return
        val listener = LocationListener { location -> onOpportunisticFix(location) }
        val request = LocationRequest.Builder(PASSIVE_INTERVAL_MS).build()
        locationManager.requestLocationUpdates(
            LocationManager.PASSIVE_PROVIDER, request, executor, listener
        )
        passiveListener = listener
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun startFallbackPoll(executor: Executor, scope: CoroutineScope) {
        pollJob = scope.launch {
            while (true) {
                delay(FALLBACK_POLL_INTERVAL_MS)
                if (onWake == null) return@launch
                val cancellation = CancellationSignal()
                pollCancellation = cancellation
                locationManager.getCurrentLocation(
                    LocationManager.GPS_PROVIDER, cancellation, executor
                ) { location ->
                    location?.let { onOpportunisticFix(it) }
                }
            }
        }
    }

    private fun onOpportunisticFix(location: Location) {
        val anchor = anchor ?: return
        val movedHorizontally = anchor.distanceTo(location) > MAX_IDLE_DRIFT_METERS
        val movedVertically = anchor.hasAltitude() && location.hasAltitude() &&
            kotlin.math.abs(anchor.altitude - location.altitude) > MAX_IDLE_ALTITUDE_DRIFT_METERS
        if (movedHorizontally || movedVertically) {
            Log.d(TAG, "Opportunistic fix shows movement")
            wake()
        }
    }

    companion object {
        private const val TAG = "IdleWakeMonitor"
        private const val PRESSURE_SAMPLING_PERIOD_US = 1_000_000 // 1 Hz
        private const val PASSIVE_INTERVAL_MS = 10_000L
        private const val FALLBACK_POLL_INTERVAL_MS = 180_000L // 3 min
        private const val MAX_IDLE_DRIFT_METERS = 30f
        private const val MAX_IDLE_ALTITUDE_DRIFT_METERS = 10.0
    }
}
