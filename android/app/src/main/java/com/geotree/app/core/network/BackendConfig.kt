package com.geotree.app.core.network

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The single source of truth for the backend address. The default comes from BuildConfig
 * (the local Docker backend at http://10.0.2.2:8000 unless overridden with -Pgeotree.baseUrl,
 * e.g. for an explicit hosted test build); a user-verified override (e.g. a laptop on the LAN)
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

    /**
     * One-time upgrade step for hosted builds. Older test APKs could have saved a development
     * server (emulator 10.0.2.2, localhost, or a laptop on a private LAN); that override would keep
     * a hosted-default APK stuck on a computer that is no longer running. If this build's default
     * is a hosted server, a saved local/private address is removed so the hosted default applies.
     *
     * Only the server address changes: Room, the session, images and the offline map are untouched.
     * A saved non-local (e.g. HTTPS hosted) server is kept. Runs once, so a developer can still
     * choose a LAN server again afterwards. Returns true when an override was removed.
     */
    suspend fun migrateLegacyLocalOverride(): Boolean {
        if (isLocalDevelopmentUrl(defaultBaseUrl)) return false
        var removed = false
        dataStore.edit { prefs ->
            if (prefs[HOSTED_MIGRATION_DONE] == true) return@edit
            val stored = prefs[BASE_URL]
            if (stored != null && isLocalDevelopmentUrl(stored)) {
                prefs.remove(BASE_URL)
                removed = true
            }
            prefs[HOSTED_MIGRATION_DONE] = true
        }
        return removed
    }

    companion object {
        private val BASE_URL = stringPreferencesKey("backend_base_url")
        private val HOSTED_MIGRATION_DONE = booleanPreferencesKey("backend_hosted_migration_v1")
        private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.\d{1,3}\.\d{1,3}$""")
        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

        /**
         * True for development-only servers: localhost, loopback (127/8), the emulator host alias
         * 10.0.2.2 and private LAN ranges (10/8, 172.16/12, 192.168/16). Hosted names are false.
         */
        fun isLocalDevelopmentUrl(url: String): Boolean {
            val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: return false
            if (host == "localhost") return true
            val (a, b) = IPV4.matchEntire(host)?.destructured?.let { (x, y) -> x.toInt() to y.toInt() }
                ?: return false
            return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
        }

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
