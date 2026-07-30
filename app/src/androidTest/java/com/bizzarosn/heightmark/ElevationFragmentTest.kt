package com.bizzarosn.heightmark

import android.Manifest
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.hamcrest.CoreMatchers.not
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

    /**
     * Exercises the details sources through a full arm/release/re-arm cycle.
     * A listener leaked or double-registered by DetailsSourcesController is
     * invisible to the compiler, so this is the only automated guard on it.
     */
    @Test
    fun detailsPanelSurvivesRepeatedToggling() {
        launchHome {
            Thread.sleep(1000)

            onView(withId(R.id.details_toggle)).perform(click())
            onView(withId(R.id.details_panel)).check(matches(isDisplayed()))

            onView(withId(R.id.details_toggle)).perform(click())
            onView(withId(R.id.details_panel)).check(matches(not(isDisplayed())))

            onView(withId(R.id.details_toggle)).perform(click())
            onView(withId(R.id.details_panel)).check(matches(isDisplayed()))

            // Leave the collapsed default behind for the rest of the suite
            onView(withId(R.id.details_toggle)).perform(click())
        }
    }
}
