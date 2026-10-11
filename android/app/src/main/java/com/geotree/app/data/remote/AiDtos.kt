package com.geotree.app.data.remote

import kotlinx.serialization.Serializable

/*
 * Leaf assessment and classifier administration. The AI routes use camelCase JSON keys, so no
 * @SerialName mapping is needed. Timestamps are ISO-8601 strings (parse with parseIsoMillis).
 */

@Serializable
data class ClassifierNameDto(val name: String)

@Serializable
data class ClassifierClassDto(
    val id: String,
    val name: String,
    val slug: String = "",
    val imageCount: Int = 0,
    val embeddedCount: Int = 0,
    /** READY | INSUFFICIENT | PENDING_FEATURES */
    val status: String = "INSUFFICIENT",
    val inActiveModel: Boolean = false,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class ClassifierListDto(val items: List<ClassifierClassDto>, val minImagesPerClass: Int = 0)

@Serializable
data class DatasetImageDto(
    val id: String,
    val classId: String,
    val originalFilename: String,
    val mimeType: String = "",
    val fileSize: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    /** READY | PENDING | ERROR (DINOv2 feature cached or not) */
    val embeddingStatus: String = "PENDING",
    val createdAt: String,
)

@Serializable
data class ClassImagesDto(val classId: String, val className: String, val items: List<DatasetImageDto>)

@Serializable
data class RejectedFileDto(val filename: String, val code: String, val message: String)

@Serializable
data class TrainingJobDto(
    val jobId: String,
    /** QUEUED | TRAINING | COMPLETE | ERROR */
    val status: String,
    val trigger: String? = null,
    val createdAt: String? = null,
    val runAfter: String? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val coalescedChanges: Int = 1,
    val modelVersion: Int? = null,
    val embeddingsComputed: Int? = null,
    val embeddingsReused: Int? = null,
    val errorMessage: String? = null,
)

@Serializable
data class UploadResultDto(
    val uploaded: List<DatasetImageDto>,
    val rejected: List<RejectedFileDto> = emptyList(),
    val embeddingsComputed: Int = 0,
    val datasetVersion: Long = 0,
    val job: TrainingJobDto? = null,
)

@Serializable
data class DeleteImageResultDto(val deleted: String, val datasetVersion: Long = 0, val job: TrainingJobDto? = null)

@Serializable
data class DeleteClassResultDto(
    val deletedImages: Int = 0,
    val modelInvalidated: Boolean = false,
    val datasetVersion: Long = 0,
    val job: TrainingJobDto? = null,
)

@Serializable
data class ReadinessDto(val canTrain: Boolean, val minImagesPerClass: Int, val problems: List<String> = emptyList())

@Serializable
data class ModelClassDto(val classId: String, val name: String, val trainCount: Int = 0, val validationCount: Int = 0)

@Serializable
data class PerClassMetricDto(
    val classId: String,
    val className: String,
    val precision: Double,
    val recall: Double,
    val f1: Double,
    val support: Int,
)

@Serializable
data class ConfusionMatrixDto(val labels: List<String>, val matrix: List<List<Int>>)

@Serializable
data class ModelMetricsDto(
    val accuracy: Double,
    val macroPrecision: Double,
    val macroRecall: Double,
    val macroF1: Double,
    val perClass: List<PerClassMetricDto> = emptyList(),
    val confusionMatrix: ConfusionMatrixDto? = null,
)

@Serializable
data class ActiveModelDto(
    val modelVersion: Int,
    val model: String,
    val modelDisplayName: String,
    val trainedAt: String,
    val trainCount: Int = 0,
    val validationCount: Int = 0,
    val classes: List<ModelClassDto> = emptyList(),
    val metrics: ModelMetricsDto,
)

@Serializable
data class BackboneDto(
    val name: String,
    val displayName: String,
    val embeddingDimension: Int = 0,
    val imageSize: Int = 0,
    val loaded: Boolean = false,
    val device: String? = null,
    val error: String? = null,
)

@Serializable
data class ModelStatusDto(
    /** UNTRAINED | QUEUED | TRAINING | READY | ERROR */
    val status: String,
    val stale: Boolean = false,
    val datasetVersion: Long = 0,
    val datasetImageCount: Int = 0,
    val readiness: ReadinessDto,
    val activeModel: ActiveModelDto? = null,
    val currentJob: TrainingJobDto? = null,
    val lastJob: TrainingJobDto? = null,
    val lastError: String? = null,
    val backbone: BackboneDto,
    val confidenceThreshold: Double = 0.0,
)

/** What a field user needs before assessing a leaf. */
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

@Serializable
data class ClassProbabilityDto(val classId: String, val className: String, val probability: Double)

@Serializable
data class LeafAssessmentDto(
    val classId: String,
    val className: String,
    val confidence: Double,
    val uncertain: Boolean = false,
    val confidenceThreshold: Double = 0.0,
    val model: String,
    val modelDisplayName: String = model,
    val modelVersion: Int,
    val probabilities: List<ClassProbabilityDto> = emptyList(),
    val assessedAt: String? = null,
)
