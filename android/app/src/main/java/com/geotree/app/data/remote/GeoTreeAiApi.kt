package com.geotree.app.data.remote

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

/**
 * FastAPI AI routes: classifier administration (admin role) and leaf assessment (any signed-in
 * user). Separate from [GeoTreeApi] because these calls run DINOv2 on the server and need longer
 * timeouts; field sync never depends on them.
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

    @Multipart
    @POST("api/v1/ai/leaf-assessment")
    suspend fun assessLeaf(@Part file: MultipartBody.Part, @Part("treeId") treeId: RequestBody?): LeafAssessmentDto

    companion object {
        fun thumbnailPath(classId: String, imageId: String) = "api/v1/admin/classifiers/$classId/images/$imageId/thumbnail"
    }
}
