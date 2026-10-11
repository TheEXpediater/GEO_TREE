package com.geotree.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A finished scan sent to the server (same conventions as tree sync: snake_case, ISO-8601 UTC). */
@Serializable
data class LeafAssessmentSyncRequestDto(
    @SerialName("assessment_id") val assessmentId: String,
    @SerialName("tree_id") val treeId: String,
    val status: String,
    @SerialName("class_id") val classId: String?,
    @SerialName("class_name") val className: String?,
    val confidence: Double?,
    @SerialName("model_name") val modelName: String?,
    @SerialName("model_display_name") val modelDisplayName: String?,
    @SerialName("model_version") val modelVersion: Int?,
    @SerialName("error_code") val errorCode: String?,
    @SerialName("error_message") val errorMessage: String?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("completed_at") val completedAt: String?,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class LeafAssessmentRecordDto(
    @SerialName("assessment_id") val assessmentId: String,
    @SerialName("tree_id") val treeId: String,
    val status: String,
    @SerialName("class_id") val classId: String? = null,
    @SerialName("class_name") val className: String? = null,
    val confidence: Double? = null,
    @SerialName("model_name") val modelName: String? = null,
    @SerialName("model_display_name") val modelDisplayName: String? = null,
    @SerialName("model_version") val modelVersion: Int? = null,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("image_path") val imagePath: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("server_version") val serverVersion: Long,
)

@Serializable
data class LeafAssessmentSyncResponseDto(val result: String, val assessment: LeafAssessmentRecordDto)

@Serializable
data class LeafAssessmentChangesDto(
    val items: List<LeafAssessmentRecordDto>,
    @SerialName("latest_version") val latestVersion: Long,
    @SerialName("has_more") val hasMore: Boolean,
)
