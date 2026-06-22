package com.minedetector.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "detections",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["flightId"]),
        Index(value = ["photoPath"])
    ]
)
data class DetectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long,
    val flightId: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Float,
    val photoPath: String,
    val detectionCount: Int = 0,   // для галереи (ты к нему обращаешься)
    val label: String? = null,     // тип/класс (по желанию)
    val confidence: Float? = null  // уверенность (по желанию)
)
