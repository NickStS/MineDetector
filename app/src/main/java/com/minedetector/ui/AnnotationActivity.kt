package com.minedetector.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.minedetector.R
import com.minedetector.ui.components.AnnotationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AnnotationActivity : AppCompatActivity() {

    private lateinit var annotationView: AnnotationView
    private lateinit var btnSave: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var btnExport: MaterialButton

    private var photoPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_annotation)

        photoPath = intent.getStringExtra("photo_path")

        initViews()
        loadPhotoAndExisting()
    }

    private fun initViews() {
        annotationView = findViewById(R.id.annotation_view)
        btnSave = findViewById(R.id.btn_save)
        btnCancel = findViewById(R.id.btn_cancel)
        btnExport = findViewById(R.id.btn_export)

        btnSave.setOnClickListener { saveSidecarJson() }
        btnCancel.setOnClickListener { finish() }
        btnExport.setOnClickListener { exportYoloTxt() }
    }

    private fun loadPhotoAndExisting() {
        val path = photoPath
        if (path.isNullOrEmpty()) {
            Toast.makeText(this, "Не передан путь к фото", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "Фото не найдено", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        // фон
        BitmapFactory.decodeFile(path)?.let { bmp ->
            annotationView.setImage(bmp)
        }

        // подгружаем sidecar JSON (если ты уже их сохранял ранее)
        val sidecar = File("$path.ann.json")
        if (sidecar.exists()) {
            runCatching {
                val detections = parseSidecarDetections(sidecar.readText())
                annotationView.loadExistingAnnotations(detections)
            }
        }
    }

    // --- Сохранение аннотаций в sidecar JSON рядом с фото ---
    private fun saveSidecarJson() {
        lifecycleScope.launch {
            val path = photoPath ?: return@launch
            val sidecar = File("$path.ann.json")
            val annotations = annotationView.getAnnotations()

            val json = buildString {
                append("[")
                annotations.forEachIndexed { i, a ->
                    if (i > 0) append(",")
                    append(
                        """{"label":"${a.label}","xMin":${a.xMin},"yMin":${a.yMin},"xMax":${a.xMax},"yMax":${a.yMax},"classId":${a.classId},"timestamp":${a.timestamp}}"""
                    )
                }
                append("]")
            }

            withContext(Dispatchers.IO) {
                sidecar.writeText(json)
            }

            Toast.makeText(this@AnnotationActivity, "Аннотации сохранены: ${sidecar.name}", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Экспорт в YOLO TXT ---
    private fun exportYoloTxt() {
        val path = photoPath ?: return
        val bmp = BitmapFactory.decodeFile(path)
        val txt = annotationView.exportToYoloFormat(bmp?.width, bmp?.height)

        val out = File(filesDir, "annotations_${System.currentTimeMillis()}.txt")
        out.writeText(txt)

        Toast.makeText(this, "Экспортировано в: ${out.absolutePath}", Toast.LENGTH_LONG).show()
    }

    // Очень простой парсер sidecar JSON -> список Detection
    private fun parseSidecarDetections(json: String): List<com.minedetector.ml.Detection> {
        // без зависимостей от Gson/Kotlinx: примитивный парсинг
        // ожидается массив объектов с полями, записанными выше
        val regex = Regex("""\{[^}]+\}""")
        val items = regex.findAll(json).map { it.value }.toList()

        return items.map { obj ->
            fun num(name: String): Float =
                Regex(""""$name"\s*:\s*([-0-9.]+)""").find(obj)?.groupValues?.get(1)?.toFloat() ?: 0f
            fun str(name: String): String =
                Regex(""""$name"\s*:\s*"([^"]*)"""").find(obj)?.groupValues?.get(1) ?: ""
            fun lng(name: String): Long =
                Regex(""""$name"\s*:\s*([0-9]+)""").find(obj)?.groupValues?.get(1)?.toLong() ?: System.currentTimeMillis()
            fun int(name: String): Int = num(name).toInt()

            val left   = num("xMin")
            val top    = num("yMin")
            val right  = num("xMax")
            val bottom = num("yMax")
            val label  = str("label")
            val cls    = int("classId")
            val ts     = lng("timestamp")

            com.minedetector.ml.Detection(
                label = label,
                confidence = 1f,
                boundingBox = android.graphics.RectF(left, top, right, bottom),
                classId = cls,
                timestamp = ts
            )
        }
    }
}
