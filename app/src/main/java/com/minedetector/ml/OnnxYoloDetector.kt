package com.minedetector.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import ai.onnxruntime.*
import java.nio.FloatBuffer

class OnnxYoloDetector(context: Context) {

    private val ortSession: OrtSession
    private val ortEnvironment: OrtEnvironment
    private val labels: List<String>

    companion object {
        private const val TAG = "OnnxYoloDetector"
        private const val MODEL_NAME = "yolo_mine_detector.onnx"
        private const val LABELS_FILE = "labels.txt"
        private const val INPUT_SIZE = 800
        private const val CONFIDENCE_THRESHOLD = 0.25f
        private const val IOU_THRESHOLD = 0.45f
        private const val MAX_DETECTIONS = 100
    }

    private val pixelBuffer = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val floatBuffer = FloatArray(1 * 3 * INPUT_SIZE * INPUT_SIZE)
    private var cachedResizedBitmap: Bitmap? = null

    init {
        try {
            labels = context.assets.open(LABELS_FILE).bufferedReader().readLines()
            Log.d(TAG, "✅ Загружено ${labels.size} классов: $labels")

            ortEnvironment = OrtEnvironment.getEnvironment()
            val modelBytes = context.assets.open(MODEL_NAME).readBytes()
            Log.d(TAG, "📦 Размер модели: ${modelBytes.size / 1024} KB")

            val sessionOptions = OrtSession.SessionOptions()
            sessionOptions.setIntraOpNumThreads(4)
            sessionOptions.setInterOpNumThreads(2)
            sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

            ortSession = ortEnvironment.createSession(modelBytes, sessionOptions)
            Log.d(TAG, "✅ Модель загружена (4 threads, ALL_OPT)")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка инициализации", e)
            throw RuntimeException("Не удалось загрузить модель: ${e.message}", e)
        }
    }

    fun detectObjects(bitmap: Bitmap): List<Detection> {
        try {
            val startTime = System.currentTimeMillis()

            val inputTensor = preprocessImage(bitmap)
            val preprocessTime = System.currentTimeMillis() - startTime

            val inferenceStart = System.currentTimeMillis()
            val inputs = mapOf(ortSession.inputNames.iterator().next() to inputTensor)
            val outputs = ortSession.run(inputs)
            val inferenceTime = System.currentTimeMillis() - inferenceStart

            val postprocessStart = System.currentTimeMillis()
            val output = outputs[0].value

            // ✅ ИСПРАВЛЕНО: Передаём исходные размеры bitmap для правильного масштабирования
            val detections = postprocess(output, bitmap.width, bitmap.height)
            val postprocessTime = System.currentTimeMillis() - postprocessStart

            val totalTime = System.currentTimeMillis() - startTime

            Log.d(TAG, "✅ ${detections.size} объектов | ⏱️ ${totalTime}ms (pre:${preprocessTime}ms inf:${inferenceTime}ms post:${postprocessTime}ms)")

            outputs.forEach { it.value.close() }
            inputTensor.close()

            return detections

        } catch (e: Exception) {
            Log.e(TAG, "❌ Детекция не удалась", e)
            e.printStackTrace()
            return emptyList()
        }
    }

