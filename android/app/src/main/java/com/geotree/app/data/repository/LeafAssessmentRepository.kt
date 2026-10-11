package com.geotree.app.data.repository

import com.geotree.app.core.database.AssessmentStatus
import com.geotree.app.core.database.LeafAssessmentDao
import com.geotree.app.core.database.LeafAssessmentEntity
import com.geotree.app.core.database.SyncStatus
import com.geotree.app.data.remote.AiStatusDto
import com.geotree.app.data.remote.GeoTreeAiApi
import com.geotree.app.data.remote.LeafAssessmentRecordDto
import com.geotree.app.data.remote.toNewSyncedEntity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Leaf assessment history. Every scan is its own permanent record with its own UUID:
 *
 *   photo saved → record inserted as PROCESSING (before any network call)
 *   → DINOv2 on the local AI server → COMPLETED / UNCERTAIN / FAILED (photo always kept)
 *   → sync PENDING → SyncEngine → FastAPI → MongoDB `leaf_assessments` → SYNCED
 *
 * A final record is never changed again except for its sync fields; "assess again" creates a
 * new record. The current leaf status is derived from history, never stored on the tree.
 * Inference runs in [scope] (application-lifetime), so leaving the screen does not cancel it.
 */
class LeafAssessmentRepository(
    private val dao: LeafAssessmentDao,
    private val api: suspend () -> GeoTreeAiApi,
    private val scope: CoroutineScope,
    /** Called when a scan finishes, e.g. to request a background sync. */
    private val onFinished: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    fun observeHistory(treeId: String): Flow<List<LeafAssessmentEntity>> = dao.observeAssessmentsForTree(treeId)

    fun observeLatestResult(treeId: String): Flow<LeafAssessmentEntity?> = dao.observeLatestCompletedAssessment(treeId)

    fun observeAssessment(id: String): Flow<LeafAssessmentEntity?> = dao.observeAssessmentById(id)

    suspend fun aiStatus(): AiResult<AiStatusDto> = aiCall { api().aiStatus() }

    /**
     * Records a new scan (PROCESSING) and starts the AI request in the background.
     * Returns once the record is safely in Room, so the caller can navigate to its result page.
     */
    suspend fun start(treeId: String, imagePath: String): LeafAssessmentEntity {
        val now = clock()
        val record = LeafAssessmentEntity(
            id = newId(), treeId = treeId, imageLocalPath = imagePath, remoteImagePath = null,
            classId = null, className = null, confidence = null, modelName = null, modelDisplayName = null,
            modelVersion = null, status = AssessmentStatus.PROCESSING, errorCode = null, errorMessage = null,
            createdAt = now, completedAt = null, updatedAt = now, syncStatus = SyncStatus.PENDING,
            serverVersion = null, lastSyncError = null,
        )
        dao.insert(record)
        scope.launch { process(record.id) }
        return record
    }

    /** A new scan of an earlier scan's photo. The earlier record is left exactly as it was. */
    suspend fun reassess(fromId: String): LeafAssessmentEntity? {
        val source = dao.getById(fromId) ?: return null
        val photo = source.imageLocalPath?.takeIf { File(it).isFile } ?: return null
        if (!source.status.isFinal) return null
        return start(source.treeId, photo)
    }

    private suspend fun process(id: String) {
        val record = dao.getById(id)?.takeIf { it.status == AssessmentStatus.PROCESSING } ?: return
        val photo = record.imageLocalPath?.let(::File)
        val result = if (photo == null || !photo.isFile) {
            AiResult.Failed(AiProblem.Rejected("IMAGE_MISSING", "The leaf photo is no longer on this device. Take a new photo."))
        } else {
            aiCall {
                val part = MultipartBody.Part.createFormData("file", photo.name, photo.asRequestBody("image/jpeg".toMediaType()))
                api().assessLeaf(part, record.treeId.toRequestBody("text/plain".toMediaType()))
            }
        }
        val now = clock()
        val finished = when (result) {
            is AiResult.Ok -> record.copy(
                status = if (result.value.uncertain) AssessmentStatus.UNCERTAIN else AssessmentStatus.COMPLETED,
                classId = result.value.classId,
                className = result.value.className,
                confidence = result.value.confidence.toFloat(),
                modelName = result.value.model,
                modelDisplayName = result.value.modelDisplayName,
                modelVersion = result.value.modelVersion,
                completedAt = now,
                updatedAt = now,
            )
            is AiResult.Failed -> record.copy(
                status = AssessmentStatus.FAILED,
                errorCode = result.problem.code,
                errorMessage = result.problem.message,
                completedAt = now,
                updatedAt = now,
            )
        }
        // Only a still-processing record is finalized; history is never rewritten.
        if (dao.getById(id)?.status != AssessmentStatus.PROCESSING) return
        dao.update(finished)
        onFinished()
    }

    /** At process start: scans whose request died with the process are recorded as FAILED. */
    suspend fun failInterrupted(): Int =
        dao.failInterrupted("The assessment was interrupted before a result arrived. The photo is kept.", clock())

    // Sync support (used by SyncEngine) ---------------------------------------------------------

    suspend fun unsynced(): List<LeafAssessmentEntity> = dao.getUnsynced()

    suspend fun resetInterruptedSyncs(): Int = dao.resetInterruptedSyncs()

    suspend fun markSyncing(id: String) = updateSync(id) { it.copy(syncStatus = SyncStatus.SYNCING) }

    suspend fun markSyncFailed(id: String, reason: String) =
        updateSync(id) { it.copy(syncStatus = SyncStatus.FAILED, lastSyncError = reason) }

    suspend fun markSynced(id: String, serverVersion: Long, remoteImagePath: String?, note: String? = null) =
        updateSync(id) {
            it.copy(syncStatus = SyncStatus.SYNCED, serverVersion = serverVersion, remoteImagePath = remoteImagePath, lastSyncError = note)
        }

    /** Changes only sync fields: the recorded result is untouched by construction. */
    private suspend fun updateSync(id: String, change: (LeafAssessmentEntity) -> LeafAssessmentEntity) {
        val current = dao.getById(id) ?: return
        val changed = change(current)
        dao.update(
            current.copy(
                syncStatus = changed.syncStatus,
                serverVersion = changed.serverVersion,
                remoteImagePath = changed.remoteImagePath,
                lastSyncError = changed.lastSyncError,
            ),
        )
    }

    /**
     * Merges server history into Room. Unknown scans (from other devices) are added. A scan this
     * device already has keeps its recorded result and local photo; only the server version and
     * photo path are refreshed, and only once the local copy is synced. Nothing is deleted.
     * Returns the number of new scans.
     */
    suspend fun applyRemote(items: List<LeafAssessmentRecordDto>): Int {
        var added = 0
        for (item in items) {
            val local = dao.getById(item.assessmentId)
            when {
                local == null -> {
                    dao.insert(item.toNewSyncedEntity())
                    added++
                }
                local.syncStatus == SyncStatus.SYNCED &&
                    ((local.serverVersion ?: 0) < item.serverVersion || local.remoteImagePath != item.imagePath) ->
                    markSynced(local.id, maxOf(local.serverVersion ?: 0, item.serverVersion), item.imagePath ?: local.remoteImagePath)
            }
        }
        return added
    }
}
