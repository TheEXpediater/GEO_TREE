package com.geotree.app.data.remote

import retrofit2.HttpException

data class ApiError(val status: Int, val code: String, val message: String)

/** Reads the backend's structured `{"error": {...}}` body; falls back to the HTTP status. */
fun HttpException.toApiError(): ApiError {
    val raw = runCatching { response()?.errorBody()?.string() }.getOrNull()
    val parsed = raw?.let { runCatching { GeoTreeJson.decodeFromString(ApiErrorBody.serializer(), it) }.getOrNull() }
    return ApiError(
        status = code(),
        code = parsed?.error?.code ?: "HTTP_${code()}",
        message = parsed?.error?.message ?: "Server responded with HTTP ${code()}.",
    )
}
