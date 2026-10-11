package com.geotree.app.data.repository

import com.geotree.app.data.remote.ClassImagesDto
import com.geotree.app.data.remote.ClassifierClassDto
import com.geotree.app.data.remote.ClassifierListDto
import com.geotree.app.data.remote.ClassifierNameDto
import com.geotree.app.data.remote.DeleteClassResultDto
import com.geotree.app.data.remote.DeleteImageResultDto
import com.geotree.app.data.remote.GeoTreeAiApi
import com.geotree.app.data.remote.ModelStatusDto
import com.geotree.app.data.remote.TrainingJobDto
import com.geotree.app.data.remote.UploadResultDto
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/** An image picked on the device, read into memory for one upload. */
class LocalImage(val name: String, val mimeType: String?, val bytes: ByteArray)

/** Reads a picked image (content:// URI string). Null when it cannot be read. */
fun interface LocalImageReader {
    suspend fun read(uri: String): LocalImage?
}

/**
 * Admin management of the server-side classifier dataset. Unlike tree records this is
 * server-owned data (the training set lives with the DINOv2 backend), so nothing is cached in Room:
 * every screen state comes from these calls and is refreshed after each change.
 */
class ClassifierRepository(
    private val api: suspend () -> GeoTreeAiApi,
    private val url: suspend (String) -> String,
) {
    suspend fun classes(): AiResult<ClassifierListDto> = aiCall { api().classifiers() }

    suspend fun createClass(name: String): AiResult<ClassifierClassDto> = aiCall { api().createClassifier(ClassifierNameDto(name.trim())) }

    suspend fun renameClass(id: String, name: String): AiResult<ClassifierClassDto> =
        aiCall { api().renameClassifier(id, ClassifierNameDto(name.trim())) }

    suspend fun deleteClass(id: String): AiResult<DeleteClassResultDto> = aiCall { api().deleteClassifier(id) }

    suspend fun images(classId: String): AiResult<ClassImagesDto> = aiCall { api().classImages(classId) }

    /** One image per request so progress and per-file errors (e.g. DUPLICATE_IMAGE) are exact. */
    suspend fun uploadImage(classId: String, image: LocalImage): AiResult<UploadResultDto> = aiCall {
        val body = image.bytes.toRequestBody(image.mimeType?.toMediaTypeOrNull())
        api().uploadClassImages(classId, listOf(MultipartBody.Part.createFormData("files", image.name, body)))
    }

    suspend fun deleteImage(classId: String, imageId: String): AiResult<DeleteImageResultDto> =
        aiCall { api().deleteClassImage(classId, imageId) }

    suspend fun modelStatus(): AiResult<ModelStatusDto> = aiCall { api().modelStatus() }

    suspend fun retrain(): AiResult<TrainingJobDto> = aiCall { api().retrain() }

    suspend fun thumbnailUrl(classId: String, imageId: String): String = url(GeoTreeAiApi.thumbnailPath(classId, imageId))
}
