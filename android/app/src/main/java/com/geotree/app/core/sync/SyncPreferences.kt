package com.geotree.app.core.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Pull cursor (the highest server_version fully applied to Room, never a device clock) and sync history. */
class SyncPreferences(private val dataStore: DataStore<Preferences>) {

    suspend fun lastPulledServerVersion(): Long = dataStore.data.first()[LAST_PULLED] ?: 0L

    suspend fun setLastPulledServerVersion(version: Long) {
        dataStore.edit { prefs ->
            // Monotonic: never move the cursor backwards.
            if (version > (prefs[LAST_PULLED] ?: 0L)) prefs[LAST_PULLED] = version
        }
    }

    /** Device time of the last sync run that completed. Display only; never a sync cursor. */
    val lastSuccessfulSyncAt: Flow<Long?> = dataStore.data.map { it[LAST_SUCCESS_AT] }

    suspend fun setLastSuccessfulSyncAt(epochMillis: Long) {
        dataStore.edit { it[LAST_SUCCESS_AT] = epochMillis }
    }

    private companion object {
        val LAST_PULLED = longPreferencesKey("last_pulled_server_version")
        val LAST_SUCCESS_AT = longPreferencesKey("last_successful_sync_at")
    }
}
