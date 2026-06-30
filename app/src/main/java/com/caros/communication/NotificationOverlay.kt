package com.caros.communication

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationOverlay @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    fun showToast(message: String, durationMs: Long = 3000L) {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 80
        }
        val tv = TextView(context).apply {
            text = message
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xCC1565C0.toInt())
            setPadding(32, 16, 32, 16)
            textSize = 13f
        }
        handler.post {
            try {
                windowManager.addView(tv, params)
                handler.postDelayed({ runCatching { windowManager.removeView(tv) } }, durationMs)
            } catch (_: Exception) {}
        }
    }
}
