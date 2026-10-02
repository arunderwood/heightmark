package com.bizzarosn.heightmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPoolTest {

    private fun window(averageMeters: Double, accuracyMeters: Double) = ElevationService.Snapshot(
        averageMeters = averageMeters,
        readingCount = 30,
        progress = 1f,
        settled = true,
        accuracyMeters = accuracyMeters
    )

    @Test
    fun `a session reporting better than the floor is measured at the floor`() {
        val measured = SessionPool.measurement(window(100.0, accuracyMeters = 2.0))

        assertEquals(100.0, measured.meters, 0.0)
        assertEquals(4.75 * 4.75, measured.variance, 1e-9)
    }

    @Test
    fun `a session reporting worse than the floor keeps its own accuracy`() {
        assertEquals(19.0 * 19.0, SessionPool.measurement(window(100.0, 20.0)).variance, 1e-9)
    }

    @Test
    fun `pooling weighs by variance and shrinks it`() {
        val verdict = SessionPool.weigh(
            pool = HeightEstimate(100.0, variance = 30.0),
            session = HeightEstimate(90.0, variance = 10.0),
            sessionReadings = 30
        )

        assertEquals(SessionPool.Verdict.Pooled(HeightEstimate(92.5, 7.5)), verdict)
    }

    @Test
    fun `a session beyond the gate refutes the pool once it has enough readings`() {
        val pool = HeightEstimate(100.0, variance = 16.0)
        // Combined SD 5 m: 3 SD is 15 m
        val session = HeightEstimate(115.1, variance = 9.0)

        assertEquals(
            SessionPool.Verdict.Undecided,
            SessionPool.weigh(pool, session, SessionPool.MIN_REFUTING_READINGS - 1)
        )
        assertEquals(
            SessionPool.Verdict.Refuted,
            SessionPool.weigh(pool, session, SessionPool.MIN_REFUTING_READINGS)
        )
    }

    @Test
    fun `a session just inside the gate is pooled`() {
        val verdict = SessionPool.weigh(
            HeightEstimate(100.0, 16.0), HeightEstimate(114.9, 9.0), sessionReadings = 30
        )

        assertTrue(verdict is SessionPool.Verdict.Pooled)
    }
}
