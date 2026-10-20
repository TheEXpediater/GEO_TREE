package com.geotree.app.data.remote

import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

/**
 * FastAPI AI routes: classifier administration (admin role), plus the model status and the
 * classifier-head download that keep the ON-DEVICE leaf model current (any signed-in user).
 * Leaf assessment itself runs on the phone (core/ai); no photo is sent here to be classified.
 * Separate from [GeoTreeApi] because uploads need longer timeouts; field sync never depends on it.
 */
interface GeoTreeAiApi {
    @GET("api/v1/admin/classifiers")
    suspend fun classifiers(): ClassifierListDto

    @POST("api/v1/admin/classifiers")
    suspend fun createClassifier(@Body body: ClassifierNameDto): ClassifierClassDto

    @PATCH("api/v1/admin/classifiers/{id}")
    suspend fun renameClassifier(@Path("id") id: String, @Body body: ClassifierNameDto): ClassifierClassDto

    @DELETE("api/v1/admin/classifiers/{id}")
    suspend fun deleteClassifier(@Path("id") id: String): DeleteClassResultDto

    @GET("api/v1/admin/classifiers/{id}/images")
    suspend fun classImages(@Path("id") classId: String): ClassImagesDto

    @Multipart
    @POST("api/v1/admin/classifiers/{id}/images")
    suspend fun uploadClassImages(@Path("id") classId: String, @Part files: List<MultipartBody.Part>): UploadResultDto

    @DELETE("api/v1/admin/classifiers/{id}/images/{imageId}")
    suspend fun deleteClassImage(@Path("id") classId: String, @Path("imageId") imageId: String): DeleteImageResultDto

    @GET("api/v1/admin/ai/model")
    suspend fun modelStatus(): ModelStatusDto

    @POST("api/v1/admin/ai/retrain")
    suspend fun retrain(): TrainingJobDto

    @GET("api/v1/ai/jobs/{jobId}")
    suspend fun job(@Path("jobId") jobId: String): TrainingJobDto

    @GET("api/v1/ai/status")
    suspend fun aiStatus(): AiStatusDto

    /** The active classifier head in the on-device format (see core/ai/LeafModelSpec.kt). */
    @GET("api/v1/ai/model/head")
    suspend fun modelHead(): ResponseBody

    companion object {
        fun thumbnailPath(classId: String, imageId: String) = "api/v1/admin/classifiers/$classId/images/$imageId/thumbnail"
    }
}
