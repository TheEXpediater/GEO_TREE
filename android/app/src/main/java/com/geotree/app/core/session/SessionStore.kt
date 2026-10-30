package com.geotree.app.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * [role] is the server-assigned role ("ADMIN" for the development admin). It is null for a
 * session stored before roles existed until the profile is refreshed from /auth/me.
 */
data class Session(val accessToken: String, val email: String, val expiresAtMillis: Long, val role: String? = null) {
    /** The account role for display (Settings → Account); the server enforces every permission. */
    val roleLabel: String?
        get() = when (role) {
            null, "" -> null
            ROLE_FIELD_TESTER -> "Field Tester"
            ROLE_ADMIN -> "Admin"
            ROLE_SUPER_ADMIN -> "Super Admin"
            else -> role
        }

    /**
     * True once the server-issued expiry (JWT `exp`, 7 days after sign-in) has passed on this
     * device's clock. An unknown expiry (<= 0) is not treated as expired: the server still
     * rejects a bad token with 401, which sync reports as "sign in to sync".
     */
    fun isExpired(nowMillis: Long): Boolean = expiresAtMillis in 1..nowMillis

    companion object {
        const val ROLE_FIELD_TESTER = "FIELD_TESTER"
        const val ROLE_ADMIN = "ADMIN"
        const val ROLE_SUPER_ADMIN = "SUPER_ADMIN"
    }
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
            if (session.role != null) it[ROLE] = session.role else it.remove(ROLE)
        }
        cachedToken = session.accessToken
    }

    /** Updates the role of the stored session, only if it still belongs to [accessToken]. */
    suspend fun updateRole(accessToken: String, role: String?) {
        dataStore.edit {
            if (it[TOKEN] != accessToken) return@edit
            if (role != null) it[ROLE] = role else it.remove(ROLE)
        }
    }

    suspend fun clear() {
        dataStore.edit {
            it.remove(TOKEN)
            it.remove(EMAIL)
            it.remove(EXPIRES_AT)
            it.remove(ROLE)
        }
        cachedToken = null
    }

    private fun Preferences.toSession(): Session? {
        val token = this[TOKEN] ?: return null
        return Session(token, this[EMAIL].orEmpty(), this[EXPIRES_AT] ?: 0L, this[ROLE])
    }

    private companion object {
        val TOKEN = stringPreferencesKey("session_access_token")
        val EMAIL = stringPreferencesKey("session_email")
        val EXPIRES_AT = longPreferencesKey("session_expires_at")
        val ROLE = stringPreferencesKey("session_role")
    }
}
