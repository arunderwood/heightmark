package com.bizzarosn.heightmark

/** Length-unit conversions shared across the app. */
object UnitConverter {
    /** Feet per meter (international foot). */
    const val FEET_PER_METER = 3.28084

    /** Converts a length in meters to feet. */
    fun metersToFeet(meters: Double): Double = meters * FEET_PER_METER
}
