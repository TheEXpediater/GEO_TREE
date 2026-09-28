package com.geotree.app.core.network

import com.geotree.app.BuildConfig
import com.geotree.app.data.remote.GeoTreeApi
import com.geotree.app.data.remote.GeoTreeJson
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Builds the Retrofit API for the currently configured backend. Changing the base URL
 * (emulator → LAN → hosted) needs no change in repositories.
 */
class ApiProvider(
    private val backendConfig: BackendConfig,
    tokenProvider: () -> String?,
) {
    @Volatile
    private var activeHost: String? = null

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(BearerTokenInterceptor(tokenProvider) { activeHost })
        .apply {
            if (BuildConfig.DEBUG) addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
        }
        .build()

    @Volatile
    private var cached: Pair<String, GeoTreeApi>? = null

    suspend fun api(): GeoTreeApi = forBaseUrl(backendConfig.current())

    /** Also used to probe a candidate URL before it is saved. */
    fun forBaseUrl(baseUrl: String, makeActive: Boolean = true): GeoTreeApi {
        cached?.let { (url, api) -> if (url == baseUrl) return api }
        val api = Retrofit.Builder()
            .baseUrl("$baseUrl/")
            .client(okHttpClient)
            .addConverterFactory(GeoTreeJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GeoTreeApi::class.java)
        if (makeActive) {
            activeHost = baseUrl.toHttpUrlOrNull()?.host
            cached = baseUrl to api
        }
        return api
    }

    /** Image URL for a server-relative path such as "tree_images/abc.jpg". */
    suspend fun imageUrl(remotePath: String): String {
        val baseUrl = backendConfig.current()
        forBaseUrl(baseUrl) // ensures the token interceptor recognises this host
        return "$baseUrl/uploads/$remotePath"
    }
}

/** Attaches the session token, but only to the configured GEO Tree host. */
private class BearerTokenInterceptor(
    private val tokenProvider: () -> String?,
    private val activeHost: () -> String?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = tokenProvider()
        if (token == null || request.header("Authorization") != null || request.url.host != activeHost()) {
            return chain.proceed(request)
        }
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
    }
}
