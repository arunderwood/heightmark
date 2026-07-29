package com.bizzarosn.heightmark

import com.bizzarosn.heightmark.LocationPermissionPolicy.Dialog
import com.bizzarosn.heightmark.LocationPermissionPolicy.Resolution
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks in the permission decision table. The expectations mirror the two
 * historical when-chains in LocationPermissionHandler (checkPermission and
 * handlePermissionResult), which this policy replaced.
 */
class LocationPermissionPolicyTest {

    private fun resolve(
        fine: Boolean = false,
        any: Boolean = false,
        rationale: Boolean = false,
        isResult: Boolean
    ) = LocationPermissionPolicy.resolve(
        fineGranted = fine,
        anyGranted = any,
        shouldShowRationale = rationale,
        isRequestResult = isResult
    )

    @Test
    fun `fine grant wins on both paths with no dialog`() {
        for (isResult in listOf(false, true)) {
            // fineGranted implies anyGranted in practice; both shapes resolve the same
            for (any in listOf(false, true)) {
                assertEquals(
                    Resolution.Report(LocationPermissionState.Granted, null),
                    resolve(fine = true, any = any, isResult = isResult)
                )
            }
        }
    }

    @Test
    fun `coarse-only grant reports CoarseOnly with the upgrade dialog on both paths`() {
        for (isResult in listOf(false, true)) {
            // rationale must not matter once something is granted
            for (rationale in listOf(false, true)) {
                assertEquals(
                    Resolution.Report(LocationPermissionState.CoarseOnly, Dialog.PreciseUpgrade),
                    resolve(any = true, rationale = rationale, isResult = isResult)
                )
            }
        }
    }

    @Test
    fun `nothing granted with rationale reports RequiresRationale on both paths`() {
        for (isResult in listOf(false, true)) {
            assertEquals(
                Resolution.Report(
                    LocationPermissionState.RequiresRationale, Dialog.PermissionRequired
                ),
                resolve(rationale = true, isResult = isResult)
            )
        }
    }

    @Test
    fun `nothing granted and no rationale requests permissions on the check path`() {
        assertEquals(Resolution.RequestPermissions, resolve(isResult = false))
    }

    @Test
    fun `nothing granted and no rationale is a permanent denial on the result path`() {
        assertEquals(
            Resolution.Report(LocationPermissionState.PermanentlyDenied, Dialog.PermanentDenial),
            resolve(isResult = true)
        )
    }
}
