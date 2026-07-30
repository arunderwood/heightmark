package com.bizzarosn.heightmark

import android.location.Location
import androidx.annotation.StringRes
import java.util.Locale

/**
 * Builds the diagnostic panel's lines from a plain snapshot of screen state.
 * Pure JVM, following [LengthFormatter]: it returns resource IDs and their
 * arguments, and the caller resolves them against its Context.
 *
 * The clock is an input rather than a [android.os.SystemClock] call so the
 * whole panel stays assertable in a JVM test.
 */
object DetailsPanelPresenter {

    /** Shown for an accuracy the fix does not report. */
    const val UNKNOWN_VALUE = "?"

    data class Input(
        val isIdle: Boolean,
        val location: Location?,
        val nowElapsedRealtimeNanos: Long,
        val satellitesUsed: Int,
        val satellitesVisible: Int,
        val pressureHpa: Float?,
        val readingCount: Int,
        val useMetric: Boolean,
        val locale: Locale
    )

    /**
     * One line of the panel. [args] hold plain values (String, Int, Long) or a
     * [LengthFormatter.Detail], which the caller resolves to a string first.
     */
    data class Row(@param:StringRes val templateRes: Int, val args: List<Any> = emptyList())

    fun rows(input: Input): List<Row> {
        val rows = mutableListOf<Row>()
        rows += Row(
            if (input.isIdle) R.string.detail_state_idle else R.string.detail_state_tracking
        )

        val location = input.location
        if (location == null) {
            rows += Row(R.string.detail_no_fix)
        } else {
            // Both MSL rows are gated on the same check: without geoid data
            // there is no sea-level figure and no offset to report
            if (location.hasMslAltitude()) {
                rows += Row(
                    R.string.detail_msl,
                    listOf(input.length(location.mslAltitudeMeters))
                )
            }
            rows += Row(R.string.detail_ellipsoid, listOf(input.length(location.altitude)))
            if (location.hasMslAltitude()) {
                rows += Row(
                    R.string.detail_geoid_offset,
                    listOf(input.length(location.altitude - location.mslAltitudeMeters))
                )
            }
            rows += Row(
                R.string.detail_accuracy,
                listOf(
                    input.lengthOrUnknown(location.verticalAccuracyOrNull()),
                    input.lengthOrUnknown(location.horizontalAccuracyOrNull())
                )
            )
            rows += Row(
                R.string.detail_position,
                listOf(
                    location.latitude.fmt(input.locale, POSITION_DECIMALS),
                    location.longitude.fmt(input.locale, POSITION_DECIMALS)
                )
            )
            rows += Row(
                R.string.detail_fix_age,
                listOf(
                    (input.nowElapsedRealtimeNanos - location.elapsedRealtimeNanos) /
                        NANOS_PER_SECOND
                )
            )
        }

        rows += Row(
            R.string.detail_satellites,
            listOf(input.satellitesUsed, input.satellitesVisible)
        )
        input.pressureHpa?.let { pressure ->
            rows += Row(
                R.string.detail_pressure,
                listOf(pressure.toDouble().fmt(input.locale, PRESSURE_DECIMALS))
            )
        }
        rows += Row(R.string.detail_readings, listOf(input.readingCount))

        return rows
    }

    private fun Input.length(meters: Double): LengthFormatter.Detail =
        LengthFormatter.detail(meters, useMetric, locale)

    private fun Input.lengthOrUnknown(meters: Float?): Any =
        meters?.let { length(it.toDouble()) } ?: UNKNOWN_VALUE

    private fun Double.fmt(locale: Locale, decimals: Int): String =
        String.format(locale, "%.${decimals}f", this)

    private const val NANOS_PER_SECOND = 1_000_000_000
    private const val POSITION_DECIMALS = 5
    private const val PRESSURE_DECIMALS = 1
}
