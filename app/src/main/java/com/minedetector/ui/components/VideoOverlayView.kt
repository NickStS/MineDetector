package com.minedetector.ui.components

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.minedetector.ml.Detection
import kotlin.math.abs

/**
 * VideoOverlayView with CENTER_CROP scaling for proper bounding box alignment
 */
class VideoOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "VideoOverlayView"
    }

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        textSize = 42f
        color = Color.WHITE
        isAntiAlias = true
        style = Paint.Style.FILL
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val textBackgroundPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val centerPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private var detections: List<Detection> = emptyList()
    private var showSignalLost = false

    // Source image/video dimensions
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0

    // Scaling parameters for CENTER_CROP
    private var scale: Float = 1f
    private var offsetX: Float = 0f
    private var offsetY: Float = 0f

    // Matrix from PhotoView for zoom sync
    private var photoViewMatrix: Matrix? = null

    private val signalLostPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val signalLostTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 60f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    // Colors for different mine types
    private val colorMap = mapOf(
        0 to Color.RED,              // MON-50
        1 to Color.YELLOW,           // PFM-1
        2 to Color.MAGENTA,          // PMN-1
        3 to Color.rgb(255, 165, 0), // PMN-2 (Orange)
        4 to Color.CYAN,             // POM-3
        5 to Color.GREEN             // TM-62
    )

    /** Returns the currently displayed detections (for annotating saved photos). */
    fun getCurrentDetections(): List<Detection> = detections

    fun updateDetections(newDetections: List<Detection>) {
        detections = newDetections
        invalidate()
    }

    fun clearDetections() {
        detections = emptyList()
        invalidate()
    }

    fun showSignalLost() {
        showSignalLost = true
        invalidate()
    }

    fun hideSignalLost() {
        showSignalLost = false
        invalidate()
    }

    /**
     * Set source image dimensions for proper scaling.
     * Clears any stale PhotoView matrix so we don't render new-image detections
     * with a matrix calibrated for the previous image.
     */
    fun setImageDimensions(width: Int, height: Int) {
        imageWidth = width
        imageHeight = height
        photoViewMatrix = null  // clear stale matrix; PhotoView will re-fire its listener

        if (this.width > 0 && this.height > 0) {
            calculateScaling()
            invalidate()
        } else {
            post {
                calculateScaling()
                invalidate()
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateScaling()
    }

    /**
     * CENTER_CROP scaling - matches how FPVWidget displays video
     * Uses max(scaleX, scaleY) to fill the view completely
     */
    private fun calculateScaling() {
        if (width == 0 || height == 0 || imageWidth == 0 || imageHeight == 0) {
            scale = 1f
            offsetX = 0f
            offsetY = 0f
            return
        }

        // FIT_CENTER: use min scale to fit within view (may leave black bars)
        val scaleX = width.toFloat() / imageWidth.toFloat()
        val scaleY = height.toFloat() / imageHeight.toFloat()
        scale = minOf(scaleX, scaleY)

        // Center the scaled image
        offsetX = (width - imageWidth * scale) / 2f
        offsetY = (height - imageHeight * scale) / 2f

    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())

        for (detection in detections) {
            drawDetection(canvas, detection)
        }

        if (showSignalLost) {
            drawSignalLostOverlay(canvas)
        }

        canvas.restore()
    }

    private fun drawDetection(canvas: Canvas, detection: Detection) {
        val color = colorMap[detection.classId] ?: Color.RED
        boxPaint.color = color
        textBackgroundPaint.color = color

        val viewRect = toViewRect(detection.boundingBox)

        if (viewRect.right < 0f || viewRect.bottom < 0f ||
            viewRect.left > width.toFloat() || viewRect.top > height.toFloat()) {
            return
        }

        canvas.drawRect(viewRect, boxPaint)
        drawLabel(canvas, detection, viewRect, color)
        drawCenterPoint(canvas, viewRect, color)
    }

    /**
     * Maps a bounding box from image-pixel coordinates to overlay view coordinates.
     *
     * Primary path: our own FIT_CENTER calculation (calculateScaling) — always computed
     * directly from image dimensions and view dimensions, reliable for any image size.
     *
     * Zoom path: if the user has zoomed/panned the PhotoView, the photoViewMatrix scale
     * will differ from the FIT_CENTER scale; in that case we use the matrix to track
     * the image as it moves.
     */
    private fun toViewRect(imageRect: RectF): RectF {
        val pm = photoViewMatrix
        if (pm != null && scale > 0f) {
            val mv = FloatArray(9)
            pm.getValues(mv)
            val matrixScale = mv[Matrix.MSCALE_X]
            if (abs(matrixScale - scale) > 0.02f) {
                val r = RectF(imageRect)
                pm.mapRect(r)
                return r
            }
        }
        return scaleRect(imageRect)
    }

    /**
     * Scale rectangle from image coordinates to view coordinates
     */
    private fun scaleRect(rect: RectF): RectF {
        if (imageWidth == 0 || imageHeight == 0) {
            return rect
        }

        return RectF(
            rect.left * scale + offsetX,
            rect.top * scale + offsetY,
            rect.right * scale + offsetX,
            rect.bottom * scale + offsetY
        )
    }

    private fun drawLabel(canvas: Canvas, detection: Detection, rect: RectF, color: Int) {
        val confidencePercent = (detection.confidence * 100).toInt()
        val text = if (detection.trackId > 0) {
            "ID${detection.trackId} ${detection.label} ${confidencePercent}%"
        } else {
            "${detection.label} ${confidencePercent}%"
        }

        val textBounds = Rect()
        textPaint.getTextBounds(text, 0, text.length, textBounds)

        val textX = rect.left
        val textY = rect.top - 10f

        // Background for text
        val textBackgroundRect = RectF(
            textX,
            textY - textBounds.height() - 16f,
            textX + textBounds.width() + 24f,
            textY - 4f
        )
        canvas.drawRect(textBackgroundRect, textBackgroundPaint)

        // Text
        canvas.drawText(text, textX + 12f, textY - 8f, textPaint)
    }

    private fun drawCenterPoint(canvas: Canvas, rect: RectF, color: Int) {
        centerPaint.color = color
        canvas.drawCircle(rect.centerX(), rect.centerY(), 8f, centerPaint)
    }

    private fun drawSignalLostOverlay(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), signalLostPaint)
        canvas.drawText(
            "SIGNAL LOST",
            width / 2f,
            height / 2f,
            signalLostTextPaint
        )
    }

    /**
     * Set Matrix from PhotoView for zoom sync
     */
    fun setPhotoViewMatrix(matrix: Matrix?) {
        this.photoViewMatrix = matrix
    }
}
