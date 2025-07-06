package com.bizzarosn.heightmark

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

@RunWith(AndroidJUnit4::class)
class StartupCrashTest {

    @Test
    fun mainActivityStartsSuccessfully() {
        // This test specifically checks for startup crashes
        var crashed = false
        var scenario: ActivityScenario<MainActivity>? = null
        
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            
            // Wait for activity to fully initialize
            Thread.sleep(3000)
            
            scenario.onActivity { activity ->
                // Verify activity is in a valid state
                assertNotNull("Activity should not be null", activity)
                assertFalse("Activity should not be finishing", activity.isFinishing)
                assertFalse("Activity should not be destroyed", activity.isDestroyed)
            }
            
        } catch (e: Exception) {
            crashed = true
            fail("MainActivity crashed on startup: ${e.message}")
        } finally {
            scenario?.close()
        }
        
        assertFalse("App should not crash on startup", crashed)
    }

    @Test
    fun fragmentInitializationDoesNotCrash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Verify the fragment manager is in a valid state
                val fragmentManager = activity.supportFragmentManager
                assertNotNull("Fragment manager should not be null", fragmentManager)
                
                // Wait for fragment initialization
                Thread.sleep(2000)
                
                // Check that no fragments are in an error state
                val fragments = fragmentManager.fragments
                for (fragment in fragments) {
                    if (fragment != null) {
                        assertNotNull("Fragment view should be created", fragment.view)
                        assertTrue("Fragment should be added", fragment.isAdded)
                        assertFalse("Fragment should not be detached", fragment.isDetached)
                    }
                }
            }
        }
    }

    @Test
    fun permissionHandlerInitializationDoesNotCrash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // This test ensures that the permission handler initialization
            // doesn't cause crashes during startup
            
            scenario.onActivity { activity ->
                // Wait for permission handler to initialize
                Thread.sleep(1000)
                
                // Verify activity is still in a valid state after permission handler init
                assertFalse("Activity should not be finishing after permission init", activity.isFinishing)
            }
        }
    }

    @Test
    fun preferencesRepositoryInitializationDoesNotCrash() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        
        // Test that PreferencesRepository can be instantiated without crashing
        var crashed = false
        
        try {
            val preferencesRepository = PreferencesRepository(context)
            assertNotNull("PreferencesRepository should be created", preferencesRepository)
        } catch (e: Exception) {
            crashed = true
            fail("PreferencesRepository initialization crashed: ${e.message}")
        }
        
        assertFalse("PreferencesRepository initialization should not crash", crashed)
    }

    @Test
    fun elevationServiceInitializationDoesNotCrash() {
        // Test that ElevationService can be instantiated without crashing
        var crashed = false
        
        try {
            val elevationService = ElevationService(10)
            assertNotNull("ElevationService should be created", elevationService)
            
            // Test basic functionality
            val result = elevationService.addElevationReading(100.0)
            assertEquals("First reading should equal input", 100.0, result, 0.001)
            
        } catch (e: Exception) {
            crashed = true
            fail("ElevationService initialization crashed: ${e.message}")
        }
        
        assertFalse("ElevationService initialization should not crash", crashed)
    }
}