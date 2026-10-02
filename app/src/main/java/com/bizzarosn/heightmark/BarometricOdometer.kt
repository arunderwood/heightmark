package com.bizzarosn.heightmark

import kotlin.math.abs
import kotlin.math.pow

/**
 * Turns barometer samples into [motionMeters]: the height the device has been
 * carried up (positive) or down since tracking began, with weather left out.
 *
 * A barometer is precise relative to itself (centimeters between samples) but
 * its absolute reading wanders with the weather, up to a few hPa an hour,
 * about 8 m of apparent height per hPa. Carried height and weather are told
 * apart by rate: weather changes pressure by at most about 0.25 m of
 * apparent height per [windowNanos], while stairs, slopes and elevators
 * clear [motionThresholdMeters] over the same span several times over.
 *
 *  - Still: every pressure change is weather, so [motionMeters] holds. This
 *    is what keeps a phone lying on a desk at one height for hours.
 *  - Moving: once the change across the window clears the threshold, the
 *    whole window counts as motion, including the part that arrived before
 *    the threshold was crossed, and every later change follows it. Weather
 *    cannot be told apart from motion here, so [varianceMeters2] grows at
 *    [weatherVarianceRate] per second for whoever calibrates against it.
 *
 * A gap in samples longer than [maxGapNanos] (the app was in the
 * background) is taken as motion, since the pressure change across it is the
 * best estimate of where the device went, with the gap's weather variance
 * added.
 *
 * [SecondAverager] turns the samples into one reading per second by their
 * own timestamps, because the sensor may deliver them far faster than
 * requested. Pure JVM;
 * the caller supplies the timestamps.
 */
class BarometricOdometer(
    private val windowNanos: Long = 30_000_000_000L,
    private val motionThresholdMeters: Double = 1.0,
    private val maxGapNanos: Long = 10_000_000_000L,
    private val weatherVarianceRate: Double = 0.018
) {

    /** Carried height since the first sample, in meters; weather excluded. */
    var motionMeters: Double = 0.0
        private set

    /** Variance, in m², that weather has added to [motionMeters] so far. */
    var varianceMeters2: Double = 0.0
        private set

    /** True once any sample has been stepped: this device has a working barometer. */
    var isTracking: Boolean = false
        private set

    var isMoving: Boolean = false
        private set

    /** The latest reading's [standardAltitude], in meters; null before the first. */
    val standardAltitudeMeters: Double?
        get() = last?.altitudeMeters

    private data class Reading(val atNanos: Long, val altitudeMeters: Double)

    private val window = ArrayDeque<Reading>()
    private var last: Reading? = null

    private val perSecond = SecondAverager()

    /**
     * Feeds one sample of [pressureHpa] taken at [atNanos] on the
     * elapsed-realtime clock. Returns true when [motionMeters] changed.
     */
    fun feed(pressureHpa: Float, atNanos: Long): Boolean {
        val reading = perSecond.add(pressureHpa, atNanos) ?: return false
        return step(standardAltitude(reading.mean), reading.atNanos)
    }

    private fun step(altitudeMeters: Double, atNanos: Long): Boolean {
        val reading = Reading(atNanos, altitudeMeters)
        val previous = last
        last = reading
        isTracking = true
        if (previous == null) {
            window.addLast(reading)
            return false
        }

        val elapsedNanos = atNanos - previous.atNanos
        val delta = altitudeMeters - previous.altitudeMeters
        if (elapsedNanos > maxGapNanos) {
            motionMeters += delta
            varianceMeters2 += weatherVarianceRate * elapsedNanos / NANOS_PER_SECOND
            isMoving = false
            window.clear()
            window.addLast(reading)
            return delta != 0.0
        }

        window.addLast(reading)
        while (window.size > 2 && atNanos - window[1].atNanos >= windowNanos) {
            window.removeFirst()
        }
        val net = altitudeMeters - window.first().altitudeMeters

        if (isMoving) {
            motionMeters += delta
            varianceMeters2 += weatherVarianceRate * elapsedNanos / NANOS_PER_SECOND
            if (abs(net) < motionThresholdMeters / 2 && windowSpanNanos() >= windowNanos) {
                isMoving = false
                // The window held motion that is already counted; a still
                // spell has to start from here or the next onset counts it twice
                window.clear()
                window.addLast(reading)
            }
            return delta != 0.0
        }

        if (abs(net) > motionThresholdMeters) {
            isMoving = true
            motionMeters += net
            return true
        }
        return false
    }

    private fun windowSpanNanos(): Long = window.last().atNanos - window.first().atNanos

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000L

        /** Standard sea-level pressure, the reference [standardAltitude] measures from. */
        private const val STANDARD_PRESSURE_HPA = 1013.25

        /**
         * Height in the International Standard Atmosphere for [pressureHpa],
         * the formula behind `SensorManager.getAltitude`. Only differences
         * are used, so the unknown sea-level pressure of the day cancels.
         */
        fun standardAltitude(pressureHpa: Double): Double =
            44_330.0 * (1.0 - (pressureHpa / STANDARD_PRESSURE_HPA).pow(1.0 / 5.255))
    }
}
