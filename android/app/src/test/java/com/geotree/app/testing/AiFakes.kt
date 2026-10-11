package com.geotree.app.testing

import android.database.sqlite.SQLiteConstraintException
import com.geotree.app.core.database.AssessmentStatus
import com.geotree.app.core.database.LeafAssessmentDao
import com.geotree.app.core.database.LeafAssessmentEntity
import com.geotree.app.core.database.SyncStatus
import com.geotree.app.data.remote.AiStatusDto
import com.geotree.app.data.remote.BackboneDto
import com.geotree.app.data.remote.ClassImagesDto
import com.geotree.app.data.remote.ClassifierClassDto
import com.geotree.app.data.remote.ClassifierListDto
import com.geotree.app.data.remote.ClassifierNameDto
import com.geotree.app.data.remote.DatasetImageDto
import com.geotree.app.data.remote.DeleteClassResultDto
import com.geotree.app.data.remote.DeleteImageResultDto
import com.geotree.app.data.remote.GeoTreeAiApi
import com.geotree.app.data.remote.LeafAssessmentDto
import com.geotree.app.data.remote.ModelStatusDto
import com.geotree.app.data.remote.ReadinessDto
import com.geotree.app.data.remote.TrainingJobDto
import com.geotree.app.data.remote.UploadResultDto
import com.geotree.app.data.repository.LocalImage
import com.geotree.app.testing.FakeGeoTreeServer.Companion.httpError
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.Buffer

/**
 * In-memory model of the FastAPI AI routes: class CRUD with unique names, per-file uploads with
 * duplicate detection, a scriptable model status sequence, and leaf assessment.
 */
class FakeAiServer : GeoTreeAiApi {
    val classes = linkedMapOf<String, ClassifierClassDto>()
    val images = linkedMapOf<String, MutableList<DatasetImageDto>>()
    private val uploadedContent = mutableSetOf<String>()
    private var nextId = 0

    var unreachable = false
    var admin = true
    /** Statuses returned by successive modelStatus() calls; the last one repeats. */
    val statusSequence = ArrayDeque(listOf("UNTRAINED"))
    var modelStatusCalls = 0
    var assessment: LeafAssessmentDto? = null
    var assessmentFailure: Exception? = null
    var assessCalls = 0
    var uploadCalls = 0
    var failDeleteFor: String? = null

    private fun guard() {
        if (unreachable) throw IOException("Failed to connect to /10.0.2.2:8000")
        if (!admin) throw httpError(403, "ADMIN_REQUIRED", "Administrator access is required.")
    }

    fun addClass(name: String, imageCount: Int = 0): ClassifierClassDto {
        val dto = ClassifierClassDto(id = "c${nextId++}", name = name, imageCount = imageCount, status = if (imageCount >= 10) "READY" else "INSUFFICIENT")
        classes[dto.id] = dto
        images[dto.id] = MutableList(imageCount) { DatasetImageDto("i${nextId++}", dto.id, "seed_$it.jpg", createdAt = "2026-10-08T00:00:00Z") }
        return dto
    }

    override suspend fun classifiers(): ClassifierListDto {
        guard()
        return ClassifierListDto(classes.values.map { it.copy(imageCount = images[it.id]?.size ?: 0) }, minImagesPerClass = 10)
    }

    override suspend fun createClassifier(body: ClassifierNameDto): ClassifierClassDto {
        guard()
        if (classes.values.any { it.name.equals(body.name, ignoreCase = true) }) {
            throw httpError(409, "CLASS_NAME_CONFLICT", "A class named ${body.name} already exists.")
        }
        return addClass(body.name)
    }

    override suspend fun renameClassifier(id: String, body: ClassifierNameDto): ClassifierClassDto {
        guard()
        val current = classes[id] ?: throw httpError(404, "CLASS_NOT_FOUND", "Classifier class not found.")
        return current.copy(name = body.name).also { classes[id] = it }
    }

