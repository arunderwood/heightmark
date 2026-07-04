package com.bizzarosn.heightmark

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PressureDeltaDetectorTest {

    private val detector = PressureDeltaDetector()

    @Test
    fun `steady pressure never triggers`() {
        repeat(100) {
            assertFalse(detector.feed(1013.25f))
        }
    }

    @Test
    fun `sustained pressure drop triggers`() {
        // ~4m of ascent: pressure drops 0.5 hPa and stays there
        detector.feed(1000.0f)
        var triggered = false
        repeat(10) {
            if (detector.feed(999.5f)) triggered = true
        }
        assertTrue("Elevator-scale sustained change should wake", triggered)
    }

    @Test
    fun `brief spike from a closing door is rejected`() {
        detector.feed(1000.0f)
        // Single 2 hPa transient, then pressure recovers
        assertFalse(detector.feed(998.0f))
        var triggered = false
        repeat(50) {
            if (detector.feed(1000.0f)) triggered = true
        }
        assertFalse("Momentary spike must not wake", triggered)
    }

    @Test
    fun `weather-front drift is absorbed by the baseline`() {
        // 1 hPa/hour at 1 Hz sampling — the fastest realistic weather change
        var pressure = 1010.0f
        repeat(3600) {
            assertFalse("Weather drift must not wake", detector.feed(pressure))
            pressure -= 1f / 3600f
        }
    }

    @Test
    fun `reset requires a new baseline`() {
        detector.feed(1000.0f)
        repeat(3) { detector.feed(999.5f) }
        detector.reset()
        // First sample after reset only seeds the baseline
        assertFalse(detector.feed(950.0f))
        assertFalse(detector.feed(950.0f))
    }
}
