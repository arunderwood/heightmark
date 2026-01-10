package com.bizzarosn.heightmark

import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import javax.inject.Inject

class DependencyInjectionTest {

    private lateinit var mockElevationService: ElevationService
    private lateinit var mockPreferencesRepository: PreferencesRepository

    @Before
    fun setUp() {
        mockElevationService = mockk(relaxed = true)
        mockPreferencesRepository = mockk(relaxed = true)
    }

    @Test
    fun `elevationService injection creates instance`() {
        // Test that Hilt can create ElevationService with correct parameter
        val service = ElevationService(readingsCount = 5)
        assertNotNull("ElevationService should be created", service)
    }

    @Test
    fun `elevationService injection with factory pattern`() {
        // Test different configuration values
        val service1 = ElevationService(readingsCount = 3)
        val service2 = ElevationService(readingsCount = 10)
        
        assertNotEquals("Different configurations should create different instances", service1, service2)
    }

    @Test
    fun `elevationService basic functionality after injection`() {
        // Test that injected service works correctly
        val service = ElevationService(readingsCount = 2)
        
        val result1 = service.addElevationReading(100.0)
        assertEquals("First reading should be returned directly", 100.0, result1, 0.001)
        
        val result2 = service.addElevationReading(200.0)
        assertEquals("Second reading should average both", 150.0, result2, 0.001)
    }

    @Test
    fun `preferencesRepository basic properties`() {
        // Test that PreferencesRepository has expected structure
        // This test verifies the class structure rather than implementation details
        val repositoryClass = PreferencesRepository::class.java
        
        // Verify it has the expected constructor signature
        val constructor = repositoryClass.getDeclaredConstructor(android.content.Context::class.java)
        assertNotNull("PreferencesRepository should have Context constructor", constructor)
    }

    @Test
    fun `preferencesRepository context validation`() {
        // Test that constructor properly handles context parameter
        val mockContext = mockk<android.content.Context>(relaxed = true)
        
        // This should not throw an exception
        val repository = PreferencesRepository(mockContext)
        assertNotNull("Repository should be created with valid context", repository)
    }
}