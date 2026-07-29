package com.bizzarosn.heightmark

import android.Manifest
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ElevationFragmentTest : HiltUiTestBase() {

    @get:Rule(order = 1)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    @Test
    fun appStartsWithoutCrashing() {
        // launchHome verifies the main activity starts and shows the hero view
        launchHome()
    }

    @Test
    fun unitToggleIsDisplayed() {
        launchHome {
            onView(withId(R.id.unit_toggle_group)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun stabilityLineIsDisplayed() {
        launchHome {
            // The settling line is visible whenever GPS is usable
            onView(withId(R.id.stability_line)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun unitToggleChangesUnits() {
        launchHome {
            // Wait for initial state
            Thread.sleep(1000)

            // Select feet
            onView(withId(R.id.button_feet)).perform(click())

            // Verify the feet button is now checked
            onView(withId(R.id.button_feet)).check(matches(isChecked()))
        }
    }

    @Test
    fun elevationServiceHandlesMultipleReadings() {
        val elevationService = ElevationService(3)

        // Readings within the jump threshold roll through the window
        val avg1 = elevationService.addElevationReading(100.0)
        val avg2 = elevationService.addElevationReading(102.0)
        val avg3 = elevationService.addElevationReading(98.0)
        val avg4 = elevationService.addElevationReading(104.0) // Should drop first reading

        // Verify averaging behavior
        assert(avg1.averageMeters == 100.0)
        assert(avg2.averageMeters == 101.0)
        assert(avg3.averageMeters == 100.0)
        assert(avg4.averageMeters > 101.3 && avg4.averageMeters < 101.4) // (102 + 98 + 104) / 3
    }

    @Test
    fun elevationServiceUnitConversion() {
        val elevationService = ElevationService(1)
        val snapshot = elevationService.addElevationReading(100.0) // 100 meters

        // Test metric (meters)
        assert(snapshot.averageMeters == 100.0)

        // Test imperial (feet) - 100m * 3.28084 ≈ 328.084
        val imperial = UnitConverter.metersToFeet(snapshot.averageMeters)
        assert(imperial > 328.0 && imperial < 329.0)
    }
}
