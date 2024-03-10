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
}