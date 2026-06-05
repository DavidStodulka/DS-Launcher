package com.caros.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

class VoiceWaveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class WaveState { IDLE, LISTENING, PROCESSING, SPEAKING }

    private var state = WaveState.IDLE
    private var phase = 0f
    private var animator: ValueAnimator? = null

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; strokeCap = Paint.Cap.ROUND
    }
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF757575.toInt(); textSize = 28f; textAlign = Paint.Align.CENTER
    }

    // rmsLevel 0..1 from microphone
    var rmsLevel = 0f

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(width, height) / 2f * 0.45f

        // Background circle
        circlePaint.color = when (state) {
            WaveState.IDLE -> 0xFF1E1E1E.toInt()
            WaveState.LISTENING -> 0xFF0D2E5E.toInt()
            WaveState.PROCESSING -> 0xFF1A1A2E.toInt()
            WaveState.SPEAKING -> 0xFF0D3B1F.toInt()
        }
        canvas.drawCircle(cx, cy, r, circlePaint)

        when (state) {
            WaveState.LISTENING -> drawSoundWaves(canvas, cx, cy, r)
            WaveState.PROCESSING -> drawRotatingArc(canvas, cx, cy, r)
            WaveState.SPEAKING -> drawSpeakerPulse(canvas, cx, cy, r)
            WaveState.IDLE -> drawMicIcon(canvas, cx, cy)
        }
    }

    private fun drawSoundWaves(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val numWaves = 3
        for (i in 0 until numWaves) {
            val amp = (20f + rmsLevel * 40f) * (1f - i * 0.25f)
            val freq = 2f + i * 0.5f
            val alpha = 255 - i * 60
            wavePaint.color = Color.argb(alpha, 21, 101, 192)
            val path = Path()
            val steps = 100
            for (step in 0..steps) {
                val x = cx - r + step * (2 * r / steps)
                val y = cy + amp * sin((step.toFloat() / steps * Math.PI * freq + phase + i * 0.5f).toFloat())
                if (step == 0) path.moveTo(x, y.toFloat()) else path.lineTo(x, y.toFloat())
            }
            canvas.drawPath(path, wavePaint)
        }
    }

    private fun drawRotatingArc(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val sweep = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 5f
            color = 0xFF1565C0.toInt()
        }
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, phase * 360f, 270f, false, sweep)
        textPaint.color = 0xFF757575.toInt()
        canvas.drawText("Zpracovávám...", cx, cy + 12f, textPaint)
    }

    private fun drawSpeakerPulse(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val pulse = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFF2E7D32.toInt()
        }
        val scale = 0.7f + 0.3f * sin(phase * Math.PI.toFloat() * 4)
        canvas.drawCircle(cx, cy, r * scale, pulse)
        canvas.drawCircle(cx, cy, r * scale * 0.6f, pulse)
    }

    private fun drawMicIcon(canvas: Canvas, cx: Float, cy: Float) {
        textPaint.color = 0xFF444444.toInt()
        textPaint.textSize = 48f
        canvas.drawText("🎤", cx, cy + 16f, textPaint)
        textPaint.textSize = 28f
    }

    fun setState(newState: WaveState) {
        state = newState
        animator?.cancel()
        if (newState != WaveState.IDLE) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = when (newState) { WaveState.LISTENING -> 800L; WaveState.PROCESSING -> 1500L; else -> 1000L }
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { phase = it.animatedValue as Float; invalidate() }
                start()
            }
        } else { invalidate() }
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); animator?.cancel() }
}
