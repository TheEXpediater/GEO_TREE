package com.geotree.app.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.geotree.app.core.database.SyncStatus
import com.geotree.app.core.database.TreeDao
import com.geotree.app.core.database.TreeEntity
import com.geotree.app.data.model.DraftError
import com.geotree.app.data.model.TreeCode
import com.geotree.app.data.model.TreeDraft
import com.geotree.app.data.model.TreeDraftValidator
import com.geotree.app.data.remote.TreeDto
import com.geotree.app.data.remote.parseIsoMillis
import com.geotree.app.data.remote.toSyncedEntity
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

sealed interface CreateTreeResult {
    data class Created(val tree: TreeEntity) : CreateTreeResult
    data class Invalid(val errors: Set<DraftError>) : CreateTreeResult
    data class DuplicateTreeCode(val treeCode: String) : CreateTreeResult
}

sealed interface RenameResult {
    data object Renamed : RenameResult
    data object Invalid : RenameResult
    data object Duplicate : RenameResult
    data object NotFound : RenameResult
}

data class SyncCounts(val pending: Int = 0, val syncing: Int = 0, val synced: Int = 0, val failed: Int = 0) {
    val total: Int get() = pending + syncing + synced + failed
}

/** Result of applying a page of server changes. [appliedThroughVersion] is safe to persist. */
data class ApplyResult(val appliedThroughVersion: Long?, val blockedTreeCode: String?)

/**
 * Room is the on-device source of truth. Every write lands here first; the network only
 * ever reconciles afterwards via the sync worker.
 */
class TreeRepository(
    private val dao: TreeDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    fun observeTrees(): Flow<List<TreeEntity>> = dao.observeAll()

    fun observeTree(id: String): Flow<TreeEntity?> = dao.observeById(id)

    fun observeSyncCounts(): Flow<SyncCounts> = dao.observeStatusCounts().map { rows ->
        val byStatus = rows.associate { it.syncStatus to it.count }
        SyncCounts(
            pending = byStatus[SyncStatus.PENDING] ?: 0,
            syncing = byStatus[SyncStatus.SYNCING] ?: 0,
            synced = byStatus[SyncStatus.SYNCED] ?: 0,
            failed = byStatus[SyncStatus.FAILED] ?: 0,
        )
    }

    suspend fun treeCount(): Int = dao.count()

    suspend fun isTreeCodeTaken(rawCode: String): Boolean = dao.getByTreeCode(TreeCode.normalize(rawCode)) != null

    /** validate → Room insert as PENDING → local success. Never calls the network. */
    suspend fun createTree(draft: TreeDraft): CreateTreeResult {
        val validation = TreeDraftValidator.validate(draft)
        if (!validation.isValid) return CreateTreeResult.Invalid(validation.errors)
        val fix = requireNotNull(draft.fix)
        val code = TreeCode.normalize(draft.treeCode)
        if (dao.getByTreeCode(code) != null) return CreateTreeResult.DuplicateTreeCode(code)

        val now = clock()
        val tree = TreeEntity(
            id = newId(),
            treeCode = code,
            localImagePath = draft.localImagePath,
            remoteImagePath = null,
            latitude = fix.latitude,
            longitude = fix.longitude,
            accuracyMeters = fix.accuracyMeters,
            altitudeMeters = fix.altitudeMeters,
            locationCapturedAt = fix.capturedAt,
            age = draft.details.age,
            tasteCategory = draft.details.tasteCategory?.trim()?.ifEmpty { null },
            yearlyYield = draft.details.yearlyYield,
            fruitQuality = draft.details.fruitQuality?.trim()?.ifEmpty { null },
            notes = draft.details.notes?.trim()?.ifEmpty { null },
            createdAt = now,
            updatedAt = now,
            syncStatus = SyncStatus.PENDING,
            serverVersion = null,
            lastSyncError = null,
        )
        return try {
            dao.insert(tree)
            CreateTreeResult.Created(tree)
        } catch (e: SQLiteConstraintException) {
            CreateTreeResult.DuplicateTreeCode(code)
        }
    }

    /** Used to resolve a server-side Tree Code conflict. The edit is local-first and re-queued. */
    suspend fun renameTreeCode(id: String, rawCode: String): RenameResult {
        val code = TreeCode.normalize(rawCode)
        if (!TreeCode.isValid(code)) return RenameResult.Invalid
        val tree = dao.getById(id) ?: return RenameResult.NotFound
        val holder = dao.getByTreeCode(code)
        if (holder != null && holder.id != id) return RenameResult.Duplicate
        return try {
            dao.update(
                tree.copy(
                    treeCode = code,
                    updatedAt = maxOf(clock(), tree.updatedAt + 1),
                    syncStatus = SyncStatus.PENDING,
                    lastSyncError = null,
                ),
            )
            RenameResult.Renamed
        } catch (e: SQLiteConstraintException) {
            RenameResult.Duplicate
        }
    }

    suspend fun unsyncedTrees(): List<TreeEntity> = dao.getUnsynced()

    suspend fun markSyncing(id: String) = dao.setSyncStatus(id, SyncStatus.SYNCING, null)

    suspend fun markPending(id: String, reason: String?) = dao.setSyncStatus(id, SyncStatus.PENDING, reason)

    suspend fun markFailed(id: String, reason: String) = dao.setSyncStatus(id, SyncStatus.FAILED, reason)

    /** Returns false when the row changed during upload; it then stays PENDING for the next pass. */
    suspend fun markSynced(tree: TreeEntity, serverVersion: Long, remoteImagePath: String?): Boolean {
        val updated = dao.markSynced(tree.id, tree.updatedAt, serverVersion, remoteImagePath)
        if (updated == 0) dao.getById(tree.id)?.takeIf { it.syncStatus == SyncStatus.SYNCING }?.let {
            dao.setSyncStatus(it.id, SyncStatus.PENDING, null)
        }
        return updated > 0
    }

    suspend fun resetInterruptedSyncs() = dao.resetInterruptedSyncs()

    /**
     * Upserts server records in version order. Stops at the first record that cannot be
     * applied (a different local tree holds its Tree Code) so the pull cursor never skips it.
     */
    suspend fun applyRemoteChanges(items: List<TreeDto>): ApplyResult {
        var appliedThrough: Long? = null
        for (remote in items.sortedBy { it.serverVersion }) {
            val local = dao.getById(remote.id)
            val localEditIsNewer = local != null &&
                local.syncStatus != SyncStatus.SYNCED &&
                local.updatedAt > remote.updatedAt.parseIsoMillis()
            if (!localEditIsNewer) {
                val holder = dao.getByTreeCode(remote.treeCode)
                if (holder != null && holder.id != remote.id) {
                    return ApplyResult(appliedThrough, blockedTreeCode = remote.treeCode)
                }
                try {
                    dao.upsert(remote.toSyncedEntity(local))
                } catch (e: SQLiteConstraintException) {
                    return ApplyResult(appliedThrough, blockedTreeCode = remote.treeCode)
                }
            }
            appliedThrough = remote.serverVersion
        }
        return ApplyResult(appliedThrough, blockedTreeCode = null)
    }
}
