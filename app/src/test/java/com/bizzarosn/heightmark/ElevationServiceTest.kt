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
        assertEquals(100.0, result.averageMeters, 0.001)
        assertEquals(1, result.readingCount)
    }

    @Test
    fun `addElevationReading maintains rolling average for readings within threshold`() {
        assertEquals(100.0, elevationService.addElevationReading(100.0).averageMeters, 0.001)
        assertEquals(101.0, elevationService.addElevationReading(102.0).averageMeters, 0.001)
        assertEquals(100.0, elevationService.addElevationReading(98.0).averageMeters, 0.001)

        // 4th reading drops the first one: (102 + 98 + 104) / 3
        assertEquals(101.333, elevationService.addElevationReading(104.0).averageMeters, 0.001)
    }

    @Test
    fun `snapshot averageMeters is NaN when no readings`() {
        assertTrue(elevationService.snapshot().averageMeters.isNaN())
        assertEquals(0, elevationService.snapshot().readingCount)
    }

    @Test
    fun `addElevationReading handles negative values`() {
        val result = elevationService.addElevationReading(-100.0)
        assertEquals(-100.0, result.averageMeters, 0.001)
    }

    @Test
    fun `single reading window updates within the jump threshold`() {
        val singleReadingService = ElevationService(1)
        assertEquals(100.0, singleReadingService.addElevationReading(100.0).averageMeters, 0.001)
        // 7 m step is inside the 8 m floor, so it replaces the reading directly
        assertEquals(107.0, singleReadingService.addElevationReading(107.0).averageMeters, 0.001)
    }

    @Test
    fun `readingCount tracks the rolling window size`() {
        assertEquals(0, elevationService.readingCount())
        elevationService.addElevationReading(100.0)
        assertEquals(1, elevationService.readingCount())
        repeat(5) { elevationService.addElevationReading(100.0) }
        // Capped at the window size (3)
        assertEquals(3, elevationService.readingCount())
    }

    @Test
    fun `rolling average calculation is accurate with decimal values`() {
        elevationService.addElevationReading(100.5)
        elevationService.addElevationReading(101.3)
        val result = elevationService.addElevationReading(99.7)
        assertEquals(100.5, result.averageMeters, 0.001)
    }

    // --- Jump detection ---

    @Test
    fun `single spike is excluded from the average`() {
        elevationService.addElevationReading(100.0)
        elevationService.addElevationReading(101.0)
        elevationService.addElevationReading(99.0)

        // 40 m above the average: an outlier, held out of the window
        val result = elevationService.addElevationReading(140.0)
        assertEquals(100.0, result.averageMeters, 0.001)
        assertEquals(3, result.readingCount)
    }

    @Test
    fun `pending outliers are discarded when readings return to the average`() {
        elevationService.addElevationReading(100.0)
        elevationService.addElevationReading(101.0)
        elevationService.addElevationReading(99.0)

        elevationService.addElevationReading(140.0)
        elevationService.addElevationReading(141.0)
        // Back in band: the spikes were noise, and this reading enters the window
        val result = elevationService.addElevationReading(100.0)
        assertEquals(100.0, result.averageMeters, 0.001)

        // Two more spikes still do not flush: the pending run restarted
        elevationService.addElevationReading(140.0)
        val after = elevationService.addElevationReading(141.0)
        assertEquals(100.0, after.averageMeters, 0.001)
    }

    @Test
    fun `three consecutive same-side outliers flush and re-anchor the average`() {
        elevationService.addElevationReading(100.0)
        elevationService.addElevationReading(101.0)
        elevationService.addElevationReading(99.0)

        elevationService.addElevationReading(150.0)
        elevationService.addElevationReading(151.0)
        val result = elevationService.addElevationReading(149.0)

        // Window re-seeded with the three confirming readings
        assertEquals(150.0, result.averageMeters, 0.001)
        assertEquals(3, result.readingCount)
    }

    @Test
    fun `jump flush resets progress on a larger window`() {
        val service = ElevationService(10)
        repeat(10) { service.addElevationReading(100.0 + it * 0.1) }
        assertEquals(1.0f, service.snapshot().progress, 0.001f)
        assertTrue(service.snapshot().settled)

        service.addElevationReading(150.0)
        service.addElevationReading(151.0)
        val result = service.addElevationReading(149.0)

        assertEquals(150.0, result.averageMeters, 0.001)
        assertEquals(0.3f, result.progress, 0.001f)
        assertFalse("Flush must un-latch settled", result.settled)
    }

    @Test
    fun `sign flip restarts the pending outlier run`() {
        elevationService.addElevationReading(100.0)
        elevationService.addElevationReading(101.0)
        elevationService.addElevationReading(99.0)

        elevationService.addElevationReading(140.0) // above
        elevationService.addElevationReading(60.0) // below: restart
        elevationService.addElevationReading(140.0) // above: restart
        val second = elevationService.addElevationReading(141.0)
        assertEquals("Two same-side outliers must not flush", 100.0, second.averageMeters, 0.001)

        val third = elevationService.addElevationReading(142.0)
        assertEquals(141.0, third.averageMeters, 0.001)
    }

    @Test
    fun `jump threshold scales with reported vertical accuracy`() {
        elevationService.addElevationReading(100.0, verticalAccuracyMeters = 10f)
        elevationService.addElevationReading(100.0, verticalAccuracyMeters = 10f)

        // 15 m step: within 2 x 10 m accuracy, accepted
        val accepted = elevationService.addElevationReading(115.0, verticalAccuracyMeters = 10f)
        assertEquals(105.0, accepted.averageMeters, 0.001)
    }

    @Test
    fun `jump threshold floor applies when accuracy is optimistic`() {
        elevationService.addElevationReading(100.0, verticalAccuracyMeters = 3f)
        elevationService.addElevationReading(100.0, verticalAccuracyMeters = 3f)

        // 15 m step: beyond max(2 x 3 m, 8 m) = 8 m, held out as an outlier
        val rejected = elevationService.addElevationReading(115.0, verticalAccuracyMeters = 3f)
        assertEquals(100.0, rejected.averageMeters, 0.001)
        assertEquals(2, rejected.readingCount)
    }

    // --- Settled latch and reset ---

    @Test
    fun `settled requires a full window`() {
        elevationService.addElevationReading(100.0)
        val result = elevationService.addElevationReading(100.0)
        assertFalse(result.settled)
        assertEquals(2f / 3f, result.progress, 0.001f)
    }

    @Test
    fun `settled latches when the full window is tight`() {
        elevationService.addElevationReading(100.0)
        elevationService.addElevationReading(101.0)
        val result = elevationService.addElevationReading(99.0)
        assertTrue(result.settled)
    }

    @Test
    fun `settled stays latched when spread widens within the jump threshold`() {
        elevationService.addElevationReading(100.0)
        elevationService.addElevationReading(101.0)
        assertTrue(elevationService.addElevationReading(99.0).settled)

        // In-band but noisy readings (threshold is 2 x 10 m accuracy = 20 m)
        elevationService.addElevationReading(115.0)
        val result = elevationService.addElevationReading(87.0)
        assertTrue("Spread creep must not demote a settled reading", result.settled)
    }

    @Test
    fun `settled is not latched when the full window is loose`() {
        val service = ElevationService(3)
        // In-band readings whose stddev (~8.3 m) exceeds max(4, 0.75 x 10 m)
        service.addElevationReading(100.0)
        service.addElevationReading(113.0)
        val result = service.addElevationReading(93.0)
        assertEquals(3, result.readingCount)
        assertFalse(result.settled)
    }

    @Test
    fun `reset clears the window and un-latches settled`() {
        elevationService.addElevationReading(100.0)
        elevationService.addElevationReading(101.0)
        assertTrue(elevationService.addElevationReading(99.0).settled)

        elevationService.reset()

        val snapshot = elevationService.snapshot()
        assertEquals(0, snapshot.readingCount)
        assertEquals(0f, snapshot.progress, 0.001f)
        assertFalse(snapshot.settled)
        assertTrue(snapshot.averageMeters.isNaN())
    }

    @Test
    fun `first reading after reset is accepted regardless of distance`() {
        elevationService.addElevationReading(100.0)
        elevationService.addElevationReading(101.0)
        elevationService.addElevationReading(99.0)

        elevationService.reset()

        val result = elevationService.addElevationReading(500.0)
        assertEquals(500.0, result.averageMeters, 0.001)
        assertEquals(1, result.readingCount)
    }
}
