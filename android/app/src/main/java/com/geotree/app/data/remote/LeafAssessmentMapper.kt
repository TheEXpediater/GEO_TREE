package com.geotree.app.data.remote

import com.geotree.app.core.database.AssessmentStatus
import com.geotree.app.core.database.LeafAssessmentEntity
import com.geotree.app.core.database.SyncStatus

fun LeafAssessmentEntity.toSyncRequest() = LeafAssessmentSyncRequestDto(
    assessmentId = id,
    treeId = treeId,
    status = status.name,
    classId = classId,
    className = className,
    confidence = confidence?.toDouble(),
    modelName = modelName,
    modelDisplayName = modelDisplayName,
    modelVersion = modelVersion,
    errorCode = errorCode,
    errorMessage = errorMessage,
    createdAt = createdAt.toIsoUtc(),
    completedAt = completedAt?.toIsoUtc(),
    updatedAt = updatedAt.toIsoUtc(),
)

/** A scan recorded by another device, new to this one. It has no local photo. */
fun LeafAssessmentRecordDto.toNewSyncedEntity() = LeafAssessmentEntity(
    id = assessmentId,
    treeId = treeId,
    imageLocalPath = null,
    remoteImagePath = imagePath,
    classId = classId,
    className = className,
    confidence = confidence?.toFloat(),
    modelName = modelName,
    modelDisplayName = modelDisplayName,
    modelVersion = modelVersion,
    status = runCatching { AssessmentStatus.valueOf(status) }.getOrDefault(AssessmentStatus.FAILED),
    errorCode = errorCode,
    errorMessage = errorMessage,
    createdAt = createdAt.parseIsoMillis(),
    completedAt = completedAt?.parseIsoMillis(),
    updatedAt = updatedAt.parseIsoMillis(),
    syncStatus = SyncStatus.SYNCED,
    serverVersion = serverVersion,
    lastSyncError = null,
)
