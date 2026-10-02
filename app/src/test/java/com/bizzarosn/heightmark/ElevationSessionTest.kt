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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

class ElevationSessionTest {

    /** A three-reading window keeps settled and progress reachable in three commits. */
    private val session = ElevationSession(ElevationService(WINDOW_SIZE))

    /** Admits and commits a fix in one step, as the tracker does per GNSS fix. */
    private fun addReading(
        meters: Double,
        verticalAccuracy: Float? = null,
        datum: ElevationDatum = MEAN_SEA_LEVEL,
        atNanos: Long = 0L
    ): Boolean {
        val pending = session.offer(
            TestLocations.fixForAdmission(verticalAccuracy = verticalAccuracy, atNanos = atNanos)
        )
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
    fun `commit's accuracy override supersedes the pre-conversion accuracy for weighting`() {
        val first = session.offer(TestLocations.fixForAdmission(verticalAccuracy = 10f))!!
        session.commit(first, Elevation(100.0, MEAN_SEA_LEVEL), accuracyMeters = 10f)

        val second = session.offer(TestLocations.fixForAdmission(verticalAccuracy = 10f))!!
        // A resolved accuracy far tighter than what admission captured (e.g. a
        // post-conversion MSL figure) should dominate the weighted average
        session.commit(second, Elevation(106.0, MEAN_SEA_LEVEL), accuracyMeters = 1f)

        // weight(10 m) = 0.01, weight(1 m) = 1: (100 x 0.01 + 106 x 1) / 1.01
        assertEquals(105.941, session.displayedElevation!!.meters, 0.001)
    }

    @Test
    fun `commit without an override weighs by the fix's pre-conversion accuracy`() {
        val loose = session.offer(TestLocations.fixForAdmission(verticalAccuracy = 10f))!!
        session.commit(loose, Elevation(100.0, MEAN_SEA_LEVEL))

        val tight = session.offer(TestLocations.fixForAdmission(verticalAccuracy = 1f))!!
        session.commit(tight, Elevation(106.0, MEAN_SEA_LEVEL))

        assertEquals(105.941, session.displayedElevation!!.meters, 0.001)
    }

    @Test
    fun `a fix converted across a wake is dropped`() {
        addReading(100.0)
        val pending = session.offer(TestLocations.fixForAdmission())!!
        session.wake(WakeTrigger.SIGNIFICANT_MOTION)

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
    fun `a fix admitted after a wake starts the next session's window`() {
        addReading(100.0)
        session.wake(WakeTrigger.SIGNIFICANT_MOTION)

        assertTrue(addReading(250.0))
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
    fun `waking flushes an unsettled window but keeps the last value on screen`() {
        addReading(100.0)
        session.enterIdle()
        session.wake(WakeTrigger.SIGNIFICANT_MOTION)

        assertFalse(session.isIdle)
        assertEquals(0, session.readingCount)
        // Dormant, not Acquiring: there is still a number worth showing, dimmed
        assertEquals(ReadingState.Dormant, session.readingState())
        assertEquals(100.0, session.displayedElevation!!.meters, 1e-9)
    }

    @Test
    fun `blocking an idle session ends idle and flushes the window`() {
        addReading(100.0)
        session.enterIdle()
        session.onBlocked()

        assertFalse(session.isIdle)
        assertEquals(0, session.readingCount)
        assertEquals(100.0, session.displayedElevation!!.meters, 1e-9)
    }

    @Test
    fun `the first fix after blocking an idle session clears the dormant state`() {
        addReading(100.0)
        session.enterIdle()
        session.onBlocked()
        addReading(100.0)

        // Stable, not Converging: the reading rests on the session pooled before the block
        assertEquals(ReadingState.Stable, session.readingState())
    }

    @Test
    fun `blocking an active session keeps its averaging window`() {
        addReading(100.0)
        session.onBlocked()

        assertEquals(1, session.readingCount)
        assertEquals(ReadingState.Converging(1f / WINDOW_SIZE), session.readingState())
    }

    @Test
    fun `the next fix after a wake clears the dormant state`() {
        addReading(100.0)
        session.wake(WakeTrigger.SIGNIFICANT_MOTION)
        addReading(100.0)

        assertEquals(ReadingState.Stable, session.readingState())
    }

    @Test
    fun `a flush before the first fix leaves the session acquiring`() {
        session.wake(WakeTrigger.SIGNIFICANT_MOTION)

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

    // ---- Pooling sessions ----

    @Test
    fun `a wake keeps the last session on screen until fresh fixes land`() {
        settleAt(100.0)
        session.enterIdle()
        session.wake(WakeTrigger.SIGNIFICANT_MOTION)

        assertEquals(100.0, session.displayedElevation!!.meters, 1e-9)
        assertEquals(ReadingState.Dormant, session.readingState())
        assertEquals(0, session.readingCount)
    }

    @Test
    fun `the next session is pooled with the last instead of replacing it`() {
        startBarometer()
        settleAt(100.0)
        session.wake(WakeTrigger.SIGNIFICANT_MOTION)

        // Equal session variances: the pool moves halfway, not all the way
        addReading(91.0, verticalAccuracy = 2f)
        assertEquals(95.5, session.displayedElevation!!.meters, 1e-9)
        assertEquals(ReadingState.Stable, session.readingState())
    }

    @Test
    fun `pooled sessions converge on their mean`() {
        startBarometer()
        // Three desk sessions on one phone that never moved
        listOf(115.1, 106.0, 112.0).forEach { height ->
            settleAt(height)
            session.enterIdle()
            session.wake(WakeTrigger.SIGNIFICANT_MOTION)
        }

        assertEquals(111.033, session.displayedElevation!!.meters, 0.001)
    }

    @Test
    fun `a loose fix barely moves the pool`() {
        startBarometer()
        settleAt(100.0)
        session.wake(WakeTrigger.SIGNIFICANT_MOTION)

        // A first fix after restart reporting 20 m is weighed as such
        addReading(80.0, verticalAccuracy = 20f)
        // pool R = (0.95 x 5)^2, session R = (0.95 x 20)^2: gain 1/17
        assertEquals(98.824, session.displayedElevation!!.meters, 0.001)
    }

    @Test
    fun `without a barometer a wake loosens the pool`() {
        settleAt(100.0)
        session.wake(WakeTrigger.SIGNIFICANT_MOTION)

        addReading(91.0, verticalAccuracy = 2f)
        // The pool's variance grew by a floor squared, so the session weighs more
        val r = (SessionPool.SESSION_ERROR_FRACTION * SessionPool.SESSION_SIGMA_FLOOR_M).let { it * it }
        val p = r + ElevationSession.UNTRACKED_WAKE_VARIANCE_M2
        assertEquals(100.0 - 9.0 * p / (p + r), session.displayedElevation!!.meters, 1e-9)
    }

    @Test
    fun `a session far from the pool waits for confirming readings, then replaces it`() {
        startBarometer()
        settleAt(100.0)
        session.wake(WakeTrigger.SIGNIFICANT_MOTION)

        addReading(150.0, verticalAccuracy = 2f)
        addReading(150.0, verticalAccuracy = 2f)
        assertEquals(100.0, session.displayedElevation!!.meters, 1e-9)

        addReading(150.0, verticalAccuracy = 2f)
        assertEquals(150.0, session.displayedElevation!!.meters, 1e-9)

        // And it is the pool from then on
        session.enterIdle()
        session.wake(WakeTrigger.SIGNIFICANT_MOTION)
        assertEquals(150.0, session.displayedElevation!!.meters, 1e-9)
    }

    @Test
    fun `a long session folds a window into the pool every interval`() {
        startBarometer()
        settleAt(100.0)

        assertTrue(addReading(90.0, verticalAccuracy = 2f, atNanos = ElevationSession.FOLD_INTERVAL_NANOS))
        assertEquals(1, session.readingCount)
        assertEquals(95.0, session.displayedElevation!!.meters, 1e-9)
    }

    @Test
    fun `a pressure wake without a barometer reading discards the pool`() {
        settleAt(100.0)
        session.enterIdle()
        session.wake(WakeTrigger.PRESSURE_CHANGE)

        assertEquals(0, session.readingCount)
        addReading(91.0, verticalAccuracy = 2f)
        assertEquals(91.0, session.displayedElevation!!.meters, 1e-9)
    }

    @Test
    fun `a pressure wake with a tracking barometer keeps the pool`() {
        startBarometer()
        settleAt(100.0)
        session.enterIdle()
        session.wake(WakeTrigger.PRESSURE_CHANGE)

        addReading(91.0, verticalAccuracy = 2f)
        assertEquals(95.5, session.displayedElevation!!.meters, 1e-9)
    }

    @Test
    fun `a fix converted across a wake never reaches the pool`() {
        settleAt(100.0)
        val pending = session.offer(TestLocations.fixForAdmission())!!
        session.wake(WakeTrigger.LOCATION_FIX)

        assertFalse(session.commit(pending, Elevation(250.0, MEAN_SEA_LEVEL)))
        assertEquals(100.0, session.displayedElevation!!.meters, 1e-9)
    }

    @Test
    fun `blocking an idle session pools the next one`() {
        startBarometer()
        settleAt(100.0)
        session.enterIdle()
        session.onBlocked()

        assertFalse(session.isIdle)
        addReading(91.0, verticalAccuracy = 2f)
        assertEquals(95.5, session.displayedElevation!!.meters, 1e-9)
    }

    @Test
    fun `a long background gap pools the next session`() {
        startBarometer()
        settleAt(100.0)
        session.onPaused(0L)
        session.onResumed(RESET_AFTER_GAP_MS + 1)

        assertEquals(ReadingState.Dormant, session.readingState())
        addReading(91.0, verticalAccuracy = 2f)
        assertEquals(95.5, session.displayedElevation!!.meters, 1e-9)
    }

    @Test
    fun `an ellipsoid fix after a long gap replaces a sea-level pool`() {
        settleAt(100.0)
        session.onPaused(0L)
        session.onResumed(RESET_AFTER_GAP_MS + 1)

        // The pool cannot be weighed against another datum, and with the latch
        // cleared the ellipsoid window is the recovery path
        assertTrue(addReading(50.0, datum = ELLIPSOID))
        assertEquals(1, session.readingCount)
        assertEquals(Elevation(50.0, ELLIPSOID), session.displayedElevation)
        session.wake(WakeTrigger.SIGNIFICANT_MOTION)
        assertEquals(Elevation(50.0, ELLIPSOID), session.displayedElevation)
    }

    // ---- Barometer ----

    @Test
    fun `a climb moves the reading as the barometer sees it`() {
        startBarometer()
        settleAt(100.0)
        session.enterIdle()

        climb(3.0)

        assertEquals(103.0, session.displayedElevation!!.meters, 0.01)
    }

    @Test
    fun `fixes after a climb pool against the same session`() {
        startBarometer()
        settleAt(100.0)
        climb(10.0)

        addReading(110.0, verticalAccuracy = 2f)
        assertEquals(110.0, session.displayedElevation!!.meters, 0.01)
        assertEquals(WINDOW_SIZE, session.readingCount)
    }

    @Test
    fun `weather drift leaves a still reading alone`() {
        startBarometer()
        settleAt(100.0)
        session.enterIdle()

        // A fast-moving front: 3 hPa an hour, about 25 m of apparent descent
        val start = pressureAt(0.0)
        repeat(3600) { holdPressure(start + 3.0 * it / 3600, seconds = 1) }

        assertEquals(100.0, session.displayedElevation!!.meters, 1e-9)
    }

    // ---- Pressurized cabin ----
    //
    // An airliner's barometer reads the cabin, held at or below 8,000 ft
    // (14 CFR 25.841), while GNSS reads the aircraft. A climb to cruise moves
    // the cabin about a fifth as far as the aircraft.

    /** Ground, climb to cruise and cruise, with a fix every second. */
    private fun Journey.flyToCruise() {
        leg(300, deviceTo = GROUND_M)
        leg(1200, deviceTo = CRUISE_M, barometerTo = CABIN_CEILING_M)
        leg(600, deviceTo = CRUISE_M, barometerTo = CABIN_CEILING_M)
    }

    @Test
    fun `with fixes, a flight's reading tracks GNSS as closely as without a barometer`() {
        val flights = listOf(Journey(), Journey(hasBarometer = false))
        for (flight in flights) {
            flight.flyToCruise()
            flight.leg(1500, deviceTo = GROUND_M, barometerTo = GROUND_M)
            flight.leg(300, deviceTo = GROUND_M)
        }
        val (withCabin, withoutBarometer) = flights

        assertTrue(
            "worst gap ${withCabin.worstGapMeters} m against ${withoutBarometer.worstGapMeters} m",
            withCabin.worstGapMeters <= withoutBarometer.worstGapMeters + 1.0
        )
        assertEquals(GROUND_M, withCabin.session.displayedElevation!!.meters, 5.0)
    }

    @Test
    fun `a descent without fixes holds the reading instead of following the cabin`() {
        val flight = Journey()
        flight.flyToCruise()
        // Away from a window: only the barometer sees the descent
        flight.leg(1500, deviceTo = GROUND_M, barometerTo = GROUND_M, fixes = false)

        assertEquals(CRUISE_M, flight.session.displayedElevation!!.meters, 50.0)
    }

    @Test
    fun `after a descent without fixes, the first fixes on the ground replace the cruise height`() {
        val flight = Journey()
        flight.flyToCruise()
        flight.leg(1500, deviceTo = GROUND_M, barometerTo = GROUND_M, fixes = false)

        flight.leg(ElevationService.JUMP_CONFIRM_COUNT, deviceTo = GROUND_M)

        assertEquals(GROUND_M, flight.session.displayedElevation!!.meters, 5.0)
    }

    @Test
    fun `after landing, the barometer moves the reading again`() {
        val flight = Journey()
        flight.flyToCruise()
        flight.leg(1500, deviceTo = GROUND_M, barometerTo = GROUND_M)
        flight.leg(120, deviceTo = GROUND_M)
        val landed = flight.session.displayedElevation!!.meters

        flight.leg(10, deviceTo = GROUND_M + 3.0, fixes = false)
        flight.leg(40, deviceTo = GROUND_M + 3.0, fixes = false)

        assertEquals(landed + 3.0, flight.session.displayedElevation!!.meters, 0.5)
    }

    @Test
    fun `a mountain drive keeps the barometer in use`() {
        val drive = Journey()
        drive.leg(300, deviceTo = GROUND_M)
        drive.leg(1800, deviceTo = GROUND_M + 1_400.0)
        drive.leg(120, deviceTo = GROUND_M + 1_400.0)
        val parked = drive.session.displayedElevation!!.meters

        drive.leg(10, deviceTo = GROUND_M + 1_403.0, fixes = false)
        drive.leg(40, deviceTo = GROUND_M + 1_403.0, fixes = false)

        assertEquals(parked + 3.0, drive.session.displayedElevation!!.meters, 0.5)
    }

    /**
     * A session at the production window size, fed one fix (unless a leg
     * has none) and, when [hasBarometer], one barometer sample a second.
     * Starts on the ground.
     */
    private class Journey(private val hasBarometer: Boolean = true) {
        val session = ElevationSession(ElevationService(ElevationService.DEFAULT_WINDOW_SIZE))
        private var second = 0L
        private var deviceMeters = GROUND_M
        private var barometerMeters = GROUND_M

        /** Largest gap between the reading and the device's height after any fix. */
        var worstGapMeters = 0.0
            private set

        /**
         * Moves the device linearly to [deviceTo] over [seconds], and the
         * height the barometer's pressure stands for to [barometerTo]. The
         * two differ only in a pressurized cabin.
         */
        fun leg(seconds: Int, deviceTo: Double, barometerTo: Double = deviceTo, fixes: Boolean = true) {
            val deviceFrom = deviceMeters
            val barometerFrom = barometerMeters
            for (i in 1..seconds) {
                deviceMeters = deviceFrom + (deviceTo - deviceFrom) * i / seconds
                barometerMeters = barometerFrom + (barometerTo - barometerFrom) * i / seconds
                val atNanos = second++ * NANOS_PER_SECOND
                if (hasBarometer) session.onPressure(pressureAt(barometerMeters).toFloat(), atNanos)
                if (!fixes) continue
                val pending = session.offer(
                    TestLocations.fixForAdmission(verticalAccuracy = 5f, atNanos = atNanos)
                )
                session.commit(pending!!, Elevation(deviceMeters, MEAN_SEA_LEVEL))
                val gap = abs(session.displayedElevation!!.meters - deviceMeters)
                worstGapMeters = max(worstGapMeters, gap)
            }
        }
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
    fun `a long background gap flushes an unsettled window and keeps the last value`() {
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

    // ---- Datum recovery after a background gap ----

    @Test
    fun `ellipsoid fixes after a background-gap reset build a consistent window`() {
        addReading(100.0)
        session.onPaused(0L)
        session.onResumed(RESET_AFTER_GAP_MS + 1)

        // With geoid conversion still failing after the reset, the window has
        // to carry the reading on the ellipsoid datum
        assertTrue(addReading(50.0, datum = ELLIPSOID))
        assertTrue(addReading(52.0, datum = ELLIPSOID))

        assertEquals(2, session.readingCount)
        assertEquals(Elevation(51.0, ELLIPSOID), session.displayedElevation)
    }

    @Test
    fun `an MSL fix after a background-gap reset re-latches the datum`() {
        addReading(100.0)
        session.onPaused(0L)
        session.onResumed(RESET_AFTER_GAP_MS + 1)

        assertTrue(addReading(70.0))
        assertFalse(addReading(50.0, datum = ELLIPSOID))

        assertEquals(1, session.readingCount)
        assertEquals(MEAN_SEA_LEVEL, session.displayedElevation!!.datum)
    }

    @Test
    fun `waking does not clear the datum latch`() {
        addReading(100.0)
        session.wake(WakeTrigger.SIGNIFICANT_MOTION)

        assertFalse(addReading(50.0, datum = ELLIPSOID))
        assertEquals(0, session.readingCount)
        assertEquals(100.0, session.displayedElevation!!.meters, 1e-9)
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
        assertEquals(100.0, session.displayedElevation!!.meters, 1e-9)
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
        session.wake(WakeTrigger.SIGNIFICANT_MOTION)
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

    private fun settleAt(meters: Double) {
        repeat(WINDOW_SIZE) { addReading(meters, verticalAccuracy = 2f) }
        assertEquals(ReadingState.Stable, session.readingState())
    }

    private var pressureSecond = 0L

    /** Feeds [hpa] once a second for [seconds], continuing the barometer's clock. */
    private fun holdPressure(hpa: Double, seconds: Int) {
        repeat(seconds) { session.onPressure(hpa.toFloat(), pressureSecond++ * NANOS_PER_SECOND) }
    }

    private var barometerHeight = 0.0

    /** Gives the session a working barometer at a steady pressure. */
    private fun startBarometer() {
        holdPressure(pressureAt(barometerHeight), seconds = 31)
    }

    /** Carries the device up [meters] at 0.3 m/s, then holds until the odometer is still. */
    private fun climb(meters: Double) {
        val steps = (meters / 0.3).toInt()
        repeat(steps) {
            barometerHeight += meters / steps
            holdPressure(pressureAt(barometerHeight), seconds = 1)
        }
        holdPressure(pressureAt(barometerHeight), seconds = 40)
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val WINDOW_SIZE = 3

        const val GROUND_M = 100.0
        const val CRUISE_M = 11_000.0

        /** 8,000 ft, the highest cabin altitude a transport-category airliner may hold. */
        const val CABIN_CEILING_M = 2_438.0

        /** International Standard Atmosphere pressure at [heightMeters]. */
        fun pressureAt(heightMeters: Double): Double =
            1013.25 * (1 - heightMeters / 44_330.0).pow(5.255)
    }
}
