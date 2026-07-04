package com.bizzarosn.heightmark

import android.content.Context
import android.location.Location
import android.location.altitude.AltitudeConverter
import android.util.Log
import androidx.annotation.WorkerThread
import java.io.IOException

/**
 * Resolves the elevation above Mean Sea Level for a GNSS fix.
 *
 * Raw [Location.getAltitude] is height above the WGS84 reference ellipsoid, which
 * differs from sea-level elevation by roughly -100 m to +85 m depending on where on
 * Earth the fix is. The platform [AltitudeConverter] corrects this using on-device
 * geoid data, entirely offline.
 *
 * Keep a single instance: the converter caches geoid data between calls, so the
 * first conversion in a region may take seconds while later ones are cheap.
 */
class AltitudeResolver(
    private val context: Context,
    private val converter: AltitudeConverter = AltitudeConverter()
) {

    /**
     * Returns the location's elevation above Mean Sea Level in meters, falling back
     * to the raw ellipsoid altitude if geoid data cannot be loaded.
     *
     * The location must have an altitude ([Location.hasAltitude]).
     */
    @WorkerThread
    @Synchronized
    fun mslAltitudeMeters(location: Location): Double {
        return try {
            converter.addMslAltitudeToLocation(context, location)
            if (location.hasMslAltitude()) location.mslAltitudeMeters else location.altitude
        } catch (e: IOException) {
            Log.w(TAG, "Geoid data unavailable, using ellipsoid altitude", e)
            location.altitude
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Location rejected by AltitudeConverter, using ellipsoid altitude", e)
            location.altitude
        }
    }

    companion object {
        private const val TAG = "AltitudeResolver"
    }
}
