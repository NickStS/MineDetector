package com.minedetector.data.repository

import com.minedetector.data.local.AppDatabase
import com.minedetector.data.local.entities.FlightLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FlightLogRepository(private val database: AppDatabase) {

    suspend fun getAllFlightLogs(): List<FlightLogEntity> = withContext(Dispatchers.IO) {
        database.flightLogDao().getAllFlightLogs()
    }

    suspend fun getFlightLogById(id: Long): FlightLogEntity? = withContext(Dispatchers.IO) {
        database.flightLogDao().getFlightLogById(id)
    }

    suspend fun getFlightLogByFlightId(flightId: String): FlightLogEntity? =
        withContext(Dispatchers.IO) {
            database.flightLogDao().getFlightLogByFlightId(flightId)
        }

    suspend fun insertFlightLog(flightLog: FlightLogEntity): Long = withContext(Dispatchers.IO) {
        database.flightLogDao().insertFlightLog(flightLog)
    }

    suspend fun updateFlightLog(flightLog: FlightLogEntity) = withContext(Dispatchers.IO) {
        database.flightLogDao().updateFlightLog(flightLog)
    }

    suspend fun deleteFlightLog(flightLog: FlightLogEntity) = withContext(Dispatchers.IO) {
        database.flightLogDao().deleteFlightLog(flightLog)
    }

    suspend fun deleteAllFlightLogs() = withContext(Dispatchers.IO) {
        database.flightLogDao().deleteAllFlightLogs()
    }
}