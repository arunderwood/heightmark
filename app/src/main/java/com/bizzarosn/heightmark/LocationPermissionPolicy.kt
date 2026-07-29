package com.bizzarosn.heightmark

/**
 * Pure decision table for the location-permission flow, shared by the
 * initial check and the request-result callback in
 * [LocationPermissionHandler]. The two paths differ only in their fallback:
 * an initial check can still launch the system request, while a denied
 * request result means the system dialog can no longer be shown.
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
        isRequestResult: Boolean
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

        else -> Resolution.RequestPermissions
    }
}
