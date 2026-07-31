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
        val progress: Float,
        val settled: Boolean
    )

    private data class Reading(val elevationMeters: Double, val accuracyMeters: Float)

    private val window = ArrayDeque<Reading>()
    private val pendingOutliers = ArrayDeque<Reading>()
    private var pendingAbove = false
    private var settled = false

    /**
     * Adds a reading. A null [verticalAccuracyMeters] means the fix did not
     * report one, which is not the same as reporting a bad one: the window
     * substitutes [DEFAULT_VERTICAL_ACCURACY_M] so the jump and settle
     * thresholds still have a scale to work from.
     */
    fun addElevationReading(
        elevation: Double,
        verticalAccuracyMeters: Float? = null
    ): Snapshot {
        val accuracy = verticalAccuracyMeters ?: DEFAULT_VERTICAL_ACCURACY_M
        val reading = Reading(elevation, accuracy)
        if (window.isEmpty()) {
            append(reading)
            return snapshot()
        }

        val deviation = elevation - getAverageElevation()
        val threshold = max(2.0 * accuracy, JUMP_THRESHOLD_FLOOR_M)
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

    // Inverse-variance weighted: a precise fix pulls the average toward itself
    // much harder than a marginal one, instead of moving it exactly as far as
    // any other reading admitted into the window.
    private fun getAverageElevation(): Double {
        var weightedSum = 0.0
        var totalWeight = 0.0
        window.forEach { reading ->
            val accuracy = max(reading.accuracyMeters.toDouble(), WEIGHT_ACCURACY_FLOOR_M)
            val weight = 1.0 / (accuracy * accuracy)
            weightedSum += reading.elevationMeters * weight
            totalWeight += weight
        }
        return weightedSum / totalWeight
    }

    companion object {
        // ~30 readings at the tracker's 1 Hz update interval, spanning the
        // stationary period before the duty cycle turns the GPS radio off
        // (StillnessDetector's default window), rather than judging "settled"
        // over a few seconds of nearly fully correlated GNSS vertical error.
        const val DEFAULT_WINDOW_SIZE = 30
        const val DEFAULT_VERTICAL_ACCURACY_M = 10f
        const val JUMP_CONFIRM_COUNT = 3
        const val JUMP_THRESHOLD_FLOOR_M = 8.0
        const val SETTLE_STDDEV_FLOOR_M = 4.0
        const val SETTLE_ACCURACY_FACTOR = 0.75
        // A reading claiming sub-meter accuracy should not dominate the
        // average by a huge margin over one claiming 1 m; this bounds the
        // weight ratio between the best and worst admitted fixes.
        const val WEIGHT_ACCURACY_FLOOR_M = 1.0
    }
}
