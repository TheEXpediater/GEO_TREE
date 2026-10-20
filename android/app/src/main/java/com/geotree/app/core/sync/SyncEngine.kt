package com.geotree.app.core.sync

import com.geotree.app.core.database.LeafAssessmentEntity
import com.geotree.app.core.database.TreeEntity
import com.geotree.app.core.session.SessionState
import com.geotree.app.core.session.SessionStore
import com.geotree.app.data.remote.GeoTreeApi
import com.geotree.app.data.remote.TreeDto
import com.geotree.app.data.remote.toApiError
import com.geotree.app.data.remote.toSyncRequest
import com.geotree.app.data.repository.LeafAssessmentRepository
import com.geotree.app.data.repository.TreeRepository
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
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
    data class Completed(
        val pushed: Int,
        val rejected: Int,
        val pulled: Int,
        val blockedTreeCode: String?,
        val assessmentsPushed: Int = 0,
        val assessmentsPulled: Int = 0,
    ) : SyncOutcome
}

data class SyncActivity(
    val running: Boolean = false,
    val lastOutcome: SyncOutcome? = null,
    val lastFinishedAt: Long? = null,
)

/**
 * Sync needs a new sign-in when the stored session has expired, or when the server rejected the
 * token on the last run (e.g. it was revoked). Local field work is never blocked by this.
 */
fun authRequired(session: SessionState, activity: SyncActivity): Boolean =
    session == SessionState.EXPIRED || (session == SessionState.VALID && activity.lastOutcome == SyncOutcome.AuthExpired)

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
 *  4. leaf assessment history: push finished scans by assessment UUID (photo uploaded once),
 *     then pull scans from other devices after their own server_version cursor
 * Local records are never deleted by a failure.
 */
