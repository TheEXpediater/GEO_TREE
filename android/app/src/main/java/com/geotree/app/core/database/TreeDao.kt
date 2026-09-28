package com.geotree.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TreeDao {
    @Query("SELECT * FROM trees ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TreeEntity>>

    @Query("SELECT * FROM trees WHERE id = :id")
    fun observeById(id: String): Flow<TreeEntity?>

    @Query("SELECT syncStatus, COUNT(*) AS count FROM trees GROUP BY syncStatus")
    fun observeStatusCounts(): Flow<List<SyncStatusCount>>

    @Query("SELECT COUNT(*) FROM trees")
    suspend fun count(): Int

    @Query("SELECT * FROM trees WHERE id = :id")
    suspend fun getById(id: String): TreeEntity?

    @Query("SELECT * FROM trees WHERE treeCode = :treeCode LIMIT 1")
    suspend fun getByTreeCode(treeCode: String): TreeEntity?

    @Query("SELECT * FROM trees WHERE syncStatus IN ('PENDING', 'FAILED') ORDER BY createdAt ASC")
    suspend fun getUnsynced(): List<TreeEntity>

    /** Fails with a constraint error if the id or Tree Code already exists. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(tree: TreeEntity)

    @Update
    suspend fun update(tree: TreeEntity)

    /** Updates by primary key or inserts. Still fails if another tree owns the Tree Code. */
    @Upsert
    suspend fun upsert(tree: TreeEntity)

    @Query("UPDATE trees SET syncStatus = :status, lastSyncError = :error WHERE id = :id")
    suspend fun setSyncStatus(id: String, status: SyncStatus, error: String?)

    /**
     * Marks SYNCED only if the row was not edited while the upload was in flight.
     * Returns the number of rows updated (0 means a newer local edit is still pending).
     */
    @Query(
        """
        UPDATE trees SET syncStatus = 'SYNCED', serverVersion = :serverVersion,
            remoteImagePath = :remoteImagePath, lastSyncError = NULL
        WHERE id = :id AND updatedAt = :expectedUpdatedAt
        """,
    )
    suspend fun markSynced(id: String, expectedUpdatedAt: Long, serverVersion: Long, remoteImagePath: String?): Int

    /** A sync interrupted by process death leaves rows SYNCING; make them eligible again. */
    @Query("UPDATE trees SET syncStatus = 'PENDING' WHERE syncStatus = 'SYNCING'")
    suspend fun resetInterruptedSyncs(): Int
}
