package com.bizzarosn.heightmark.baselineprofile

import androidx.benchmark.macro.ArtMetric
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold-start time with no AOT compilation (a fresh install or update before
 * any profile applies) against the committed baseline profile.
 *
 * [ArtMetric] counts the JIT and class-loading work the profile removes,
 * across startup and the settling line's first seconds of animation. Neither
 * frame metric works on the aosp-atd image: FrameTimingMetric finds no
 * SurfaceFlinger frame slices, and FrameTimingGfxInfoMetric counts no frames.
 *
 * Run with `./gradlew :baselineprofile:pixel8proapi36profileBenchmarkReleaseAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark`.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupNoCompilation() = startup(CompilationMode.None())

    @Test
    fun startupBaselineProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(compilationMode: CompilationMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric(), ArtMetric()),
        compilationMode = compilationMode,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = {
            grantLocationPermission()
            pressHome()
        }
    ) {
        startActivityAndWait()
        waitForSettlingLine()
        watchSettlingLine()
    }

    private companion object {
        const val ITERATIONS = 20
    }
}
