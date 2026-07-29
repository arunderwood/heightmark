package com.bizzarosn.heightmark

import android.app.Application
import android.content.Context
import androidx.test.espresso.accessibility.AccessibilityChecks
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

class HiltTestRunner : AndroidJUnitRunner() {
    init {
        // Every Espresso ViewAction in the whole instrumented suite validates
        // the full view hierarchy against Google's Accessibility Test
        // Framework and fails the test on any ERROR finding (touch target
        // size, missing speakable text, insufficient text contrast, ...).
        // The validator runs the framework's LATEST check preset, so checks
        // added in newer versions apply automatically as Dependabot bumps the
        // dependency. Suppressions, if one is ever unavoidable, belong here
        // via setSuppressingResultMatcher scoped to a single check + view.
        AccessibilityChecks.enable()
            .setRunChecksFromRootView(true)
    }

    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?
    ): Application {
        return super.newApplication(cl, HiltTestApplication::class.java.name, context)
    }
}
