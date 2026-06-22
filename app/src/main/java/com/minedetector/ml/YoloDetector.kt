package com.minedetector.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.minedetector.data.models.DetectionStats
import kotlin.random.Random

/**
 * ТЕСТОВАЯ ВЕРСИЯ YoloDetector с заглушкой
 * Используйте это ТОЛЬКО для тестирования UI без обученной модели
 *
 * Для реальной работы:
 * 1. Добавьте yolo_mine_detector.tflite в assets/
 * 2. Замените этот файл на оригинальную версию YoloDetector.kt
 */
class YoloDetector(private val context: Context) {

    companion object {
        private const val TAG = "YoloDetector"
        const val TEST_MODE = true // ФЛАГ ТЕСТОВОГО РЕЖИМА
    }

    private var labels: List<String> = listOf(
        "Mine Type A",
        "Mine Type B",
        "Mine Type C",
        "Unexploded Ordnance"
    )

    init {
        Log.w(TAG, "⚠️⚠️⚠️ ЗАПУЩЕН В ТЕСТОВОМ РЕЖИМЕ БЕЗ МОДЕЛИ ⚠️⚠️⚠️")
        Log.w(TAG, "Детекции будут симулированы для демонстрации UI")
        loadLabels()
    }

    private fun loadLabels() {
        try {
            labels = context.assets.open("labels.txt").bufferedReader().readLines()
            Log.d(TAG, "Labels loaded: ${labels.size} classes")
        } catch (e: Exception) {
            Log.w(TAG, "Using default labels (labels.txt not found)")
        }
    }

    /**
     * ТЕСТОВАЯ ФУНКЦИЯ ДЕТЕКЦИИ
     * Генерирует случайные детекции для демонстрации
     *
     * В реальной версии здесь запускается TensorFlow Lite модель
     */
    fun detect(bitmap: Bitmap): List<Detection> {
        Log.w(TAG, "⚠️ ТЕСТОВЫЙ РЕЖИМ: Генерация симулированных детекций")

        // Случайное количество детекций (0-3)
        val numDetections = Random.nextInt(0, 4)

        if (numDetections == 0) {
            Log.d(TAG, "Не обнаружено объектов (симуляция)")
            return emptyList()
        }

        val detections = mutableListOf<Detection>()

        for (i in 0 until numDetections) {
            // Случайная позиция
            val x1 = Random.nextFloat() * bitmap.width * 0.6f
            val y1 = Random.nextFloat() * bitmap.height * 0.6f
            val width = bitmap.width * 0.15f + Random.nextFloat() * bitmap.width * 0.15f
            val height = bitmap.height * 0.15f + Random.nextFloat() * bitmap.height * 0.15f

            val classId = Random.nextInt(labels.size)
            val confidence = 0.6f + Random.nextFloat() * 0.35f // 60-95%

            detections.add(
                Detection(
                    label = labels[classId],
                    confidence = confidence,
                    boundingBox = RectF(x1, y1, x1 + width, y1 + height),
                    classId = classId
                )
            )
        }

        Log.d(TAG, "Симулировано ${detections.size} детекций")
        detections.forEach {
            Log.d(TAG, "  - ${it.label}: ${String.format("%.1f", it.confidence * 100)}%")
        }

        return detections
    }

    fun close() {
        Log.d(TAG, "Closing detector (test mode)")
    }
}

/*
==============================================================================
КАК ЗАМЕНИТЬ НА РЕАЛЬНУЮ ВЕРСИЮ:
==============================================================================

1. Добавьте yolo_mine_detector.tflite в app/src/main/assets/

2. Замените этот файл на следующий код:
*/

