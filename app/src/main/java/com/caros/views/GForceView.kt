package com.caros.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class GForceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var lateralG = 0f
    private var longitudinalG = 0f
    private var maxLateral = 0f
    private var maxLongitudinal = 0f
    private val trailPoints = ArrayDeque<Pair<Float, Float>>(20)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF111111.toInt(); style = Paint.Style.FILL
    }
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E1E1E.toInt(); style = Paint.Style.FILL
    }
    private val frictionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF444444.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF333333.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1565C0.toInt(); style = Paint.Style.FILL
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x441565C0; style = Paint.Style.FILL
    }
    private val maxMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE65100.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF757575.toInt(); textSize = 20f; textAlign = Paint.Align.CENTER
    }

    private val SCALE = 1.5f // max G shown

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - 8f

        canvas.drawCircle(cx, cy, radius, circlePaint)
        canvas.drawCircle(cx, cy, radius * (1f / SCALE), frictionPaint)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, crosshairPaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, crosshairPaint)

        canvas.drawText("±${SCALE}g", cx, cy - radius - 4f, labelPaint)

        // Trail
        for ((i, pt) in trailPoints.withIndex()) {
            val alpha = (i.toFloat() / trailPoints.size * 120).toInt()
            trailPaint.alpha = alpha
            val bx = cx + (pt.first / SCALE) * radius
            val by = cy - (pt.second / SCALE) * radius
            canvas.drawCircle(bx, by, 8f, trailPaint)
        }

        // Max G marker
        val maxBx = cx + (maxLateral / SCALE) * radius
        val maxBy = cy - (maxLongitudinal / SCALE) * radius
        canvas.drawCircle(maxBx, maxBy, 12f, maxMarkerPaint)

        // Current bubble
        val bx = cx + (lateralG / SCALE) * radius
        val by = cy - (longitudinalG / SCALE) * radius
        canvas.drawCircle(bx, by, 18f, bubblePaint)
    }

    fun update(lateral: Float, longitudinal: Float) {
        lateralG = lateral.coerceIn(-SCALE, SCALE)
        longitudinalG = longitudinal.coerceIn(-SCALE, SCALE)
        if (trailPoints.size >= 20) trailPoints.removeFirst()
        trailPoints.addLast(Pair(lateralG, longitudinalG))
        if (kotlin.math.abs(lateral) > kotlin.math.abs(maxLateral)) maxLateral = lateral
        if (kotlin.math.abs(longitudinal) > kotlin.math.abs(maxLongitudinal)) maxLongitudinal = longitudinal
        invalidate()
    }

    fun resetSession() { maxLateral = 0f; maxLongitudinal = 0f; trailPoints.clear(); invalidate() }
}
