package com.bizzarosn.heightmark

import android.location.Location

/**
 * Accuracy accessors that collapse the platform's has-a-value/read-the-value
 * pairs into a nullable. Callers decide what an absent value means — the
 * reading filter treats it as acceptable, the average substitutes a default,
 * and the details panel shows "?".
 */

/** Vertical accuracy in meters, or null when the fix does not report one. */
fun Location.verticalAccuracyOrNull(): Float? =
    if (hasVerticalAccuracy()) verticalAccuracyMeters else null

/** Horizontal accuracy in meters, or null when the fix does not report one. */
fun Location.horizontalAccuracyOrNull(): Float? =
    if (hasAccuracy()) accuracy else null

/**
 * MSL altitude accuracy in meters (API 34), or null when unavailable — either
 * the platform is older, or [android.location.altitude.AltitudeConverter]
 * never populated it (no geoid data, ellipsoid fallback). Unlike
 * [verticalAccuracyOrNull], this bounds the error of the converted,
 * sea-level number actually shown on screen, geoid model error included.
 */
fun Location.mslAltitudeAccuracyOrNull(): Float? =
    if (hasMslAltitudeAccuracy()) mslAltitudeAccuracyMeters else null
