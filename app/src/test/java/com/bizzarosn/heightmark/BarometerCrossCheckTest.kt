package com.bizzarosn.heightmark

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarometerCrossCheckTest {

    private val check = BarometerCrossCheck()
    private var second = 0L

    /**
     * Feeds one fix a second for [seconds]. Each lambda gets the seconds into
     * this call and returns a height in meters. The barometer's
     * standard-atmosphere height defaults to the GNSS height.
     */
    private fun fixes(
        seconds: Int,
        sigmaMeters: Double = 5.0,
        gnss: (Int) -> Double,
        barometer: (Int) -> Double,
        standardAltitude: (Int) -> Double = gnss
    ) {
        for (s in 1..seconds) {
            check.onFix(
                atNanos = second++ * NANOS_PER_SECOND,
                gnssMeters = gnss(s),
                sigmaMeters = sigmaMeters,
                barometerMeters = barometer(s),
                standardAltitudeMeters = standardAltitude(s)
            )
        }
    }

    /** An airliner climbing at 9 m/s with its cabin climbing at 1.8 m/s. */
    private fun climbInCabin(seconds: Int) {
        fixes(seconds, gnss = { 100.0 + 9.0 * it }, barometer = { 1.8 * it }, standardAltitude = { 100.0 + 1.8 * it })
    }

    @Test
    fun `a climb the barometer agrees with keeps it trusted`() {
        // A mountain road: 1,400 m in 30 minutes, GNSS and barometer together
        fixes(1800, gnss = { 100.0 + 1_400.0 * it / 1800 }, barometer = { 1_400.0 * it / 1800 })

        assertTrue(check.isTrusted)
    }

    @Test
    fun `a cabin climbing a fifth as fast as the aircraft loses trust within seconds`() {
        climbInCabin(20)

        assertFalse(check.isTrusted)
    }

    @Test
    fun `a jump within the fixes' own error keeps trust`() {
        fixes(1, sigmaMeters = 50.0, gnss = { 100.0 }, barometer = { 0.0 })
        fixes(1, sigmaMeters = 50.0, gnss = { 250.0 }, barometer = { 0.0 })

        assertTrue("150 m is inside three sigmas of two 50 m fixes", check.isTrusted)
    }

    @Test
    fun `the same jump between tight fixes loses trust`() {
        fixes(1, gnss = { 100.0 }, barometer = { 0.0 })
        fixes(1, gnss = { 250.0 }, barometer = { 0.0 })

        assertFalse(check.isTrusted)
    }

    @Test
    fun `level cruise does not restore trust while the cabin is far below the aircraft`() {
        climbInCabin(1200)
        // Both heights hold still, but the cabin reads 2,260 m against 10,900 m
        fixes(1800, gnss = { 10_900.0 }, barometer = { 2_160.0 }, standardAltitude = { 2_260.0 })

        assertFalse(check.isTrusted)
    }

    @Test
    fun `agreement on the ground restores trust once the disagreement leaves the window`() {
        climbInCabin(60)
        fixes(60, gnss = { 640.0 }, barometer = { 108.0 }, standardAltitude = { 640.0 })
        assertFalse("the climb is still in the window", check.isTrusted)

        fixes(70, gnss = { 640.0 }, barometer = { 108.0 }, standardAltitude = { 640.0 })
        assertTrue(check.isTrusted)
    }

    @Test
    fun `two agreeing fixes seconds apart do not restore trust`() {
        climbInCabin(60)
        // A long GNSS outage empties the window
        second += 600
        fixes(10, gnss = { 640.0 }, barometer = { 108.0 }, standardAltitude = { 640.0 })

        assertFalse(check.isTrusted)
    }

    @Test
    fun `fixes before the barometer's first reading are not judged`() {
        check.onFix(0L, gnssMeters = 100.0, sigmaMeters = 5.0, barometerMeters = 0.0, standardAltitudeMeters = null)
        check.onFix(NANOS_PER_SECOND, gnssMeters = 900.0, sigmaMeters = 5.0, barometerMeters = 0.0, standardAltitudeMeters = null)

        assertTrue(check.isTrusted)
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
