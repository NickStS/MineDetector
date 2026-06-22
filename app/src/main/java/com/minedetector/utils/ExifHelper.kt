package com.minedetector.utils

import androidx.exifinterface.media.ExifInterface
import com.minedetector.data.models.Telemetry
import java.text.SimpleDateFormat
import java.util.*

object ExifHelper {

    fun writeExifData(filePath: String, telemetry: Telemetry) {
        try {
            val exif = ExifInterface(filePath)

            // Координаты
            exif.setLatLong(telemetry.latitude, telemetry.longitude)
            exif.setAltitude(telemetry.altitude.toDouble())

            // Дата/время
            val date = Date(telemetry.timestamp)
            val dateTime = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(date)
            val dateStamp = SimpleDateFormat("yyyy:MM:dd", Locale.US).format(date)
            val timeStamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(date)

            exif.setAttribute(ExifInterface.TAG_DATETIME, dateTime)
            exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, dateStamp)
            exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, timeStamp)

            // Пользовательские данные
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, buildMetadataJson(telemetry))

            exif.saveAttributes()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun buildMetadataJson(t: Telemetry) = """
        {"battery":${t.batteryPercent},"satellites":${t.satelliteCount},
         "speed":${t.speed},"heading":${t.heading},
         "flightMode":"${t.flightMode}","timestamp":${t.timestamp}}
    """.trimIndent()
}
