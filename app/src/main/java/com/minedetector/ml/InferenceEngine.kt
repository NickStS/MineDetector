package com.minedetector.ml

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Uses the real TFLiteYoloDetector for inference (not the mock YoloDetector).
 */
class InferenceEngine(context: Context) {

    private val detector = TFLiteYoloDetector(context)
    private val isRunning = AtomicBoolean(false)

    suspend fun detectAsync(bitmap: Bitmap): List<Detection> = withContext(Dispatchers.Default) {
        // Guard against concurrent inference — skip if already running
        if (!isRunning.compareAndSet(false, true)) {
            return@withContext emptyList()
        }
        try {
            detector.detectObjects(bitmap)
        } finally {
            isRunning.set(false)
        }
    }

    fun isRunning(): Boolean = isRunning.get()

    fun release() {
        detector.close()
    }
}
