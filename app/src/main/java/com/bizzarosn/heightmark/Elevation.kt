package com.bizzarosn.heightmark

/**
 * The surface an elevation is measured from.
 *
 * The two differ by the local geoid separation — about -30 m across most of
 * North America, -106 m to +85 m worldwide — so a height is only meaningful
 * next to the datum it belongs to. Averaging across the two would offset the
 * result by that separation with nothing on screen to show for it.
 */
enum class ElevationDatum {
    /** Height above Mean Sea Level: the number this app exists to show. */
    MEAN_SEA_LEVEL,

    /**
     * Raw GNSS height above the WGS84 reference ellipsoid, which is what a fix
     * carries before conversion and all [AltitudeResolver] has to offer when
     * the device cannot load geoid data.
     */
    ELLIPSOID
}

/** A height in meters together with the [ElevationDatum] that gives it meaning. */
data class Elevation(val meters: Double, val datum: ElevationDatum)
