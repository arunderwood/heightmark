package com.bizzarosn.heightmark

import android.location.Location
import io.mockk.every
import io.mockk.mockk

/** Shared mockk [Location] factories for JVM unit tests. */
object TestLocations {

    /** A GNSS fix for stillness tests: timestamp, speed, and drift from anchor. */
    fun movingFix(atMs: Long, speed: Float = 0f, driftMeters: Float = 0f): Location {
        val location = mockk<Location>()
        every { location.elapsedRealtimeNanos } returns atMs * 1_000_000
        every { location.hasSpeed() } returns true
        every { location.speed } returns speed
        // distanceTo is only ever called with the window anchor as receiver;
        // model the anchor's drift parameter as its distance to everything
        every { location.distanceTo(any()) } returns driftMeters
        return location
    }

    /** A fix carrying altitude data for geoid-conversion tests. */
    fun altitudeFix(ellipsoid: Double, msl: Double? = null): Location {
        val location = mockk<Location>()
        every { location.altitude } returns ellipsoid
        every { location.hasMslAltitude() } returns (msl != null)
        if (msl != null) {
            every { location.mslAltitudeMeters } returns msl
        }
        return location
    }
}