    override suspend fun deleteClassifier(id: String): DeleteClassResultDto {
        guard()
        classes.remove(id) ?: throw httpError(404, "CLASS_NOT_FOUND", "Classifier class not found.")
        val removed = images.remove(id)?.size ?: 0
        return DeleteClassResultDto(deletedImages = removed, modelInvalidated = true)
    }

    override suspend fun classImages(classId: String): ClassImagesDto {
        guard()
        val dto = classes[classId] ?: throw httpError(404, "CLASS_NOT_FOUND", "Classifier class not found.")
        return ClassImagesDto(classId, dto.name, images[classId].orEmpty().toList())
    }

    override suspend fun uploadClassImages(classId: String, files: List<MultipartBody.Part>): UploadResultDto {
        uploadCalls++
        guard()
        val part = files.single()
        val content = Buffer().also { part.body.writeTo(it) }.readUtf8()
        if (!uploadedContent.add(content)) throw httpError(409, "DUPLICATE_IMAGE", "An identical image is already in the dataset.")
        val image = DatasetImageDto("i${nextId++}", classId, "upload.jpg", embeddingStatus = "READY", createdAt = "2026-10-08T01:00:00Z")
        images.getOrPut(classId) { mutableListOf() }.add(0, image)
        return UploadResultDto(listOf(image), job = TrainingJobDto("job-1", "QUEUED", trigger = "AUTO"))
    }

    override suspend fun deleteClassImage(classId: String, imageId: String): DeleteImageResultDto {
        guard()
        if (imageId == failDeleteFor) throw httpError(404, "IMAGE_NOT_FOUND", "Image not found.")
        images[classId]?.removeAll { it.id == imageId }
        return DeleteImageResultDto(imageId, job = TrainingJobDto("job-1", "QUEUED"))
    }

    override suspend fun modelStatus(): ModelStatusDto {
        guard()
        modelStatusCalls++
        val status = if (statusSequence.size > 1) statusSequence.removeFirst() else statusSequence.first()
        return ModelStatusDto(
            status = status,
            datasetImageCount = images.values.sumOf { it.size },
            readiness = ReadinessDto(canTrain = true, minImagesPerClass = 10),
            backbone = BackboneDto("dinov2_vits14", "DINOv2 ViT-S/14", 384, 336, loaded = true, device = "cpu"),
            confidenceThreshold = 0.6,
        )
    }

    override suspend fun retrain(): TrainingJobDto {
        guard()
        return TrainingJobDto("job-2", "QUEUED", trigger = "MANUAL")
    }

    override suspend fun job(jobId: String): TrainingJobDto = TrainingJobDto(jobId, "COMPLETE")

    override suspend fun aiStatus(): AiStatusDto {
        if (unreachable) throw IOException("Failed to connect")
        val ready = assessment != null
        return AiStatusDto(ready, if (ready) "READY" else "NOT_READY", "dinov2_vits14", "DINOv2 ViT-S/14", if (ready) 3 else null, listOf("Healthy", "Mold", "Spotting"), 0.6)
    }

    override suspend fun assessLeaf(file: MultipartBody.Part, treeId: RequestBody?): LeafAssessmentDto {
        assessCalls++
        if (unreachable) throw IOException("Failed to connect to /10.0.2.2:8000")
        assessmentFailure?.let { throw it }
        return assessment ?: throw httpError(409, "AI_MODEL_NOT_READY", "No trained tamarind leaf model is active yet.")
    }

    companion object {
        fun result(className: String = "Spotting", confidence: Double = 0.94, version: Int = 7) = LeafAssessmentDto(
            classId = "c-$className", className = className, confidence = confidence, uncertain = confidence < 0.6,
            confidenceThreshold = 0.6, model = "dinov2_vits14", modelDisplayName = "DINOv2 ViT-S/14", modelVersion = version,
            assessedAt = "2026-10-08T02:00:00Z",
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
