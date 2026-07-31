package com.bizzarosn.heightmark

/**
 * Pure decision table for the location-permission flow, shared by the
 * initial check and the request-result callback in
 * [LocationPermissionHandler]. The two paths differ only in their fallback:
 * an initial check can still launch the system request, while a denied
 * request result means the system dialog can no longer be shown.
 *
 * [hasRequestedBefore] disambiguates the one case Android's own APIs can't:
 * `shouldShowRequestPermissionRationale` returning false means either "never
 * asked" or "permanently denied", and only the caller's own persisted record
 * of ever having launched the system dialog tells those apart. A true first
 * launch lands on the blocked screen's own explanation instead of firing the
 * system dialog with no context; a returning, already-permanently-denied
 * user still gets the old auto-fire fallback, which resolves back to
 * [LocationPermissionState.PermanentlyDenied] once the system silently
 * re-denies it.
 */
internal object LocationPermissionPolicy {

    enum class Dialog { PreciseUpgrade, PermissionRequired, PermanentDenial }

    sealed interface Resolution {
        /** Report [state] to the owner, optionally backed by [dialog]. */
        data class Report(val state: LocationPermissionState, val dialog: Dialog?) : Resolution

        /** No decision yet: launch the system permission request. */
        data object RequestPermissions : Resolution
    }

    fun resolve(
        fineGranted: Boolean,
        anyGranted: Boolean,
        shouldShowRationale: Boolean,
        isRequestResult: Boolean,
        hasRequestedBefore: Boolean
    ): Resolution = when {
        fineGranted -> Resolution.Report(LocationPermissionState.Granted, null)

        // Approximate-only grant: GPS (and therefore elevation) needs precise
        anyGranted -> Resolution.Report(
            LocationPermissionState.CoarseOnly, Dialog.PreciseUpgrade
        )

        shouldShowRationale -> Resolution.Report(
            LocationPermissionState.RequiresRationale, Dialog.PermissionRequired
        )

        isRequestResult -> Resolution.Report(
            LocationPermissionState.PermanentlyDenied, Dialog.PermanentDenial
        )

        // Nothing granted, no rationale owed, and not a request result: only
        // reachable by a plain check. Without a prior request this is a true
        // first launch — explain in place and let the user trigger the
        // system dialog themselves, per Android's contextual-priming guidance.
        !hasRequestedBefore -> Resolution.Report(LocationPermissionState.NotYetRequested, null)

        else -> Resolution.RequestPermissions
    }
}
