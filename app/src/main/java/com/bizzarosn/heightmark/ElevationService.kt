package com.bizzarosn.heightmark

class ElevationService(private val readingsCount: Int) {
    private val elevationReadings = mutableListOf<Double>()

    fun addElevationReading(elevation: Double): Double {
        if (elevationReadings.size >= readingsCount) {
            elevationReadings.removeAt(0)
        }
        elevationReadings.add(elevation)
        return getAverageElevation()
    }

    private fun getAverageElevation(): Double {
        return elevationReadings.average()
    }

    fun getLocalizedElevation(useMetric: Boolean): Double {
        val averageElevation = getAverageElevation()
        return if (useMetric) averageElevation else averageElevation * 3.28084 // Convert meters to feet
    }
}
