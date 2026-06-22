package com.minedetector.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.minedetector.data.local.dao.AnnotationDao
import com.minedetector.data.local.dao.DetectionDao
import com.minedetector.data.local.dao.FlightLogDao
import com.minedetector.data.local.entities.AnnotationEntity
import com.minedetector.data.local.entities.DetectionEntity
import com.minedetector.data.local.entities.FlightLogEntity

@Database(
    entities = [
        AnnotationEntity::class,
        DetectionEntity::class,
        FlightLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun annotationDao(): AnnotationDao
    abstract fun detectionDao(): DetectionDao
    abstract fun flightLogDao(): FlightLogDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "minedetector.db"
                )
                    .fallbackToDestructiveMigration() // просто и быстро; если нужна миграция — заменишь
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
