package com.bizzarosn.heightmark

import kotlin.math.abs

/**
 * Detects vertical movement (elevator, escalator, stairs) from barometric
 * pressure samples while the device is otherwise stationary.
 *
 * Pressure falls ~0.12 hPa per meter of ascent, so [thresholdHpa] of 0.3
 * corresponds to roughly 2.5 m of elevation change. Two defenses against
 * false wakes:
 *  - the change must persist for [consecutiveSamples] samples, rejecting the
 *    brief spikes HVAC systems and closing doors produce
 *  - while quiet, the baseline slowly tracks the current pressure
 *    ([baselineAlpha]), absorbing weather-front drift (~1 hPa/hour at worst)
 */
class PressureDeltaDetector(
    private val thresholdHpa: Float = 0.3f,
    private val consecutiveSamples: Int = 3,
    private val baselineAlpha: Float = 0.01f,
    private val smoothingAlpha: Float = 0.3f
) {
    private var baseline = Float.NaN
    private var smoothed = Float.NaN
    private var beyondCount = 0

    /** Feeds one pressure sample in hPa. Returns true on sustained vertical movement. */
    fun feed(pressureHpa: Float): Boolean {
        if (baseline.isNaN()) {
            baseline = pressureHpa
            smoothed = pressureHpa
            return false
        }
        smoothed += smoothingAlpha * (pressureHpa - smoothed)
        if (abs(smoothed - baseline) > thresholdHpa) {
            beyondCount++
            if (beyondCount >= consecutiveSamples) return true
        } else {
            beyondCount = 0
            baseline += baselineAlpha * (pressureHpa - baseline)
        }
        return false
    }

    fun reset() {
        baseline = Float.NaN
        smoothed = Float.NaN
        beyondCount = 0
    }
}
