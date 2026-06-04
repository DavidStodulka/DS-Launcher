package com.caros.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class RadarChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class RadarData(
        val ecoScore: Float = 0f,
        val sportScore: Float = 0f,
        val mechanicalScore: Float = 0f,
        val smoothnessScore: Float = 0f
    )

    private var data = RadarData()
    private val labels = arrayOf("Eco", "Sport", "Mechanika", "Plynulost")
    private val AXES = 4
    private val RINGS = 4

    private val bgPaint = Paint().apply { color = 0xFF111111.toInt() }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2A2A2A.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF444444.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x661565C0; style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1565C0.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1565C0.toInt(); style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 28f; textAlign = Paint.Align.CENTER
    }
    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE65100.toInt(); textSize = 22f; textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - 56f

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Grid rings
        for (r in 1..RINGS) {
            val ringRadius = radius * r / RINGS
            val path = buildPolygon(cx, cy, ringRadius, AXES, -Math.PI / 4)
            canvas.drawPath(path, gridPaint)
        }

        // Axes
        for (i in 0 until AXES) {
            val angle = 2 * Math.PI * i / AXES - Math.PI / 4
            canvas.drawLine(cx, cy, (cx + radius * cos(angle)).toFloat(), (cy + radius * sin(angle)).toFloat(), axisPaint)
        }

        // Data polygon
        val scores = floatArrayOf(data.ecoScore, data.sportScore, data.mechanicalScore, data.smoothnessScore)
        val dataPath = Path()
        for (i in 0 until AXES) {
            val angle = 2 * Math.PI * i / AXES - Math.PI / 4
            val r = radius * (scores[i].coerceIn(0f, 100f) / 100f)
            val x = (cx + r * cos(angle)).toFloat()
            val y = (cy + r * sin(angle)).toFloat()
            if (i == 0) dataPath.moveTo(x, y) else dataPath.lineTo(x, y)
        }
        dataPath.close()
        canvas.drawPath(dataPath, fillPaint)
        canvas.drawPath(dataPath, strokePaint)

        // Vertex dots + labels
        for (i in 0 until AXES) {
            val angle = 2 * Math.PI * i / AXES - Math.PI / 4
            val r = radius * (scores[i].coerceIn(0f, 100f) / 100f)
            val x = (cx + r * cos(angle)).toFloat()
            val y = (cy + r * sin(angle)).toFloat()
            canvas.drawCircle(x, y, 6f, dotPaint)

            val labelR = radius + 40f
            val lx = (cx + labelR * cos(angle)).toFloat()
            val ly = (cy + labelR * sin(angle)).toFloat()
            canvas.drawText(labels[i], lx, ly + 8f, labelPaint)

            val scoreR = radius * (scores[i] / 100f) + 20f
            val sx = (cx + scoreR * cos(angle)).toFloat()
            val sy = (cy + scoreR * sin(angle)).toFloat()
            canvas.drawText("${scores[i].toInt()}", sx, sy + 8f, scorePaint)
        }
    }

    private fun buildPolygon(cx: Float, cy: Float, radius: Float, sides: Int, startAngle: Double): Path {
        val path = Path()
        for (i in 0..sides) {
            val angle = 2 * Math.PI * (i % sides) / sides + startAngle
            val x = (cx + radius * cos(angle)).toFloat()
            val y = (cy + radius * sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close(); return path
    }

    fun setData(data: RadarData) { this.data = data; invalidate() }
    fun setScores(eco: Int, sport: Int, mechanical: Int, smoothness: Int) {
        data = RadarData(eco.toFloat(), sport.toFloat(), mechanical.toFloat(), smoothness.toFloat()); invalidate()
    }
}
