package com.bizzarosn.heightmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.roundToLong

class PressureDeltaDetectorTest {

    /**
     * Samples [pressureAt] (hPa at t seconds) at [rateHz] for [seconds] into
     * [detector]. Returns the time in seconds of the first wake, or null.
     */
    private fun wakeTime(
        rateHz: Double,
        seconds: Int,
        detector: PressureDeltaDetector = PressureDeltaDetector(),
        pressureAt: (Double) -> Double
    ): Double? {
        val samples = (rateHz * seconds).toInt()
        for (i in 0 until samples) {
            val t = i / rateHz
            val atNanos = START_NANOS + (t * NANOS_PER_SECOND).roundToLong()
            if (detector.feed(pressureAt(t).toFloat(), atNanos)) return t
        }
        return null
    }

    /** About 4 m of ascent at t = 10 s: pressure drops 0.5 hPa and stays there. */
    private val stepChange: (Double) -> Double = { t -> if (t < 10.0) 1000.0 else 999.5 }

    /** A 2 hPa pressure transient lasting [lengthS], starting at t = 10 s + [phaseS]. */
    private fun transient(lengthS: Double, phaseS: Double = 0.0): (Double) -> Double = { t ->
        if (t >= 10.0 + phaseS && t < 10.0 + phaseS + lengthS) 998.0 else 1000.0
    }

    /** About 5 m of ascent: 0.6 hPa spread evenly over 60 s, then held. */
    private val slowRamp: (Double) -> Double = { t ->
        1000.0 - 0.6 * ((t - 10.0) / 60.0).coerceIn(0.0, 1.0)
    }

    /** 1 hPa/hour, the fastest realistic weather change. */
    private val weatherFront: (Double) -> Double = { t -> 1010.0 - t / 3600.0 }

    @Test
    fun `steady pressure never wakes`() {
        for (rate in RATES_HZ) {
            assertNull("at $rate Hz", wakeTime(rate, 300) { 1013.25 })
        }
    }

    @Test
    fun `a sustained step change wakes within a few seconds`() {
        for (rate in RATES_HZ) {
            val woke = wakeTime(rate, 60, pressureAt = stepChange)
            assertNotNull("at $rate Hz", woke)
            // The smoothed change takes ~2 s to cross, then must hold for 3 s
            assertEquals("at $rate Hz: seconds after the step", 5.0, woke!! - 10.0, 1.0)
        }
    }

    @Test
    fun `the same change wakes at the same time at any delivery rate`() {
        val scenarios = mapOf(
            "step change" to stepChange,
            "slow ramp" to slowRamp,
            "transient" to transient(lengthS = 0.5)
        )
        for ((name, pressureAt) in scenarios) {
            val atOneHz = wakeTime(1.0, 120, pressureAt = pressureAt)
            for (rate in RATES_HZ) {
                val atRate = wakeTime(rate, 120, pressureAt = pressureAt)
                assertEquals("$name at $rate Hz: verdict", atOneHz != null, atRate != null)
                if (atOneHz != null && atRate != null) {
                    assertEquals("$name at $rate Hz: wake time", atOneHz, atRate, 1.0)
                }
            }
        }
    }

    @Test
    fun `a half-second transient at 15 Hz does not wake`() {
        for (phase in listOf(0.0, 0.25, 0.5, 0.75)) {
            assertNull(
                "transient starting $phase s into a second",
                wakeTime(15.0, 60, pressureAt = transient(lengthS = 0.5, phaseS = phase))
            )
        }
    }

    @Test
    fun `a single-sample transient at 1 Hz does not wake`() {
        assertNull(wakeTime(1.0, 60, pressureAt = transient(lengthS = 1.0)))
    }

    @Test
    fun `a slow sustained ramp at 15 Hz wakes`() {
        val woke = wakeTime(15.0, 120, pressureAt = slowRamp)
        assertNotNull("5 m over a minute must outrun the baseline", woke)
    }

    @Test
    fun `weather-front drift is absorbed by the baseline`() {
        for (rate in listOf(1.0, 15.0)) {
            assertNull("at $rate Hz", wakeTime(rate, 3600, pressureAt = weatherFront))
        }
    }

    @Test
    fun `reset requires a new baseline`() {
        val detector = PressureDeltaDetector()
        wakeTime(15.0, 12, detector, stepChange)
        detector.reset()
        // After reset, 950 hPa is the new baseline, not a 50 hPa change
        assertNull(wakeTime(15.0, 30, detector) { 950.0 })
    }

    companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L

        /** Sensor timestamps are elapsed-realtime, so they start at an arbitrary offset. */
        const val START_NANOS = 123_456_789_012L

        /** 1 Hz is the requested rate and 15 Hz the rate a Pixel 8 Pro delivers. */
        val RATES_HZ = listOf(1.0, 5.0, 15.0, 50.0)
    }
}
