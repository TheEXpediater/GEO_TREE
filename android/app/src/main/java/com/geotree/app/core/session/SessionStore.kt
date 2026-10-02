package com.geotree.app.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class Session(val accessToken: String, val email: String, val expiresAtMillis: Long) {
    /**
     * True once the server-issued expiry (JWT `exp`, 7 days after sign-in) has passed on this
     * device's clock. An unknown expiry (<= 0) is not treated as expired: the server still
     * rejects a bad token with 401, which sync reports as "sign in to sync".
     */
    fun isExpired(nowMillis: Long): Boolean = expiresAtMillis in 1..nowMillis
}

/**
 * NONE: never signed in (or signed out) → Login.
 * VALID: full use, including sync.
 * EXPIRED: local field work continues; only server sync waits for a new sign-in.
 */
enum class SessionState { NONE, VALID, EXPIRED }

fun sessionState(session: Session?, nowMillis: Long): SessionState = when {
    session == null -> SessionState.NONE
    session.isExpired(nowMillis) -> SessionState.EXPIRED
    else -> SessionState.VALID
}

/**
 * Persists the signed-in session so field work continues offline after one successful login.
 * The session survives app restarts and reboots; it is removed only by an explicit Sign Out.
 * Expiry never deletes it (see [SessionState.EXPIRED]).
 */
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
