package com.geotree.app.data.remote

import kotlinx.serialization.Serializable

/*
 * The server's active leaf model, as reported to field users. The AI routes use camelCase JSON
 * keys, so no @SerialName mapping is needed. Timestamps are ISO-8601 strings.
 */

/** What the phone compares with its own model before downloading a newer classifier head. */
@Serializable
data class AiStatusDto(
    val ready: Boolean,
    val status: String,
    val model: String,
    val modelDisplayName: String,
    val modelVersion: Int? = null,
    val classes: List<String> = emptyList(),
    val confidenceThreshold: Double = 0.0,
    val trainedAt: String? = null,
)
