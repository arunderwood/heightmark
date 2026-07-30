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
