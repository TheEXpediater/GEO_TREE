package com.geotree.app.core.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The on-device leaf model, as written by `backend/scripts/export_mobile_model.py` into
 * `assets/leaf_model/` (and, for head updates, served by `GET /api/v1/ai/model/head`).
 *
 *   manifest.json            backbone file + checksum, preprocessing, compatibility key
 *   dinov2_vits14_336.onnx   frozen DINOv2 ViT-S/14, (1, 3, 336, 336) → CLS feature (1, 384)
 *   classifier_head.json     the trained logistic-regression head, class order and model version
 *
 * [LeafModelManifest.embeddingModel] fingerprints backbone + preprocessing. A head trained for a
 * different key is never applied: its predictions would be meaningless.
 */
const val LEAF_MODEL_FORMAT_VERSION = 1

internal val modelJson = Json { ignoreUnknownKeys = true }

@Serializable
data class LeafModelManifest(
    val formatVersion: Int,
    val model: String,
    val modelDisplayName: String,
    val embeddingModel: String,
    val embeddingDimension: Int,
    val preprocessing: Preprocessing,
    val confidenceThreshold: Double,
    val backbone: BackboneFile,
    val head: HeadFile,
) {
    @Serializable
    data class Preprocessing(
        val version: Int,
        val imageSize: Int,
        val resizeShortSide: Int,
        val resample: String,
        val crop: String,
        val mean: List<Float>,
        val std: List<Float>,
        val layout: String,
    )

    @Serializable
    data class BackboneFile(val file: String, val sha256: String, val bytes: Long, val input: String, val output: String)

    @Serializable
    data class HeadFile(val file: String, val sha256: String)

    /** A reason this manifest cannot be used by this app version, or null. */
    fun problem(): String? = when {
        formatVersion != LEAF_MODEL_FORMAT_VERSION -> "Unsupported model format $formatVersion."
        preprocessing.resample != "pillow-bicubic" || preprocessing.crop != "center" || preprocessing.layout != "NCHW" ->
            "Unsupported preprocessing ${preprocessing.resample}/${preprocessing.crop}/${preprocessing.layout}."
        preprocessing.mean.size != 3 || preprocessing.std.size != 3 || preprocessing.std.any { it <= 0f } -> "Invalid normalization."
        preprocessing.imageSize < 14 || preprocessing.resizeShortSide < preprocessing.imageSize -> "Invalid image size."
        embeddingDimension < 1 -> "Invalid embedding dimension."
        confidenceThreshold !in 0.0..1.0 -> "Invalid confidence threshold."
        else -> null
    }
}

@Serializable
data class ClassifierHeadSpec(
    val formatVersion: Int,
    val modelVersion: Int,
    val embeddingModel: String,
    val embeddingDimension: Int,
    val classes: List<HeadClass>,
    val weights: List<List<Double>>,
    val bias: List<Double>,
    val mean: List<Double>,
    val scale: List<Double>,
    val trainedAt: String? = null,
    val serverArtifactSha256: String? = null,
) {
    @Serializable
    data class HeadClass(val index: Int, val classId: String, val name: String)

    /**
     * A reason this head must not be used with [manifest]'s backbone, or null. Checks shape,
     * class order and finiteness so a truncated or foreign file can never produce a prediction.
     */
    fun problemWith(manifest: LeafModelManifest): String? {
        val dimension = manifest.embeddingDimension
        return when {
            formatVersion != LEAF_MODEL_FORMAT_VERSION -> "Unsupported classifier format $formatVersion."
            embeddingModel != manifest.embeddingModel -> "Classifier was trained for a different feature extractor ($embeddingModel)."
            embeddingDimension != dimension -> "Classifier expects $embeddingDimension features, the backbone gives $dimension."
            modelVersion < 1 -> "Invalid model version."
            classes.size < 2 -> "A classifier needs at least two classes."
            classes.map { it.index } != classes.indices.toList() -> "Class order is not 0..${classes.size - 1}."
            classes.any { it.classId.isBlank() || it.name.isBlank() } -> "A class has no id or name."
            classes.map { it.classId }.toSet().size != classes.size -> "Duplicate class ids."
            weights.size != classes.size || weights.any { it.size != dimension } -> "Weights do not match ${classes.size} classes × $dimension."
            bias.size != classes.size || mean.size != dimension || scale.size != dimension -> "Bias/mean/scale have the wrong size."
            (weights.flatten() + bias + mean + scale).any { !it.isFinite() } -> "Classifier contains non-finite values."
            scale.any { it == 0.0 } -> "Classifier scale contains zero."
            else -> null
        }
    }
}

fun parseManifest(text: String): LeafModelManifest = modelJson.decodeFromString(text)

fun parseHead(text: String): ClassifierHeadSpec = modelJson.decodeFromString(text)
