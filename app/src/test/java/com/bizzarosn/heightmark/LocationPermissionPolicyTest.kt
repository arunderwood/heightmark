package com.bizzarosn.heightmark

import com.bizzarosn.heightmark.LocationPermissionPolicy.Dialog
import com.bizzarosn.heightmark.LocationPermissionPolicy.Resolution
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks in the permission decision table. The expectations mirror the two
 * historical when-chains in LocationPermissionHandler (checkPermission and
 * handlePermissionResult), which this policy replaced, plus the
 * [hasRequestedBefore] branch that tells a true first launch apart from a
 * returning, already-permanently-denied user.
 */
class LocationPermissionPolicyTest {

    private fun resolve(
        fine: Boolean = false,
        any: Boolean = false,
        rationale: Boolean = false,
        isResult: Boolean,
        hasRequestedBefore: Boolean = true
    ) = LocationPermissionPolicy.resolve(
        fineGranted = fine,
        anyGranted = any,
        shouldShowRationale = rationale,
        isRequestResult = isResult,
        hasRequestedBefore = hasRequestedBefore
    )

    @Test
    fun `fine grant wins on both paths with no dialog`() {
        for (isResult in listOf(false, true)) {
            // fineGranted implies anyGranted in practice; both shapes resolve the same
            for (any in listOf(false, true)) {
                // hasRequestedBefore must not matter once fine is granted
                for (hasRequestedBefore in listOf(false, true)) {
                    assertEquals(
                        Resolution.Report(LocationPermissionState.Granted, null),
                        resolve(
                            fine = true, any = any, isResult = isResult,
                            hasRequestedBefore = hasRequestedBefore
                        )
                    )
                }
            }
        }
    }

    @Test
    fun `coarse-only grant reports CoarseOnly with the upgrade dialog on both paths`() {
        for (isResult in listOf(false, true)) {
            // rationale and hasRequestedBefore must not matter once something is granted
            for (rationale in listOf(false, true)) {
                for (hasRequestedBefore in listOf(false, true)) {
                    assertEquals(
                        Resolution.Report(
                            LocationPermissionState.CoarseOnly, Dialog.PreciseUpgrade
                        ),
                        resolve(
                            any = true, rationale = rationale, isResult = isResult,
                            hasRequestedBefore = hasRequestedBefore
                        )
                    )
                }
            }
        }
    }

    @Test
    fun `nothing granted with rationale reports RequiresRationale on both paths`() {
        for (isResult in listOf(false, true)) {
            // hasRequestedBefore must not matter once a rationale is owed
            for (hasRequestedBefore in listOf(false, true)) {
                assertEquals(
                    Resolution.Report(
                        LocationPermissionState.RequiresRationale, Dialog.PermissionRequired
                    ),
                    resolve(
                        rationale = true, isResult = isResult,
                        hasRequestedBefore = hasRequestedBefore
                    )
                )
            }
        }
    }

    @Test
    fun `a request result always reports PermanentlyDenied regardless of hasRequestedBefore`() {
        for (hasRequestedBefore in listOf(false, true)) {
            assertEquals(
                Resolution.Report(
                    LocationPermissionState.PermanentlyDenied, Dialog.PermanentDenial
                ),
                resolve(isResult = true, hasRequestedBefore = hasRequestedBefore)
            )
        }
    }

    @Test
    fun `a true first launch reports NotYetRequested with no dialog on the check path`() {
        assertEquals(
            Resolution.Report(LocationPermissionState.NotYetRequested, null),
            resolve(isResult = false, hasRequestedBefore = false)
        )
    }

    @Test
    fun `a returning already-denied user requests permissions on the check path`() {
        assertEquals(
            Resolution.RequestPermissions,
            resolve(isResult = false, hasRequestedBefore = true)
        )
    }
}
