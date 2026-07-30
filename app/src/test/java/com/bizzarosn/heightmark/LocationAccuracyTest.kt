package com.bizzarosn.heightmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocationAccuracyTest {

    @Test
    fun `vertical accuracy is null when the fix does not report one`() {
        val location = TestLocations.detailsFix(verticalAccuracy = null)

        assertNull(location.verticalAccuracyOrNull())
    }

    @Test
    fun `vertical accuracy is returned when the fix reports one`() {
        val location = TestLocations.detailsFix(verticalAccuracy = 3.5f)

        assertEquals(3.5f, location.verticalAccuracyOrNull()!!, 0.001f)
    }

    @Test
    fun `horizontal accuracy is null when the fix does not report one`() {
        val location = TestLocations.detailsFix(horizontalAccuracy = null)

        assertNull(location.horizontalAccuracyOrNull())
    }

    @Test
    fun `horizontal accuracy is returned when the fix reports one`() {
        val location = TestLocations.detailsFix(horizontalAccuracy = 12f)

        assertEquals(12f, location.horizontalAccuracyOrNull()!!, 0.001f)
    }

    @Test
    fun `a reported zero accuracy is distinct from an absent one`() {
        val reported = TestLocations.detailsFix(verticalAccuracy = 0f)

        assertEquals(0f, reported.verticalAccuracyOrNull()!!, 0.001f)
    }
}
