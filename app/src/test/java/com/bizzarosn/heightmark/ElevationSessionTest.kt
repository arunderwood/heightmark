package com.bizzarosn.heightmark

import com.bizzarosn.heightmark.ElevationDatum.ELLIPSOID
import com.bizzarosn.heightmark.ElevationDatum.MEAN_SEA_LEVEL
import com.bizzarosn.heightmark.ElevationSession.Companion.MAX_VERTICAL_ACCURACY_M
import com.bizzarosn.heightmark.ElevationSession.Companion.RESET_AFTER_GAP_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevationSessionTest {

    /** A three-reading window keeps settled and progress reachable in three commits. */
    private val session = ElevationSession(ElevationService(WINDOW_SIZE))

    /** Admits and commits a fix in one step, as the tracker does per GNSS fix. */
    private fun addReading(
        meters: Double,
        verticalAccuracy: Float? = null,
        datum: ElevationDatum = MEAN_SEA_LEVEL
    ): Boolean {
        val pending = session.offer(TestLocations.fixForAdmission(verticalAccuracy = verticalAccuracy))
        assertNotNull("fix should have been admitted", pending)
        return session.commit(pending!!, Elevation(meters, datum))
    }

    // ---- Admission ----

    @Test
    fun `a fix without altitude is rejected`() {
        assertNull(session.offer(TestLocations.fixForAdmission(hasAltitude = false)))
    }

    @Test
    fun `a fix worse than the accuracy limit is rejected`() {
        val location = TestLocations.fixForAdmission(
            verticalAccuracy = MAX_VERTICAL_ACCURACY_M + 0.1f
        )
        assertNull(session.offer(location))
    }

    @Test
    fun `a fix exactly at the accuracy limit is admitted`() {
        val location = TestLocations.fixForAdmission(verticalAccuracy = MAX_VERTICAL_ACCURACY_M)
        assertNotNull(session.offer(location))
    }

    @Test
    fun `a fix reporting no vertical accuracy is admitted as unknown`() {
        // Unknown is not the same as bad; substituting a default is the
        // averaging window's job, not the filter's
        val pending = session.offer(TestLocations.fixForAdmission(verticalAccuracy = null))
        assertNotNull(pending)
        assertNull(pending!!.verticalAccuracyMeters)
    }

    @Test
    fun `an admitted fix carries the accuracy it reported`() {
        val pending = session.offer(TestLocations.fixForAdmission(verticalAccuracy = 7.5f))
        assertEquals(7.5f, pending!!.verticalAccuracyMeters!!, 0f)
    }

    // ---- Commit and the epoch guard ----

    @Test
    fun `committing a converted fix updates the displayed elevation`() {
        assertTrue(addReading(100.0))

        assertTrue(session.hasFix)
        assertEquals(100.0, session.displayedElevation!!.meters, 1e-9)
        assertEquals(1, session.readingCount)
    }

    @Test
    fun `a fix converted across a wake is dropped`() {
        addReading(100.0)
        val pending = session.offer(TestLocations.fixForAdmission())!!
        session.wake()

        assertFalse(session.commit(pending, Elevation(250.0, MEAN_SEA_LEVEL)))
        assertEquals(0, session.readingCount)
        // The pre-wake value stays on screen rather than jumping to the stale reading
        assertEquals(100.0, session.displayedElevation!!.meters, 1e-9)
    }

    @Test
    fun `a fix converted across a background-gap flush is dropped`() {
        addReading(100.0)
        val pending = session.offer(TestLocations.fixForAdmission())!!
        session.onPaused(0L)
        session.onResumed(RESET_AFTER_GAP_MS + 1)

        assertFalse(session.commit(pending, Elevation(250.0, MEAN_SEA_LEVEL)))
        assertEquals(0, session.readingCount)
        assertEquals(100.0, session.displayedElevation!!.meters, 1e-9)
    }

    @Test
    fun `a fix admitted after a flush commits normally`() {
        addReading(100.0)
        session.wake()

        assertTrue(addReading(250.0))
        assertEquals(250.0, session.displayedElevation!!.meters, 1e-9)
        assertEquals(1, session.readingCount)
    }

    // ---- Datum policy ----

    @Test
    fun `a committed reading carries the datum it was measured against`() {
        addReading(100.0)

        assertEquals(Elevation(100.0, MEAN_SEA_LEVEL), session.displayedElevation)
    }

    @Test
    fun `a device without geoid data averages ellipsoid heights consistently`() {
        // Conversion never succeeds here, so there is no sea-level figure to
        // mix with: one datum throughout, named as such on screen
        assertTrue(addReading(100.0, datum = ELLIPSOID))
        assertTrue(addReading(102.0, datum = ELLIPSOID))

        assertEquals(2, session.readingCount)
        assertEquals(Elevation(101.0, ELLIPSOID), session.displayedElevation)
    }

    @Test
    fun `an unconverted fix is dropped once sea level has been measured`() {
        addReading(100.0)

        // The fallback is the same place on a different surface, not a 30 m
        // descent, and at 1 Hz another fix is a second away
        assertFalse(addReading(70.0, datum = ELLIPSOID))
        assertEquals(1, session.readingCount)
        assertEquals(Elevation(100.0, MEAN_SEA_LEVEL), session.displayedElevation)
    }

    @Test
    fun `a run of unconverted fixes never re-anchors the window`() {
        addReading(100.0)

        // A same-side run this long is exactly what the jump detector treats as
        // a real elevation change, so these must not reach it at all
        repeat(ElevationService.JUMP_CONFIRM_COUNT) {
            assertFalse(addReading(70.0, datum = ELLIPSOID))
        }

        assertEquals(1, session.readingCount)
        assertEquals(Elevation(100.0, MEAN_SEA_LEVEL), session.displayedElevation)
    }

    @Test
    fun `geoid data arriving mid-session flushes the ellipsoid window`() {
        addReading(100.0, datum = ELLIPSOID)
        addReading(102.0, datum = ELLIPSOID)

        assertTrue(addReading(70.0))

        // The sea-level reading stands alone: neither averaged with heights on
        // another datum nor measured against them for a jump
        assertEquals(1, session.readingCount)
        assertEquals(Elevation(70.0, MEAN_SEA_LEVEL), session.displayedElevation)
        // And the flush is invisible on screen — the fix that caused it lands
        // in the same commit, so the reading never falls back to dormant
        assertEquals(ReadingState.Converging(1f / WINDOW_SIZE), session.readingState())
    }

    @Test
    fun `the datum switch happens once, not on every later fix`() {
        addReading(100.0, datum = ELLIPSOID)
        addReading(70.0)
        addReading(72.0)

        assertEquals(2, session.readingCount)
        assertEquals(Elevation(71.0, MEAN_SEA_LEVEL), session.displayedElevation)
    }

    // ---- Duty cycle and reading state ----

    @Test
    fun `a session with no fix is acquiring and has nothing to display`() {
        assertEquals(ReadingState.Acquiring, session.readingState())
        assertNull(session.displayedElevation)
        assertFalse(session.hasFix)
    }

    @Test
    fun `going idle makes the reading dormant`() {
        addReading(100.0)
        session.enterIdle()

        assertTrue(session.isIdle)
        assertEquals(ReadingState.Dormant, session.readingState())
    }

    @Test
    fun `waking flushes the window but keeps the last value on screen`() {
        addReading(100.0)
        session.enterIdle()
        session.wake()

        assertFalse(session.isIdle)
        assertEquals(0, session.readingCount)
        // Dormant, not Acquiring: there is still a number worth showing, dimmed
        assertEquals(ReadingState.Dormant, session.readingState())
        assertEquals(100.0, session.displayedElevation!!.meters, 1e-9)
    }

    @Test
    fun `the next fix after a wake clears the dormant state`() {
        addReading(100.0)
        session.wake()
        addReading(250.0)

        assertEquals(ReadingState.Converging(1f / WINDOW_SIZE), session.readingState())
    }

    @Test
    fun `a flush before the first fix leaves the session acquiring`() {
        session.wake()

        assertEquals(ReadingState.Acquiring, session.readingState())
        assertNull(session.displayedElevation)
    }

    @Test
    fun `pausing ends the duty cycle`() {
        repeat(WINDOW_SIZE) { addReading(100.0) }
        session.enterIdle()
        session.onPaused(0L)

        assertFalse(session.isIdle)
        assertEquals(ReadingState.Stable, session.readingState())
    }

    // ---- Background-gap policy ----

    @Test
    fun `a short background gap keeps the averaging window`() {
        addReading(100.0)
        session.onPaused(0L)
        session.onResumed(RESET_AFTER_GAP_MS)

        assertEquals(1, session.readingCount)
        assertEquals(ReadingState.Converging(1f / WINDOW_SIZE), session.readingState())
    }

    @Test
    fun `a long background gap flushes the window and keeps the last value`() {
        addReading(100.0)
        session.onPaused(0L)
        session.onResumed(RESET_AFTER_GAP_MS + 1)

        assertEquals(0, session.readingCount)
        assertEquals(ReadingState.Dormant, session.readingState())
        assertEquals(100.0, session.displayedElevation!!.meters, 1e-9)
    }

    @Test
    fun `resuming without a prior pause never flushes`() {
        addReading(100.0)
        session.onResumed(RESET_AFTER_GAP_MS * 100)

        assertEquals(1, session.readingCount)
    }

    private companion object {
        const val WINDOW_SIZE = 3
    }
}
