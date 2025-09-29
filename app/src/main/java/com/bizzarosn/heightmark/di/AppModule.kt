package com.bizzarosn.heightmark.di

import android.content.Context
import android.location.LocationManager
import com.bizzarosn.heightmark.ElevationService
import com.bizzarosn.heightmark.PreferencesRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun providePreferencesRepository(
        @ApplicationContext context: Context
    ): PreferencesRepository {
        return PreferencesRepository(context)
    }

    @Provides
    fun provideElevationService(): ElevationService {
        return ElevationService(readingsCount = 10)
    }

    @Provides
    @Singleton
    fun provideLocationManager(
        @ApplicationContext context: Context
    ): LocationManager {
        return context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
}