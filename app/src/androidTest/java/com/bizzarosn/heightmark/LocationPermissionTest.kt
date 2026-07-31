package com.bizzarosn.heightmark

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LocationPermissionTest : HiltUiTestBase() {

    @get:Rule(order = 1)
    val fineLocationPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    @Test
    fun appStartsWithoutPermissions() {
        // The app should start without crashing regardless of the granted set;
        // launchHome verifies the hero view renders (permission message or value)
        launchHome()
    }

    @Test
    fun appWorksWithFineLocationOnly() {
        launchHome {
            onView(withId(R.id.unit_toggle_group)).check(matches(isDisplayed()))
            // Fine location is granted, so nothing is blocked and the
            // persistent recovery button has nothing to offer
            onView(withId(R.id.blocked_action_button)).check(matches(not(isDisplayed())))
        }
    }
}

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CoarseLocationPermissionTest : HiltUiTestBase() {

    @get:Rule(order = 1)
    val coarseLocationPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    @Test
    fun appPromptsForPreciseLocationWithCoarseOnly() {
        // GrantPermissionRule grants persist for the whole instrumentation run, so
        // when another test class has already granted FINE this coarse-only
        // scenario can't be exercised — skip rather than assert the wrong flow.
        // The assertion runs when this class executes in isolation.
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeFalse(
            "FINE already granted by an earlier test; coarse-only flow unavailable",
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )

        // Approximate-only grants can't drive GPS, so the app should ask the user
        // to upgrade to precise location
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // The permission check runs after an async preferences read
            Thread.sleep(2000)
            onView(withText(R.string.precise_location_required))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))

            // The dialog is dismissible rather than forced (setCancelable(true));
            // back-press is the least destructive way to refuse it — neither of
            // its two buttons ("Grant Permission" / "Open Settings") represents
            // "no thanks", so this is the only way to decline without acting
            pressBack()

            // Dismissal lands on the blocked screen, which explains the block
            // and keeps the same recovery action reachable as a persistent button
            onView(withId(R.id.elevation_text_view))
                .check(matches(withText(R.string.precise_location_blocked_message)))
            onView(withId(R.id.blocked_action_button))
                .check(matches(allOf(isDisplayed(), withText(R.string.grant_permission))))

            // A perform(), not just a check(), so the globally-enabled ATF pass
            // (HiltTestRunner) validates the button's touch target and label
            // while it's visible — tapping it directly would launch the real
            // system permission dialog, which this test doesn't drive
            onView(withId(R.id.button_feet)).perform(click())
        }
    }
}

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class BothLocationPermissionsTest : HiltUiTestBase() {

    @get:Rule(order = 1)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    @Test
    fun appWorksWithBothPermissions() {
        launchHome {
            onView(withId(R.id.unit_toggle_group)).check(matches(isDisplayed()))

            // The permission check runs after an async preferences read
            Thread.sleep(2000)

            // Both grants satisfy the fine-location requirement, so the hero
            // must never land in either blocked state
            onView(withId(R.id.elevation_text_view)).check(
                matches(not(withText(R.string.location_permission_required)))
            )
            onView(withId(R.id.elevation_text_view)).check(
                matches(not(withText(R.string.precise_location_required)))
            )
            // Nothing is blocked, so the persistent recovery button stays hidden
            onView(withId(R.id.blocked_action_button)).check(matches(not(isDisplayed())))
        }
    }
}
