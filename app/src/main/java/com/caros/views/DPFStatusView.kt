package com.caros.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.caros.service.DPFPrediction
import com.caros.service.DPFRecommendation

/**
 * Animated arc gauge showing DPF soot load (0–100 %) with km-to-regen estimate.
 *
 * Color zones:
 *   0–60 %  → green
 *   60–75 % → yellow
 *   75–85 % → orange
 *   85–100% → red
 */
class DPFStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val arcBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 14f
        color = Color.parseColor("#1AFFFFFF")
        strokeCap = Paint.Cap.ROUND
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 14f
        strokeCap = Paint.Cap.ROUND
    }
    private val loadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
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

    private val arcRect = RectF()
    private var displayedLoad = 0f
    private var prediction: DPFPrediction? = null
    private var animator: ValueAnimator? = null

    fun update(pred: DPFPrediction) {
        prediction = pred
        animateTo(pred.currentLoadPct)
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(displayedLoad, target).apply {
            duration = 600L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                displayedLoad = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h * 0.54f
        val radius = (minOf(w, h) / 2f) - 22f

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // Background arc
        canvas.drawArc(arcRect, 150f, 240f, false, arcBgPaint)

        // Colored fill arc
        val sweep = (displayedLoad / 100f) * 240f
        arcPaint.color = arcColorForLoad(displayedLoad)
        canvas.drawArc(arcRect, 150f, sweep, false, arcPaint)

        // Load percentage (center)
        loadPaint.textSize = radius * 0.48f
        canvas.drawText("%.0f%%".format(displayedLoad), cx, cy + loadPaint.textSize * 0.35f, loadPaint)

        // "DPF" label
        labelPaint.textSize = radius * 0.17f
        canvas.drawText("DPF", cx, cy - radius * 0.5f, labelPaint)

        // Km-to-regen estimate
        val pred = prediction
        if (pred != null) {
            subPaint.textSize = radius * 0.155f
            val regenText = when {
                pred.estimatedKmToRegen == 0 -> "regenerace nyní"
                pred.estimatedKmToRegen > 0  -> "regen za ${pred.regenRangeStr}"
                else -> "regen: neznámo"
            }
            canvas.drawText(regenText, cx, cy + radius * 0.68f, subPaint)

            // Recommendation badge
            if (pred.recommendation != DPFRecommendation.OK) {
                labelPaint.textSize = radius * 0.14f
                labelPaint.color = arcColorForLoad(displayedLoad)
                canvas.drawText(recommendationText(pred.recommendation), cx, cy + radius * 0.85f, labelPaint)
                labelPaint.color = Color.parseColor("#B3FFFFFF")
            }
        }
    }

    private fun arcColorForLoad(load: Float): Int = when {
        load < 60f  -> Color.parseColor("#4CAF50")
        load < 75f  -> Color.parseColor("#FFC107")
        load < 85f  -> Color.parseColor("#FF5722")
        else        -> Color.parseColor("#F44336")
    }

    private fun recommendationText(rec: DPFRecommendation): String = when (rec) {
        DPFRecommendation.HIGHWAY_DRIVE_RECOMMENDED -> "⚠ doporučena dálnice"
        DPFRecommendation.URGENT_REGEN              -> "⚠ nutná jízda po dálnici"
        DPFRecommendation.SERVICE_REQUIRED          -> "⛔ servis"
        else -> ""
    }
}
