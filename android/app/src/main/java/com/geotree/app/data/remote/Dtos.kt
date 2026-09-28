package com.geotree.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

val GeoTreeJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class HealthDto(
    val status: String,
    val service: String,
    val version: String? = null,
    val database: String? = null,
)

@Serializable
data class LoginRequestDto(val email: String, val password: String)

@Serializable
data class UserDto(
    val email: String,
    @SerialName("display_name") val displayName: String,
)

@Serializable
data class LoginResponseDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_at") val expiresAt: String,
    val user: UserDto,
)

/** Timestamps travel as ISO-8601 UTC strings. */
@Serializable
data class TreeSyncRequestDto(
    val id: String,
    @SerialName("tree_code") val treeCode: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("accuracy_meters") val accuracyMeters: Float,
    @SerialName("altitude_meters") val altitudeMeters: Double?,
    @SerialName("location_captured_at") val locationCapturedAt: String,
    val age: Int?,
    @SerialName("taste_category") val tasteCategory: String?,
    @SerialName("yearly_yield") val yearlyYield: Double?,
    @SerialName("fruit_quality") val fruitQuality: String?,
    val notes: String?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class TreeDto(
    val id: String,
    @SerialName("tree_code") val treeCode: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("accuracy_meters") val accuracyMeters: Float,
    @SerialName("altitude_meters") val altitudeMeters: Double? = null,
    @SerialName("location_captured_at") val locationCapturedAt: String,
    val age: Int? = null,
    @SerialName("taste_category") val tasteCategory: String? = null,
    @SerialName("yearly_yield") val yearlyYield: Double? = null,
    @SerialName("fruit_quality") val fruitQuality: String? = null,
    val notes: String? = null,
    @SerialName("image_path") val imagePath: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("server_version") val serverVersion: Long,
)

@Serializable
data class TreeSyncResponseDto(val result: String, val tree: TreeDto)

@Serializable
data class TreeChangesDto(
    val items: List<TreeDto>,
    @SerialName("latest_version") val latestVersion: Long,
    @SerialName("has_more") val hasMore: Boolean,
)

@Serializable
data class ApiErrorBody(val error: ApiErrorDetail)

@Serializable
data class ApiErrorDetail(val code: String, val message: String, val details: JsonObject? = null)
