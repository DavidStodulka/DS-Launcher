package com.caros.views

// ─────────────────────────────────────────────────────────────────────────────
//  SpeedView.kt — Large speed display with smooth digit transition
//
//  Huge Roboto Condensed numeric value, "km/h" label below.
//  Color thresholds: white (< 100), yellow (100–149), red (≥ 150).
//  Smooth digit crossfade via ValueAnimator.
// ─────────────────────────────────────────────────────────────────────────────

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import timber.log.Timber

class SpeedView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val LOG_TAG = "SpeedView"

        private const val COLOR_BG      = 0xFF080808.toInt()
        private const val COLOR_WHITE   = 0xFFFFFFFF.toInt()
        private const val COLOR_YELLOW  = 0xFFF9A825.toInt()
        private const val COLOR_RED     = 0xFFC62828.toInt()
        private const val COLOR_SUBTEXT = 0xFFAAAAAA.toInt()
        private const val ANIM_DURATION = 120L
    }

    // ── Configurable thresholds ───────────────────────────────────────────────

    var yellowThreshold = 100f
    var redThreshold    = 150f

    // ── State ─────────────────────────────────────────────────────────────────

    private var displayedSpeed = 0f
    private var targetSpeed    = 0f

    private val animator = ValueAnimator().apply {
        duration = ANIM_DURATION
        addUpdateListener { anim ->
            displayedSpeed = anim.animatedValue as Float
            invalidate()
        }
    }

    // ── Paints ────────────────────────────────────────────────────────────────

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_BG
        style = Paint.Style.FILL
    }

    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = COLOR_WHITE
        typeface  = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = COLOR_SUBTEXT
        typeface  = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun setSpeed(kmh: Float) {
        val clamped = kmh.coerceAtLeast(0f)
        if (clamped == targetSpeed) return
        targetSpeed = clamped
        animator.cancel()
        animator.setFloatValues(displayedSpeed, clamped)
        animator.start()
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        speedPaint.textSize = h * 0.62f
        unitPaint.textSize  = h * 0.14f
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Color based on speed thresholds
        speedPaint.color = when {
            displayedSpeed >= redThreshold    -> COLOR_RED
            displayedSpeed >= yellowThreshold -> COLOR_YELLOW
            else                             -> COLOR_WHITE
        }

        // Speed number
        val speedStr = "${displayedSpeed.toInt()}"
        val metrics  = speedPaint.fontMetrics
        val textH    = metrics.descent - metrics.ascent
        val totalH   = textH + unitPaint.textSize + h * 0.03f
        val topY     = (h - totalH) / 2f - metrics.ascent

        canvas.drawText(speedStr, cx, topY, speedPaint)

        // "km/h" label
        val unitY = topY + metrics.descent + h * 0.03f + unitPaint.textSize
        canvas.drawText("km/h", cx, unitY, unitPaint)
    }

    init {
        Timber.tag(LOG_TAG).d("SpeedView initialised")
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }
}
