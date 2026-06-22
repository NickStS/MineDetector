package com.minedetector.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "flight_logs",
    indices = [Index(value = ["startTime"]), Index(value = ["flightId"], unique = true)]
)
data class FlightLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val flightId: String,     // произвольный UID вылета
    val startTime: Long,
    val endTime: Long? = null,
    val notes: String? = null
)
