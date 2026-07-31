package com.bizzarosn.heightmark

import android.content.Context
import android.location.Location
import android.location.altitude.AltitudeConverter
import android.util.Log
import androidx.annotation.WorkerThread
import java.io.IOException

/**
 * Resolves the elevation of a GNSS fix, tagged with the datum it landed on.
 *
 * Raw [Location.getAltitude] is height above the WGS84 reference ellipsoid, which
 * differs from sea-level elevation by roughly -100 m to +85 m depending on where on
 * Earth the fix is. The platform [AltitudeConverter] corrects this using on-device
 * geoid data, entirely offline.
 *
 * Conversion can fail — no geoid data, a location the converter rejects — and the
 * ellipsoid height is still worth returning, but only if callers can tell the two
 * apart: [ElevationSession] keeps them out of one another's averages, and the
 * screen names the datum it is showing. Hence [Elevation] rather than a bare
 * Double, which is what let a fallback pass for a sea-level reading.
 *
 * Keep a single instance: the converter caches geoid data between calls, so the
 * first conversion in a region may take seconds while later ones are cheap.
 */
class AltitudeResolver(
    private val context: Context,
    private val converter: AltitudeConverter = AltitudeConverter()
) {

    /**
     * Returns the location's elevation above Mean Sea Level, falling back to its
     * raw ellipsoid altitude — tagged [ElevationDatum.ELLIPSOID] — whenever the
     * conversion cannot be made.
     *
     * The location must have an altitude ([Location.hasAltitude]).
     */
    @WorkerThread
    @Synchronized
    fun resolve(location: Location): Elevation {
        return try {
            converter.addMslAltitudeToLocation(context, location)
            if (location.hasMslAltitude()) {
                Elevation(location.mslAltitudeMeters, ElevationDatum.MEAN_SEA_LEVEL)
            } else {
                Log.w(TAG, "Converter left no MSL altitude, using ellipsoid altitude")
                location.ellipsoidHeight()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Geoid data unavailable, using ellipsoid altitude", e)
            location.ellipsoidHeight()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Location rejected by AltitudeConverter, using ellipsoid altitude", e)
            location.ellipsoidHeight()
        }
    }

    private fun Location.ellipsoidHeight() = Elevation(altitude, ElevationDatum.ELLIPSOID)

    companion object {
        private const val TAG = "AltitudeResolver"
    }
}
