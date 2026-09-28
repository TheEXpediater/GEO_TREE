package com.geotree.app.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SyncStatus { PENDING, SYNCING, SYNCED, FAILED }

/**
 * A registered tamarind tree. [id] is a device-generated UUID and the permanent identity;
 * coordinates are attributes only. Images live on disk ([localImagePath]) and on the
 * server ([remoteImagePath]); bytes are never stored here.
 */
@Entity(
    tableName = "trees",
    indices = [
        Index(value = ["treeCode"], unique = true),
        Index(value = ["syncStatus"]),
    ],
)
data class TreeEntity(
    @PrimaryKey val id: String,
    val treeCode: String,
    val localImagePath: String?,
    val remoteImagePath: String?,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val altitudeMeters: Double?,
    val locationCapturedAt: Long,
    val age: Int?,
    val tasteCategory: String?,
    val yearlyYield: Double?,
    val fruitQuality: String?,
    val notes: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val syncStatus: SyncStatus,
    val serverVersion: Long?,
    val lastSyncError: String?,
)

data class SyncStatusCount(val syncStatus: SyncStatus, val count: Int)
