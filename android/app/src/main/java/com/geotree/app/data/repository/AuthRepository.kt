package com.geotree.app.data.repository

import com.geotree.app.core.session.Session
import com.geotree.app.core.session.SessionStore
import com.geotree.app.data.remote.GeoTreeApi
import com.geotree.app.data.remote.LoginRequestDto
import com.geotree.app.data.remote.parseIsoMillis
import com.geotree.app.data.remote.toApiError
import java.io.IOException
import retrofit2.HttpException

sealed interface LoginResult {
    data class Success(val session: Session) : LoginResult
    data object InvalidCredentials : LoginResult
    data class Unreachable(val detail: String) : LoginResult
    data class ServerError(val message: String) : LoginResult
}

class AuthRepository(
    private val api: suspend () -> GeoTreeApi,
    private val sessionStore: SessionStore,
) {
    suspend fun login(email: String, password: String): LoginResult = try {
        val response = api().login(LoginRequestDto(email.trim(), password))
        val session = Session(
            accessToken = response.accessToken,
            email = response.user.email,
            expiresAtMillis = runCatching { response.expiresAt.parseIsoMillis() }.getOrDefault(0L),
        )
        sessionStore.save(session)
        LoginResult.Success(session)
    } catch (e: HttpException) {
        if (e.code() == 401) LoginResult.InvalidCredentials else LoginResult.ServerError(e.toApiError().message)
    } catch (e: IOException) {
        LoginResult.Unreachable(e.message ?: "Network error")
    }

    /** Local trees and images are kept; only the session is removed. */
    suspend fun logout() = sessionStore.clear()
}
