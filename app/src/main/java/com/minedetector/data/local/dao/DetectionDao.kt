package com.minedetector.data.local.dao

import androidx.room.*
import com.minedetector.data.local.entities.DetectionEntity

@Dao
interface DetectionDao {

    @Query("SELECT * FROM detections ORDER BY timestamp DESC")
    suspend fun getAllDetections(): List<DetectionEntity>

    @Query("SELECT * FROM detections WHERE id = :id")
    suspend fun getDetectionById(id: Long): DetectionEntity?

    @Query("SELECT * FROM detections WHERE flightId = :flightId")
    suspend fun getDetectionsByFlightId(flightId: String): List<DetectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDetection(detection: DetectionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDetections(detections: List<DetectionEntity>)

    @Delete
    suspend fun deleteDetection(detection: DetectionEntity)

    @Query("DELETE FROM detections")
    suspend fun deleteAllDetections()

    @Query("SELECT COUNT(*) FROM detections")
    suspend fun getDetectionCount(): Int
}