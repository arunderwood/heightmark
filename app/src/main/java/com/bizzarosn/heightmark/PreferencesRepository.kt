package com.bizzarosn.heightmark

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

private object PreferencesKeys {
    val USE_METRIC_UNIT = booleanPreferencesKey("use_metric_unit")
    val SHOW_DETAILS = booleanPreferencesKey("show_details")
    val HAS_REQUESTED_LOCATION_PERMISSION = booleanPreferencesKey(
        "has_requested_location_permission"
    )
}

@Singleton
class PreferencesRepository @Inject constructor(@ApplicationContext context: Context) {
    private val dataStore = context.dataStore

    val useMetricUnit: Flow<Boolean> =
        booleanFlow(PreferencesKeys.USE_METRIC_UNIT, default = true)

    suspend fun setUseMetricUnit(useMetric: Boolean) {
        setBoolean(PreferencesKeys.USE_METRIC_UNIT, useMetric)
    }

    val showDetails: Flow<Boolean> =
        booleanFlow(PreferencesKeys.SHOW_DETAILS, default = false)

    suspend fun setShowDetails(show: Boolean) {
        setBoolean(PreferencesKeys.SHOW_DETAILS, show)
    }

    /**
     * Whether this install has ever triggered the system location-permission
     * dialog. Persisted, not session state: [LocationPermissionPolicy] uses
     * it to tell a true first launch (land on the blocked screen's own
     * explanation, no dialog) apart from a returning user the OS can no
     * longer show a rationale for — a distinction that must survive the
     * process dying between sessions, which a ViewModel-held flag would not.
     */
    val hasRequestedLocationPermission: Flow<Boolean> =
        booleanFlow(PreferencesKeys.HAS_REQUESTED_LOCATION_PERMISSION, default = false)

    suspend fun setHasRequestedLocationPermission(requested: Boolean) {
        setBoolean(PreferencesKeys.HAS_REQUESTED_LOCATION_PERMISSION, requested)
    }

    private fun booleanFlow(key: Preferences.Key<Boolean>, default: Boolean): Flow<Boolean> =
        dataStore.data
            .catch { exception ->
                if (exception is IOException) emit(emptyPreferences()) else throw exception
            }
            .map { preferences -> preferences[key] ?: default }

    private suspend fun setBoolean(key: Preferences.Key<Boolean>, value: Boolean) {
        dataStore.edit { preferences -> preferences[key] = value }
    }
}
