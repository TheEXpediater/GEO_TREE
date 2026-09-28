package com.geotree.app.core.network

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The single source of truth for the backend address. Emulator default comes from
 * BuildConfig; a user-verified override (physical phone on LAN, later a hosted URL)
 * is stored in DataStore. Repositories never see the address directly.
 */
class BackendConfig(
    private val dataStore: DataStore<Preferences>,
    val defaultBaseUrl: String,
) {
    val baseUrl: Flow<String> = dataStore.data.map { it[BASE_URL] ?: defaultBaseUrl }

    suspend fun current(): String = baseUrl.first()

    suspend fun save(normalizedUrl: String) {
        dataStore.edit { it[BASE_URL] = normalizedUrl }
    }

    suspend fun resetToDefault() {
        dataStore.edit { it.remove(BASE_URL) }
    }

    companion object {
        private val BASE_URL = stringPreferencesKey("backend_base_url")
        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

        /** Accepts "192.168.1.20:8000" or "http://host:8000/" and returns "http://host:8000", or null. */
        fun normalize(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if (SCHEME.containsMatchIn(trimmed)) trimmed else "http://$trimmed"
            // Rejects non-http(s) schemes and addresses without a host.
            withScheme.toHttpUrlOrNull() ?: return null
            return withScheme.trimEnd('/')
        }
    }
}
