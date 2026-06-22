package com.minedetector.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.minedetector.R
import com.minedetector.network.NetworkManager
import com.minedetector.utils.Constants
import java.io.File
import java.util.concurrent.TimeUnit

class ModelUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ModelUpdateWorker"
        private const val NOTIFICATION_ID = 1001
        private const val CURRENT_MODEL_VERSION = "1.0.0"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val updateWork = PeriodicWorkRequestBuilder<ModelUpdateWorker>(
                1, TimeUnit.DAYS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "model_update",
                ExistingPeriodicWorkPolicy.KEEP,
                updateWork
            )
        }
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "Checking for model updates...")

            val networkManager = NetworkManager(applicationContext)
            val modelInfo = networkManager.checkForModelUpdate(CURRENT_MODEL_VERSION)

            if (modelInfo != null) {
                showNotification("Обновление модели", "Загрузка новой версии...")

                val modelFile = File(applicationContext.filesDir, "yolo_mine_detector_new.tflite")
                val success = networkManager.downloadModel(modelInfo, modelFile)

                if (success) {
                    showNotification("Обновление завершено", "Новая модель готова к использованию")
                    Result.success()
                } else {
                    showNotification("Ошибка обновления", "Не удалось загрузить модель")
                    Result.retry()
                }
            } else {
                Log.d(TAG, "No updates available")
                Result.success()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in ModelUpdateWorker", e)
            Result.retry()
        }
    }

    private fun showNotification(title: String, message: String) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_ID,
                Constants.NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}