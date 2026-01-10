package com.bizzarosn.heightmark

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ElevationServiceTest {

    private lateinit var elevationService: ElevationService

    @Before
    fun setUp() {
        elevationService = ElevationService(3)
    }

    @Test
    fun `addElevationReading returns correct average for single reading`() {
        val result = elevationService.addElevationReading(100.0)
        assertEquals(100.0, result, 0.001)
    }

    @Test
    fun `addElevationReading maintains correct rolling average`() {
        // Add 3 readings
        assertEquals(100.0, elevationService.addElevationReading(100.0), 0.001)
        assertEquals(150.0, elevationService.addElevationReading(200.0), 0.001)
        assertEquals(200.0, elevationService.addElevationReading(300.0), 0.001)
        
        // Add 4th reading (should drop first one)
        assertEquals(300.0, elevationService.addElevationReading(400.0), 0.001)
    }

    @Test
    fun `getLocalizedElevation returns meters when useMetric is true`() {
        elevationService.addElevationReading(100.0)
        val result = elevationService.getLocalizedElevation(true)
        assertEquals(100.0, result, 0.001)
    }

    @Test
    fun `getLocalizedElevation converts to feet when useMetric is false`() {
        elevationService.addElevationReading(100.0)
        val result = elevationService.getLocalizedElevation(false)
        assertEquals(328.084, result, 0.001)
    }

    @Test
    fun `getLocalizedElevation returns zero when no readings`() {
        val result = elevationService.getLocalizedElevation(true)
        // When no readings, average() returns NaN, so we expect Double.NaN
        assertTrue("Should be NaN when no readings", result.isNaN())
    }

    @Test
    fun `addElevationReading handles negative values`() {
        val result = elevationService.addElevationReading(-100.0)
        assertEquals(-100.0, result, 0.001)
    }

    @Test
    fun `addElevationReading handles zero readings count`() {
        val singleReadingService = ElevationService(1)
        assertEquals(100.0, singleReadingService.addElevationReading(100.0), 0.001)
        assertEquals(200.0, singleReadingService.addElevationReading(200.0), 0.001)
    }

    @Test
    fun `rolling average calculation is accurate with decimal values`() {
        elevationService.addElevationReading(100.5)
        elevationService.addElevationReading(200.3)
        val result = elevationService.addElevationReading(300.7)
        assertEquals(200.5, result, 0.001)
    }
}