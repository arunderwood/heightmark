package com.bizzarosn.heightmark

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Decides whether the barometer measures the device's height, by checking
 * the height change it reports against GNSS over the same span.
 *
 * On foot, in a car or in an unpressurized aircraft the two agree within
 * GNSS error. In a pressurized cabin the barometer reads the cabin, which an
 * airliner holds at or below 8,000 ft while the aircraft cruises near
 * 11,000 m. The cabin moves about a fifth as far as the aircraft, so over any
 * climb or descent the two disagree by hundreds of meters.
 *
 *  - Trust is lost when, across the fixes of the last [windowNanos], the two
 *    changes differ by more than [minDisagreementMeters] or three combined
 *    GNSS sigmas, whichever is larger.
 *  - Trust returns when the changes agree across at least
 *    [minAgreementNanos] and the barometer's standard-atmosphere height is
 *    within [maxStandardAltitudeGapMeters] of GNSS. Agreement alone is not
 *    enough: in level cruise neither height changes, while the cabin is still
 *    pressurized thousands of meters below the aircraft. Weather moves the
 *    standard-atmosphere height by a few hundred meters, well inside the gap.
 *
 * Pure JVM; the caller supplies the timestamps.
 */
class BarometerCrossCheck(
    private val windowNanos: Long = 120_000_000_000L,
    private val minAgreementNanos: Long = 60_000_000_000L,
    private val minDisagreementMeters: Double = 100.0,
    private val maxStandardAltitudeGapMeters: Double = 1_000.0
) {

    /** True while the barometer's height changes may move the reading. */
    var isTrusted: Boolean = true
        private set

    private data class Sample(
        val atNanos: Long,
        val gnssMeters: Double,
        val sigmaMeters: Double,
        val barometerMeters: Double
    )

    private val samples = ArrayDeque<Sample>()

    /**
     * Records a fix of [gnssMeters], with 1-sigma vertical error
     * [sigmaMeters], taken at [atNanos] on the elapsed-realtime clock.
     * [barometerMeters] is the barometer's height change since tracking
     * began, and [standardAltitudeMeters] its standard-atmosphere height, at
     * the same moment; null when the barometer has no reading yet.
     */
    fun onFix(
        atNanos: Long,
        gnssMeters: Double,
        sigmaMeters: Double,
        barometerMeters: Double,
        standardAltitudeMeters: Double?
    ) {
        if (standardAltitudeMeters == null) return
        val sample = Sample(atNanos, gnssMeters, sigmaMeters, barometerMeters)
        samples.addLast(sample)
        while (atNanos - samples.first().atNanos > windowNanos) samples.removeFirst()
        val oldest = samples.first()
        if (oldest === sample) return

        val disagreement = abs(
            (sample.gnssMeters - oldest.gnssMeters) - (sample.barometerMeters - oldest.barometerMeters)
        )
        val tolerance = max(minDisagreementMeters, 3 * hypot(sample.sigmaMeters, oldest.sigmaMeters))
        val agrees = disagreement <= tolerance
        isTrusted = if (isTrusted) {
            agrees
        } else {
            agrees &&
                atNanos - oldest.atNanos >= minAgreementNanos &&
                abs(standardAltitudeMeters - gnssMeters) <= maxStandardAltitudeGapMeters
        }
    }

    /** Forgets every fix, for when later fixes are on another datum. Trust is kept. */
    fun clearFixes() {
        samples.clear()
    }
}
