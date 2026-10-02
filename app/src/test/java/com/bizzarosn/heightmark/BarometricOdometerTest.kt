package com.bizzarosn.heightmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class BarometricOdometerTest {

    private val odometer = BarometricOdometer()
    private var second = 0L
    private var height = 0.0

    /** Feeds the pressure for the current [height] once a second for [seconds]. */
    private fun hold(seconds: Int) {
        repeat(seconds) { odometer.feed(pressureAt(height).toFloat(), second++ * NANOS_PER_SECOND) }
    }

    /** Moves [height] by [meters] at [metersPerSecond], one sample a second. */
    private fun climb(meters: Double, metersPerSecond: Double = 0.3) {
        val steps = (kotlin.math.abs(meters) / metersPerSecond).toInt()
        repeat(steps) {
            height += meters / steps
            hold(1)
        }
    }

    @Test
    fun `standard altitude is zero at standard pressure and about 8 m per hPa near sea level`() {
        assertEquals(0.0, BarometricOdometer.standardAltitude(1013.25), 1e-9)
        assertEquals(8.3, BarometricOdometer.standardAltitude(1012.25), 0.1)
    }

    @Test
    fun `nothing is tracked before the first full second`() {
        odometer.feed(1000f, 0L)

        assertFalse(odometer.isTracking)
    }

    @Test
    fun `a climb counts in full, including the part before it was detected`() {
        hold(31)
        climb(3.0)

        assertTrue(odometer.isMoving)
        hold(40)
        assertEquals(3.0, odometer.motionMeters, 0.01)
        assertFalse(odometer.isMoving)
    }

    @Test
    fun `a descent counts negative`() {
        hold(31)
        climb(-6.0)
        hold(40)

        assertEquals(-6.0, odometer.motionMeters, 0.01)
    }

    @Test
    fun `two climbs with a still spell between them are not double counted`() {
        hold(31)
        climb(3.0)
        hold(40)
        climb(3.0)
        hold(40)

        assertEquals(6.0, odometer.motionMeters, 0.01)
    }

    @Test
    fun `weather drift while still is not motion and adds no variance`() {
        hold(31)
        val start = pressureAt(0.0)
        // A fast-falling front, 3 hPa an hour, about 25 m of apparent climb
        repeat(3600) { odometer.feed((start - 3.0 * it / 3600).toFloat(), second++ * NANOS_PER_SECOND) }

        assertEquals(0.0, odometer.motionMeters, 1e-9)
        assertEquals(0.0, odometer.varianceMeters2, 1e-9)
        assertFalse(odometer.isMoving)
    }

    @Test
    fun `moving accrues weather variance by the second`() {
        hold(31)
        climb(3.0)
        val before = odometer.varianceMeters2
        climb(3.0)

        // Ten more seconds of moving
        assertEquals(10 * 0.018, odometer.varianceMeters2 - before, 1e-9)
    }

    @Test
    fun `a gap in samples counts its pressure change as motion with the gap's variance`() {
        hold(6)
        height = 5.0
        second += 94
        hold(2)

        assertEquals(5.0, odometer.motionMeters, 0.01)
        assertEquals(0.018 * 95, odometer.varianceMeters2, 1e-9)
    }

    @Test
    fun `samples delivered faster than once a second are averaged, not stepped one by one`() {
        hold(31)
        val base = pressureAt(0.0)
        // 15 samples a second, alternating 0.2 hPa (about 1.7 m) either side
        repeat(60) { s ->
            repeat(16) { i ->
                val hpa = base + if (i % 2 == 0) 0.2 else -0.2
                odometer.feed(hpa.toFloat(), (second + s) * NANOS_PER_SECOND + i * 60_000_000L)
            }
        }

        assertEquals(0.0, odometer.motionMeters, 0.01)
        assertFalse(odometer.isMoving)
    }

    private fun pressureAt(heightMeters: Double): Double =
        1013.25 * (1 - heightMeters / 44_330.0).pow(5.255)

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
