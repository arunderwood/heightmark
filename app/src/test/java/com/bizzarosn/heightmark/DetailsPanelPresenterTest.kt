package com.bizzarosn.heightmark

import com.bizzarosn.heightmark.DetailsPanelPresenter.Input
import com.bizzarosn.heightmark.DetailsPanelPresenter.Row
import com.bizzarosn.heightmark.DetailsPanelPresenter.UNKNOWN_VALUE
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Row order is asserted whole-list on purpose: the panel is read top to
 * bottom, so a reordering is a real regression, not an implementation detail.
 */
class DetailsPanelPresenterTest {

    private fun input(
        isIdle: Boolean = false,
        location: android.location.Location? = null,
        nowNanos: Long = 0L,
        satellitesUsed: Int = 0,
        satellitesVisible: Int = 0,
        pressureHpa: Float? = null,
        readingCount: Int = 0,
        useMetric: Boolean = true
    ) = Input(
        isIdle = isIdle,
        location = location,
        nowElapsedRealtimeNanos = nowNanos,
        satellitesUsed = satellitesUsed,
        satellitesVisible = satellitesVisible,
        pressureHpa = pressureHpa,
        readingCount = readingCount,
        useMetric = useMetric,
        locale = Locale.US
    )

    private fun meters(value: String) = LengthFormatter.Detail(R.string.value_meters, value)

    private fun feet(value: String) = LengthFormatter.Detail(R.string.value_feet, value)

    @Test
    fun `without a fix the panel shows state, waiting, satellites and readings`() {
        val rows = DetailsPanelPresenter.rows(
            input(satellitesUsed = 2, satellitesVisible = 9, readingCount = 4)
        )

        assertEquals(
            listOf(
                Row(R.string.detail_state_tracking),
                Row(R.string.detail_no_fix),
                Row(R.string.detail_satellites, listOf(2, 9)),
                Row(R.string.detail_readings, listOf(4))
            ),
            rows
        )
    }

    @Test
    fun `a full fix renders every row in order`() {
        val location = TestLocations.detailsFix(
            ellipsoid = 150.0,
            msl = 112.4,
            verticalAccuracy = 3f,
            horizontalAccuracy = 5f,
            latitude = 47.61,
            longitude = -122.33,
            atNanos = 18_000_000_000L
        )

        val rows = DetailsPanelPresenter.rows(
            input(
                location = location,
                nowNanos = 30_000_000_000L,
                satellitesUsed = 7,
                satellitesVisible = 11,
                pressureHpa = 1013.2f,
                readingCount = 10
            )
        )

        assertEquals(
            listOf(
                Row(R.string.detail_state_tracking),
                Row(R.string.detail_msl, listOf(meters("112.4"))),
                Row(R.string.detail_ellipsoid, listOf(meters("150.0"))),
                Row(R.string.detail_geoid_offset, listOf(meters("37.6"))),
                Row(R.string.detail_accuracy, listOf(meters("3.0"), meters("5.0"))),
                Row(R.string.detail_position, listOf("47.61000", "-122.33000")),
                Row(R.string.detail_fix_age, listOf(12L)),
                Row(R.string.detail_satellites, listOf(7, 11)),
                Row(R.string.detail_pressure, listOf("1013.2")),
                Row(R.string.detail_readings, listOf(10))
            ),
            rows
        )
    }

    @Test
    fun `without geoid data both the sea-level and offset rows disappear`() {
        val location = TestLocations.detailsFix(ellipsoid = 150.0, msl = null)

        val templates = DetailsPanelPresenter.rows(input(location = location)).map { it.templateRes }

        assertEquals(
            listOf(
                R.string.detail_state_tracking,
                R.string.detail_ellipsoid,
                R.string.detail_accuracy,
                R.string.detail_position,
                R.string.detail_fix_age,
                R.string.detail_satellites,
                R.string.detail_readings
            ),
            templates
        )
    }

    @Test
    fun `unreported accuracies render as unknown`() {
        val location = TestLocations.detailsFix(verticalAccuracy = null, horizontalAccuracy = null)

        val accuracy = DetailsPanelPresenter.rows(input(location = location))
            .first { it.templateRes == R.string.detail_accuracy }

        assertEquals(listOf(UNKNOWN_VALUE, UNKNOWN_VALUE), accuracy.args)
    }

    @Test
    fun `one reported accuracy still renders alongside an unknown one`() {
        val location = TestLocations.detailsFix(verticalAccuracy = 2.5f, horizontalAccuracy = null)

        val accuracy = DetailsPanelPresenter.rows(input(location = location))
            .first { it.templateRes == R.string.detail_accuracy }

        assertEquals(listOf(meters("2.5"), UNKNOWN_VALUE), accuracy.args)
    }

    @Test
    fun `the pressure row is omitted on a barometer-less device`() {
        val rows = DetailsPanelPresenter.rows(input(pressureHpa = null))

        assertEquals(emptyList<Row>(), rows.filter { it.templateRes == R.string.detail_pressure })
    }

    @Test
    fun `imperial units switch every length row to feet`() {
        val location = TestLocations.detailsFix(
            ellipsoid = 150.0,
            msl = 112.4,
            verticalAccuracy = 3f,
            horizontalAccuracy = 5f
        )

        val rows = DetailsPanelPresenter.rows(input(location = location, useMetric = false))

        assertEquals(listOf(feet("369")), rows.first { it.templateRes == R.string.detail_msl }.args)
        assertEquals(
            listOf(feet("492")),
            rows.first { it.templateRes == R.string.detail_ellipsoid }.args
        )
        assertEquals(
            listOf(feet("10"), feet("16")),
            rows.first { it.templateRes == R.string.detail_accuracy }.args
        )
    }

    @Test
    fun `idle state swaps the leading row`() {
        val rows = DetailsPanelPresenter.rows(input(isIdle = true))

        assertEquals(Row(R.string.detail_state_idle), rows.first())
    }

    @Test
    fun `fix age truncates toward zero rather than rounding`() {
        val location = TestLocations.detailsFix(atNanos = 0L)

        val age = DetailsPanelPresenter.rows(input(location = location, nowNanos = 1_900_000_000L))
            .first { it.templateRes == R.string.detail_fix_age }

        assertEquals(listOf(1L), age.args)
    }
}
