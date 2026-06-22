package com.minedetector.network

import com.minedetector.network.models.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {

    @GET("api/v1/models/latest")
    suspend fun getLatestModel(): Response<ModelInfo>

    @GET("api/v1/models/{version}")
    suspend fun downloadModel(@Path("version") version: String): Response<ByteArray>

    @Multipart
    @POST("api/v1/detections/upload")
    suspend fun uploadDetection(
        @Part photo: MultipartBody.Part,
        @Part("metadata") metadata: RequestBody
    ): Response<UploadResponse>

    @POST("api/v1/detections/batch")
    suspend fun uploadDetectionsBatch(
        @Body detections: List<DetectionUpload>
    ): Response<BatchUploadResponse>

    @GET("api/v1/alerts")
    suspend fun getAlerts(
        @Query("lat") latitude: Double,
        @Query("lon") longitude: Double,
        @Query("radius") radiusKm: Double
    ): Response<List<Alert>>

    @POST("api/v1/alerts/create")
    suspend fun createAlert(
        @Body alert: AlertCreate
    ): Response<Alert>

    @GET("api/v1/statistics")
    suspend fun getStatistics(): Response<Statistics>

    @POST("api/v1/feedback")
    suspend fun submitFeedback(
        @Body feedback: Feedback
    ): Response<FeedbackResponse>
}