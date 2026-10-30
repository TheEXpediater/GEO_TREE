package com.geotree.app.testing

import android.database.sqlite.SQLiteConstraintException
import com.geotree.app.core.ai.ClassProbability
import com.geotree.app.core.ai.HeadSource
import com.geotree.app.core.ai.InferenceTimings
import com.geotree.app.core.ai.LeafClassifier
import com.geotree.app.core.ai.LeafInferenceErrors
import com.geotree.app.core.ai.LeafModelInfo
import com.geotree.app.core.ai.LeafPrediction
import com.geotree.app.core.database.AssessmentStatus
import com.geotree.app.core.database.LeafAssessmentDao
import com.geotree.app.core.database.LeafAssessmentEntity
import com.geotree.app.core.database.SyncStatus
import com.geotree.app.data.remote.AiStatusDto
import com.geotree.app.data.remote.GeoTreeAiApi
import com.geotree.app.data.repository.AiResult
import com.geotree.app.data.repository.LocalImage
import com.geotree.app.testing.FakeGeoTreeServer.Companion.httpError
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * In-memory model of the two AI routes the field app uses: the server's active model status and
 * its classifier head download (null head = 404, like a server without the endpoint).
 */
class FakeAiServer : GeoTreeAiApi {
    var unreachable = false
    /** The server's active model version for field users (null = no model). */
    var activeModelVersion: Int? = null
    var activeClasses = listOf("Healthy", "Spotting", "Powdery Mildew")
    /** What GET /api/v1/ai/model/head returns; null = 404 (a server without the endpoint). */
    var headBytes: ByteArray? = null
    var headCalls = 0

    override suspend fun aiStatus(): AiStatusDto {
        if (unreachable) throw IOException("Failed to connect")
        val version = activeModelVersion
        return AiStatusDto(version != null, if (version != null) "READY" else "NOT_READY", "dinov2_vits14", "DINOv2 ViT-S/14", version, if (version != null) activeClasses else emptyList(), 0.6)
    }

    override suspend fun modelHead(): ResponseBody {
        headCalls++
        if (unreachable) throw IOException("Failed to connect")
        val bytes = headBytes ?: throw httpError(404, "NOT_FOUND", "Not Found")
        return bytes.toResponseBody("application/json".toMediaType())
    }
}

/**
 * The on-device classifier, scripted: [prediction] is returned for every photo; null means no
 * usable model (AI_MODEL_NOT_READY). Records which photos were classified.
 */
class FakeLeafClassifier : LeafClassifier {
    var prediction: LeafPrediction? = null
    var failure: AiResult.Failed? = null
    var classifyCalls = 0
    val classified = mutableListOf<File>()

    override suspend fun status(): AiResult<LeafModelInfo> {
        val p = prediction ?: return LeafInferenceErrors.modelUnavailable(NO_MODEL)
        return AiResult.Ok(LeafModelInfo(p.model, p.modelDisplayName, p.modelVersion, listOf("Healthy", "Spotting", "Mold"), 0.6, HeadSource.BUNDLED))
    }

    override suspend fun classify(image: File): AiResult<LeafPrediction> {
        classifyCalls++
        classified += image
        failure?.let { return it }
        return prediction?.let { AiResult.Ok(it) } ?: LeafInferenceErrors.modelUnavailable(NO_MODEL)
    }

    companion object {
        const val NO_MODEL = "The on-device leaf model is missing."
        val inferenceFailure = LeafInferenceErrors.inferenceFailed("test")

        fun result(className: String = "Spotting", confidence: Double = 0.94, version: Int = 7) = LeafPrediction(
            classId = "c-$className", className = className, confidence = confidence, uncertain = confidence < 0.6,
            confidenceThreshold = 0.6, model = "dinov2_vits14", modelDisplayName = "DINOv2 ViT-S/14", modelVersion = version,
            probabilities = listOf(ClassProbability("c-$className", className, confidence)),
            timings = InferenceTimings(0, 0, 0, 0),
        )
    }
}

/**
 * In-memory LeafAssessmentDao with the same ordering and filters as the Room queries.
 * Records every write so tests can prove an earlier scan was never rewritten.
 */
class FakeLeafAssessmentDao : LeafAssessmentDao {
    val rows = MutableStateFlow<Map<String, LeafAssessmentEntity>>(emptyMap())
    val writes = mutableListOf<LeafAssessmentEntity>()

    private val newestFirst = compareByDescending<LeafAssessmentEntity> { it.createdAt }.thenByDescending { it.id }

    override fun observeAssessmentsForTree(treeId: String): Flow<List<LeafAssessmentEntity>> =
        rows.map { all -> all.values.filter { it.treeId == treeId }.sortedWith(newestFirst) }

    override fun observeLatestCompletedAssessment(treeId: String): Flow<LeafAssessmentEntity?> =
        rows.map { all -> all.values.filter { it.treeId == treeId && it.status.hasResult }.sortedWith(newestFirst).firstOrNull() }

    override fun observeAssessmentById(id: String): Flow<LeafAssessmentEntity?> = rows.map { it[id] }

    override suspend fun getById(id: String): LeafAssessmentEntity? = rows.value[id]

    override fun observeUnsyncedCount(): Flow<Int> = rows.map { all -> all.values.count { it.syncStatus != SyncStatus.SYNCED } }

    override suspend fun getUnsynced(): List<LeafAssessmentEntity> = rows.value.values
        .filter { (it.syncStatus == SyncStatus.PENDING || it.syncStatus == SyncStatus.FAILED) && it.status.isFinal }
        .sortedBy { it.createdAt }

    override suspend fun insert(assessment: LeafAssessmentEntity) {
        if (rows.value.containsKey(assessment.id)) throw SQLiteConstraintException("UNIQUE constraint failed: leaf_assessments.id")
        rows.value = rows.value + (assessment.id to assessment)
        writes += assessment
    }

    override suspend fun update(assessment: LeafAssessmentEntity) {
        if (rows.value.containsKey(assessment.id)) {
            rows.value = rows.value + (assessment.id to assessment)
            writes += assessment
        }
    }

    override suspend fun failInterrupted(message: String, now: Long): Int {
        val stuck = rows.value.values.filter { it.status == AssessmentStatus.PENDING || it.status == AssessmentStatus.PROCESSING }
        stuck.forEach {
            update(it.copy(status = AssessmentStatus.FAILED, errorCode = "INTERRUPTED", errorMessage = message, completedAt = now, updatedAt = now, syncStatus = SyncStatus.PENDING))
        }
        return stuck.size
    }

    override suspend fun resetInterruptedSyncs(): Int {
        val syncing = rows.value.values.filter { it.syncStatus == SyncStatus.SYNCING }
        syncing.forEach { update(it.copy(syncStatus = SyncStatus.PENDING)) }
        return syncing.size
    }
}

/** Picked images by fake URI; "unreadable" URIs return null like an unreadable content URI. */
fun fakeImageReader(): (String) -> LocalImage? = { uri ->
    if (uri.startsWith("unreadable")) null else LocalImage("$uri.jpg", "image/jpeg", uri.toByteArray())
}
