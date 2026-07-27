package com.bizzarosn.heightmark

/**
 * What the current elevation reading is worth, derived from the fragment's
 * tracking flags and the averaging window. Drives [StabilityLineView] and the
 * hero number's opacity.
 */
sealed interface ReadingState {
    /** No fix yet this session: still searching for a GPS signal. */
    data object Acquiring : ReadingState

    /** Averaging toward a settled value; [progress] is the window fill (0..1). */
    data class Converging(val progress: Float) : ReadingState

    /** Window full and tight: the displayed value can be trusted. */
    data object Stable : ReadingState

    /** GPS is off (stationary duty-cycle) or the reading predates a gap. */
    data object Dormant : ReadingState

    companion object {
        fun derive(
            hasFixEver: Boolean,
            isIdle: Boolean,
            awaitingFreshFix: Boolean,
            snapshot: ElevationService.Snapshot
        ): ReadingState = when {
            !hasFixEver -> Acquiring
            isIdle || awaitingFreshFix -> Dormant
            snapshot.settled -> Stable
            else -> Converging(snapshot.progress)
        }
    }
}
