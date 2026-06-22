package com.minedetector.data.repository

import com.minedetector.data.local.AppDatabase
import com.minedetector.data.local.entities.DetectionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DetectionRepository(private val database: AppDatabase) {

    suspend fun getAllDetections(): List<DetectionEntity> = withContext(Dispatchers.IO) {
        database.detectionDao().getAllDetections()
    }

    suspend fun getDetectionById(id: Long): DetectionEntity? = withContext(Dispatchers.IO) {
        database.detectionDao().getDetectionById(id)
    }

    suspend fun getDetectionsByFlightId(flightId: String): List<DetectionEntity> =
        withContext(Dispatchers.IO) {
            database.detectionDao().getDetectionsByFlightId(flightId)
        }

    suspend fun insertDetection(detection: DetectionEntity): Long = withContext(Dispatchers.IO) {
        database.detectionDao().insertDetection(detection)
    }

    suspend fun insertDetections(detections: List<DetectionEntity>) = withContext(Dispatchers.IO) {
        database.detectionDao().insertDetections(detections)
    }

    suspend fun deleteDetection(detection: DetectionEntity) = withContext(Dispatchers.IO) {
        database.detectionDao().deleteDetection(detection)
    }

    suspend fun deleteAllDetections() = withContext(Dispatchers.IO) {
        database.detectionDao().deleteAllDetections()
    }

    suspend fun getDetectionCount(): Int = withContext(Dispatchers.IO) {
        database.detectionDao().getDetectionCount()
    }
}