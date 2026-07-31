package com.bizzarosn.heightmark

import android.location.Location
import androidx.annotation.StringRes

/**
 * Everything a host needs to draw the screen, derived in one place so the hero
 * number, the settling line, and the location-services prompt can never
 * disagree about what the app is currently doing.
 *
 * Following [ReadingState], the derivation is a pure function rather than a
 * sequence of view calls: [ElevationTracker] owns the Android plumbing that
 * produces the inputs, and hosts resolve the resource IDs it hands back.
 */
data class ElevationUiState(
    val hero: Hero,
    /**
     * Null while blocked — an explanatory message and a kinetic signal widget
     * alongside it would contradict each other, so hosts hide the line.
     */
    val readingState: ReadingState?,
    /**
     * Set only for [Blocked.LocationServicesOff], the one block a user can fix,
     * and only until they have answered it. It is a question being asked, not a
     * property of being blocked: a host renders it as a dialog, and a dialog the
     * user has already closed must not come back on its own.
     */
    val promptLocationSettings: Boolean,
    /** Null unless blocked — the persistent action button hosts should offer. */
    val blockedAction: BlockedAction?,
    /** Null while the diagnostic panel is closed; nothing feeds it then. */
    val details: DetailsFacts?
) {

    /** The hero number's two mutually exclusive configurations. */
    sealed interface Hero {
        /** Explanatory text: still searching, or a reason there is nothing to show. */
        data class Status(@param:StringRes val messageRes: Int) : Hero

        /**
         * A real reading, in meters, for the host to format and label. The
         * [datum] travels with it: a height above the ellipsoid is not the
         * sea-level elevation this screen otherwise promises, and the label
         * over the number has to say which one it is.
         */
        data class Value(val meters: Double, val datum: ElevationDatum) : Hero
    }

    /** The corrective action a [Blocked] state's persistent button performs. */
    enum class BlockedAction(@param:StringRes val labelRes: Int) {
        RequestPermission(R.string.grant_permission),
        OpenAppSettings(R.string.open_settings),
        OpenLocationSettings(R.string.open_location_settings)
    }

    /**
     * Why tracking cannot run. Each replaces the reading with a sentence-style
     * [messageRes] and offers a persistent [action] — the dialogs that first
     * surface these reasons are dismissible, so this is the only recovery path
     * guaranteed to still be reachable later in the session.
     */
    enum class Blocked(@param:StringRes val messageRes: Int, val action: BlockedAction) {
        PermissionRequired(
            R.string.location_permission_blocked_message, BlockedAction.RequestPermission
        ),
        PermissionPermanentlyDenied(
            R.string.location_permission_denied_blocked_message, BlockedAction.OpenAppSettings
        ),
        PreciseLocationRequired(
            R.string.precise_location_blocked_message, BlockedAction.RequestPermission
        ),
        LocationServicesOff(
            R.string.location_services_off_blocked_message, BlockedAction.OpenLocationSettings
        )
    }

    /**
     * The panel inputs the tracker owns; hosts add the presentation-only rest
     * — units and locale — to build a [DetailsPanelPresenter.Input].
     *
     * [nowElapsedRealtimeNanos] is stamped when the state is built rather than
     * when it is drawn: it is what makes a bare ticker update a distinct state,
     * so the fix age keeps moving instead of being deduplicated away.
     */
    data class DetailsFacts(
        val isIdle: Boolean,
        val isBlocked: Boolean,
        /** The fix-age watchdog has gone off; see [ElevationSession.signalStale]. */
        val signalStale: Boolean,
        val location: Location?,
        val nowElapsedRealtimeNanos: Long,
        val satellitesUsed: Int,
        val satellitesVisible: Int,
        val pressureHpa: Float?,
        val readingCount: Int,
        /** The datum the window is averaging on; null before the first fix. */
        val datum: ElevationDatum?
    )

    companion object {
        /**
         * A block outranks everything: its message replaces even a good reading,
         * because that reading is no longer being kept current. Otherwise the
         * last committed elevation wins, and the search text only appears while
         * there has never been one.
         *
         * [locationPromptAnswered] silences the settings prompt for as long as
         * this occurrence of the block lasts. Without it the ask would ride on
         * every emission, and the diagnostic panel's ticker alone republishes
         * once a second — enough to put a dismissed dialog straight back up.
         * The hero still carries the block's message either way, so silencing
         * the ask never hides why tracking stopped.
         */
        fun derive(
            blocked: Blocked?,
            locationPromptAnswered: Boolean,
            searchTimedOut: Boolean,
            elevation: Elevation?,
            readingState: ReadingState,
            details: DetailsFacts?
        ): ElevationUiState = if (blocked != null) {
            ElevationUiState(
                hero = Hero.Status(blocked.messageRes),
                readingState = null,
                promptLocationSettings =
                    blocked == Blocked.LocationServicesOff && !locationPromptAnswered,
                blockedAction = blocked.action,
                details = details
            )
        } else {
            ElevationUiState(
                hero = elevation?.let { Hero.Value(it.meters, it.datum) } ?: Hero.Status(
                    if (searchTimedOut) R.string.still_searching else R.string.loading_elevation
                ),
                readingState = readingState,
                promptLocationSettings = false,
                blockedAction = null,
                details = details
            )
        }
    }
}
