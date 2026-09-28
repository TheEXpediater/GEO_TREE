package com.geotree.app.core.sync

import com.geotree.app.core.database.TreeEntity
import com.geotree.app.core.session.SessionStore
import com.geotree.app.data.remote.GeoTreeApi
import com.geotree.app.data.remote.TreeDto
import com.geotree.app.data.remote.toApiError
import com.geotree.app.data.remote.toSyncRequest
import com.geotree.app.data.repository.TreeRepository
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.HttpException

sealed interface SyncOutcome {
    data object NotSignedIn : SyncOutcome
    data object AuthExpired : SyncOutcome
    /** Health check failed: nothing was attempted, local records untouched. */
    data class BackendUnavailable(val reason: String) : SyncOutcome
    /** The connection dropped mid-sync. Worth retrying with backoff. */
    data class Interrupted(val reason: String, val pushed: Int) : SyncOutcome
    data class Completed(val pushed: Int, val rejected: Int, val pulled: Int, val blockedTreeCode: String?) : SyncOutcome
}

data class SyncActivity(
    val running: Boolean = false,
    val lastOutcome: SyncOutcome? = null,
    val lastFinishedAt: Long? = null,
)

/** Process-wide view of sync activity for the UI; record-level state always comes from Room. */
class SyncStatusTracker(private val clock: () -> Long = System::currentTimeMillis) {
    private val _state = MutableStateFlow(SyncActivity())
    val state: StateFlow<SyncActivity> = _state.asStateFlow()

    fun started() = _state.update { it.copy(running = true) }
    fun finished(outcome: SyncOutcome) = _state.update { SyncActivity(false, outcome, clock()) }
}

/**
 * Two-way sync:
 *  1. health check (the dev server may be on a LAN, so "connected" is not enough)
 *  2. push PENDING/FAILED trees by UUID (SYNCING → SYNCED / FAILED), uploading images
 *  3. pull changes after the stored server_version cursor and upsert them into Room
 * Local records are never deleted by a failure.
 */
class SyncEngine(
    private val repository: TreeRepository,
    private val api: suspend () -> GeoTreeApi,
    private val sessionStore: SessionStore,
    private val preferences: SyncPreferences,
    private val tracker: SyncStatusTracker,
) {
    private val mutex = Mutex()

    suspend fun sync(): SyncOutcome = mutex.withLock {
        tracker.started()
        var outcome: SyncOutcome = SyncOutcome.Interrupted("Sync cancelled", 0)
        try {
            outcome = runSync()
        } finally {
            tracker.finished(outcome)
        }
        outcome
    }

    private suspend fun runSync(): SyncOutcome {
        if (sessionStore.current() == null) return SyncOutcome.NotSignedIn
        val api = api()
        checkHealth(api)?.let { return it }

        repository.resetInterruptedSyncs()
        var pushed = 0
        var rejected = 0
        for (tree in repository.unsyncedTrees()) {
            when (val result = push(api, tree)) {
                PushResult.Pushed -> pushed++
                PushResult.Rejected -> rejected++
                PushResult.AuthExpired -> return SyncOutcome.AuthExpired
                is PushResult.Transient -> return SyncOutcome.Interrupted(result.reason, pushed)
            }
        }

        var pulled = 0
        var after = preferences.lastPulledServerVersion()
        try {
            repeat(MAX_PULL_PAGES) {
                val page = api.changes(after)
                if (page.items.isEmpty()) return SyncOutcome.Completed(pushed, rejected, pulled, null)
                val applied = repository.applyRemoteChanges(page.items)
                applied.appliedThroughVersion?.let { version ->
                    pulled += page.items.count { it.serverVersion <= version }
                    preferences.setLastPulledServerVersion(version)
                    after = version
                }
                if (applied.blockedTreeCode != null || !page.hasMore) {
                    return SyncOutcome.Completed(pushed, rejected, pulled, applied.blockedTreeCode)
                }
            }
        } catch (e: HttpException) {
            if (e.code() == 401) return SyncOutcome.AuthExpired
            return SyncOutcome.Interrupted("Pull failed: ${e.toApiError().message}", pushed)
        } catch (e: IOException) {
            return SyncOutcome.Interrupted("Pull interrupted: ${e.message}", pushed)
        }
        return SyncOutcome.Completed(pushed, rejected, pulled, null)
    }

    private suspend fun checkHealth(api: GeoTreeApi): SyncOutcome? = try {
        val health = api.health()
        when {
            health.service != GeoTreeApi.SERVICE_NAME -> SyncOutcome.BackendUnavailable("Configured server is not GEO Tree.")
            health.status != "ok" -> SyncOutcome.BackendUnavailable("Server database unavailable.")
            else -> null
        }
    } catch (e: HttpException) {
        SyncOutcome.BackendUnavailable("Server not ready (HTTP ${e.code()}).")
    } catch (e: IOException) {
        SyncOutcome.BackendUnavailable("Server unreachable.")
    } catch (e: SerializationException) {
        SyncOutcome.BackendUnavailable("Configured server is not GEO Tree.")
    }

    private sealed interface PushResult {
        data object Pushed : PushResult
        data object Rejected : PushResult
        data object AuthExpired : PushResult
        data class Transient(val reason: String) : PushResult
    }

    private suspend fun push(api: GeoTreeApi, tree: TreeEntity): PushResult {
        repository.markSyncing(tree.id)
        return try {
            var serverTree: TreeDto = api.syncTree(tree.toSyncRequest()).tree
            val imageFile = tree.localImagePath?.let(::File)
            // Upload only when the server has no image yet: retries never duplicate work.
            if (serverTree.imagePath == null && imageFile != null && imageFile.exists()) {
                serverTree = api.uploadImage(tree.id, imageFile.toImagePart())
            }
            repository.markSynced(tree, serverTree.serverVersion, serverTree.imagePath)
            PushResult.Pushed
        } catch (e: HttpException) {
            val error = e.toApiError()
            when {
                e.code() == 401 -> {
                    repository.markPending(tree.id, "Sign in again to sync.")
                    PushResult.AuthExpired
                }
                error.code == "TREE_CODE_CONFLICT" -> {
                    repository.markFailed(tree.id, "${error.message} Change this tree's code to sync it.")
                    PushResult.Rejected
                }
                e.code() in 400..499 -> {
                    repository.markFailed(tree.id, error.message)
                    PushResult.Rejected
                }
                else -> {
                    repository.markFailed(tree.id, "Server error (HTTP ${e.code()}). Will retry.")
                    PushResult.Transient(error.message)
                }
            }
        } catch (e: IOException) {
            repository.markFailed(tree.id, "Connection lost during sync. Will retry.")
            PushResult.Transient(e.message ?: "Connection lost")
        }
    }

    private fun File.toImagePart(): MultipartBody.Part {
        val type = when (extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        return MultipartBody.Part.createFormData("file", name, asRequestBody(type.toMediaType()))
    }

    private companion object {
        const val MAX_PULL_PAGES = 50
    }
}
