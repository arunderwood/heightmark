package com.bizzarosn.heightmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Startup smoke tests. A crash during launch or injection surfaces as a thrown
 * exception out of [launchHome], which fails the test on its own — these add
 * the checks that a silent bad state would otherwise pass: an activity that
 * came up already finishing, or a fragment attached without a view.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class StartupCrashTest : HiltUiTestBase() {

    @Test
    fun mainActivityStartsSuccessfully() {
        launchHome { scenario ->
            scenario.onActivity { activity ->
                assertFalse("Activity should not be finishing", activity.isFinishing)
                assertFalse("Activity should not be destroyed", activity.isDestroyed)
            }
        }
    }

    @Test
    fun fragmentInitializationDoesNotCrash() {
        launchHome { scenario ->
            scenario.onActivity { activity ->
                val fragments = activity.supportFragmentManager.fragments
                assertTrue("The host should have a fragment", fragments.isNotEmpty())
                for (fragment in fragments) {
                    assertNotNull("Fragment view should be created", fragment.view)
                    assertTrue("Fragment should be added", fragment.isAdded)
                    assertFalse("Fragment should not be detached", fragment.isDetached)
                }
            }
        }
    }

    @Test
    fun permissionHandlerInitializationDoesNotCrash() {
        // The handler registers its ActivityResultLauncher in onCreate; a
        // late registration throws and takes the activity down with it
        launchHome { scenario ->
            scenario.onActivity { activity ->
                assertFalse(
                    "Activity should not be finishing after permission init",
                    activity.isFinishing
                )
            }
        }
    }
}
