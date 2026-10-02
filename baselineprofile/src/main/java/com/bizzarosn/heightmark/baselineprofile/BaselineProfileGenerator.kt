package com.bizzarosn.heightmark.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the classes and methods a cold start runs, up to and including the
 * settling line's first seconds of animation.
 *
 * Run with `./gradlew :app:generateReleaseBaselineProfile`. The plugin writes
 * the result to app/src/release/generated/baselineProfiles/, which is
 * committed.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = TARGET_PACKAGE) {
        grantLocationPermission()
        pressHome()
        startActivityAndWait()
        waitForSettlingLine()
        watchSettlingLine()
    }
}
