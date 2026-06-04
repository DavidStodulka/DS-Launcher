package com.caros.views

// ─────────────────────────────────────────────────────────────────────────────
//  EQBarView.kt — Single EQ band vertical slider
//
//  Range: -12 to +12 dB.
//  Blue fill below 0 dB, orange fill above 0 dB.
//  Shows dB value label (top) and frequency label (bottom).
//  Touch drag moves the slider; listener notified on change.
// ─────────────────────────────────────────────────────────────────────────────

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import timber.log.Timber
import kotlin.math.roundToInt

class EQBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val LOG_TAG = "EQBarView"

        private const val MIN_DB          = -12f
        private const val MAX_DB          = 12f
        private const val COLOR_BG        = 0xFF111111.toInt()
        private const val COLOR_TRACK     = 0xFF2A2A2A.toInt()
        private const val COLOR_ZERO_LINE = 0xFF444444.toInt()
        private const val COLOR_BLUE      = 0xFF1565C0.toInt()
        private const val COLOR_ORANGE    = 0xFFE65100.toInt()
        private const val COLOR_TEXT_PRI  = 0xFFFFFFFF.toInt()
        private const val COLOR_TEXT_SEC  = 0xFFAAAAAA.toInt()
        private const val COLOR_THUMB     = 0xFFFFFFFF.toInt()
        private const val TRACK_WIDTH_DP  = 10f
    }

    // ── Configuration ─────────────────────────────────────────────────────────

    var frequencyLabel = "1k"
        set(v) { field = v; invalidate() }

    var onValueChanged: ((db: Float) -> Unit)? = null

    // ── State ─────────────────────────────────────────────────────────────────

    private var currentDb = 0f
        set(v) {
            field = v.coerceIn(MIN_DB, MAX_DB)
            onValueChanged?.invoke(field)
            invalidate()
        }

    // ── Geometry (computed in onSizeChanged) ──────────────────────────────────

    private var trackTop    = 0f
    private var trackBottom = 0f
    private var trackLeft   = 0f
    private var trackRight  = 0f
    private var zeroY       = 0f
    private var trackW      = 0f
    private val trackRect   = RectF()
    private val fillRect    = RectF()
    private var thumbY      = 0f
    private var thumbRadius = 0f
    private var labelAreaTop = 0f
    private var labelAreaBot = 0f

    // ── Paints ────────────────────────────────────────────────────────────────

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_BG
        style = Paint.Style.FILL
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TRACK
        style = Paint.Style.FILL
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val zeroLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = COLOR_ZERO_LINE
        style       = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_THUMB
        style = Paint.Style.FILL
    }

    private val dbTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = COLOR_TEXT_PRI
        textAlign = Paint.Align.CENTER
        typeface  = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    private val freqTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = COLOR_TEXT_SEC
        textAlign = Paint.Align.CENTER
        typeface  = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun setDb(db: Float) {
        currentDb = db.coerceIn(MIN_DB, MAX_DB)
    }

    fun getDb(): Float = currentDb

    // ── Layout ────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density  = resources.displayMetrics.density
        trackW       = TRACK_WIDTH_DP * density
        val labelH   = h * 0.14f
        labelAreaTop = labelH
        labelAreaBot = h - labelH

        trackLeft    = w / 2f - trackW / 2f
        trackRight   = w / 2f + trackW / 2f
        trackTop     = labelAreaTop + 4f
        trackBottom  = labelAreaBot - 4f
        zeroY        = trackTop + (trackBottom - trackTop) / 2f
        thumbRadius  = trackW * 1.0f

        dbTextPaint.textSize   = labelH * 0.65f
        freqTextPaint.textSize = labelH * 0.60f

        updateThumbY()
    }

    private fun updateThumbY() {
        // Map db → y: MAX_DB = trackTop, MIN_DB = trackBottom
        val fraction = (MAX_DB - currentDb) / (MAX_DB - MIN_DB)
        thumbY = trackTop + fraction * (trackBottom - trackTop)
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Track background
        trackRect.set(trackLeft, trackTop, trackRight, trackBottom)
        canvas.drawRoundRect(trackRect, trackW / 2f, trackW / 2f, trackPaint)

        // Fill from zero line to thumb
        updateThumbY()
        val curThumbY = thumbY
        if (currentDb > 0f) {
            // Above zero: fill from thumbY up to zeroY (orange)
            fillPaint.color = COLOR_ORANGE
            fillRect.set(trackLeft, curThumbY, trackRight, zeroY)
            canvas.drawRoundRect(fillRect, trackW / 2f, trackW / 2f, fillPaint)
        } else if (currentDb < 0f) {
            // Below zero: fill from zeroY down to thumbY (blue)
            fillPaint.color = COLOR_BLUE
            fillRect.set(trackLeft, zeroY, trackRight, curThumbY)
            canvas.drawRoundRect(fillRect, trackW / 2f, trackW / 2f, fillPaint)
        }

        // Zero line
        canvas.drawLine(trackLeft - 4f, zeroY, trackRight + 4f, zeroY, zeroLinePaint)

        // Thumb
        canvas.drawCircle(w / 2f, curThumbY, thumbRadius, thumbPaint)

        // dB label at top
        val dbStr = if (currentDb >= 0) "+${"%.1f".format(currentDb)}" else "${"%.1f".format(currentDb)}"
        canvas.drawText(dbStr, w / 2f, labelAreaTop - 2f, dbTextPaint)

        // Frequency label at bottom
        canvas.drawText(frequencyLabel, w / 2f, height.toFloat() - 2f, freqTextPaint)
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                val y = event.y.coerceIn(trackTop, trackBottom)
                val fraction = (y - trackTop) / (trackBottom - trackTop)
                currentDb = MAX_DB - fraction * (MAX_DB - MIN_DB)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    init {
        Timber.tag(LOG_TAG).d("EQBarView initialised")
        isClickable  = true
        isFocusable  = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }
}
