package com.geotree.app.core.network

import com.geotree.app.data.remote.GeoTreeApi
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.HttpException

sealed interface BackendCheck {
    data class Verified(val baseUrl: String, val version: String?) : BackendCheck
    data class NotGeoTree(val baseUrl: String) : BackendCheck
    data class DatabaseUnavailable(val baseUrl: String) : BackendCheck
    data class Unreachable(val baseUrl: String, val reason: String) : BackendCheck
    data object InvalidUrl : BackendCheck
}

enum class BackendReachability { CHECKING, CONNECTED, OFFLINE }

/** Last known reachability of the configured backend. Informational only: nothing waits on it. */
data class BackendStatus(
    val reachability: BackendReachability,
    val baseUrl: String? = null,
    val detail: String? = null,
    val checkedAt: Long? = null,
)

fun BackendCheck.toStatus(checkedAt: Long): BackendStatus = when (this) {
    is BackendCheck.Verified -> BackendStatus(BackendReachability.CONNECTED, baseUrl, version?.let { "GEO Tree API $it" }, checkedAt)
    is BackendCheck.NotGeoTree -> BackendStatus(BackendReachability.OFFLINE, baseUrl, "Server responded but is not GEO Tree.", checkedAt)
    is BackendCheck.DatabaseUnavailable -> BackendStatus(BackendReachability.OFFLINE, baseUrl, "Server database unavailable.", checkedAt)
    is BackendCheck.Unreachable -> BackendStatus(BackendReachability.OFFLINE, baseUrl, "Server unreachable.", checkedAt)
    BackendCheck.InvalidUrl -> BackendStatus(BackendReachability.OFFLINE, null, "Invalid server address.", checkedAt)
}

/**
 * Verifies a backend address via /api/v1/health before it is ever persisted, and reports
 * whether the configured backend is currently reachable. [BackendConfig] stays the only
 * place the address is stored.
 */
class BackendConnectionManager(
    private val backendConfig: BackendConfig,
    private val apiProvider: ApiProvider,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _status = MutableStateFlow(BackendStatus(BackendReachability.CHECKING))
    val status: StateFlow<BackendStatus> = _status.asStateFlow()

    /** Health-checks the configured backend. Safe to call offline; it only updates [status]. */
    suspend fun refreshStatus(): BackendStatus {
        val baseUrl = backendConfig.current()
        _status.value = BackendStatus(BackendReachability.CHECKING, baseUrl, checkedAt = _status.value.checkedAt)
        return check(baseUrl).toStatus(clock()).also { _status.value = it }
    }

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
        if (result is BackendCheck.Verified) {
            backendConfig.save(result.baseUrl)
            _status.value = result.toStatus(clock())
        }
        return result
    }
}
