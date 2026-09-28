package com.geotree.app.data.remote

import com.geotree.app.core.database.SyncStatus
import com.geotree.app.core.database.TreeEntity
import java.time.Instant
import java.time.OffsetDateTime

fun Long.toIsoUtc(): String = Instant.ofEpochMilli(this).toString()

fun String.parseIsoMillis(): Long = OffsetDateTime.parse(this).toInstant().toEpochMilli()

fun TreeEntity.toSyncRequest() = TreeSyncRequestDto(
    id = id,
    treeCode = treeCode,
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters,
    altitudeMeters = altitudeMeters,
    locationCapturedAt = locationCapturedAt.toIsoUtc(),
    age = age,
    tasteCategory = tasteCategory,
    yearlyYield = yearlyYield,
    fruitQuality = fruitQuality,
    notes = notes,
    createdAt = createdAt.toIsoUtc(),
    updatedAt = updatedAt.toIsoUtc(),
)

/** A server record applied to Room. The local image file (if this device took it) is kept. */
fun TreeDto.toSyncedEntity(existing: TreeEntity?) = TreeEntity(
    id = id,
    treeCode = treeCode,
    localImagePath = existing?.localImagePath,
    remoteImagePath = imagePath,
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters,
    altitudeMeters = altitudeMeters,
    locationCapturedAt = locationCapturedAt.parseIsoMillis(),
    age = age,
    tasteCategory = tasteCategory,
    yearlyYield = yearlyYield,
    fruitQuality = fruitQuality,
    notes = notes,
    createdAt = createdAt.parseIsoMillis(),
    updatedAt = updatedAt.parseIsoMillis(),
    syncStatus = SyncStatus.SYNCED,
    serverVersion = serverVersion,
    lastSyncError = null,
)
