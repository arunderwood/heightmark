package com.bizzarosn.heightmark

import android.location.Location

/**
 * Decides from a stream of GNSS fixes whether the device has been stationary
 * long enough to switch the GPS radio off.
 *
 * Stationary means: every fix in the last [windowMs] reports a speed below
 * [maxSpeedMps] and stays within [maxDriftMeters] of the window's first fix.
 * GNSS jitter makes exact-position checks useless, hence the drift allowance.
 */
class StillnessDetector(
    private val windowMs: Long = 30_000L,
    private val maxSpeedMps: Float = 0.5f,
    private val maxDriftMeters: Float = 15f
) {
    private val window = ArrayDeque<Location>()

    /**
     * Feeds the next fix. Returns true once the device has been still for the
     * full window; the caller is expected to stop feeding after that.
     */
    fun feed(location: Location): Boolean {
        if (location.hasSpeed() && location.speed > maxSpeedMps) {
            window.clear()
            return false
        }
        window.addLast(location)

        // Bound memory if the caller keeps feeding while already stationary
        while (ageMs(window.first(), location) > 2 * windowMs) {
            window.removeFirst()
        }

        val anchor = window.first()
        if (ageMs(anchor, location) < windowMs) return false

        if (window.any { anchor.distanceTo(it) > maxDriftMeters }) {
            // Slow drift (e.g. walking pace below the speed threshold): restart
            window.clear()
            return false
        }
        return true
    }

    fun reset() {
        window.clear()
    }

    private fun ageMs(older: Location, newer: Location): Long {
        return (newer.elapsedRealtimeNanos - older.elapsedRealtimeNanos) / 1_000_000
    }
}
