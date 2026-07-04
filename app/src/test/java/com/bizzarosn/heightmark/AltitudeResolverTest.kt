package com.bizzarosn.heightmark

import android.content.Context
import android.location.Location
import android.location.altitude.AltitudeConverter
import android.util.Log
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.IOException

class AltitudeResolverTest {

    private lateinit var context: Context
    private lateinit var converter: AltitudeConverter
    private lateinit var resolver: AltitudeResolver

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>(), any()) } returns 0
        context = mockk()
        converter = mockk()
        resolver = AltitudeResolver(context, converter)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun location(ellipsoid: Double, msl: Double? = null): Location {
        val location = mockk<Location>()
        every { location.altitude } returns ellipsoid
        every { location.hasMslAltitude() } returns (msl != null)
        if (msl != null) {
            every { location.mslAltitudeMeters } returns msl
        }
        return location
    }

    @Test
    fun `returns MSL altitude when conversion succeeds`() {
        val location = location(ellipsoid = 100.0, msl = 121.5)
        every { converter.addMslAltitudeToLocation(context, location) } just runs

        assertEquals(121.5, resolver.mslAltitudeMeters(location), 0.001)
    }

    @Test
    fun `falls back to ellipsoid altitude when converter does not populate MSL`() {
        val location = location(ellipsoid = 100.0)
        every { converter.addMslAltitudeToLocation(context, location) } just runs

        assertEquals(100.0, resolver.mslAltitudeMeters(location), 0.001)
    }

    @Test
    fun `falls back to ellipsoid altitude when geoid data cannot be loaded`() {
        val location = location(ellipsoid = 100.0)
        every { converter.addMslAltitudeToLocation(context, location) } throws IOException("no geoid data")

        assertEquals(100.0, resolver.mslAltitudeMeters(location), 0.001)
    }

    @Test
    fun `falls back to ellipsoid altitude when converter rejects the location`() {
        val location = location(ellipsoid = 100.0)
        every {
            converter.addMslAltitudeToLocation(context, location)
        } throws IllegalArgumentException("invalid latitude")

        assertEquals(100.0, resolver.mslAltitudeMeters(location), 0.001)
    }
}
