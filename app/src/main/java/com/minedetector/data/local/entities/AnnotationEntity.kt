package com.minedetector.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "annotations",
    indices = [Index(value = ["photoPath"]), Index(value = ["timestamp"]), Index(value = ["isVerified"])]
)
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val photoPath: String,
    val timestamp: Long,
    val isVerified: Boolean = false,
    val note: String? = null // опционально: комментарий к аннотации
)
