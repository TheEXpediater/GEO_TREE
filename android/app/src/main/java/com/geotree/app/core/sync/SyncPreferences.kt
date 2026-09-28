package com.geotree.app.core.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.first

/** Pull cursor: the highest server_version fully applied to Room. Never a device clock. */
class SyncPreferences(private val dataStore: DataStore<Preferences>) {

    suspend fun lastPulledServerVersion(): Long = dataStore.data.first()[LAST_PULLED] ?: 0L

    suspend fun setLastPulledServerVersion(version: Long) {
        dataStore.edit { prefs ->
            // Monotonic: never move the cursor backwards.
            if (version > (prefs[LAST_PULLED] ?: 0L)) prefs[LAST_PULLED] = version
        }
    }

    private companion object {
        val LAST_PULLED = longPreferencesKey("last_pulled_server_version")
    }
}
