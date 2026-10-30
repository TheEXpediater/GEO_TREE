package com.geotree.app.data.remote

import okhttp3.ResponseBody
import retrofit2.http.GET

/**
 * The two AI routes the field app uses to keep its ON-DEVICE leaf model current (any signed-in
 * user): the server's active model status and its classifier head in the on-device format.
 * Leaf assessment itself runs on the phone (core/ai); no photo is sent here to be classified.
 * Classifier administration (classes, training images, retraining) is web-only.
 * A server without AI (e.g. the hosted Render API) answers 503 AI_DISABLED and the bundled model stays.
 */
interface GeoTreeAiApi {
    @GET("api/v1/ai/status")
    suspend fun aiStatus(): AiStatusDto

    /** The active classifier head in the on-device format (see core/ai/LeafModelSpec.kt). */
    @GET("api/v1/ai/model/head")
    suspend fun modelHead(): ResponseBody
}
