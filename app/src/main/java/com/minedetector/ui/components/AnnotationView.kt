package com.minedetector.ui.components

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.minedetector.ml.Detection
import kotlin.math.max

/**
 * Кастомный вью для разметки: умеет
 *  - показывать фоновое изображение (setImage)
 *  - добавлять/обновлять боксы (addBox / setDetections)
 *  - отдать список боксов (getAnnotations)
 *  - экспорт в YOLO txt (exportToYoloFormat)
 *  - загрузить существующие аннотации из Detection-объектов (loadExistingAnnotations)
 */
class AnnotationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class AnnotationBox(
        val label: String,
        val xMin: Float,
        val yMin: Float,
        val xMax: Float,
        val yMax: Float,
        val classId: Int,
        val timestamp: Long
    )

    private val boxes = mutableListOf<AnnotationBox>()

    // фон
    private var backgroundBitmap: Bitmap? = null
    private val bitmapMatrix = Matrix()
    private val bitmapSrc = RectF()
    private val bitmapDst = RectF()

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = 32f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x66000000
    }

    /** Задать фон для разметки */
    fun setImage(bitmap: Bitmap) {
        backgroundBitmap = bitmap
        requestLayout()
        invalidate()
    }

    /** Очистить все боксы */
    fun clear() {
        boxes.clear()
        invalidate()
    }

    /** Добавить бокс именованными параметрами (как было в проекте) */
    fun addBox(
        label: String,
        xMin: Float,
        yMin: Float,
        xMax: Float,
        yMax: Float,
        classId: Int,
        timestamp: Long
    ) {
        boxes.add(AnnotationBox(label, xMin, yMin, xMax, yMax, classId, timestamp))
        invalidate()
    }

    /** Добавить из Detection */
    fun addFromDetection(d: Detection) {
        val r = d.boundingBox
        addBox(
            label = "${d.label} ${"%.2f".format(d.confidence)}",
            xMin = r.left,
            yMin = r.top,
            xMax = r.right,
            yMax = r.bottom,
            classId = d.classId,
            timestamp = d.timestamp
        )
    }

    /** Массово установить список детекций */
    fun setDetections(detections: List<Detection>) {
        boxes.clear()
        detections.forEach { addFromDetection(it) }
        invalidate()
    }

    /** Загрузить существующие (обёртка под старые вызовы) */
    fun loadExistingAnnotations(detections: List<Detection>) = setDetections(detections)

    /** Вернуть текущие боксы (для сохранения) */
    fun getAnnotations(): List<AnnotationBox> = boxes.toList()

    /**
     * Экспорт в YOLO txt:
     *  class_id x_center_norm y_center_norm w_norm h_norm
     * Требуются реальные размеры исходного изображения.
     * Если фон задан — берём его размеры, иначе нужно передать вручную.
     */
    fun exportToYoloFormat(
        imageWidth: Int? = backgroundBitmap?.width,
        imageHeight: Int? = backgroundBitmap?.height
    ): String {
        val w = imageWidth ?: 1
        val h = imageHeight ?: 1

        return buildString {
            boxes.forEach { b ->
                val xCenter = (b.xMin + b.xMax) / 2f
                val yCenter = (b.yMin + b.yMax) / 2f
                val bw = (b.xMax - b.xMin)
                val bh = (b.yMax - b.yMin)

                val nx = xCenter / w
                val ny = yCenter / h
                val nw = bw / w
                val nh = bh / h

                append("${b.classId} ${nx.coerceIn(0f,1f)} ${ny.coerceIn(0f,1f)} ${nw.coerceAtLeast(0f)} ${nh.coerceAtLeast(0f)}\n")
            }
        }.trim()
    }

    // ===== Рисование =====

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeBitmapMatrix()
    }

    private fun computeBitmapMatrix() {
        val bmp = backgroundBitmap ?: return
        bitmapSrc.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        bitmapDst.set(0f, 0f, width.toFloat(), height.toFloat())
        bitmapMatrix.reset()
        bitmapMatrix.setRectToRect(bitmapSrc, bitmapDst, Matrix.ScaleToFit.CENTER)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        backgroundBitmap?.let { bmp ->
            val save = canvas.save()
            canvas.concat(bitmapMatrix)
            canvas.drawBitmap(bmp, 0f, 0f, null)
            canvas.restoreToCount(save)
        }

        for (box in boxes) {
            val color = colorFromClass(box.classId)
            boxPaint.color = color
            textPaint.color = color

            val rect = RectF(box.xMin, box.yMin, box.xMax, box.yMax)
            canvas.drawRect(rect, boxPaint)

            val caption = box.label
            val pad = 6f
            val textW = textPaint.measureText(caption)
            val textH = textPaint.fontMetrics.let { it.bottom - it.top }
            val bgRect = RectF(
                rect.left,
                max(0f, rect.top - textH - pad * 2),
                rect.left + textW + pad * 2,
                rect.top
            )
            canvas.drawRect(bgRect, bgPaint)
            canvas.drawText(caption, bgRect.left + pad, bgRect.bottom - pad, textPaint)
        }
    }

    private fun colorFromClass(id: Int): Int {
        val palette = intArrayOf(
            Color.RED, Color.GREEN, Color.CYAN, Color.MAGENTA,
            Color.YELLOW, Color.WHITE, 0xFFFF8800.toInt(), 0xFF00BCD4.toInt()
        )
        return palette[(id and Int.MAX_VALUE) % palette.size]
    }
}
