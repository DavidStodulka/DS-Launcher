package com.caros.views

// ─────────────────────────────────────────────────────────────────────────────
//  GaugeWidgetView.kt — Compact multi-purpose gauge widget (80dp × 80dp)
//
//  Shows: label (small, secondary color), value (large, primary color),
//  unit (small), min/max arc, color threshold zones (green/yellow/red).
// ─────────────────────────────────────────────────────────────────────────────

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import timber.log.Timber
import kotlin.math.min

class GaugeWidgetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val LOG_TAG = "GaugeWidgetView"

        private const val COLOR_BG       = 0xFF080808.toInt()
        private const val COLOR_TRACK    = 0xFF2A2A2A.toInt()
        private const val COLOR_PRIMARY  = 0xFF1565C0.toInt()
        private const val COLOR_SECONDARY = 0xFFE65100.toInt()
        private const val COLOR_TEXT_PRI = 0xFFFFFFFF.toInt()
        private const val COLOR_TEXT_SEC = 0xFFAAAAAA.toInt()
        private const val COLOR_GREEN    = 0xFF2E7D32.toInt()
        private const val COLOR_YELLOW   = 0xFFF9A825.toInt()
        private const val COLOR_RED      = 0xFFC62828.toInt()
        private const val SWEEP_ANGLE    = 220f
        private const val START_ANGLE    = 160f
        private const val ANIM_DURATION  = 200L
    }

    // ── Public configuration ──────────────────────────────────────────────────

    var label     = "LABEL"
        set(v) { field = v; invalidate() }
    var unit      = ""
        set(v) { field = v; invalidate() }
    var minValue  = 0f
    var maxValue  = 100f
    var yellowAt  = 70f   // fraction of range where yellow starts
    var redAt     = 90f   // fraction of range where red starts

    // ── State ─────────────────────────────────────────────────────────────────

    private var displayedValue = 0f
    private var targetValue    = 0f
    private var rawValue       = 0f

    private val animator = ValueAnimator().apply {
        duration = ANIM_DURATION
        addUpdateListener { anim ->
            displayedValue = anim.animatedValue as Float
            invalidate()
        }
    }

    // ── Geometry ──────────────────────────────────────────────────────────────

    private var cx = 0f
    private var cy = 0f
    private var arcRect  = RectF()
    private var arcStrokeW = 0f

    // ── Paints ────────────────────────────────────────────────────────────────

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_BG
        style = Paint.Style.FILL
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = COLOR_TRACK
        style       = Paint.Style.STROKE
        strokeCap   = Paint.Cap.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style     = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = COLOR_SECONDARY
        typeface  = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = COLOR_TEXT_PRI
        typeface  = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = COLOR_TEXT_SEC
        typeface  = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun setValue(value: Float) {
        rawValue    = value
        val clamped = value.coerceIn(minValue, maxValue)
        if (clamped == targetValue) return
        targetValue = clamped
        animator.cancel()
        animator.setFloatValues(displayedValue, clamped)
        animator.start()
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (80 * resources.displayMetrics.density).toInt()
        val w = resolveSize(desired, widthMeasureSpec)
        val h = resolveSize(desired, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        arcStrokeW = min(w, h) * 0.09f
        val radius = min(w, h) / 2f - arcStrokeW / 2f - min(w, h) * 0.04f
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        trackPaint.strokeWidth = arcStrokeW
        fillPaint.strokeWidth  = arcStrokeW
        labelPaint.textSize    = min(w, h) * 0.12f
        valuePaint.textSize    = min(w, h) * 0.22f
        unitPaint.textSize     = min(w, h) * 0.10f
        updateShader()
    }

    private fun updateShader() {
        fillPaint.shader = LinearGradient(
            cx - arcRect.width() / 2f, cy,
            cx + arcRect.width() / 2f, cy,
            intArrayOf(COLOR_GREEN, COLOR_YELLOW, COLOR_RED),
            floatArrayOf(0f, (yellowAt - minValue) / (maxValue - minValue), (redAt - minValue) / (maxValue - minValue)),
            Shader.TileMode.CLAMP
        )
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Track arc
        canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE, false, trackPaint)

        // Fill arc
        val fraction = (displayedValue - minValue) / (maxValue - minValue)
        val sweep    = fraction.coerceIn(0f, 1f) * SWEEP_ANGLE
        if (sweep > 0f) {
            canvas.drawArc(arcRect, START_ANGLE, sweep, false, fillPaint)
        }

        // Label text (top above center)
        val labelY = cy - valuePaint.textSize * 0.60f
        canvas.drawText(label, cx, labelY, labelPaint)

        // Value text (center)
        val valueStr = formatValue(displayedValue)
        canvas.drawText(valueStr, cx, cy + valuePaint.textSize * 0.35f, valuePaint)

        // Unit text (below value)
        if (unit.isNotEmpty()) {
            canvas.drawText(unit, cx, cy + valuePaint.textSize * 0.35f + unitPaint.textSize + 2f, unitPaint)
        }
    }

    private fun formatValue(v: Float): String {
        return if (v == v.toLong().toFloat()) "${v.toInt()}" else "%.1f".format(v)
    }

    init {
        Timber.tag(LOG_TAG).d("GaugeWidgetView initialised")
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }
}
