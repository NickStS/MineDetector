package com.minedetector.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.minedetector.R
import com.minedetector.ml.TFLiteYoloDetector

/**
 * Диагностическая активность для проверки модели
 */
class DiagnosticActivity : AppCompatActivity() {

    private lateinit var tvDiagnostics: TextView
    private lateinit var detector: TFLiteYoloDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val diagnosticsText = StringBuilder()

        try {
            diagnosticsText.append("=== ДИАГНОСТИКА МОДЕЛИ ===\n\n")

            // 1. Проверяем файлы
            diagnosticsText.append("1. Проверка файлов:\n")
            val modelExists = assets.list("")?.contains("yolo_mine_detector.tflite") == true
            val labelsExists = assets.list("")?.contains("labels.txt") == true
            diagnosticsText.append("   yolo_mine_detector.tflite: ${if (modelExists) "✅" else "❌"}\n")
            diagnosticsText.append("   labels.txt: ${if (labelsExists) "✅" else "❌"}\n\n")

            // 2. Загружаем labels
            if (labelsExists) {
                val labels = assets.open("labels.txt").bufferedReader().readLines()
                diagnosticsText.append("2. Labels (${labels.size} классов):\n")
                labels.forEachIndexed { index, label ->
                    diagnosticsText.append("   [$index] $label\n")
                }
                diagnosticsText.append("\n")
            }

            // 3. Инициализируем детектор
            diagnosticsText.append("3. Инициализация модели:\n")
            detector = TFLiteYoloDetector(this)
            diagnosticsText.append("   ✅ Модель загружена успешно\n\n")

            // 4. Тестовое изображение
            diagnosticsText.append("4. Тест на пустом изображении:\n")
            val testBitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
            val detections = detector.detectObjects(testBitmap)
            diagnosticsText.append("   Обнаружено объектов: ${detections.size}\n\n")

            diagnosticsText.append("=== ДИАГНОСТИКА ЗАВЕРШЕНА ===\n")
            diagnosticsText.append("\nЕсли модель не находит объекты:\n")
            diagnosticsText.append("1. Проверьте что изображения содержат мины\n")
            diagnosticsText.append("2. Проверьте threshold: текущий = 0.25\n")
            diagnosticsText.append("3. Убедитесь что модель обучена правильно\n")

        } catch (e: Exception) {
            diagnosticsText.append("\n❌ ОШИБКА:\n")
            diagnosticsText.append(e.message)
            diagnosticsText.append("\n\n")
            diagnosticsText.append(Log.getStackTraceString(e))
        }

        // Показываем результаты
        tvDiagnostics = TextView(this).apply {
            text = diagnosticsText.toString()
            setPadding(32, 32, 32, 32)
            textSize = 12f
        }
        setContentView(tvDiagnostics)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::detector.isInitialized) {
            detector.close()
        }
    }
}