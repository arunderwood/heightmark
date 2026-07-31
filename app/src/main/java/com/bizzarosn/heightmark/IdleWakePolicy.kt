package com.bizzarosn.heightmark

import android.location.Location
import kotlin.math.abs

/**
 * Decides whether an opportunistic fix — passive-provider or fallback-poll —
 * proves the device moved while [IdleWakeMonitor] is idle.
 *
 * The passive provider relays every other app's fixes, un-coarsened once this
 * app holds fine location: a cell-tower or Wi-Fi fix routinely reports
 * hundreds of meters of "movement" that is really just its own error. Each
 * test below is gated by its own accuracy report — a fix with none, or one
 * worse than the threshold, cannot prove that axis moved at all — and a fix
 * that clears the gate must still beat the drift threshold *plus* its
 * accuracy radius, not the raw threshold alone.
 *
 * A false wake self-corrects once the GPS radio confirms nothing moved; a
 * missed wake only leaves the displayed elevation stale until the next
 * trigger. Tuned to lean toward the cheaper mistake — reject the fix, not
 * the movement.
 */
object IdleWakePolicy {

    /** True if [location] shows movement from [anchor] worth waking the GPS radio for. */
    fun shouldWake(anchor: Location, location: Location): Boolean =
        movedHorizontally(anchor, location) || movedVertically(anchor, location)

    private fun movedHorizontally(anchor: Location, location: Location): Boolean {
        val accuracy = location.horizontalAccuracyOrNull() ?: return false
        if (accuracy > MAX_FIX_HORIZONTAL_ACCURACY_M) return false
        return anchor.distanceTo(location) > MAX_IDLE_DRIFT_METERS + accuracy
    }

    private fun movedVertically(anchor: Location, location: Location): Boolean {
        if (!anchor.hasAltitude() || !location.hasAltitude()) return false
        val accuracy = location.verticalAccuracyOrNull() ?: return false
        if (accuracy > MAX_FIX_VERTICAL_ACCURACY_M) return false
        return abs(anchor.altitude - location.altitude) > MAX_IDLE_ALTITUDE_DRIFT_METERS
    }

    /** Horizontal drift beyond this, past the fix's own accuracy radius, counts as movement. */
    const val MAX_IDLE_DRIFT_METERS = 30f

    /** Altitude change beyond this counts as vertical movement. */
    const val MAX_IDLE_ALTITUDE_DRIFT_METERS = 10.0

    /** A horizontal accuracy worse than this (cell-tower fixes) can't reliably clear the drift threshold. */
    const val MAX_FIX_HORIZONTAL_ACCURACY_M = 50f

    /** A vertical accuracy worse than this leaves too little margin under the altitude-drift threshold. */
    const val MAX_FIX_VERTICAL_ACCURACY_M = 5.0
}
