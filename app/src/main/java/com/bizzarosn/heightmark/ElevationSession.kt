package com.bizzarosn.heightmark

import android.location.Location
import javax.inject.Inject

/**
 * The tracking session's domain policy: which GNSS fixes are worth averaging,
 * and what the number on screen is currently worth.
 *
 * Owns the [ElevationService] window together with the flags that describe it
 * — first fix seen, duty-cycle idleness, the post-flush wait for a fresh fix,
 * the last value shown — so a flush and the state explaining it can never
 * drift apart.
 *
 * Following [DetailsPanelPresenter], the clock is an input rather than a
 * [android.os.SystemClock] call, so the whole policy stays assertable in a JVM
 * test. Nothing here touches a Context or a view: [ElevationTracker] keeps the
 * listener registration and the coroutines, and [ElevationFragment] the
 * rendering.
 *
 * Confined to the main thread — GNSS callbacks and the geoid-conversion
 * coroutine both land there — so none of the state is synchronized.
 */
class ElevationSession @Inject constructor(
    private val elevationService: ElevationService
) {

    /**
     * A fix admitted for geoid conversion. [epoch] pins it to the averaging
     * window it was admitted against, so [commit] can drop it if that window
     * was flushed while the conversion was in flight. A null
     * [verticalAccuracyMeters] means the fix reported none.
     */
    data class PendingFix(val epoch: Int, val verticalAccuracyMeters: Float?)

    /** True once any fix has been committed this session. */
    var hasFix: Boolean = false
        private set

    /** True while the GPS radio is off for the stationary duty cycle. */
    var isIdle: Boolean = false
        private set

    /**
     * True once [ElevationTracker]'s fix-age watchdog has gone off: tracking
     * is still active but no fix has committed recently enough to trust the
     * displayed reading. Distinct from [awaitingFreshFix] — a watchdog expiry
     * does not discard the averaging window, since the outage is usually
     * brief (elevator lobby, parking garage) and the same reading is still
     * likely right once fixes resume.
     */
    var signalStale: Boolean = false
        private set

    /**
     * The last elevation committed, kept across a flush so the hero number can
     * stay on screen — dimmed by [ReadingState.Dormant] — until fresh fixes
     * land. Null until the first fix.
     */
    var displayedElevationMeters: Double? = null
        private set

    /** Readings currently in the averaging window; the details panel shows it. */
    val readingCount: Int
        get() = elevationService.snapshot().readingCount

    private var awaitingFreshFix = false
    private var epoch = 0

    /** Null until the first pause, so a pause timestamp of 0 is still a pause. */
    private var pausedAtElapsedMs: Long? = null

    /**
     * Admits a fix for conversion, or returns null if it must not reach the
     * average.
     */
    fun offer(location: Location): PendingFix? {
        // A fix without altitude would read as 0.0 and poison the average
        if (!location.hasAltitude()) return null
        // Skip fixes whose vertical error would drag the average around. A fix
        // that reports no vertical accuracy is kept: unknown is not the same as bad.
        val reported = location.verticalAccuracyOrNull()
        if (reported != null && reported > MAX_VERTICAL_ACCURACY_M) return null
        return PendingFix(epoch, reported)
    }

    /**
     * Adds a converted fix to the average, unless its window was flushed while
     * it was converting. Returns true when the reading was applied and the
     * screen needs a repaint.
     */
    fun commit(pending: PendingFix, elevationMeters: Double): Boolean {
        if (pending.epoch != epoch) return false
        displayedElevationMeters = elevationService
            .addElevationReading(elevationMeters, pending.verticalAccuracyMeters)
            .averageMeters
        hasFix = true
        awaitingFreshFix = false
        signalStale = false
        return true
    }

    /** The GPS radio went off for the stationary duty cycle. */
    fun enterIdle() {
        isIdle = true
    }

    /**
     * The fix-age watchdog expired: tracking is active but no fix has
     * committed recently enough to trust the displayed reading. A no-op
     * before the first fix, where [ReadingState.Acquiring] already covers it,
     * and while idle, where the radio is off on purpose and a fix drought is
     * the expected condition rather than a lost signal.
     */
    fun onFixWatchdogExpired() {
        if (hasFix && !isIdle) signalStale = true
    }

    /** A wake fired: the device moved, so the window is stale. */
    fun wake() {
        isIdle = false
        flush()
    }

    /** Screen backgrounded at [nowElapsedRealtimeMs]; the duty cycle ends with it. */
    fun onPaused(nowElapsedRealtimeMs: Long) {
        pausedAtElapsedMs = nowElapsedRealtimeMs
        isIdle = false
    }

    /**
     * Screen foregrounded at [nowElapsedRealtimeMs]. A long gap away from the
     * app can mean a whole new elevation, so the average starts over rather
     * than walking the stale window there.
     */
    fun onResumed(nowElapsedRealtimeMs: Long) {
        val pausedAt = pausedAtElapsedMs ?: return
        if (nowElapsedRealtimeMs - pausedAt > RESET_AFTER_GAP_MS) {
            flush()
        }
    }

    /** What the current reading is worth. */
    fun readingState(): ReadingState = ReadingState.derive(
        hasFixEver = hasFix,
        isIdle = isIdle,
        awaitingFreshFix = awaitingFreshFix,
        signalStale = signalStale,
        snapshot = elevationService.snapshot()
    )

    /** Discards the averaging window; the cached number stays available, dimmed. */
    private fun flush() {
        elevationService.reset()
        epoch++
        if (hasFix) {
            awaitingFreshFix = true
        }
    }

    companion object {
        /** Fixes reporting worse vertical error than this would drag the average around. */
        const val MAX_VERTICAL_ACCURACY_M = 50f

        /** A background gap longer than this can mean a whole new elevation. */
        const val RESET_AFTER_GAP_MS = 30_000L
    }
}