    private fun preprocessImage(bitmap: Bitmap): OnnxTensor {
        val resized = if (cachedResizedBitmap == null ||
            cachedResizedBitmap!!.width != INPUT_SIZE ||
            cachedResizedBitmap!!.height != INPUT_SIZE) {
            cachedResizedBitmap?.recycle()
            cachedResizedBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
            cachedResizedBitmap!!
        } else {
            cachedResizedBitmap!!
        }

        val canvas = android.graphics.Canvas(resized)
        val srcRect = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
        val dstRect = android.graphics.Rect(0, 0, INPUT_SIZE, INPUT_SIZE)
        canvas.drawBitmap(bitmap, srcRect, dstRect, null)

        resized.getPixels(pixelBuffer, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        var index = 0
        for (c in 0 until 3) {
            for (y in 0 until INPUT_SIZE) {
                for (x in 0 until INPUT_SIZE) {
                    val pixel = pixelBuffer[y * INPUT_SIZE + x]
                    floatBuffer[index++] = when (c) {
                        0 -> ((pixel shr 16) and 0xFF) * 0.003921569f
                        1 -> ((pixel shr 8) and 0xFF) * 0.003921569f
                        else -> (pixel and 0xFF) * 0.003921569f
                    }
                }
            }
        }

        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        val buffer = FloatBuffer.wrap(floatBuffer)

        return OnnxTensor.createTensor(ortEnvironment, buffer, shape)
    }

    private fun postprocess(output: Any, imageWidth: Int, imageHeight: Int): List<Detection> {
        val detections = mutableListOf<Detection>()

        try {
            val outputArray = when (output) {
                is Array<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val arr3d = output as Array<Array<FloatArray>>
                    arr3d[0]
                }
                else -> {
                    Log.e(TAG, "❌ Неожиданный тип выхода: ${output::class.java}")
                    return emptyList()
                }
            }

            val numClasses = labels.size
            val numPredictions = outputArray[0].size

            // ✅ ИСПРАВЛЕНО: Масштабирование для ИСХОДНОГО размера изображения
            val scaleX = imageWidth.toFloat() / INPUT_SIZE
            val scaleY = imageHeight.toFloat() / INPUT_SIZE

            Log.d(TAG, "📊 Image: ${imageWidth}x${imageHeight}, Model: ${INPUT_SIZE}x${INPUT_SIZE}, Scale: ${scaleX}x${scaleY}")

            val classScores = FloatArray(numClasses)

            for (i in 0 until numPredictions) {
                // Координаты В ПИКСЕЛЯХ МОДЕЛИ (0-800)
                val cx_model = outputArray[0][i]
                val cy_model = outputArray[1][i]
                val w_model = outputArray[2][i]
                val h_model = outputArray[3][i]

                // ✅ ИСПРАВЛЕНО: Масштабируем В ИСХОДНЫЕ размеры изображения
                val cx = cx_model * scaleX
                val cy = cy_model * scaleY
                val w = w_model * scaleX
                val h = h_model * scaleY

                for (classIdx in 0 until numClasses) {
                    classScores[classIdx] = outputArray[4 + classIdx][i]
                }

                var maxScore = -1f
                var maxClassId = 0

                for (classIdx in 0 until numClasses) {
                    if (classScores[classIdx] > maxScore) {
                        maxScore = classScores[classIdx]
                        maxClassId = classIdx
                    }
                }

                if (maxScore > CONFIDENCE_THRESHOLD) {
                    // ✅ ИСПРАВЛЕНО: Координаты в ИСХОДНЫХ размерах изображения
                    val left = cx - w / 2
                    val top = cy - h / 2
                    val right = cx + w / 2
                    val bottom = cy + h / 2

                    // ✅ УБРАЛ проверку границ - пусть VideoOverlayView обрежет если надо
                    detections.add(Detection(
                        label = labels.getOrElse(maxClassId) { "Unknown-$maxClassId" },
                        confidence = maxScore,
                        boundingBox = RectF(left, top, right, bottom),
                        classId = maxClassId
                    ))

                    if (detections.size < 3) {
                        Log.d(TAG, "  Detection: ${labels[maxClassId]} at (${left.toInt()},${top.toInt()},${right.toInt()},${bottom.toInt()}) conf=${String.format("%.2f", maxScore * 100)}%")
                    }
                }
            }

            val filteredDetections = applyNMS(detections)
            Log.d(TAG, "✅ Before NMS: ${detections.size}, After NMS: ${filteredDetections.size}")

            return filteredDetections

        } catch (e: Exception) {
            Log.e(TAG, "❌ Постобработка не удалась", e)
            e.printStackTrace()
            return emptyList()
        }
    }

    private fun applyNMS(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()

        val detectionsByClass = detections.groupBy { it.classId }
        val result = mutableListOf<Detection>()

        for ((_, classDetections) in detectionsByClass) {
            val sorted = classDetections.sortedByDescending { it.confidence }
            val kept = mutableListOf<Detection>()

            for (detection in sorted) {
                var shouldKeep = true

                for (keptDetection in kept) {
                    val iou = calculateIoU(detection.boundingBox, keptDetection.boundingBox)
                    if (iou > IOU_THRESHOLD) {
                        shouldKeep = false
                        break
                    }
                }

                if (shouldKeep) {
                    kept.add(detection)
                    if (kept.size >= MAX_DETECTIONS) break
                }
            }

            result.addAll(kept)
        }

        return result
    }

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)

        val intersectLeft = maxOf(a.left, b.left)
        val intersectTop = maxOf(a.top, b.top)
        val intersectRight = minOf(a.right, b.right)
        val intersectBottom = minOf(a.bottom, b.bottom)

        if (intersectRight <= intersectLeft || intersectBottom <= intersectTop) {
            return 0f
        }

        val intersectArea = (intersectRight - intersectLeft) * (intersectBottom - intersectTop)
        val unionArea = areaA + areaB - intersectArea

        return if (unionArea > 0f) intersectArea / unionArea else 0f
    }

    fun close() {
        try {
            cachedResizedBitmap?.recycle()
            cachedResizedBitmap = null
            ortSession.close()
            ortEnvironment.close()
            Log.d(TAG, "✅ Detector closed")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error closing detector", e)
        }
    }
}