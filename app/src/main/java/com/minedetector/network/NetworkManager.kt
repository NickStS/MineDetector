package com.minedetector.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.minedetector.network.models.DetectionUpload
import com.minedetector.network.models.ModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class NetworkManager(private val context: Context) {

    companion object {
        private const val TAG = "NetworkManager"
    }

    private val apiService: ApiService by lazy {
        RetrofitClient.create(context)
    }

    suspend fun checkForModelUpdate(currentVersion: String): ModelInfo? = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getLatestModel()
            if (response.isSuccessful) {
                val latestModel = response.body()
                if (latestModel != null && latestModel.version != currentVersion) {
                    Log.d(TAG, "New model available: ${latestModel.version}")
                    return@withContext latestModel
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for model update", e)
            null
        }
    }

    suspend fun downloadModel(modelInfo: ModelInfo, outputFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = apiService.downloadModel(modelInfo.version)
            if (response.isSuccessful) {
                response.body()?.let { bytes ->
                    outputFile.writeBytes(bytes)

                    // Проверка контрольной суммы
                    val checksum = calculateChecksum(outputFile)
                    if (checksum == modelInfo.checksum) {
                        Log.d(TAG, "Model downloaded successfully")
                        return@withContext true
                    } else {
                        Log.e(TAG, "Checksum mismatch!")
                        outputFile.delete()
                    }
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading model", e)
            false
        }
    }

    suspend fun uploadDetection(photoFile: File, metadata: Map<String, Any>): Boolean = withContext(Dispatchers.IO) {
        try {
            val photoBody = photoFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
            val photoPart = MultipartBody.Part.createFormData("photo", photoFile.name, photoBody)

            val metadataJson = Gson().toJson(metadata)
            val metadataBody = metadataJson.toRequestBody("application/json".toMediaTypeOrNull())

            val response = apiService.uploadDetection(photoPart, metadataBody)
            if (response.isSuccessful) {
                Log.d(TAG, "Detection uploaded successfully")
                return@withContext true
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading detection", e)
            false
        }
    }

    suspend fun uploadDetectionsBatch(detections: List<DetectionUpload>): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = apiService.uploadDetectionsBatch(detections)
            if (response.isSuccessful) {
                val result = response.body()
                Log.d(TAG, "Batch upload: ${result?.uploadedCount} uploaded, ${result?.failedCount} failed")
                return@withContext true
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading batch", e)
            false
        }
    }

    private fun calculateChecksum(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = file.readBytes()
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }
}