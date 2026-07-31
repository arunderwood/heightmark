package com.bizzarosn.heightmark

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

    /** Admits and commits a fix in one step, as the fragment does per GNSS fix. */
    private fun addReading(meters: Double, verticalAccuracy: Float? = null): Boolean {
        val pending = session.offer(TestLocations.fixForAdmission(verticalAccuracy = verticalAccuracy))
        assertNotNull("fix should have been admitted", pending)
        return session.commit(pending!!, meters)
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
        assertEquals(100.0, session.displayedElevationMeters!!, 1e-9)
        assertEquals(1, session.readingCount)
    }

    @Test
    fun `a fix converted across a wake is dropped`() {
        addReading(100.0)
        val pending = session.offer(TestLocations.fixForAdmission())!!
        session.wake()

        assertFalse(session.commit(pending, 250.0))
        assertEquals(0, session.readingCount)
        // The pre-wake value stays on screen rather than jumping to the stale reading
        assertEquals(100.0, session.displayedElevationMeters!!, 1e-9)
    }

    @Test
    fun `a fix converted across a background-gap flush is dropped`() {
        addReading(100.0)
        val pending = session.offer(TestLocations.fixForAdmission())!!
        session.onPaused(0L)
        session.onResumed(RESET_AFTER_GAP_MS + 1)

        assertFalse(session.commit(pending, 250.0))
        assertEquals(0, session.readingCount)
        assertEquals(100.0, session.displayedElevationMeters!!, 1e-9)
    }

    @Test
    fun `a fix admitted after a flush commits normally`() {
        addReading(100.0)
        session.wake()

        assertTrue(addReading(250.0))
        assertEquals(250.0, session.displayedElevationMeters!!, 1e-9)
        assertEquals(1, session.readingCount)
    }

    // ---- Duty cycle and reading state ----

    @Test
    fun `a session with no fix is acquiring and has nothing to display`() {
        assertEquals(ReadingState.Acquiring, session.readingState())
        assertNull(session.displayedElevationMeters)
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
        assertEquals(100.0, session.displayedElevationMeters!!, 1e-9)
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
        assertNull(session.displayedElevationMeters)
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
        assertEquals(100.0, session.displayedElevationMeters!!, 1e-9)
    }

    @Test
    fun `resuming without a prior pause never flushes`() {
        addReading(100.0)
        session.onResumed(RESET_AFTER_GAP_MS * 100)

        assertEquals(1, session.readingCount)
    }

    // ---- Fix-age watchdog ----

    @Test
    fun `a watchdog expiry with no prior fix does nothing`() {
        session.onFixWatchdogExpired()

        assertFalse(session.signalStale)
        assertEquals(ReadingState.Acquiring, session.readingState())
    }

    @Test
    fun `a watchdog expiry makes a settled reading dormant without discarding the window`() {
        repeat(WINDOW_SIZE) { addReading(100.0) }
        session.onFixWatchdogExpired()

        assertTrue(session.signalStale)
        assertFalse(session.isIdle)
        assertEquals(ReadingState.Dormant, session.readingState())
        // Unlike a flush, the averaging window survives a signal-loss expiry
        assertEquals(WINDOW_SIZE, session.readingCount)
        assertEquals(100.0, session.displayedElevationMeters!!, 1e-9)
    }

    @Test
    fun `a watchdog expiry while idle does not mark the signal stale`() {
        // The fix that tips the stillness detector commits after enterIdle,
        // so a countdown it armed can expire mid-duty-cycle — with the radio
        // off on purpose, that silence is not a lost signal
        addReading(100.0)
        session.enterIdle()
        session.onFixWatchdogExpired()

        assertFalse(session.signalStale)
        assertEquals(ReadingState.Dormant, session.readingState())

        // The wake's reacquisition must not carry a stale-signal verdict either
        session.wake()
        assertFalse(session.signalStale)
    }

    @Test
    fun `the next fix after a watchdog expiry clears the stale state`() {
        repeat(WINDOW_SIZE) { addReading(100.0) }
        session.onFixWatchdogExpired()
        addReading(100.0)

        assertFalse(session.signalStale)
        assertEquals(ReadingState.Stable, session.readingState())
    }

    private companion object {
        const val WINDOW_SIZE = 3
    }
}
