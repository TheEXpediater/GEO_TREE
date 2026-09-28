package com.geotree.app.data.remote

import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/** FastAPI contract. Android talks only to FastAPI, never to MongoDB. */
interface GeoTreeApi {
    @GET("api/v1/health")
    suspend fun health(): HealthDto

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequestDto): LoginResponseDto

    @GET("api/v1/auth/me")
    suspend fun me(): UserDto

    @POST("api/v1/trees/sync")
    suspend fun syncTree(@Body body: TreeSyncRequestDto): TreeSyncResponseDto

    @GET("api/v1/trees/changes")
    suspend fun changes(
        @Query("after_version") afterVersion: Long,
        @Query("limit") limit: Int = 200,
    ): TreeChangesDto

    @GET("api/v1/trees/{id}")
    suspend fun tree(@Path("id") id: String): TreeDto

    @Multipart
    @POST("api/v1/trees/{id}/image")
    suspend fun uploadImage(@Path("id") id: String, @Part file: MultipartBody.Part): TreeDto

    companion object {
        const val SERVICE_NAME = "geo-tree-api"
    }
}
