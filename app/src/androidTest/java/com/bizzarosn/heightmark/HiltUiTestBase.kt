package com.bizzarosn.heightmark

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import dagger.hilt.android.testing.HiltAndroidRule
import org.junit.Before
import org.junit.Rule

/**
 * Shared scaffolding for instrumented UI tests: the Hilt rule + injection
 * and a home-screen launch helper. Subclasses still carry @HiltAndroidTest
 * and declare their own GrantPermissionRule with order = 1 — the granted
 * set is what distinguishes the permission test classes.
 */
abstract class HiltUiTestBase {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @Before
    fun injectHilt() {
        hiltRule.inject()
    }

    /** Launches the home screen, verifies the hero view is showing, then runs [block]. */
    protected fun launchHome(block: (ActivityScenario<MainActivity>) -> Unit = {}) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.elevation_text_view)).check(matches(isDisplayed()))
            block(scenario)
        }
    }
}
