package com.bizzarosn.heightmark

import com.bizzarosn.heightmark.TestLocations.movingFix
import io.mockk.every
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StillnessDetectorTest {

    private val detector = StillnessDetector(
        windowMs = 30_000L,
        maxSpeedMps = 0.5f,
        maxDriftMeters = 15f
    )

    @Test
    fun `not stationary until the full window is covered`() {
        assertFalse(detector.feed(movingFix(atMs = 0)))
        assertFalse(detector.feed(movingFix(atMs = 10_000)))
        assertFalse(detector.feed(movingFix(atMs = 20_000)))
        assertTrue(detector.feed(movingFix(atMs = 30_000)))
    }

    @Test
    fun `movement above speed threshold restarts the window`() {
        assertFalse(detector.feed(movingFix(atMs = 0)))
        assertFalse(detector.feed(movingFix(atMs = 15_000, speed = 2f)))
        // Window restarted: 30s must elapse from the next still fix
        assertFalse(detector.feed(movingFix(atMs = 20_000)))
        assertFalse(detector.feed(movingFix(atMs = 45_000)))
        assertTrue(detector.feed(movingFix(atMs = 50_000)))
    }

    @Test
    fun `position drift beyond threshold restarts the window`() {
        val anchor = movingFix(atMs = 0)
        every { anchor.distanceTo(any()) } returns 20f
        assertFalse(detector.feed(anchor))
        assertFalse(detector.feed(movingFix(atMs = 31_000)))
        // Drift detected relative to the anchor; window restarted
        assertFalse(detector.feed(movingFix(atMs = 40_000)))
        assertFalse(detector.feed(movingFix(atMs = 60_000)))
        assertTrue(detector.feed(movingFix(atMs = 70_000)))
    }

    @Test
    fun `reset clears accumulated stillness`() {
        assertFalse(detector.feed(movingFix(atMs = 0)))
        detector.reset()
        assertFalse(detector.feed(movingFix(atMs = 30_000)))
        assertFalse(detector.feed(movingFix(atMs = 59_000)))
        assertTrue(detector.feed(movingFix(atMs = 60_000)))
    }
}
