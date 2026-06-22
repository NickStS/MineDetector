package com.minedetector.ml

import android.graphics.RectF

object PostProcessor {

    fun filterDetectionsByConfidence(
        detections: List<Detection>,
        minConfidence: Float
    ): List<Detection> {
        return detections.filter { it.confidence >= minConfidence }
    }

    fun filterDetectionsByArea(
        detections: List<Detection>,
        minArea: Float
    ): List<Detection> {
        return detections.filter {
            val area = it.boundingBox.width() * it.boundingBox.height()
            area >= minArea
        }
    }

    fun groupDetectionsByProximity(
        detections: List<Detection>,
        maxDistance: Float
    ): List<List<Detection>> {
        if (detections.isEmpty()) return emptyList()

        val groups = mutableListOf<MutableList<Detection>>()
        val visited = BooleanArray(detections.size)

        for (i in detections.indices) {
            if (visited[i]) continue

            val group = mutableListOf(detections[i])
            visited[i] = true

            for (j in i + 1 until detections.size) {
                if (visited[j]) continue

                val distance = calculateDistance(
                    detections[i].boundingBox,
                    detections[j].boundingBox
                )

                if (distance <= maxDistance) {
                    group.add(detections[j])
                    visited[j] = true
                }
            }

            groups.add(group)
        }

        return groups
    }

    private fun calculateDistance(box1: RectF, box2: RectF): Float {
        val center1X = box1.centerX()
        val center1Y = box1.centerY()
        val center2X = box2.centerX()
        val center2Y = box2.centerY()

        val dx = center1X - center2X
        val dy = center1Y - center2Y

        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    fun mergeBoundingBoxes(detections: List<Detection>): RectF {
        if (detections.isEmpty()) return RectF()

        var minLeft = Float.MAX_VALUE
        var minTop = Float.MAX_VALUE
        var maxRight = Float.MIN_VALUE
        var maxBottom = Float.MIN_VALUE

        for (detection in detections) {
            minLeft = minOf(minLeft, detection.boundingBox.left)
            minTop = minOf(minTop, detection.boundingBox.top)
            maxRight = maxOf(maxRight, detection.boundingBox.right)
            maxBottom = maxOf(maxBottom, detection.boundingBox.bottom)
        }

        return RectF(minLeft, minTop, maxRight, maxBottom)
    }
}