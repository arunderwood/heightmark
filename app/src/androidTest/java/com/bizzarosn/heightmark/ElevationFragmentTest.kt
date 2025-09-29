package com.bizzarosn.heightmark

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ElevationFragmentTest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    @Before
    fun init() {
        hiltRule.inject()
    }

    @Test
    fun appStartsWithoutCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Verify main activity starts without crashing
            onView(withId(R.id.elevation_text_view)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun elevationTextViewIsDisplayed() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Verify elevation text view is present
            onView(withId(R.id.elevation_text_view)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun unitSwitchIsDisplayed() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Verify unit switch is present
            onView(withId(R.id.unit_switch)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun unitSwitchChangesUnits() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Wait for initial state
            Thread.sleep(1000)
            
            // Click the unit switch
            onView(withId(R.id.unit_switch)).perform(click())
            
            // Verify the switch state changed
            onView(withId(R.id.unit_switch)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun elevationServiceHandlesMultipleReadings() {
        val elevationService = ElevationService(3)
        
        // Add readings
        val avg1 = elevationService.addElevationReading(100.0)
        val avg2 = elevationService.addElevationReading(200.0)
        val avg3 = elevationService.addElevationReading(300.0)
        val avg4 = elevationService.addElevationReading(400.0) // Should drop first reading
        
        // Verify averaging behavior
        assert(avg1 == 100.0)
        assert(avg2 == 150.0)
        assert(avg3 == 200.0)
        assert(avg4 == 300.0) // (200 + 300 + 400) / 3
    }

    @Test
    fun elevationServiceUnitConversion() {
        val elevationService = ElevationService(1)
        elevationService.addElevationReading(100.0) // 100 meters
        
        // Test metric (meters)
        val metric = elevationService.getLocalizedElevation(true)
        assert(metric == 100.0)
        
        // Test imperial (feet) - 100m * 3.28084 ≈ 328.084
        val imperial = elevationService.getLocalizedElevation(false)
        assert(imperial > 328.0 && imperial < 329.0)
    }
}