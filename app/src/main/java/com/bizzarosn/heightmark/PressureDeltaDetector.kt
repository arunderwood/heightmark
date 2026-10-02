package com.bizzarosn.heightmark

import kotlin.math.abs
import kotlin.math.exp

/**
 * Detects vertical movement (elevator, escalator, stairs) from barometric
 * pressure samples while the device is otherwise stationary.
 *
 * Pressure falls ~0.12 hPa per meter of ascent, so [thresholdHpa] of 0.3
 * corresponds to roughly 2.5 m of elevation change. The smoothed pressure
 * (time constant [smoothingTauNanos], about 3 s) is compared against a
 * baseline. Two defenses against false wakes:
 *  - the change must stay beyond the threshold for [sustainNanos], about 3 s,
 *    rejecting the brief spikes HVAC systems and closing doors produce
 *  - while quiet, the baseline tracks the current pressure with time constant
 *    [baselineTauNanos], about 100 s. It absorbs weather-front drift (~1
 *    hPa/hour at worst) but lags a climb of a few meters a minute far enough
 *    to cross the threshold.
 *
 * Every filter setting is a duration, applied by sample timestamp. The
 * requested sampling period is only a hint: a Pixel 8 Pro delivers 15 Hz when
 * asked for 1 Hz. [SecondAverager] reduces the samples to one reading per
 * second, so the verdict does not depend on the delivery rate.
 */
class PressureDeltaDetector(
    private val thresholdHpa: Double = 0.3,
    private val sustainNanos: Long = 3_000_000_000L,
    private val baselineTauNanos: Long = 100_000_000_000L,
    private val smoothingTauNanos: Long = 3_000_000_000L
) {
    private val perSecond = SecondAverager()
    private var lastAtNanos: Long? = null
    private var baseline = Double.NaN
    private var smoothed = Double.NaN
    private var beyondSinceNanos: Long? = null

    /**
     * Feeds one pressure sample in hPa, taken at [atNanos] on the
     * elapsed-realtime clock. Returns true on sustained vertical movement.
     */
    fun feed(pressureHpa: Float, atNanos: Long): Boolean {
        val reading = perSecond.add(pressureHpa, atNanos) ?: return false
        return step(reading.mean, reading.atNanos)
    }

    private fun step(pressureHpa: Double, atNanos: Long): Boolean {
        val previousAtNanos = lastAtNanos
        lastAtNanos = atNanos
        if (previousAtNanos == null) {
            baseline = pressureHpa
            smoothed = pressureHpa
            return false
        }
        val elapsedNanos = atNanos - previousAtNanos
        smoothed += alpha(elapsedNanos, smoothingTauNanos) * (pressureHpa - smoothed)
        if (abs(smoothed - baseline) > thresholdHpa) {
            val since = beyondSinceNanos ?: atNanos.also { beyondSinceNanos = it }
            // A reading stands for the whole second that starts at its timestamp
            return atNanos + NANOS_PER_SECOND - since >= sustainNanos
        }
        beyondSinceNanos = null
        baseline += alpha(elapsedNanos, baselineTauNanos) * (pressureHpa - baseline)
        return false
    }

    fun reset() {
        perSecond.reset()
        lastAtNanos = null
        baseline = Double.NaN
        smoothed = Double.NaN
        beyondSinceNanos = null
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L

        /** The exponential-average weight that gives time constant [tauNanos] over [elapsedNanos]. */
        fun alpha(elapsedNanos: Long, tauNanos: Long): Double =
            1.0 - exp(-elapsedNanos.toDouble() / tauNanos)
    }
}
