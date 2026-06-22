package com.minedetector.data.local.dao

import androidx.room.*
import com.minedetector.data.local.entities.AnnotationEntity

@Dao
interface AnnotationDao {

    @Query("SELECT * FROM annotations ORDER BY timestamp DESC")
    suspend fun getAllAnnotations(): List<AnnotationEntity>

    @Query("SELECT * FROM annotations WHERE photoPath = :photoPath")
    suspend fun getAnnotationsByPhotoPath(photoPath: String): List<AnnotationEntity>

    @Query("SELECT * FROM annotations WHERE isVerified = :isVerified")
    suspend fun getAnnotationsByVerificationStatus(isVerified: Boolean): List<AnnotationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnnotation(annotation: AnnotationEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnnotations(annotations: List<AnnotationEntity>)

    @Update
    suspend fun updateAnnotation(annotation: AnnotationEntity)

    @Delete
    suspend fun deleteAnnotation(annotation: AnnotationEntity)

    @Query("DELETE FROM annotations")
    suspend fun deleteAllAnnotations()

    @Query("SELECT COUNT(*) FROM annotations WHERE isVerified = 1")
    suspend fun getVerifiedAnnotationCount(): Int
}