/*
package com.minedetector.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.minedetector.data.models.Detection
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class YoloDetector(private val context: Context) {

    companion object {
        private const val TAG = "YoloDetector"
        private const val MODEL_FILE = "yolo_mine_detector.tflite"
        private const val LABELS_FILE = "labels.txt"
        private const val INPUT_SIZE = 640
        private const val PIXEL_SIZE = 3
        private const val IMAGE_MEAN = 0f
        private const val IMAGE_STD = 255f
        private const val CONFIDENCE_THRESHOLD = 0.5f
        private const val IOU_THRESHOLD = 0.45f
    }

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var inputImageBuffer: ByteBuffer? = null
    private var isModelLoaded = false

    init {
        loadModel()
        loadLabels()
    }

    private fun loadModel() {
        try {
            val options = Interpreter.Options()

            val compatList = CompatibilityList()
            if (compatList.isDelegateSupportedOnThisDevice) {
                options.addDelegate(GpuDelegate())
                Log.d(TAG, "GPU delegate enabled")
            } else {
                options.setNumThreads(4)
                Log.d(TAG, "CPU mode with 4 threads")
            }

            val modelBuffer = loadModelFile()
            interpreter = Interpreter(modelBuffer, options)
            isModelLoaded = true

            inputImageBuffer = ByteBuffer.allocateDirect(
                4 * INPUT_SIZE * INPUT_SIZE * PIXEL_SIZE
            ).apply {
                order(ByteOrder.nativeOrder())
            }

            Log.d(TAG, "Model loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model", e)
            isModelLoaded = false
        }
    }

    private fun loadModelFile(): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(MODEL_FILE)
        val fileChannel = FileInputStream(assetFileDescriptor.fileDescriptor).channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun loadLabels() {
        try {
            labels = context.assets.open(LABELS_FILE).bufferedReader().readLines()
            Log.d(TAG, "Labels loaded: ${labels.size} classes")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading labels", e)
            labels = listOf("Mine Type A", "Mine Type B", "Mine Type C", "Unexploded Ordnance")
        }
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        if (!isModelLoaded || interpreter == null) {
            Log.w(TAG, "Model not loaded, returning empty detections")
            return emptyList()
        }

        try {
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            convertBitmapToByteBuffer(scaledBitmap)

            val outputShape = interpreter!!.getOutputTensor(0).shape()
            val numDetections = outputShape[1]
            val numCoordinates = outputShape[2]
            val output = Array(1) { Array(numDetections) { FloatArray(numCoordinates) } }

            interpreter!!.run(inputImageBuffer, output)

            val detections = postProcess(output[0], bitmap.width, bitmap.height)
            Log.d(TAG, "Detected ${detections.size} objects")
            return detections
        } catch (e: Exception) {
            Log.e(TAG, "Error during detection", e)
            return emptyList()
        }
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap) {
        inputImageBuffer?.rewind()
        val intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(intValues, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        var pixel = 0
        for (i in 0 until INPUT_SIZE) {
            for (j in 0 until INPUT_SIZE) {
                val pixelValue = intValues[pixel++]
                inputImageBuffer?.putFloat(((pixelValue shr 16 and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
                inputImageBuffer?.putFloat(((pixelValue shr 8 and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
                inputImageBuffer?.putFloat(((pixelValue and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
            }
        }
    }

    private fun postProcess(
        output: Array<FloatArray>,
        originalWidth: Int,
        originalHeight: Int
    ): List<Detection> {
        val detections = mutableListOf<Detection>()

        for (detection in output) {
            if (detection.size < 5) continue
            val confidence = detection[4]
            if (confidence < CONFIDENCE_THRESHOLD) continue

            val xCenter = detection[0] * originalWidth / INPUT_SIZE
            val yCenter = detection[1] * originalHeight / INPUT_SIZE
            val width = detection[2] * originalWidth / INPUT_SIZE
            val height = detection[3] * originalHeight / INPUT_SIZE

            val left = xCenter - width / 2
            val top = yCenter - height / 2
            val right = xCenter + width / 2
            val bottom = yCenter + height / 2

            var maxClassConfidence = 0f
            var classId = 0
            for (i in 5 until detection.size) {
                if (detection[i] > maxClassConfidence) {
                    maxClassConfidence = detection[i]
                    classId = i - 5
                }
            }

            val finalConfidence = confidence * maxClassConfidence
            if (finalConfidence < CONFIDENCE_THRESHOLD) continue

            val label = if (classId < labels.size) labels[classId] else "Unknown"

            detections.add(
                Detection(
                    label = label,
                    confidence = finalConfidence,
                    boundingBox = RectF(left, top, right, bottom),
                    classId = classId
                )
            )
        }

        return applyNMS(detections)
    }

    private fun applyNMS(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()

        val sortedDetections = detections.sortedByDescending { it.confidence }
        val selectedDetections = mutableListOf<Detection>()
        val suppressed = BooleanArray(sortedDetections.size)

        for (i in sortedDetections.indices) {
            if (suppressed[i]) continue
            selectedDetections.add(sortedDetections[i])

            for (j in i + 1 until sortedDetections.size) {
                if (suppressed[j]) continue
                val iou = calculateIoU(sortedDetections[i].boundingBox, sortedDetections[j].boundingBox)
                if (iou > IOU_THRESHOLD) {
                    suppressed[j] = true
                }
            }
        }

        return selectedDetections
    }

    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val intersectionLeft = maxOf(box1.left, box2.left)
        val intersectionTop = maxOf(box1.top, box2.top)
        val intersectionRight = minOf(box1.right, box2.right)
        val intersectionBottom = minOf(box1.bottom, box2.bottom)

        if (intersectionRight < intersectionLeft || intersectionBottom < intersectionTop) {
            return 0f
        }

        val intersectionArea = (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
        val box1Area = box1.width() * box1.height()
        val box2Area = box2.width() * box2.height()
        val unionArea = box1Area + box2Area - intersectionArea

        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
*/