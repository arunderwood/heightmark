package com.bizzarosn.heightmark

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Walks the screen through its interactive states so the Accessibility Test
 * Framework checks (enabled globally in [HiltTestRunner]) validate the whole
 * hierarchy in each of them. Every perform() triggers a full-root-view pass:
 * touch target sizes, speakable text, screenshot-based text contrast,
 * duplicate descriptions, and whatever checks future framework versions add.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AccessibilityChecksTest {

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
    fun allInteractiveStatesPassAccessibilityChecks() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.button_feet)).perform(click())
            onView(withId(R.id.details_toggle)).perform(click()) // expand details
            onView(withId(R.id.details_toggle)).perform(click()) // collapse details
            onView(withId(R.id.button_meters)).perform(click()) // restore default unit
        }
    }
}
