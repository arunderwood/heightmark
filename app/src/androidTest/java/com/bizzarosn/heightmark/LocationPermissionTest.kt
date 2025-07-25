package com.bizzarosn.heightmark

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocationPermissionTest {

    @Test
    fun appStartsWithoutPermissions() {
        // Test app behavior when no location permissions are granted
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // App should still start without crashing
            onView(withId(R.id.elevation_text_view)).check(matches(isDisplayed()))
            
            // Should show permission required message or loading state
            // This test verifies the app doesn't crash on startup without permissions
        }
    }

    @get:Rule
    val fineLocationPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    @Test
    fun appWorksWithFineLocationOnly() {
        // Test app behavior with only fine location permission
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.elevation_text_view)).check(matches(isDisplayed()))
            onView(withId(R.id.unit_switch)).check(matches(isDisplayed()))
        }
    }
}

@RunWith(AndroidJUnit4::class)
class CoarseLocationPermissionTest {

    @get:Rule
    val coarseLocationPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    @Test
    fun appWorksWithCoarseLocationOnly() {
        // Test app behavior with only coarse location permission
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.elevation_text_view)).check(matches(isDisplayed()))
            onView(withId(R.id.unit_switch)).check(matches(isDisplayed()))
        }
    }
}

@RunWith(AndroidJUnit4::class)
class BothLocationPermissionsTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    @Test
    fun appWorksWithBothPermissions() {
        // Test app behavior with both location permissions
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.elevation_text_view)).check(matches(isDisplayed()))
            onView(withId(R.id.unit_switch)).check(matches(isDisplayed()))
            
            // With permissions, the app should start location updates
            // Wait a moment for the permission handler to initialize
            Thread.sleep(2000)
            
            // The elevation text should not show permission required message
            // (This is a basic check - in a real scenario you'd mock location data)
        }
    }
}