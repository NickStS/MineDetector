package com.minedetector.ml

import android.graphics.RectF

/**
 * Detection для старого кода (с boundingBox) + tracking
 */
data class Detection(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF,
    val classId: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val trackId: Int = -1 // -1 means not tracked
) {
    // Alias для совместимости
    val box: RectF get() = boundingBox

    fun getDisplayText(): String {
        return if (trackId > 0) {
            "$label ID:$trackId ${String.format("%.1f", confidence * 100)}%"
        } else {
            "$label ${String.format("%.1f", confidence * 100)}%"
        }
    }

    // Совместимость с ONNX детектором
    val className: String get() = label
    val left: Float get() = boundingBox.left
    val top: Float get() = boundingBox.top
    val right: Float get() = boundingBox.right
    val bottom: Float get() = boundingBox.bottom
    val centerX: Float get() = boundingBox.centerX()
    val centerY: Float get() = boundingBox.centerY()
}

/**
 * Внутренний класс для ONNX детектора
 */
internal data class OnnxDetection(
    val classId: Int,
    val className: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2

    /**
     * Конвертация в старый формат Detection
     */
    fun toDetection(): Detection {
        return Detection(
            label = className,
            confidence = confidence,
            boundingBox = RectF(left, top, right, bottom),
            classId = classId
        )
    }
}