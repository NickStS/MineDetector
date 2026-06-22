package com.minedetector.services

import android.content.Context
import android.util.Log
import androidx.work.*
import com.minedetector.data.local.AppDatabase
import com.minedetector.network.NetworkManager
import com.minedetector.network.models.DetectionUpload
import java.util.UUID
import java.util.concurrent.TimeUnit

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SyncWorker"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncWork = PeriodicWorkRequestBuilder<SyncWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "data_sync",
                ExistingPeriodicWorkPolicy.KEEP,
                syncWork
            )
        }
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "Starting data synchronization...")

            val database = AppDatabase.getDatabase(applicationContext)
            val detections = database.detectionDao().getAllDetections()

            if (detections.isEmpty()) {
                Log.d(TAG, "No data to sync")
                return Result.success()
            }

            val networkManager = NetworkManager(applicationContext)
            val deviceId = getDeviceId()
            val uploads = detections.map { detection ->
                DetectionUpload(
                    latitude = detection.latitude,
                    longitude = detection.longitude,
                    altitude = detection.altitude,
                    detectionType = detection.label ?: "unknown",
                    confidence = detection.confidence ?: 0f,
                    timestamp = detection.timestamp,
                    deviceId = deviceId
                )
            }

            val success = networkManager.uploadDetectionsBatch(uploads)

            if (success) {
                Log.d(TAG, "Sync completed successfully")
                Result.success()
            } else {
                Log.w(TAG, "Sync failed, will retry")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in SyncWorker", e)
            Result.retry()
        }
    }

    private fun getDeviceId(): String {
        // Use a random UUID stored in SharedPreferences instead of ANDROID_ID
        val prefs = applicationContext.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        return prefs.getString("device_id", null) ?: run {
            val id = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
            id
        }
    }
}
