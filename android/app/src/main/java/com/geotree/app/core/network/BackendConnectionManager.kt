package com.geotree.app.core.network

import com.geotree.app.data.remote.GeoTreeApi
import java.io.IOException
import retrofit2.HttpException

sealed interface BackendCheck {
    data class Verified(val baseUrl: String, val version: String?) : BackendCheck
    data class NotGeoTree(val baseUrl: String) : BackendCheck
    data class DatabaseUnavailable(val baseUrl: String) : BackendCheck
    data class Unreachable(val baseUrl: String, val reason: String) : BackendCheck
    data object InvalidUrl : BackendCheck
}

/** Verifies a backend address via /api/v1/health before it is ever persisted. */
class BackendConnectionManager(
    private val backendConfig: BackendConfig,
    private val apiProvider: ApiProvider,
) {
    suspend fun check(rawUrl: String): BackendCheck {
        val baseUrl = BackendConfig.normalize(rawUrl) ?: return BackendCheck.InvalidUrl
        return try {
            val health = apiProvider.forBaseUrl(baseUrl, makeActive = false).health()
            when {
                health.service != GeoTreeApi.SERVICE_NAME -> BackendCheck.NotGeoTree(baseUrl)
                health.status != "ok" -> BackendCheck.DatabaseUnavailable(baseUrl)
                else -> BackendCheck.Verified(baseUrl, health.version)
            }
        } catch (e: HttpException) {
            if (e.code() == 503) BackendCheck.DatabaseUnavailable(baseUrl) else BackendCheck.NotGeoTree(baseUrl)
        } catch (e: IOException) {
            BackendCheck.Unreachable(baseUrl, e.message ?: e.javaClass.simpleName)
        } catch (e: Exception) {
            // e.g. a non-JSON page on that port
            BackendCheck.NotGeoTree(baseUrl)
        }
    }

    suspend fun verifyAndSave(rawUrl: String): BackendCheck {
        val result = check(rawUrl)
        if (result is BackendCheck.Verified) backendConfig.save(result.baseUrl)
        return result
    }
}
