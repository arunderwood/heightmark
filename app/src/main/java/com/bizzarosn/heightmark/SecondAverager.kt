package com.bizzarosn.heightmark

/**
 * Averages sensor samples into one reading per second of the samples' own
 * timestamps.
 *
 * The sampling period an app requests is only a hint, and a sensor may
 * deliver many times faster. A filter fed through this sees one reading a
 * second whatever the hardware does. A second's reading comes out when the
 * first sample of a later second arrives. Pure JVM.
 */
class SecondAverager {

    /** The mean of the samples in the second that starts at [atNanos]. */
    data class Reading(val atNanos: Long, val mean: Double)

    private var second = Long.MIN_VALUE
    private var sum = 0.0
    private var count = 0

    /**
     * Adds [value], sampled at [atNanos] on the elapsed-realtime clock.
     * Returns the previous second's reading when [value] opens a new second,
     * otherwise null.
     */
    fun add(value: Float, atNanos: Long): Reading? {
        val sampleSecond = atNanos / NANOS_PER_SECOND
        if (sampleSecond == second) {
            sum += value
            count++
            return null
        }
        val closed = if (count > 0) Reading(second * NANOS_PER_SECOND, sum / count) else null
        second = sampleSecond
        sum = value.toDouble()
        count = 1
        return closed
    }

    fun reset() {
        second = Long.MIN_VALUE
        sum = 0.0
        count = 0
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
