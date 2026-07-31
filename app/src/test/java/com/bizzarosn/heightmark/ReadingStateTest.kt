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
        progress = readingCount.toFloat() / windowSize,
        settled = settled
    )

    @Test
    fun `acquiring before the first fix regardless of other flags`() {
        assertEquals(
            ReadingState.Acquiring,
            ReadingState.derive(
                hasFixEver = false, isIdle = true, awaitingFreshFix = true,
                signalStale = true, snapshot = snapshot(settled = true)
            )
        )
    }

    @Test
    fun `idle beats settled`() {
        assertEquals(
            ReadingState.Dormant,
            ReadingState.derive(
                hasFixEver = true, isIdle = true, awaitingFreshFix = false,
                signalStale = false, snapshot = snapshot(readingCount = 10, settled = true)
            )
        )
    }

    @Test
    fun `awaiting a fresh fix forces dormant`() {
        assertEquals(
            ReadingState.Dormant,
            ReadingState.derive(
                hasFixEver = true, isIdle = false, awaitingFreshFix = true,
                signalStale = false, snapshot = snapshot(readingCount = 0)
            )
        )
    }

    @Test
    fun `a stale signal forces dormant`() {
        assertEquals(
            ReadingState.Dormant,
            ReadingState.derive(
                hasFixEver = true, isIdle = false, awaitingFreshFix = false,
                signalStale = true, snapshot = snapshot(readingCount = 10, settled = true)
            )
        )
    }

    @Test
    fun `settled window is stable`() {
        assertEquals(
            ReadingState.Stable,
            ReadingState.derive(
                hasFixEver = true, isIdle = false, awaitingFreshFix = false,
                signalStale = false, snapshot = snapshot(readingCount = 10, settled = true)
            )
        )
    }

    @Test
    fun `unsettled window is converging with the fill progress`() {
        val state = ReadingState.derive(
            hasFixEver = true, isIdle = false, awaitingFreshFix = false,
            signalStale = false, snapshot = snapshot(readingCount = 3)
        )
        assertEquals(ReadingState.Converging(0.3f), state)
    }

    @Test
    fun `a dormant reading dims the hero`() {
        assertEquals(
            ReadingState.DIMMED_TEXT_ALPHA,
            ReadingState.heroAlpha(ReadingState.Dormant),
            0f
        )
    }

    @Test
    fun `converging ramps the hero from dimmed to full as the window fills`() {
        val dimmed = ReadingState.DIMMED_TEXT_ALPHA
        assertEquals(dimmed, ReadingState.heroAlpha(ReadingState.Converging(0f)), 1e-6f)
        assertEquals(
            dimmed + (1f - dimmed) / 2f,
            ReadingState.heroAlpha(ReadingState.Converging(0.5f)),
            1e-6f
        )
        assertEquals(1f, ReadingState.heroAlpha(ReadingState.Converging(1f)), 1e-6f)
    }

    @Test
    fun `acquiring and stable show the hero at full opacity`() {
        assertEquals(1f, ReadingState.heroAlpha(ReadingState.Acquiring), 0f)
        assertEquals(1f, ReadingState.heroAlpha(ReadingState.Stable), 0f)
    }
}
