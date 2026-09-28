package com.geotree.app.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class Session(val accessToken: String, val email: String, val expiresAtMillis: Long)

/** Persists the signed-in session so field work continues offline after one successful login. */
class SessionStore(private val dataStore: DataStore<Preferences>) {

    @Volatile
    private var cachedToken: String? = null

    val session: Flow<Session?> = dataStore.data.map { it.toSession() }

    suspend fun current(): Session? = dataStore.data.first().toSession().also { cachedToken = it?.accessToken }

    /** Synchronous read for the OkHttp interceptor; populated by [current], [save] and [clear]. */
    fun cachedAccessToken(): String? = cachedToken

    suspend fun save(session: Session) {
        dataStore.edit {
            it[TOKEN] = session.accessToken
            it[EMAIL] = session.email
            it[EXPIRES_AT] = session.expiresAtMillis
        }
        cachedToken = session.accessToken
    }

    suspend fun clear() {
        dataStore.edit {
            it.remove(TOKEN)
            it.remove(EMAIL)
            it.remove(EXPIRES_AT)
        }
        cachedToken = null
    }

    private fun Preferences.toSession(): Session? {
        val token = this[TOKEN] ?: return null
        return Session(token, this[EMAIL].orEmpty(), this[EXPIRES_AT] ?: 0L)
    }

    private companion object {
        val TOKEN = stringPreferencesKey("session_access_token")
        val EMAIL = stringPreferencesKey("session_email")
        val EXPIRES_AT = longPreferencesKey("session_expires_at")
    }
}
