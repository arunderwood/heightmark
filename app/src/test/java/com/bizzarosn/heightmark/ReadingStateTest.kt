package com.bizzarosn.heightmark

import org.junit.Assert.*
import org.junit.Test

class ReadingStateTest {

    private fun snapshot(
        readingCount: Int = 5,
        windowSize: Int = 10,
        settled: Boolean = false
    ) = ElevationService.Snapshot(
        averageMeters = 100.0,
        readingCount = readingCount,
        windowSize = windowSize,
        progress = readingCount.toFloat() / windowSize,
        settled = settled
    )

    @Test
    fun `acquiring before the first fix regardless of other flags`() {
        assertEquals(
            ReadingState.Acquiring,
            ReadingState.derive(
                hasFixEver = false, isIdle = true, awaitingFreshFix = true,
                snapshot = snapshot(settled = true)
            )
        )
    }

    @Test
    fun `idle beats settled`() {
        assertEquals(
            ReadingState.Dormant,
            ReadingState.derive(
                hasFixEver = true, isIdle = true, awaitingFreshFix = false,
                snapshot = snapshot(readingCount = 10, settled = true)
            )
        )
    }

    @Test
    fun `awaiting a fresh fix forces dormant`() {
        assertEquals(
            ReadingState.Dormant,
            ReadingState.derive(
                hasFixEver = true, isIdle = false, awaitingFreshFix = true,
                snapshot = snapshot(readingCount = 0)
            )
        )
    }

    @Test
    fun `settled window is stable`() {
        assertEquals(
            ReadingState.Stable,
            ReadingState.derive(
                hasFixEver = true, isIdle = false, awaitingFreshFix = false,
                snapshot = snapshot(readingCount = 10, settled = true)
            )
        )
    }

    @Test
    fun `unsettled window is converging with the fill progress`() {
        val state = ReadingState.derive(
            hasFixEver = true, isIdle = false, awaitingFreshFix = false,
            snapshot = snapshot(readingCount = 3)
        )
        assertEquals(ReadingState.Converging(0.3f), state)
    }
}
