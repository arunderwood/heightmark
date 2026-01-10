package com.bizzarosn.heightmark

import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class LocationPermissionHandlerUnitTest {

    @Test
    fun `permission state objects are correctly typed`() {
        // Verify sealed class hierarchy and object equality
        val granted = LocationPermissionState.Granted
        val denied = LocationPermissionState.Denied
        val permanentlyDenied = LocationPermissionState.PermanentlyDenied
        val requiresRationale = LocationPermissionState.RequiresRationale

        // Verify all are instances of the sealed class
        assertTrue("Granted should be LocationPermissionState", granted is LocationPermissionState)
        assertTrue("Denied should be LocationPermissionState", denied is LocationPermissionState)
        assertTrue("PermanentlyDenied should be LocationPermissionState", permanentlyDenied is LocationPermissionState)
        assertTrue("RequiresRationale should be LocationPermissionState", requiresRationale is LocationPermissionState)

        // Verify different states are not equal
        assertNotEquals("Different states should not be equal", granted, denied)
        assertNotEquals("Different states should not be equal", denied, permanentlyDenied)
        assertNotEquals("Different states should not be equal", permanentlyDenied, requiresRationale)
    }

    @Test
    fun `permission state when expressions work`() {
        // Verify sealed class works with when statements
        val testState = LocationPermissionState.Denied
        val result = when (testState) {
            is LocationPermissionState.Granted -> "access granted"
            is LocationPermissionState.Denied -> "access denied"
            is LocationPermissionState.PermanentlyDenied -> "permanently denied"
            is LocationPermissionState.RequiresRationale -> "requires rationale"
        }

        assertEquals("When expression should work", "access denied", result)
    }

    @Test
    fun `permission state equals and hashCode work`() {
        // Verify object equality and hash codes
        val granted1 = LocationPermissionState.Granted
        val granted2 = LocationPermissionState.Granted

        // Same objects should be equal
        assertEquals("Same states should be equal", granted1, granted2)
        assertEquals("Same states should have same hashCode", granted1.hashCode(), granted2.hashCode())

        // Different objects should not be equal
        val denied = LocationPermissionState.Denied
        assertNotEquals("Different states should not be equal", granted1, denied)

        // Hash codes for equal objects should be equal
        assertEquals("Equal objects should have same hash code", granted1.hashCode(), granted2.hashCode())
    }
}