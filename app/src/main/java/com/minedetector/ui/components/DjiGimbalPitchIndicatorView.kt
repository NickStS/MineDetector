package com.minedetector.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * DJI GO 4-style gimbal pitch indicator.
 *
 * Layout (top → bottom):
 *   ○   ← hollow circle (maxDeg endpoint, e.g. +30°)
 *   ─   ← tick 1  (grey)
 *   ─   ← tick 2  (grey)
 *   ─   ← tick 3  RED  ← marks 0° / horizon reference
 *   ─   ← tick 4  (grey)
 *   …
 *   ─   ← tick 10 (grey)
 *   ○   ← hollow circle (minDeg endpoint, e.g. -90°)
 *
 * • 10 ticks, evenly spaced between the two circles.
 * • Only tick #redTickIndex (default 3) is drawn in red.
 * • No vertical centre line.
 * • Red filled indicator dot drawn LAST → always on top of the hollow circles.
 * • Badge (light background, dark text) follows the indicator.
 * • Interactive: drag anywhere on the view → gimbal pitch via onPitchChanged.
 */
class DjiGimbalPitchIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Public config ─────────────────────────────────────────────────────────

    var minDeg: Float = -90f
    var maxDeg: Float = 30f

    var pitchDeg: Float = 0f
        set(v) { field = v.coerceIn(minDeg, maxDeg); invalidate() }

    /** Which tick (1-based from top) is drawn in red. Default = 3. */
    var redTickIndex: Int = 3

    /** Total number of evenly-spaced tick marks between the two circles. */
    var ticksCount: Int = 10

    var onPitchChanged: ((Float) -> Unit)? = null

    var isDragging: Boolean = false
        private set

    /** Set to false to make the view display-only (no drag-to-control). Default = true. */
    var interactive: Boolean = true

    // ── Debounce ──────────────────────────────────────────────────────────────
    private var lastCmdTs = 0L
    private val DEBOUNCE_MS = 60L

    // ── Geometry ──────────────────────────────────────────────────────────────
    private var trackX   = 0f   // X of circles / tick centre
    private var trackTop = 0f   // Y of TOP    circle centre  (= maxDeg)
    private var trackBot = 0f   // Y of BOTTOM circle centre  (= minDeg)

    // ── Paints ────────────────────────────────────────────────────────────────

    private val pCircle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        color       = 0xBBFFFFFF.toInt()
        strokeWidth = dp(1.5f)
    }
    private val pTickNormal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        color       = 0x88FFFFFF.toInt()
        strokeWidth = dp(1f)
    }
    private val pTickRed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        color       = Color.parseColor("#FFFF3B30")
        strokeWidth = dp(1.5f)
    }
    /** Filled red dot — radius intentionally larger than circR so it covers the hollow circles. */
    private val pDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFFF3B30")
    }
    /** Badge: light semi-transparent background (matches DJI GO 4 style). */
    private val pBadgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#DDFFFFFF")
    }
    /** Badge text: dark, as in DJI GO 4. */
    private val pBadgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style     = Paint.Style.FILL
        color     = Color.parseColor("#FF1A1A1A")
        textSize  = dp(10f)
        typeface  = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private val badgeRect = RectF()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val circR = dp(3f)
        // trackX близко к правому краю view (ближайший к CameraControlsWidget);
        // 20dp от правого края даёт бейджу (~35dp) место на обеих сторонах от центра
        // без выхода за границы view (badge: center ± 17dp ≈ view_right - 3dp..view_right - 37dp).
        trackX   = w.toFloat() - dp(20f)
        // Vertical padding — увеличь/уменьши dp(22f) чтобы регулировать длину шкалы
        trackTop = dp(22f) + circR
        trackBot = h.toFloat() - dp(22f) - circR
    }

    // ── Coordinate mapping ────────────────────────────────────────────────────

    private fun pitchToY(deg: Float): Float {
        val clamped = deg.coerceIn(minDeg, maxDeg)
        val range   = maxDeg - minDeg
        val t       = if (range == 0f) 0.5f else (maxDeg - clamped) / range
        return trackTop + t * (trackBot - trackTop)
    }

    private fun yToPitch(y: Float): Float {
        val t = ((y - trackTop) / (trackBot - trackTop)).coerceIn(0f, 1f)
        return maxDeg - t * (maxDeg - minDeg)
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val circR    = dp(3f)
        /** Dot is bigger than circR so it fully covers the hollow circle at extremes. */
        val dotR     = dp(5f)
        val tickHalf = dp(3f)

        // 1. Hollow endpoint circles — drawn FIRST (indicator will render on top at extremes)
        canvas.drawCircle(trackX, trackTop, circR, pCircle)
        canvas.drawCircle(trackX, trackBot, circR, pCircle)

        // 2. Evenly-spaced tick marks
        //    gap = distance between circle centre and first/last tick, and between ticks.
        val gap = (trackBot - trackTop) / (ticksCount + 1).toFloat()
        for (i in 1..ticksCount) {
            val y  = trackTop + gap * i
            val p  = if (i == redTickIndex) pTickRed else pTickNormal
            canvas.drawLine(trackX - tickHalf, y, trackX + tickHalf, y, p)
        }

        // 3. Red indicator dot — drawn LAST so it is always on top of circles and ticks
        val markerY = pitchToY(pitchDeg)
        canvas.drawCircle(trackX, markerY, dotR, pDot)

        // 4. Badge: rounded rect with integer pitch value, CENTRED on the indicator dot.
        //    coerceIn keeps the badge within view bounds if the text is unusually wide.
        val label     = pitchDeg.roundToInt().toString()
        val textW     = pBadgeText.measureText(label)
        val padX      = dp(7f)
        val padY      = dp(4f)
        val fm        = pBadgeText.fontMetrics
        val badgeW    = textW + padX * 2f
        val badgeH    = (fm.descent - fm.ascent) + padY * 2f
        val badgeLeft = (trackX - badgeW / 2f).coerceIn(0f, (width - badgeW).coerceAtLeast(0f))
        val badgeTop  = (markerY - badgeH / 2f).coerceIn(0f, height - badgeH)

        badgeRect.set(badgeLeft, badgeTop, badgeLeft + badgeW, badgeTop + badgeH)
        canvas.drawRoundRect(badgeRect, dp(4f), dp(4f), pBadgeBg)

        val textX = badgeRect.centerX()
        val textY = badgeRect.centerY() - (fm.ascent + fm.descent) / 2f
        canvas.drawText(label, textX, textY, pBadgeText)
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                dispatchPitch(yToPitch(event.y))
                true
            }
            MotionEvent.ACTION_MOVE -> {
                dispatchPitch(yToPitch(event.y))
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                true
            }
            else -> super.onTouchEvent(event)
        }
    }

    private fun dispatchPitch(pitch: Float) {
        pitchDeg = pitch
        val now = SystemClock.uptimeMillis()
        if (now - lastCmdTs >= DEBOUNCE_MS) {
            lastCmdTs = now
            onPitchChanged?.invoke(pitch)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
