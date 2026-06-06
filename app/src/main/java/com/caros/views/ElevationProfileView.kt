package com.caros.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ElevationViewPoint(val distanceKm: Float, val altitudeM: Float, val slopePct: Float = 0f)

class ElevationProfileView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var points = listOf<ElevationViewPoint>()
    private var scrollOffsetX = 0f
    private var scaleFactor = 1f
    private var minAlt = 0f
    private var maxAlt = 100f
    private var totalDistKm = 1f

    private val bgPaint = Paint().apply { color = 0xFF080808.toInt() }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF333333.toInt(); strokeWidth = 1f; style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF757575.toInt(); textSize = 24f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1565C0.toInt(); strokeWidth = 3f; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
    }

    private val slopeColors = mapOf(
        "FLAT" to 0xFF2E7D32.toInt(),
        "MILD" to 0xFFF9A825.toInt(),
        "STEEP" to 0xFFE65100.toInt(),
        "EXTREME" to 0xFFC62828.toInt()
    )

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dX: Float, dY: Float): Boolean {
            scrollOffsetX = (scrollOffsetX - dX).coerceIn(-(width * (scaleFactor - 1f)).coerceAtLeast(0f), 0f)
            invalidate(); return true
        }
    })
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(1f, 8f)
            invalidate(); return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (points.isEmpty()) { drawEmpty(canvas); return }

        val padL = 80f; val padR = 24f; val padT = 24f; val padB = 48f
        val chartW = (width - padL - padR) * scaleFactor
        val chartH = height - padT - padB

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Axes
        canvas.drawLine(padL, padT, padL, height - padB, axisPaint)
        canvas.drawLine(padL, height - padB, width.toFloat() - padR, height - padB, axisPaint)

        // Fill segments by slope color
        val fillPath = Path()
        var first = true
        val baseLine = height - padB

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; alpha = 160 }
        for (i in 0 until points.size - 1) {
            val p1 = points[i]; val p2 = points[i + 1]
            val x1 = padL + scrollOffsetX + (p1.distanceKm / totalDistKm) * chartW
            val x2 = padL + scrollOffsetX + (p2.distanceKm / totalDistKm) * chartW
            val y1 = padT + (1f - (p1.altitudeM - minAlt) / (maxAlt - minAlt).coerceAtLeast(1f)) * chartH
            val y2 = padT + (1f - (p2.altitudeM - minAlt) / (maxAlt - minAlt).coerceAtLeast(1f)) * chartH
            fillPaint.color = slopeColor(p1.slopePct)
            val path = Path()
            path.moveTo(x1, baseLine); path.lineTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x2, baseLine); path.close()
            canvas.drawPath(path, fillPaint)
        }

        // Profile line
        val linePath = Path()
        var pathStarted = false
        for (pt in points) {
            val x = padL + scrollOffsetX + (pt.distanceKm / totalDistKm) * chartW
            val y = padT + (1f - (pt.altitudeM - minAlt) / (maxAlt - minAlt).coerceAtLeast(1f)) * chartH
            if (!pathStarted) { linePath.moveTo(x, y); pathStarted = true } else linePath.lineTo(x, y)
        }
        canvas.drawPath(linePath, linePaint)

        // Alt labels
        labelPaint.textAlign = Paint.Align.RIGHT
        for (i in 0..4) {
            val alt = minAlt + i * (maxAlt - minAlt) / 4f
            val y = padT + (1f - i / 4f) * chartH
            canvas.drawText("${alt.toInt()}m", padL - 4f, y + 8f, labelPaint)
        }
        // Distance labels
        labelPaint.textAlign = Paint.Align.CENTER
        for (i in 0..4) {
            val distKm = i * totalDistKm / 4f
            val x = padL + scrollOffsetX + (distKm / totalDistKm) * chartW
            if (x >= padL && x <= width - padR)
                canvas.drawText("${String.format("%.1f", distKm)}km", x, height - 4f, labelPaint)
        }

        // Legend (top-right corner)
        drawLegend(canvas)
    }

    private fun drawLegend(canvas: Canvas) {
        val legendEntries = listOf(
            "≤2% Rovně" to slopeColors["FLAT"]!!,
            "≤5% Mírně" to slopeColors["MILD"]!!,
            "≤10% Prudce" to slopeColors["STEEP"]!!,
            ">10% Extrémně" to slopeColors["EXTREME"]!!
        )
        val boxSize = 14f
        val textSize = 20f
        val itemH = 22f
        val legendW = 170f
        val padH = 8f
        val totalH = legendEntries.size * itemH + padH * 2

        val bgPaint2 = Paint().apply { color = 0xCC111111.toInt() }
        val lx = width - legendW - 8f
        val ly = 8f
        canvas.drawRect(lx - 4f, ly, lx + legendW + 4f, ly + totalH, bgPaint2)

        val boxPaint2 = Paint(Paint.ANTI_ALIAS_FLAG)
        val txtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFCCCCCC.toInt(); this.textSize = textSize; textAlign = Paint.Align.LEFT
        }
        legendEntries.forEachIndexed { idx, (label, color) ->
            val itemY = ly + padH + idx * itemH
            boxPaint2.color = color
            canvas.drawRect(lx, itemY, lx + boxSize, itemY + boxSize, boxPaint2)
            canvas.drawText(label, lx + boxSize + 4f, itemY + boxSize - 1f, txtPaint)
        }
    }

    private fun slopeColor(slope: Float): Int = when {
        abs(slope) <= 2f -> slopeColors["FLAT"]!!
        abs(slope) <= 5f -> slopeColors["MILD"]!!
        abs(slope) <= 10f -> slopeColors["STEEP"]!!
        else -> slopeColors["EXTREME"]!!
    }

    private fun drawEmpty(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF444444.toInt(); textSize = 32f; textAlign = Paint.Align.CENTER }
        canvas.drawText("Žádná data", width / 2f, height / 2f, p)
    }

    fun setData(pts: List<ElevationViewPoint>) {
        points = pts
        if (pts.isEmpty()) { invalidate(); return }
        minAlt = pts.minOf { it.altitudeM }
        maxAlt = pts.maxOf { it.altitudeM }
        totalDistKm = pts.maxOf { it.distanceKm }.coerceAtLeast(0.01f)
        scrollOffsetX = 0f; scaleFactor = 1f
        invalidate()
    }

    fun setDataFromAltitudes(altitudes: List<Float>) {
        var distKm = 0f
        val pts = altitudes.mapIndexed { i, alt ->
            val prev = if (i > 0) altitudes[i - 1] else alt
            val slope = if (i > 0) (alt - prev) / 0.05f else 0f // assume 50m intervals
            distKm = i * 0.05f
            ElevationViewPoint(distKm, alt, slope)
        }
        setData(pts)
    }
}
