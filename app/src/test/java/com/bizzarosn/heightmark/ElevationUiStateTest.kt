package com.bizzarosn.heightmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The precedence table behind the screen: which of a block message, the search
 * text and the hero number wins, and when the settling line has to go away.
 */
class ElevationUiStateTest {

    private fun derive(
        blocked: ElevationUiState.Blocked? = null,
        searchTimedOut: Boolean = false,
        elevationMeters: Double? = null,
        datum: ElevationDatum = ElevationDatum.MEAN_SEA_LEVEL,
        readingState: ReadingState = ReadingState.Stable,
        details: ElevationUiState.DetailsFacts? = null
    ) = ElevationUiState.derive(
        blocked = blocked,
        searchTimedOut = searchTimedOut,
        elevation = elevationMeters?.let { Elevation(it, datum) },
        readingState = readingState,
        details = details
    )

    @Test
    fun `before the first fix the hero asks the user to wait`() {
        assertEquals(
            ElevationUiState.Hero.Status(R.string.loading_elevation),
            derive(readingState = ReadingState.Acquiring).hero
        )
    }

    @Test
    fun `a search that runs long escalates its message`() {
        assertEquals(
            ElevationUiState.Hero.Status(R.string.still_searching),
            derive(searchTimedOut = true, readingState = ReadingState.Acquiring).hero
        )
    }

    @Test
    fun `a committed elevation outranks a timed-out search`() {
        assertEquals(
            ElevationUiState.Hero.Value(1234.5, ElevationDatum.MEAN_SEA_LEVEL),
            derive(searchTimedOut = true, elevationMeters = 1234.5).hero
        )
    }

    @Test
    fun `the hero carries the datum its reading was measured against`() {
        // Without this the screen would label a raw GNSS height, tens of meters
        // off sea level, as the elevation it normally promises
        assertEquals(
            ElevationUiState.Hero.Value(1234.5, ElevationDatum.ELLIPSOID),
            derive(elevationMeters = 1234.5, datum = ElevationDatum.ELLIPSOID).hero
        )
    }

    @Test
    fun `a block replaces even a good reading`() {
        // The number is no longer being kept current, so leaving it up would
        // present a stale elevation as a live one
        assertEquals(
            ElevationUiState.Hero.Status(R.string.location_permission_required),
            derive(
                blocked = ElevationUiState.Blocked.PermissionRequired,
                elevationMeters = 1234.5
            ).hero
        )
    }

    @Test
    fun `a block hides the settling line`() {
        assertNull(
            derive(blocked = ElevationUiState.Blocked.PreciseLocationRequired).readingState
        )
    }

    @Test
    fun `tracking passes the reading state through to the settling line`() {
        assertEquals(
            ReadingState.Converging(0.4f),
            derive(
                elevationMeters = 10.0,
                readingState = ReadingState.Converging(0.4f)
            ).readingState
        )
    }

    @Test
    fun `only location services off offers a settings prompt`() {
        assertTrue(
            derive(blocked = ElevationUiState.Blocked.LocationServicesOff).promptLocationSettings
        )
        assertFalse(
            derive(blocked = ElevationUiState.Blocked.PermissionRequired).promptLocationSettings
        )
        assertFalse(
            derive(blocked = ElevationUiState.Blocked.PreciseLocationRequired)
                .promptLocationSettings
        )
        assertFalse(derive(elevationMeters = 10.0).promptLocationSettings)
    }

    @Test
    fun `the diagnostic panel keeps its rows while blocked`() {
        // The panel is how a user sees why tracking stopped, so a block must
        // not blank it out
        val facts = ElevationUiState.DetailsFacts(
            isIdle = false,
            location = null,
            nowElapsedRealtimeNanos = 0L,
            satellitesUsed = 3,
            satellitesVisible = 9,
            pressureHpa = null,
            readingCount = 4,
            datum = ElevationDatum.MEAN_SEA_LEVEL
        )
        assertEquals(
            facts,
            derive(
                blocked = ElevationUiState.Blocked.LocationServicesOff,
                details = facts
            ).details
        )
    }
}
