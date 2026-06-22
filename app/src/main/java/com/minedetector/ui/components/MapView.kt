package com.minedetector.ui.components

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.minedetector.data.models.Telemetry
import java.util.Locale

class MapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentLocation: Telemetry? = null
    private val detectionPoints = mutableListOf<DetectionPoint>()
    private var currentLatitude: Double = 0.0
    private var currentLongitude: Double = 0.0
    private var detectionCount: Int = 0

    private val mapPaint = Paint().apply {
        color = Color.parseColor("#1E1E1E")
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#333333")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val currentLocationPaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.FILL
    }

    private val detectionPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 24f
        isAntiAlias = true
    }

    data class DetectionPoint(
        val latitude: Double,
        val longitude: Double,
        val type: String
    )

    fun updateLocation(telemetry: Telemetry) {
        currentLocation = telemetry
        currentLatitude = telemetry.latitude
        currentLongitude = telemetry.longitude
        invalidate()
    }

    /**
     * Updates drone position on the map
     */
    fun updatePosition(latitude: Double, longitude: Double) {
        currentLatitude = latitude
        currentLongitude = longitude
        invalidate()
    }

    /**
     * Updates detection counter displayed in top-right corner
     */
    fun updateDetectionCount(count: Int) {
        detectionCount = count
        invalidate()
    }

    fun addDetection(latitude: Double, longitude: Double, type: String) {
        detectionPoints.add(DetectionPoint(latitude, longitude, type))
        detectionCount = detectionPoints.size
        invalidate()
    }

    fun clearDetections() {
        detectionPoints.clear()
        detectionCount = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val canvasWidth = width.toFloat()
        val canvasHeight = height.toFloat()

        // Draw background
        canvas.drawRect(0f, 0f, canvasWidth, canvasHeight, mapPaint)

        // Draw grid
        val gridSize = 30f
        val gridCountX = (canvasWidth / gridSize).toInt()
        val gridCountY = (canvasHeight / gridSize).toInt()

        for (i in 0..gridCountX) {
            val x = i * gridSize
            canvas.drawLine(x, 0f, x, canvasHeight, gridPaint)
        }
        for (i in 0..gridCountY) {
            val y = i * gridSize
            canvas.drawLine(0f, y, canvasWidth, y, gridPaint)
        }

        // Draw current position
        currentLocation?.let { location ->
            val centerX = canvasWidth / 2f
            val centerY = canvasHeight / 2f

            canvas.drawCircle(centerX, centerY, 15f, currentLocationPaint)

            // Draw heading arrow
            val arrowLength = 30f
            val angle = Math.toRadians(location.heading.toDouble())
            val endX = (centerX + arrowLength * Math.sin(angle)).toFloat()
            val endY = (centerY - arrowLength * Math.cos(angle)).toFloat()

            val arrowPaint = Paint().apply {
                color = Color.BLUE
                strokeWidth = 3f
                style = Paint.Style.STROKE
            }
            canvas.drawLine(centerX, centerY, endX, endY, arrowPaint)

            // Draw coordinates text
            val coordText = String.format(Locale.US, "%.6f, %.6f", location.latitude, location.longitude)
            canvas.drawText(coordText, 10f, canvasHeight - 40f, textPaint)

            val altText = String.format(Locale.US, "Alt: %.1fm", location.altitude)
            canvas.drawText(altText, 10f, canvasHeight - 15f, textPaint)
        }

        // Draw detections (relative to current position)
        currentLocation?.let { current ->
            detectionPoints.forEach { detection ->
                // Simple coordinate transformation
                val deltaLat = (detection.latitude - current.latitude) * 100000
                val deltaLon = (detection.longitude - current.longitude) * 100000

                val x = width / 2f + deltaLon.toFloat()
                val y = height / 2f - deltaLat.toFloat()

                if (x in 0f..canvasWidth && y in 0f..canvasHeight) {
                    canvas.drawCircle(x, y, 8f, detectionPaint)
                }
            }
        }

        // Draw detection count in top-right corner
        if (detectionCount > 0) {
            val countText = "\uD83C\uDFAF $detectionCount" // 🎯 emoji
            val countPaint = Paint().apply {
                color = Color.WHITE
                textSize = 28f
                isAntiAlias = true
                isFakeBoldText = true
            }
            canvas.drawText(countText, canvasWidth - 100f, 35f, countPaint)
        }

        // If no current location - show placeholder
        if (currentLocation == null) {
            val placeholderText = "Waiting GPS..."
            val textWidth = textPaint.measureText(placeholderText)
            canvas.drawText(
                placeholderText,
                (canvasWidth - textWidth) / 2f,
                canvasHeight / 2f,
                textPaint
            )
        }
    }
}