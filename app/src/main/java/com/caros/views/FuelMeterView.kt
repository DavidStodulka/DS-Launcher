package com.caros.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.caros.fuel.FuelStats

/**
 * Custom view displaying instant and average fuel consumption for the 1.6 TDI.
 *
 * Layout (top-to-bottom):
 *   - Large instant consumption in L/100 km (or "idle" when stopped)
 *   - Smaller average L/100 km + trip fuel litres
 *   - Progress arc for fuel tank estimate (outer ring)
 *   - Estimated range in km
 */
class FuelMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
    }
    private val arcBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        color = Color.parseColor("#1AFFFFFF")
    }
    private val instantPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3FFFFFF")
        textAlign = Paint.Align.CENTER
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF")
        textAlign = Paint.Align.CENTER
    }

    private var stats: FuelStats = FuelStats(0f, 0f, 0f, 0f, 0)
    private val arcRect = RectF()

    fun update(fuelStats: FuelStats) {
        stats = fuelStats
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = (minOf(w, h) / 2f) - 20f

        // Arc background
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(arcRect, 150f, 240f, false, arcBgPaint)

        // Tank fill arc (green = plenty, yellow = medium, red = low)
        val fillFraction = (stats.estimatedRangeKm / 600f).coerceIn(0f, 1f)
        arcPaint.color = when {
            fillFraction > 0.5f -> Color.parseColor("#4CAF50")
            fillFraction > 0.25f -> Color.parseColor("#FFC107")
            else -> Color.parseColor("#F44336")
        }
        canvas.drawArc(arcRect, 150f, 240f * fillFraction, false, arcPaint)

        // Instant consumption — large center text
        val instant = stats.instantLper100km
        val instantText = if (instant >= 99f) "idle" else "%.1f".format(instant)
        instantPaint.textSize = radius * 0.45f
        canvas.drawText(instantText, cx, cy + instantPaint.textSize * 0.35f, instantPaint)

        // "L/100 km" label
        labelPaint.textSize = radius * 0.18f
        canvas.drawText("L/100 km", cx, cy + instantPaint.textSize * 0.35f + labelPaint.textSize * 1.3f, labelPaint)

        // Average consumption
        subPaint.textSize = radius * 0.16f
        val avgText = "ø %.1f L/100 km".format(stats.avgLper100km)
        canvas.drawText(avgText, cx, cy - radius * 0.35f, subPaint)

        // Trip distance + litres
        val tripText = "%.1f km  |  %.2f L".format(stats.tripDistanceKm, stats.tripFuelLitres)
        canvas.drawText(tripText, cx, cy - radius * 0.18f, subPaint)

        // Range estimate at bottom
        labelPaint.textSize = radius * 0.17f
        canvas.drawText("~${stats.estimatedRangeKm} km dojezd", cx, cy + radius * 0.7f, labelPaint)
    }
}
