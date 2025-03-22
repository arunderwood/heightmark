package com.bizzarosn.heightmark

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

private object PreferencesKeys {
    val USE_METRIC_UNIT = booleanPreferencesKey("use_metric_unit")
}

class PreferencesRepository(context: Context) {
    private val dataStore = context.dataStore

    val useMetricUnit: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.USE_METRIC_UNIT] != false
    }

    suspend fun setUseMetricUnit(useMetric: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.USE_METRIC_UNIT] = useMetric
        }
    }
}
