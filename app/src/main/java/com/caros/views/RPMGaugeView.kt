package com.caros.views

// ─────────────────────────────────────────────────────────────────────────────
//  RPMGaugeView.kt — Circular RPM gauge for CLHA TDI (0–5000 RPM, redline 4500)
//
//  Draws a 240° arc background, a colored fill arc (green→yellow→red),
//  tick marks every 500 RPM, numeric center value, and a redline marker.
//  Smooth animation via ValueAnimator at 150 ms.
// ─────────────────────────────────────────────────────────────────────────────

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import timber.log.Timber
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class RPMGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val LOG_TAG = "RPMGaugeView"

        private const val SWEEP_ANGLE   = 240f       // total arc in degrees
        private const val START_ANGLE   = 150f       // start from bottom-left (210° in standard)
        private const val COLOR_BG      = 0xFF080808.toInt()
        private const val COLOR_TRACK   = 0xFF2A2A2A.toInt()
        private const val COLOR_GREEN   = 0xFF2E7D32.toInt()
        private const val COLOR_YELLOW  = 0xFFF9A825.toInt()
        private const val COLOR_RED     = 0xFFC62828.toInt()
        private const val COLOR_TEXT    = 0xFFFFFFFF.toInt()
        private const val COLOR_SUBTEXT = 0xFFAAAAAA.toInt()
        private const val COLOR_REDLINE = 0xFFC62828.toInt()
        private const val ANIM_DURATION = 150L
    }

    // ── Configurable properties ───────────────────────────────────────────────

    var minRpm     = 0
    var maxRpm     = 5000
    var redlineRpm = 4500
    var tickStep   = 500

    // ── Current displayed value (animated) ───────────────────────────────────

    private var displayedRpm = 0f
    private var targetRpm    = 0f
    private val animator     = ValueAnimator().apply {
        duration  = ANIM_DURATION
        addUpdateListener { anim ->
            displayedRpm = anim.animatedValue as Float
            invalidate()
        }
    }

    // ── Geometry (recomputed in onSizeChanged) ────────────────────────────────

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var arcRect = RectF()
    private var tickLen = 0f
    private var majorTickLen = 0f

    // ── Paints ────────────────────────────────────────────────────────────────

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        color       = COLOR_TRACK
        strokeCap   = Paint.Cap.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style     = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        color       = COLOR_SUBTEXT
        strokeWidth = 2f
        strokeCap   = Paint.Cap.ROUND
    }

    private val redlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        color       = COLOR_REDLINE
        strokeWidth = 4f
        strokeCap   = Paint.Cap.ROUND
    }

    private val rpmTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = COLOR_TEXT
        typeface  = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = COLOR_SUBTEXT
        typeface  = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    private val tickLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = COLOR_SUBTEXT
        typeface  = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Animate to a new RPM value. Call from any thread that can post to the view. */
    fun setRpm(rpm: Int) {
        val clamped = rpm.coerceIn(minRpm, maxRpm).toFloat()
        if (clamped == targetRpm) return
        targetRpm = clamped
        animator.cancel()
        animator.setFloatValues(displayedRpm, clamped)
        animator.start()
        Timber.tag(LOG_TAG).v("RPM target -> %d", rpm)
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = min(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        setMeasuredDimension(s, s)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        val strokeW = w * 0.10f
        radius = w / 2f - strokeW / 2f - w * 0.04f
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        trackPaint.strokeWidth  = strokeW
        fillPaint.strokeWidth   = strokeW
        tickLen      = strokeW * 0.55f
        majorTickLen = strokeW * 0.85f
        rpmTextPaint.textSize  = w * 0.20f
        labelPaint.textSize    = w * 0.085f
        tickLabelPaint.textSize = w * 0.070f
        updateFillShader()
    }

    private fun updateFillShader() {
        // Horizontal gradient: green → yellow → red
        fillPaint.shader = LinearGradient(
            cx - radius, cy, cx + radius, cy,
            intArrayOf(COLOR_GREEN, COLOR_YELLOW, COLOR_RED),
            floatArrayOf(0f, (redlineRpm - minRpm).toFloat() / (maxRpm - minRpm).coerceAtLeast(1), 1f),
            Shader.TileMode.CLAMP
        )
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Background fill
        canvas.drawColor(COLOR_BG)

        // Track arc
        canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE, false, trackPaint)

        // Value fill arc
        val fraction = (displayedRpm - minRpm) / (maxRpm - minRpm).coerceAtLeast(1).toFloat()
        val sweep    = fraction * SWEEP_ANGLE
        if (sweep > 0f) {
            canvas.drawArc(arcRect, START_ANGLE, sweep, false, fillPaint)
        }

        // Tick marks
        drawTicks(canvas)

        // Redline marker
        drawRedlineMarker(canvas)

        // Center RPM text
        val rpmStr = "${displayedRpm.toInt()}"
        canvas.drawText(rpmStr, cx, cy + rpmTextPaint.textSize * 0.38f, rpmTextPaint)
        canvas.drawText("RPM", cx, cy + rpmTextPaint.textSize * 0.38f + labelPaint.textSize + 2f, labelPaint)
    }

    private fun drawTicks(canvas: Canvas) {
        val range = (maxRpm - minRpm).coerceAtLeast(1).toFloat()
        var rpm = minRpm
        while (rpm <= maxRpm) {
            val fraction = (rpm - minRpm) / range
            val angleDeg = START_ANGLE + fraction * SWEEP_ANGLE
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val isMajor  = rpm % (tickStep * 2) == 0

            val len    = if (isMajor) majorTickLen else tickLen
            val outerR = radius + trackPaint.strokeWidth / 2f
            val innerR = outerR - len

            val cos = cos(angleRad).toFloat()
            val sin = sin(angleRad).toFloat()

            tickPaint.strokeWidth = if (isMajor) 2.5f else 1.5f
            canvas.drawLine(
                cx + innerR * cos, cy + innerR * sin,
                cx + outerR * cos, cy + outerR * sin,
                tickPaint
            )

            // Label for major ticks
            if (isMajor) {
                val labelR = innerR - tickLabelPaint.textSize * 0.7f
                val lx = cx + labelR * cos
                val ly = cy + labelR * sin + tickLabelPaint.textSize * 0.35f
                canvas.drawText("${rpm / 100}", lx, ly, tickLabelPaint)
            }
            rpm += tickStep
        }
    }

    private fun drawRedlineMarker(canvas: Canvas) {
        val fraction = (redlineRpm - minRpm).toFloat() / (maxRpm - minRpm).coerceAtLeast(1).toFloat()
        val angleDeg = START_ANGLE + fraction * SWEEP_ANGLE
        val angleRad = Math.toRadians(angleDeg.toDouble())
        val outerR   = radius + trackPaint.strokeWidth / 2f + 4f
        val innerR   = outerR - majorTickLen * 1.2f
        val cos      = cos(angleRad).toFloat()
        val sin      = sin(angleRad).toFloat()
        redlinePaint.strokeWidth = 5f
        canvas.drawLine(
            cx + innerR * cos, cy + innerR * sin,
            cx + outerR * cos, cy + outerR * sin,
            redlinePaint
        )
    }

    init {
        Timber.tag(LOG_TAG).d("RPMGaugeView initialised")
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }
}
