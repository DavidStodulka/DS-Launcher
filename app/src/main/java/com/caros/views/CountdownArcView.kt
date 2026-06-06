package com.caros.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class CountdownArcView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = 0xFF2A2A2A.toInt()
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        color = 0xFF1565C0.toInt()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 36f
    }

    private var sweepAngle = 360f   // 360 = full, counts down to 0
    private var secondsLeft = 10
    private var animator: ValueAnimator? = null
    private val oval = RectF()

    fun startCountdown(durationMs: Long = 10_000L, onExpired: () -> Unit = {}) {
        animator?.cancel()
        sweepAngle = 360f
        secondsLeft = (durationMs / 1000L).toInt()
        visibility = VISIBLE
        animator = ValueAnimator.ofFloat(360f, 0f).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                sweepAngle = va.animatedValue as Float
                secondsLeft = ((va.animatedFraction.let { 1f - it } * durationMs / 1000f)).toInt()
                invalidate()
            }
            doOnEnd { onExpired(); visibility = GONE }
            start()
        }
    }

    fun stopCountdown() {
        animator?.cancel()
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) - 12f
        oval.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawOval(oval, trackPaint)
        canvas.drawArc(oval, -90f, -sweepAngle, false, arcPaint)
        canvas.drawText(secondsLeft.toString(), cx, cy + textPaint.textSize / 3f, textPaint)
    }

    private fun ValueAnimator.doOnEnd(block: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = block()
        })
    }
}
