package com.bizzarosn.heightmark

import kotlin.math.sqrt

/**
 * What the current elevation reading is worth, derived from [ElevationSession]'s
 * tracking flags and the averaging window. Reaches the screen through
 * [ElevationUiState], driving [StabilityLineView] and the hero number's opacity.
 */
sealed interface ReadingState {
    /** No fix yet this session: still searching for a GPS signal. */
    data object Acquiring : ReadingState

    /** Averaging toward a settled value; [progress] is the window fill (0..1). */
    data class Converging(val progress: Float) : ReadingState {
        /**
         * What [progress] should look like on screen. A window sized for
         * minutes-scale GNSS bias decorrelation takes tens of seconds to
         * fill, but visual confidence should build faster than that: a
         * square-root curve front-loads the ramp so early readings already
         * read as trustworthy, while the literal fill fraction still governs
         * the settle latch.
         */
        val visualProgress: Float get() = sqrt(progress.coerceIn(0f, 1f))
    }

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

        /**
         * Opacity for the hero number in [state]. Converging ramps from dimmed
         * to full as the window fills, so the number visibly firms up.
         *
         * Exhaustive so a new [ReadingState] is a compile error here, not a
         * silently full-opacity fallthrough.
         */
        fun heroAlpha(state: ReadingState): Float = when (state) {
            Dormant -> DIMMED_TEXT_ALPHA
            is Converging -> DIMMED_TEXT_ALPHA + (1f - DIMMED_TEXT_ALPHA) * state.visualProgress
            Acquiring, Stable -> 1f
        }

        // 0.7 keeps the dimmed (large-text) hero >= 3:1 over the scrim floor
        // in day mode; verified by ScrimContrastTest, which references this
        // constant directly.
        internal const val DIMMED_TEXT_ALPHA = 0.7f
    }
}
