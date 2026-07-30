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
    data class Detail(@param:StringRes val templateRes: Int, val valueText: String)

    /**
     * The hero number and everything needed to render it, so the value and
     * the unit naming it can never be resolved from different branches.
     * [spokenUnitRes] is the screen-reader form ("meters", not "m").
     */
    data class Hero(
        val value: Int,
        @param:StringRes val unitRes: Int,
        @param:StringRes val spokenUnitRes: Int
    )

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

    /** The hero number: the value rounded to a whole unit, with its unit names. */
    fun hero(meters: Double, useMetric: Boolean): Hero {
        val localized = if (useMetric) meters else UnitConverter.metersToFeet(meters)
        return Hero(
            value = round(localized).toInt(),
            unitRes = if (useMetric) R.string.unit_meters else R.string.unit_feet,
            spokenUnitRes = if (useMetric) {
                R.string.unit_meters_spoken
            } else {
                R.string.unit_feet_spoken
            }
        )
    }
}
