package com.geotree.app.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * The AI outcome of one scan. Separate from [syncStatus] (network state):
 *  PENDING     photo saved, request not started
 *  PROCESSING  sent to the AI server, waiting for the result
 *  COMPLETED   classified at or above the confidence threshold
 *  UNCERTAIN   classified, but below the confidence threshold
 *  FAILED      no result (server unreachable, model not ready, interrupted…); photo kept
 */
enum class AssessmentStatus {
    PENDING, PROCESSING, COMPLETED, UNCERTAIN, FAILED;

    /** A final outcome. Final records are immutable history; only their sync fields change. */
    val isFinal: Boolean get() = this == COMPLETED || this == UNCERTAIN || this == FAILED

    /** The model produced a class and confidence. */
    val hasResult: Boolean get() = this == COMPLETED || this == UNCERTAIN
}

/**
 * One leaf scan of a tree: a permanent history event with its own UUID. A tree has many.
 * [treeId] links to [TreeEntity.id]; it is never a key of this table. The photo stays in app
 * storage ([imageLocalPath]) and on the server ([remoteImagePath]); bytes are never stored here.
 * Assessment photos are never training data.
 *
 * [modelName]/[modelVersion] are null when the scan never reached the model (e.g. a FAILED
 * attempt while the AI server was offline): no model version is ever invented.
 */
@Entity(
    tableName = "leaf_assessments",
    indices = [
        Index(value = ["treeId", "createdAt"]),
        Index(value = ["status"]),
        Index(value = ["syncStatus"]),
    ],
)
data class LeafAssessmentEntity(
    @PrimaryKey val id: String,
    val treeId: String,
    val imageLocalPath: String?,
    val remoteImagePath: String?,
    val classId: String?,
    val className: String?,
    val confidence: Float?,
    val modelName: String?,
    val modelDisplayName: String?,
    val modelVersion: Int?,
    val status: AssessmentStatus,
    val errorCode: String?,
    val errorMessage: String?,
    val createdAt: Long,
    val completedAt: Long?,
    val updatedAt: Long,
    val syncStatus: SyncStatus,
    val serverVersion: Long?,
    val lastSyncError: String?,
)

@Dao
interface LeafAssessmentDao {
    /** The full history of a tree, newest scan first. */
    @Query("SELECT * FROM leaf_assessments WHERE treeId = :treeId ORDER BY createdAt DESC, id DESC")
    fun observeAssessmentsForTree(treeId: String): Flow<List<LeafAssessmentEntity>>

    /** The current-status summary: the newest scan that produced a class. Derived, never stored. */
    @Query(
        "SELECT * FROM leaf_assessments WHERE treeId = :treeId AND status IN ('COMPLETED', 'UNCERTAIN') " +
            "ORDER BY createdAt DESC, id DESC LIMIT 1",
    )
    fun observeLatestCompletedAssessment(treeId: String): Flow<LeafAssessmentEntity?>

    @Query("SELECT * FROM leaf_assessments WHERE id = :id")
    fun observeAssessmentById(id: String): Flow<LeafAssessmentEntity?>

    @Query("SELECT * FROM leaf_assessments WHERE id = :id")
    suspend fun getById(id: String): LeafAssessmentEntity?

    /** Final scans not yet on the server. In-progress scans wait until they finish. */
    @Query(
        "SELECT * FROM leaf_assessments WHERE syncStatus IN ('PENDING', 'FAILED') " +
            "AND status IN ('COMPLETED', 'UNCERTAIN', 'FAILED') ORDER BY createdAt ASC",
    )
    suspend fun getUnsynced(): List<LeafAssessmentEntity>

    /** Fails if the id exists: a new scan never replaces an earlier one. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(assessment: LeafAssessmentEntity)

    @Update
    suspend fun update(assessment: LeafAssessmentEntity)

    /** Scans left PROCESSING/PENDING by a killed process: recorded as FAILED, photo kept. */
    @Query(
        "UPDATE leaf_assessments SET status = 'FAILED', errorCode = 'INTERRUPTED', " +
            "errorMessage = :message, completedAt = :now, updatedAt = :now, syncStatus = 'PENDING' " +
            "WHERE status IN ('PENDING', 'PROCESSING')",
    )
    suspend fun failInterrupted(message: String, now: Long): Int

    @Query("UPDATE leaf_assessments SET syncStatus = 'PENDING' WHERE syncStatus = 'SYNCING'")
    suspend fun resetInterruptedSyncs(): Int
}
