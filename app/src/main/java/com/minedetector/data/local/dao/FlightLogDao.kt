package com.minedetector.data.local.dao

import androidx.room.*
import com.minedetector.data.local.entities.FlightLogEntity

@Dao
interface FlightLogDao {

    @Query("SELECT * FROM flight_logs ORDER BY startTime DESC")
    suspend fun getAllFlightLogs(): List<FlightLogEntity>

    @Query("SELECT * FROM flight_logs WHERE id = :id")
    suspend fun getFlightLogById(id: Long): FlightLogEntity?

    @Query("SELECT * FROM flight_logs WHERE flightId = :flightId")
    suspend fun getFlightLogByFlightId(flightId: String): FlightLogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFlightLog(flightLog: FlightLogEntity): Long

    @Update
    suspend fun updateFlightLog(flightLog: FlightLogEntity)

    @Delete
    suspend fun deleteFlightLog(flightLog: FlightLogEntity)

    @Query("DELETE FROM flight_logs")
    suspend fun deleteAllFlightLogs()
}