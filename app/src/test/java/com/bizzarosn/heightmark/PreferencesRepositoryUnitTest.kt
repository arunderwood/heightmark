package com.bizzarosn.heightmark

import io.mockk.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier

class PreferencesRepositoryUnitTest {

    @Test
    fun `preferencesRepository maintains instance reference`() {
        // Test that repository can be instantiated and maintains reference
        val mockContext = mockk<android.content.Context>(relaxed = true)
        val repository = PreferencesRepository(mockContext)
        
        assertNotNull("Repository should maintain instance reference", repository)
    }

    @Test
    fun `preferencesRepository constructor signature`() {
        // Test that repository has expected constructor signature
        val repositoryClass = PreferencesRepository::class.java
        
        // Verify constructor exists with Context parameter
        val constructor = repositoryClass.getDeclaredConstructor(android.content.Context::class.java)
        assertNotNull("Should have Context constructor", constructor)
    }

    @Test
    fun `preferencesRepository class is not abstract`() {
        // Test that repository class is not abstract
        val repositoryClass = PreferencesRepository::class.java
        assertFalse("Repository class should not be abstract", Modifier.isAbstract(repositoryClass.modifiers))
    }

    @Test
    fun `preferencesRepository has setUseMetricUnit method`() {
        // Test that repository has method for setting values
        val repositoryClass = PreferencesRepository::class.java
        
        try {
            // Method takes Boolean and Continuation parameter
            val setMethod = repositoryClass.getDeclaredMethod("setUseMetricUnit", Boolean::class.java, kotlin.coroutines.Continuation::class.java)
            assertNotNull("Should have setUseMetricUnit method", setMethod)
        } catch (e: NoSuchMethodException) {
            fail("Should have setUseMetricUnit method")
        }
    }
}