class SyncEngine(
    private val repository: TreeRepository,
    private val api: suspend () -> GeoTreeApi,
    private val sessionStore: SessionStore,
    private val preferences: SyncPreferences,
    private val tracker: SyncStatusTracker,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Leaf assessment history; null where it is not wired (keeps tree-only tests simple). */
    private val leafAssessments: LeafAssessmentRepository? = null,
    /**
     * Brings the on-device classifier up to date after a completed sync. Best effort: its
     * failure never fails the sync, and assessments never wait for it.
     */
    private val modelUpdate: (suspend () -> Unit)? = null,
) {
    private val mutex = Mutex()

    suspend fun sync(): SyncOutcome = mutex.withLock {
        tracker.started()
        var outcome: SyncOutcome = SyncOutcome.Interrupted("Sync cancelled", 0)
        try {
            outcome = runSync()
            if (outcome is SyncOutcome.Completed) {
                preferences.setLastSuccessfulSyncAt(clock())
                refreshModel()
            }
        } finally {
            tracker.finished(outcome)
        }
        outcome
    }

    private suspend fun refreshModel() {
        try {
            modelUpdate?.invoke()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The current on-device model stays active.
        }
    }

    private suspend fun runSync(): SyncOutcome {
        val session = sessionStore.current() ?: return SyncOutcome.NotSignedIn
        // An expired token would only earn 401s: pause sync locally until the user signs in again.
        if (session.isExpired(clock())) return SyncOutcome.AuthExpired
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
        var blockedTreeCode: String? = null
        var after = preferences.lastPulledServerVersion()
        try {
            for (page in 1..MAX_PULL_PAGES) {
                val changes = api.changes(after)
                if (changes.items.isEmpty()) break
                val applied = repository.applyRemoteChanges(changes.items)
                applied.appliedThroughVersion?.let { version ->
                    pulled += changes.items.count { it.serverVersion <= version }
                    preferences.setLastPulledServerVersion(version)
                    after = version
                }
                blockedTreeCode = applied.blockedTreeCode
                if (blockedTreeCode != null || !changes.hasMore) break
            }
        } catch (e: HttpException) {
            if (e.code() == 401) return SyncOutcome.AuthExpired
            return SyncOutcome.Interrupted("Pull failed: ${e.toApiError().message}", pushed)
        } catch (e: IOException) {
            return SyncOutcome.Interrupted("Pull interrupted: ${e.message}", pushed)
        }

        val assessments = leafAssessments ?: return SyncOutcome.Completed(pushed, rejected, pulled, blockedTreeCode)
        return when (val history = syncAssessments(api, assessments)) {
            is AssessmentSync.Done -> SyncOutcome.Completed(pushed, rejected, pulled, blockedTreeCode, history.pushed, history.pulled)
            AssessmentSync.AuthExpired -> SyncOutcome.AuthExpired
            is AssessmentSync.Transient -> SyncOutcome.Interrupted(history.reason, pushed)
        }
    }

    private sealed interface AssessmentSync {
        data class Done(val pushed: Int, val pulled: Int) : AssessmentSync
        data object AuthExpired : AssessmentSync
        data class Transient(val reason: String) : AssessmentSync
    }

    /** Runs after trees: an assessment needs its tree on the server first. */
    private suspend fun syncAssessments(api: GeoTreeApi, assessments: LeafAssessmentRepository): AssessmentSync {
        assessments.resetInterruptedSyncs()
        var pushed = 0
        for (record in assessments.unsynced()) {
            when (val result = pushAssessment(api, assessments, record)) {
                PushResult.Pushed -> pushed++
                PushResult.Rejected -> Unit
                PushResult.AuthExpired -> return AssessmentSync.AuthExpired
                is PushResult.Transient -> return AssessmentSync.Transient(result.reason)
            }
        }
        var pulled = 0
        var after = preferences.lastPulledAssessmentVersion()
        try {
            for (page in 1..MAX_PULL_PAGES) {
                val changes = api.leafAssessmentChanges(after)
                if (changes.items.isEmpty()) break
                pulled += assessments.applyRemote(changes.items)
                after = changes.latestVersion
                preferences.setLastPulledAssessmentVersion(after)
                if (!changes.hasMore) break
            }
        } catch (e: HttpException) {
            if (e.code() == 401) return AssessmentSync.AuthExpired
            return AssessmentSync.Transient("Assessment pull failed: ${e.toApiError().message}")
        } catch (e: IOException) {
            return AssessmentSync.Transient("Assessment pull interrupted: ${e.message}")
        }
        return AssessmentSync.Done(pushed, pulled)
    }

    private suspend fun pushAssessment(api: GeoTreeApi, assessments: LeafAssessmentRepository, record: LeafAssessmentEntity): PushResult {
        assessments.markSyncing(record.id)
        return try {
            var server = api.syncLeafAssessment(record.toSyncRequest()).assessment
            var note: String? = null
            val photo = record.imageLocalPath?.let(::File)
            // Upload only when the server has no photo yet: retries never duplicate work.
            if (server.imagePath == null && photo != null && photo.isFile) {
                try {
                    server = api.uploadLeafAssessmentImage(record.id, photo.toImagePart())
                } catch (e: HttpException) {
                    if (e.code() != 413 && e.code() != 415) throw e
                    // The result is synced; a photo the server cannot store stays on this device.
                    note = "Photo kept on this device only: ${e.toApiError().message}"
                }
            }
            assessments.markSynced(record.id, server.serverVersion, server.imagePath, note)
            PushResult.Pushed
        } catch (e: HttpException) {
            val error = e.toApiError()
            when {
                e.code() == 401 -> {
                    assessments.markSyncFailed(record.id, "Sign in again to sync.")
                    PushResult.AuthExpired
                }
                error.code == "TREE_NOT_FOUND" -> {
                    assessments.markSyncFailed(record.id, "Waiting for its tree to sync first.")
                    PushResult.Rejected
                }
                e.code() in 400..499 -> {
                    assessments.markSyncFailed(record.id, error.message)
                    PushResult.Rejected
                }
                else -> {
                    assessments.markSyncFailed(record.id, "Server error (HTTP ${e.code()}). Will retry.")
                    PushResult.Transient(error.message)
                }
            }
        } catch (e: IOException) {
            assessments.markSyncFailed(record.id, "Connection lost during sync. Will retry.")
            PushResult.Transient(e.message ?: "Connection lost")
        }
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
