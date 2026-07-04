package com.bizzarosn.heightmark

import android.location.Location
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StillnessDetectorTest {

    private val detector = StillnessDetector(
        windowMs = 30_000L,
        maxSpeedMps = 0.5f,
        maxDriftMeters = 15f
    )

    private fun fix(atMs: Long, speed: Float = 0f, driftMeters: Float = 0f): Location {
        val location = mockk<Location>()
        every { location.elapsedRealtimeNanos } returns atMs * 1_000_000
        every { location.hasSpeed() } returns true
        every { location.speed } returns speed
        // distanceTo is only ever called with the window anchor as receiver;
        // model the anchor's drift parameter as its distance to everything
        every { location.distanceTo(any()) } returns driftMeters
        return location
    }

    @Test
    fun `not stationary until the full window is covered`() {
        assertFalse(detector.feed(fix(atMs = 0)))
        assertFalse(detector.feed(fix(atMs = 10_000)))
        assertFalse(detector.feed(fix(atMs = 20_000)))
        assertTrue(detector.feed(fix(atMs = 30_000)))
    }

    @Test
    fun `movement above speed threshold restarts the window`() {
        assertFalse(detector.feed(fix(atMs = 0)))
        assertFalse(detector.feed(fix(atMs = 15_000, speed = 2f)))
        // Window restarted: 30s must elapse from the next still fix
        assertFalse(detector.feed(fix(atMs = 20_000)))
        assertFalse(detector.feed(fix(atMs = 45_000)))
        assertTrue(detector.feed(fix(atMs = 50_000)))
    }

    @Test
    fun `position drift beyond threshold restarts the window`() {
        val anchor = fix(atMs = 0)
        every { anchor.distanceTo(any()) } returns 20f
        assertFalse(detector.feed(anchor))
        assertFalse(detector.feed(fix(atMs = 31_000)))
        // Drift detected relative to the anchor; window restarted
        assertFalse(detector.feed(fix(atMs = 40_000)))
        assertFalse(detector.feed(fix(atMs = 60_000)))
        assertTrue(detector.feed(fix(atMs = 70_000)))
    }

    @Test
    fun `reset clears accumulated stillness`() {
        assertFalse(detector.feed(fix(atMs = 0)))
        detector.reset()
        assertFalse(detector.feed(fix(atMs = 30_000)))
        assertFalse(detector.feed(fix(atMs = 59_000)))
        assertTrue(detector.feed(fix(atMs = 60_000)))
    }
}
