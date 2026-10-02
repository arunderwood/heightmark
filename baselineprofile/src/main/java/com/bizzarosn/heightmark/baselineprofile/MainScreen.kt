package com.bizzarosn.heightmark.baselineprofile

import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

/** The release build's applicationId. Benchmark build types add no suffix. */
internal const val TARGET_PACKAGE = "com.bizzarosn.heightmark"

private const val SCREEN_TIMEOUT_MS = 10_000L

// The line's state follows whatever GPS fixes the emulator delivers, and the
// Acquiring wave has no end state to wait for, so the hold is a fixed time.
private const val SETTLING_LINE_HOLD_MS = 3_000L

/**
 * Grants location so the app opens on the reading screen. Without it the
 * screen stops at the blocked state, which never composes the settling line
 * in motion. The grant survives the process kill between iterations.
 */
internal fun MacrobenchmarkScope.grantLocationPermission() {
    device.executeShellCommand("pm grant $packageName android.permission.ACCESS_FINE_LOCATION")
    device.executeShellCommand("pm grant $packageName android.permission.ACCESS_COARSE_LOCATION")
}

/** Waits for the reading screen, and fails if it is still blocked. */
internal fun MacrobenchmarkScope.waitForSettlingLine() {
    check(device.wait(Until.hasObject(By.res(packageName, "stability_line")), SCREEN_TIMEOUT_MS)) {
        "The settling line never appeared"
    }
    check(!device.hasObject(By.res(packageName, "blocked_action_button"))) {
        "The screen is blocked. Location permission or location services are off."
    }
}

/** Lets the settling line animate, so its draw path runs inside the measured window. */
internal fun MacrobenchmarkScope.watchSettlingLine() {
    SystemClock.sleep(SETTLING_LINE_HOLD_MS)
}
