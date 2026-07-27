package com.bizzarosn.heightmark

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Rolling average of elevation readings with fast re-anchoring on real
 * altitude changes.
 *
 * A reading that deviates from the current average by more than
 * max(2 x vertical accuracy, 8 m) is held out of the window; noise spikes
 * are symmetric, so the pending run is discarded on a sign flip or an
 * in-band reading. [JUMP_CONFIRM_COUNT] consecutive same-side outliers mean
 * the device genuinely changed elevation (elevator, stairs): the window is
 * flushed and re-seeded with those readings so the display snaps to the new
 * level instead of walking there one sample at a time.
 */
class ElevationService(private val readingsCount: Int) {

    data class Snapshot(
        val averageMeters: Double, // NaN while the window is empty
        val readingCount: Int,
        val windowSize: Int,
        val progress: Float,
        val settled: Boolean
    )

    private data class Reading(val elevationMeters: Double, val accuracyMeters: Float)

    private val window = ArrayDeque<Reading>()
    private val pendingOutliers = ArrayDeque<Reading>()
    private var pendingAbove = false
    private var settled = false

    fun addElevationReading(
        elevation: Double,
        verticalAccuracyMeters: Float = DEFAULT_VERTICAL_ACCURACY_M
    ): Snapshot {
        val reading = Reading(elevation, verticalAccuracyMeters)
        if (window.isEmpty()) {
            append(reading)
            return snapshot()
        }

        val deviation = elevation - getAverageElevation()
        val threshold = max(2.0 * verticalAccuracyMeters, JUMP_THRESHOLD_FLOOR_M)
        if (abs(deviation) <= threshold) {
            pendingOutliers.clear()
            append(reading)
            return snapshot()
        }

        val above = deviation > 0
        if (pendingOutliers.isNotEmpty() && above != pendingAbove) {
            pendingOutliers.clear()
        }
        pendingAbove = above
        pendingOutliers.addLast(reading)
        if (pendingOutliers.size >= JUMP_CONFIRM_COUNT) {
            window.clear()
            settled = false
            pendingOutliers.forEach { append(it) }
            pendingOutliers.clear()
        }
        return snapshot()
    }

    fun reset() {
        window.clear()
        pendingOutliers.clear()
        settled = false
    }

    fun snapshot(): Snapshot = Snapshot(
        averageMeters = getAverageElevation(),
        readingCount = window.size,
        windowSize = readingsCount,
        progress = window.size.toFloat() / readingsCount,
        settled = settled
    )

    private fun append(reading: Reading) {
        if (window.size >= readingsCount) {
            window.removeFirst()
        }
        window.addLast(reading)
        // Latched: spread creep alone never demotes a settled reading — a
        // genuine elevation change is the jump detector's job to catch.
        if (!settled && window.size == readingsCount) {
            val meanAccuracy = window.map { it.accuracyMeters.toDouble() }.average()
            val limit = max(SETTLE_STDDEV_FLOOR_M, SETTLE_ACCURACY_FACTOR * meanAccuracy)
            if (standardDeviation() <= limit) {
                settled = true
            }
        }
    }

    private fun standardDeviation(): Double {
        val mean = getAverageElevation()
        val variance = window.sumOf { (it.elevationMeters - mean).let { d -> d * d } } / window.size
        return sqrt(variance)
    }

    private fun getAverageElevation(): Double {
        return window.map { it.elevationMeters }.average()
    }

    fun getLocalizedElevation(useMetric: Boolean): Double {
        val averageElevation = getAverageElevation()
        return if (useMetric) averageElevation else UnitConverter.metersToFeet(averageElevation)
    }

    fun readingCount(): Int = window.size

    companion object {
        const val DEFAULT_VERTICAL_ACCURACY_M = 10f
        const val JUMP_CONFIRM_COUNT = 3
        const val JUMP_THRESHOLD_FLOOR_M = 8.0
        const val SETTLE_STDDEV_FLOOR_M = 4.0
        const val SETTLE_ACCURACY_FACTOR = 0.75
    }
}
