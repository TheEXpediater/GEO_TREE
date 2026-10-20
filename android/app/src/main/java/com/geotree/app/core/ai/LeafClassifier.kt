package com.geotree.app.core.ai

import com.geotree.app.data.repository.AiProblem
import com.geotree.app.data.repository.AiResult
import java.io.File

data class ClassProbability(val classId: String, val className: String, val probability: Double)

/** One genuine on-device prediction, with the exact model that produced it. */
data class LeafPrediction(
    val classId: String,
    val className: String,
    val confidence: Double,
    val uncertain: Boolean,
    val confidenceThreshold: Double,
    val model: String,
    val modelDisplayName: String,
    val modelVersion: Int,
    /** Highest first. */
    val probabilities: List<ClassProbability>,
    val timings: InferenceTimings,
)

data class InferenceTimings(val loadMs: Long, val decodeMs: Long, val preprocessMs: Long, val inferenceMs: Long)

/** The model a new scan would use right now. */
data class LeafModelInfo(
    val model: String,
    val modelDisplayName: String,
    val modelVersion: Int,
    /** Class names in the model's class order. */
    val classes: List<String>,
    val confidenceThreshold: Double,
    val headSource: HeadSource,
)

/** Leaf assessment that runs entirely on this device. No network is involved. */
interface LeafClassifier {
    suspend fun status(): AiResult<LeafModelInfo>

    suspend fun classify(image: File): AiResult<LeafPrediction>
}

/** Scores → prediction. Shared by the on-device classifier and its tests. */
fun predictionFrom(
    probabilities: DoubleArray,
    head: ClassifierHeadSpec,
    manifest: LeafModelManifest,
    timings: InferenceTimings,
): LeafPrediction {
    val ranked = head.classes.map { ClassProbability(it.classId, it.name, probabilities[it.index]) }.sortedByDescending { it.probability }
    val best = ranked.first()
    return LeafPrediction(
        classId = best.classId,
        className = best.className,
        confidence = best.probability,
        uncertain = best.probability < manifest.confidenceThreshold,
        confidenceThreshold = manifest.confidenceThreshold,
        model = manifest.model,
        modelDisplayName = manifest.modelDisplayName,
        modelVersion = head.modelVersion,
        probabilities = ranked,
        timings = timings,
    )
}

fun PreparedLeafModel.info() = LeafModelInfo(
    model = manifest.model,
    modelDisplayName = manifest.modelDisplayName,
    modelVersion = head.modelVersion,
    classes = head.classes.map { it.name },
    confidenceThreshold = manifest.confidenceThreshold,
    headSource = headSource,
)

/** Error codes recorded on FAILED scans. */
object LeafInferenceErrors {
    fun modelUnavailable(message: String) = AiResult.Failed(AiProblem.ModelNotReady(message))
    fun imageMissing() = AiResult.Failed(AiProblem.Rejected("IMAGE_MISSING", "The leaf photo is no longer on this device. Take a new photo."))
    fun unreadable() = AiResult.Failed(AiProblem.Rejected("UNSUPPORTED_IMAGE", "This photo could not be read. Use a JPEG or PNG photo."))
    fun tooSmall() = AiResult.Failed(AiProblem.Rejected("IMAGE_TOO_SMALL", "The photo must be at least 32 × 32 pixels."))
    fun tooLarge() = AiResult.Failed(AiProblem.Rejected("IMAGE_TOO_LARGE", "The photo is too large to assess on this phone. Use the camera or a smaller photo."))
    fun outOfMemory() = AiResult.Failed(AiProblem.Rejected("OUT_OF_MEMORY", "The phone ran out of memory while assessing. Close other apps and try again."))
    fun inferenceFailed(detail: String) = AiResult.Failed(AiProblem.Rejected("INFERENCE_FAILED", "The on-device model could not process this photo ($detail)."))
}
