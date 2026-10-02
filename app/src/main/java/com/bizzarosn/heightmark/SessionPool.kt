package com.bizzarosn.heightmark

import kotlin.math.max

/** A height in meters with the variance, in m², of its error. */
data class HeightEstimate(val meters: Double, val variance: Double)

/**
 * Pools GNSS sessions into one estimate of a height that has not changed.
 *
 * A phone's GNSS height error stays correlated for minutes, so the 30 fixes
 * of one [ElevationService] window are worth about one independent sample.
 * Each session therefore settles on its own bias, about 5 m RMS on phones.
 * Averaging fixes cannot remove that bias. Averaging sessions can, by
 * roughly 1/√(sessions), so each session enters here as a single
 * measurement, weighted against everything pooled before it. This is a
 * one-state Kalman update: the pooled estimate's variance shrinks with every
 * session, and later sessions move the number less and less, down to
 * [POOL_VARIANCE_FLOOR_M2].
 *
 * A session that disagrees by more than [GATE_SIGMA] combined standard
 * deviations is not this height. Over at least [MIN_REFUTING_READINGS]
 * readings it refutes the pool, and the session replaces it. With fewer, it
 * is set aside until more arrive.
 */
object SessionPool {

    /** What one session's readings say about the pooled height. */
    sealed interface Verdict {
        /** The session agrees; [estimate] is the pool with it folded in. */
        data class Pooled(val estimate: HeightEstimate) : Verdict

        /** The session is somewhere else; it replaces the pool. */
        data object Refuted : Verdict

        /** The session disagrees, on too few readings to overturn the pool yet. */
        data object Undecided : Verdict
    }

    /**
     * The measurement one session's window makes. The window's accuracy is
     * floored at [SESSION_SIGMA_FLOOR_M]: a phone's reported vertical accuracy
     * is a 68% figure that understates real error, and settled sessions at one
     * spot scatter by about that much whatever they report.
     */
    fun measurement(window: ElevationService.Snapshot): HeightEstimate {
        val sigma = SESSION_ERROR_FRACTION * max(window.accuracyMeters, SESSION_SIGMA_FLOOR_M)
        return HeightEstimate(window.averageMeters, sigma * sigma)
    }

    fun weigh(pool: HeightEstimate, session: HeightEstimate, sessionReadings: Int): Verdict {
        val innovation = session.meters - pool.meters
        val spread = pool.variance + session.variance
        if (innovation * innovation > GATE_SIGMA * GATE_SIGMA * spread) {
            return if (sessionReadings >= MIN_REFUTING_READINGS) Verdict.Refuted else Verdict.Undecided
        }
        val gain = pool.variance / spread
        return Verdict.Pooled(
            HeightEstimate(
                pool.meters + gain * innovation,
                max((1 - gain) * pool.variance, POOL_VARIANCE_FLOOR_M2)
            )
        )
    }

    /** Phone vertical RMS in open sky runs 4.7–6.4 m; one session cannot claim better. */
    const val SESSION_SIGMA_FLOOR_M = 5.0

    /**
     * How much of one fix's error survives averaging a 30 s window, given
     * errors correlated over about 240 s: 1/√(1 + 30/240).
     */
    const val SESSION_ERROR_FRACTION = 0.95

    /**
     * The pool never claims better than ±2 m. Handset and site bias do not
     * pool away below that, and a pool that claimed less would give every
     * later session almost no weight, so an offset the barometer got wrong
     * would never be corrected. At this floor a session keeps about 15% of
     * the weight, and such an offset fades within about six sessions.
     */
    const val POOL_VARIANCE_FLOOR_M2 = 4.0

    const val GATE_SIGMA = 3.0

    const val MIN_REFUTING_READINGS = ElevationService.JUMP_CONFIRM_COUNT
}
