package com.bizzarosn.heightmark

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitConverterTest {

    @Test
    fun `metersToFeet converts using the international foot`() {
        assertEquals(328.084, UnitConverter.metersToFeet(100.0), 0.001)
    }

    @Test
    fun `metersToFeet returns zero for zero meters`() {
        assertEquals(0.0, UnitConverter.metersToFeet(0.0), 0.001)
    }

    @Test
    fun `metersToFeet handles negative elevations`() {
        assertEquals(-32.8084, UnitConverter.metersToFeet(-10.0), 0.001)
    }
}
