package com.bizzarosn.heightmark

import io.mockk.every
import io.mockk.mockk
import android.location.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocationAccuracyTest {

    @Test
    fun `vertical accuracy is null when the fix does not report one`() {
        val location = mockk<Location>()
        every { location.hasVerticalAccuracy() } returns false

        assertNull(location.verticalAccuracyOrNull())
    }

    @Test
    fun `vertical accuracy is returned when the fix reports one`() {
        val location = mockk<Location>()
        every { location.hasVerticalAccuracy() } returns true
        every { location.verticalAccuracyMeters } returns 3.5f

        assertEquals(3.5f, location.verticalAccuracyOrNull()!!, 0.001f)
    }

    @Test
    fun `horizontal accuracy is null when the fix does not report one`() {
        val location = mockk<Location>()
        every { location.hasAccuracy() } returns false

        assertNull(location.horizontalAccuracyOrNull())
    }

    @Test
    fun `horizontal accuracy is returned when the fix reports one`() {
        val location = mockk<Location>()
        every { location.hasAccuracy() } returns true
        every { location.accuracy } returns 12f

        assertEquals(12f, location.horizontalAccuracyOrNull()!!, 0.001f)
    }

    @Test
    fun `a reported zero accuracy is distinct from an absent one`() {
        val reported = mockk<Location>()
        every { reported.hasVerticalAccuracy() } returns true
        every { reported.verticalAccuracyMeters } returns 0f

        assertEquals(0f, reported.verticalAccuracyOrNull()!!, 0.001f)
    }
}
