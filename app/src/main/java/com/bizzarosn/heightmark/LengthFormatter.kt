package com.bizzarosn.heightmark

import androidx.annotation.StringRes
import java.util.Locale
import kotlin.math.round

/**
 * The single home of the metric/imperial branch: value conversion, number
 * formatting, and unit-resource selection. Pure JVM — callers resolve the
 * returned resource IDs against their Context.
 */
object LengthFormatter {

    /** Template + formatted value for a detail row ("112.4 m" / "369 ft"). */
    data class Detail(@StringRes val templateRes: Int, val valueText: String)

    fun detail(meters: Double, useMetric: Boolean, locale: Locale): Detail {
        return if (useMetric) {
            Detail(R.string.value_meters, String.format(locale, "%.1f", meters))
        } else {
            Detail(
                R.string.value_feet,
                String.format(locale, "%.0f", UnitConverter.metersToFeet(meters))
            )
        }
    }

    /** The hero number: the localized value rounded to a whole unit. */
    fun heroValue(meters: Double, useMetric: Boolean): Int {
        val localized = if (useMetric) meters else UnitConverter.metersToFeet(meters)
        return round(localized).toInt()
    }

    @StringRes
    fun unitRes(useMetric: Boolean): Int =
        if (useMetric) R.string.unit_meters else R.string.unit_feet

    @StringRes
    fun spokenUnitRes(useMetric: Boolean): Int =
        if (useMetric) R.string.unit_meters_spoken else R.string.unit_feet_spoken
}
