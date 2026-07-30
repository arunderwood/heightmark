package com.bizzarosn.heightmark.di

import android.content.Context
import android.hardware.SensorManager
import android.location.LocationManager
import com.bizzarosn.heightmark.AltitudeResolver
import com.bizzarosn.heightmark.ElevationService
import com.bizzarosn.heightmark.PressureDeltaDetector
import com.bizzarosn.heightmark.StillnessDetector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Only the bindings Dagger cannot derive on its own live here: framework
 * classes reached through `getSystemService`, and constructors whose
 * parameters are tuning values with Kotlin defaults that Dagger would
 * otherwise try to inject. Everything else carries an `@Inject constructor`.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    fun provideElevationService(): ElevationService {
        return ElevationService(readingsCount = ElevationService.DEFAULT_WINDOW_SIZE)
    }

    @Provides
    @Singleton
    fun provideLocationManager(
        @ApplicationContext context: Context
    ): LocationManager {
        return context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    @Provides
    @Singleton
    fun provideAltitudeResolver(
        @ApplicationContext context: Context
    ): AltitudeResolver {
        return AltitudeResolver(context)
    }

    @Provides
    @Singleton
    fun provideSensorManager(
        @ApplicationContext context: Context
    ): SensorManager {
        return context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    @Provides
    fun provideStillnessDetector(): StillnessDetector {
        return StillnessDetector()
    }

    @Provides
    fun providePressureDeltaDetector(): PressureDeltaDetector {
        return PressureDeltaDetector()
    }
}