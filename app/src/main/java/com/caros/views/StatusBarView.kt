package com.caros.views

// ─────────────────────────────────────────────────────────────────────────────
//  StatusBarView.kt — CarOS custom top status bar (36dp height)
//
//  Displays: clock (HH:mm:ss), speed (km/h), coolant temp, battery voltage,
//  WiFi indicator, BT indicator, GPS indicator, service warning badge, ACC dot.
//  All drawn on Canvas for pixel-perfect automotive look.
// ─────────────────────────────────────────────────────────────────────────────

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.util.AttributeSet
import android.view.View
import com.caros.can.CANFrame
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StatusBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val LOG_TAG = "StatusBarView"

        // Color palette
        private const val COLOR_BG        = 0xFF080808.toInt()
        private const val COLOR_TEXT_PRI  = 0xFFFFFFFF.toInt()
        private const val COLOR_TEXT_SEC  = 0xFFAAAAAA.toInt()
        private const val COLOR_PRIMARY   = 0xFF1565C0.toInt()
        private const val COLOR_WARNING   = 0xFFF9A825.toInt()
        private const val COLOR_ERROR     = 0xFFC62828.toInt()
        private const val COLOR_OK        = 0xFF2E7D32.toInt()
        private const val COLOR_SECONDARY = 0xFFE65100.toInt()
        private const val COLOR_INACTIVE  = 0xFF444444.toInt()
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private var clockText    = "--:--:--"
    private var speedKmh     = 0f
    private var coolantC     = 0f
    private var batteryV     = 0f
    private var accOn        = false
    private var hasService   = false
    private var wifiLevel    = -1      // -1=off, 0-4 = rssi bars
    private var btConnected  = false
    private var gpsActive    = false
    private var voiceActive  = false
    private var mqttConnected = false
    private var obdConnected  = false
    private var rootStatus: com.caros.core.RootStatus = com.caros.core.RootStatus.UNKNOWN
    private var slopeDeg     = 0f
    private var altitudeM    = 0f

    private val clockFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // ── Paints ────────────────────────────────────────────────────────────────

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_BG
        style = Paint.Style.FILL
    }

    private val textPrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT_PRI
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    private val textSecPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT_SEC
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
    }

    private val rectF = RectF()

    // ── Sizing (computed once in onSizeChanged) ───────────────────────────────

    private var h = 0f
    private var baseLine = 0f
    private var smallTextSz = 0f
    private var medTextSz = 0f
    private var largeTextSz = 0f

    // ── Public API ────────────────────────────────────────────────────────────

    /** Call once per second from a coroutine to advance the clock. */
    fun tickClock() {
        clockText = clockFmt.format(Date())
        invalidate()
    }

    /** Full update from a decoded CAN frame. */
    fun update(frame: CANFrame) {
        speedKmh   = frame.vehicleSpeed?.kmh ?: speedKmh
        coolantC   = frame.coolantTemp?.celsius ?: coolantC
        batteryV   = frame.batteryVoltage?.volts ?: batteryV
        accOn      = frame.accState?.isOn ?: accOn
        hasService = frame.activeDtcs.isNotEmpty()
        invalidate()
    }

    /** Update connectivity indicators independently of CAN. */
    fun updateConnectivity(wifi: Int, bt: Boolean, gps: Boolean) {
        wifiLevel   = wifi
        btConnected = bt
        gpsActive   = gps
        invalidate()
    }

    /** Show/hide the mic/voice-active indicator. */
    fun setVoiceActive(active: Boolean) {
        voiceActive = active
        invalidate()
    }

    /** Show/hide the MQTT connected badge. */
    fun setMqttConnected(connected: Boolean) {
        mqttConnected = connected
        invalidate()
    }

    fun setRootStatus(status: com.caros.core.RootStatus) { rootStatus = status; invalidate() }

    /** Show the OBD adapter badge green when a real adapter is connected. */
    fun setObdConnected(connected: Boolean) {
        obdConnected = connected
        invalidate()
    }

    /** Update road slope in degrees (positive = uphill). */
    fun setSlope(degrees: Float) {
        slopeDeg = degrees
        invalidate()
    }

    /** Update GPS altitude in metres. */
    fun setAltitude(metres: Float) {
        altitudeM = metres
        invalidate()
    }

    // ── Measurement / layout ──────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        this.h     = h.toFloat()
        baseLine   = h * 0.72f
        smallTextSz = h * 0.38f
        medTextSz   = h * 0.50f
        largeTextSz = h * 0.62f
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()

        // Background
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val pad = h * 0.15f
        var x = pad

        // ── Clock (left anchor) ───────────────────────────────────────────────
        textPrimPaint.textSize = largeTextSz
        canvas.drawText(clockText, x, baseLine, textPrimPaint)
        x += textPrimPaint.measureText(clockText) + pad * 2

        // ── Speed ─────────────────────────────────────────────────────────────
        val speedStr = "${speedKmh.toInt()}"
        textSecPaint.textSize = smallTextSz
        textPrimPaint.textSize = medTextSz
        val speedW = textPrimPaint.measureText(speedStr)
        canvas.drawText(speedStr, x, baseLine, textPrimPaint)
        textSecPaint.textSize = smallTextSz
        canvas.drawText("km/h", x + speedW + 2f, baseLine, textSecPaint)
        x += speedW + textSecPaint.measureText("km/h") + pad * 3

        // ── Coolant ───────────────────────────────────────────────────────────
        textSecPaint.textSize = smallTextSz
        val coolStr = "CLT ${coolantC.toInt()}°C"
        val coolColor = when {
            coolantC >= 110f -> COLOR_ERROR
            coolantC >= 95f  -> COLOR_WARNING
            coolantC < 60f   -> COLOR_TEXT_SEC
            else             -> COLOR_OK
        }
        textSecPaint.color = coolColor
        canvas.drawText(coolStr, x, baseLine, textSecPaint)
        x += textSecPaint.measureText(coolStr) + pad * 2
        textSecPaint.color = COLOR_TEXT_SEC

        // ── Battery voltage ───────────────────────────────────────────────────
        val battStr = "${"%.1f".format(batteryV)}V"
        val battColor = when {
            batteryV < 11.5f -> COLOR_ERROR
            batteryV < 12.5f -> COLOR_WARNING
            batteryV > 14.8f -> COLOR_WARNING
            else             -> COLOR_TEXT_SEC
        }
        textSecPaint.color = battColor
        canvas.drawText(battStr, x, baseLine, textSecPaint)
        x += textSecPaint.measureText(battStr) + pad * 2
        textSecPaint.color = COLOR_TEXT_SEC

        // ── Right-side icons: work right to left from end ─────────────────────
        var rx = w - pad

        // ACC dot
        val dotR = h * 0.20f
        val dotY = h / 2f
        dotPaint.color = if (accOn) COLOR_OK else COLOR_INACTIVE
        rx -= dotR
        canvas.drawCircle(rx, dotY, dotR, dotPaint)
        rx -= dotR + pad

        // Service badge
        if (hasService) {
            dotPaint.color = COLOR_ERROR
            canvas.drawCircle(rx - dotR, dotY, dotR * 1.2f, dotPaint)
            textPrimPaint.textSize = smallTextSz * 0.8f
            textPrimPaint.color = COLOR_TEXT_PRI
            textPrimPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("!", rx - dotR, dotY + smallTextSz * 0.28f, textPrimPaint)
            textPrimPaint.textAlign = Paint.Align.LEFT
            textPrimPaint.color = COLOR_TEXT_PRI
            rx -= dotR * 2.5f + pad
        }

        // GPS dot
        dotPaint.color = if (gpsActive) COLOR_OK else COLOR_INACTIVE
        rx -= dotR
        canvas.drawCircle(rx, dotY, dotR, dotPaint)
        textSecPaint.textSize = smallTextSz * 0.65f
        textSecPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("GPS", rx, dotY + smallTextSz * 0.22f, textSecPaint)
        textSecPaint.textAlign = Paint.Align.LEFT
        rx -= dotR + pad * 2

        // BT dot
        dotPaint.color = if (btConnected) COLOR_PRIMARY else COLOR_INACTIVE
        rx -= dotR
        canvas.drawCircle(rx, dotY, dotR, dotPaint)
        textSecPaint.textSize = smallTextSz * 0.55f
        textSecPaint.textAlign = Paint.Align.CENTER
        textSecPaint.color = if (btConnected) COLOR_TEXT_PRI else COLOR_TEXT_SEC
        canvas.drawText("BT", rx, dotY + smallTextSz * 0.20f, textSecPaint)
        textSecPaint.textAlign = Paint.Align.LEFT
        textSecPaint.color = COLOR_TEXT_SEC
        rx -= dotR + pad * 2

        // WiFi bars
        drawWifiBars(canvas, rx, dotY, dotR * 2.5f, dotR * 2.5f)
        rx -= dotR * 3f + pad * 2

        // MQTT badge
        dotPaint.color = if (mqttConnected) COLOR_OK else COLOR_INACTIVE
        rx -= dotR
        canvas.drawCircle(rx, dotY, dotR, dotPaint)
        textSecPaint.textSize = smallTextSz * 0.55f
        textSecPaint.textAlign = Paint.Align.CENTER
        textSecPaint.color = if (mqttConnected) COLOR_TEXT_PRI else COLOR_TEXT_SEC
        canvas.drawText("MQ", rx, dotY + smallTextSz * 0.20f, textSecPaint)
        textSecPaint.textAlign = Paint.Align.LEFT
        textSecPaint.color = COLOR_TEXT_SEC
        rx -= dotR + pad * 2

        // OBD adapter badge
        dotPaint.color = if (obdConnected) COLOR_OK else COLOR_INACTIVE
        rx -= dotR
        canvas.drawCircle(rx, dotY, dotR, dotPaint)
        textSecPaint.textSize = smallTextSz * 0.45f
        textSecPaint.textAlign = Paint.Align.CENTER
        textSecPaint.color = if (obdConnected) COLOR_TEXT_PRI else COLOR_TEXT_SEC
        canvas.drawText("OBD", rx, dotY + smallTextSz * 0.16f, textSecPaint)
        textSecPaint.textAlign = Paint.Align.LEFT
        textSecPaint.color = COLOR_TEXT_SEC
        rx -= dotR + pad * 2

        // Root badge
        if (rootStatus != com.caros.core.RootStatus.UNKNOWN) {
            dotPaint.color = when (rootStatus) {
                com.caros.core.RootStatus.AVAILABLE   -> COLOR_OK
                com.caros.core.RootStatus.DENIED      -> COLOR_ERROR
                com.caros.core.RootStatus.UNAVAILABLE -> COLOR_INACTIVE
                com.caros.core.RootStatus.UNKNOWN     -> COLOR_INACTIVE
            }
            rx -= dotR
            canvas.drawCircle(rx, dotY, dotR, dotPaint)
            textSecPaint.textSize = smallTextSz * 0.55f
            textSecPaint.textAlign = Paint.Align.CENTER
            textSecPaint.color = when (rootStatus) {
                com.caros.core.RootStatus.AVAILABLE -> COLOR_TEXT_PRI
                else -> COLOR_TEXT_SEC
            }
            canvas.drawText("R", rx, dotY + smallTextSz * 0.20f, textSecPaint)
            textSecPaint.textAlign = Paint.Align.LEFT
            textSecPaint.color = COLOR_TEXT_SEC
            rx -= dotR + pad * 2
        }

        // Voice / mic indicator
        if (voiceActive) {
            dotPaint.color = COLOR_SECONDARY
            rx -= dotR
            canvas.drawCircle(rx, dotY, dotR * 1.1f, dotPaint)
            textPrimPaint.textSize = smallTextSz * 0.7f
            textPrimPaint.color = COLOR_TEXT_PRI
            textPrimPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("M", rx, dotY + smallTextSz * 0.25f, textPrimPaint)
            textPrimPaint.textAlign = Paint.Align.LEFT
            textPrimPaint.color = COLOR_TEXT_PRI
            rx -= dotR + pad * 2
        }

        // Slope and altitude (left side, after battery voltage — already drawn above)
        // Displayed as small text in the left block
        if (slopeDeg != 0f || altitudeM != 0f) {
            val slopeStr = "%+.1f°".format(slopeDeg)
            val altStr   = "%.0fm".format(altitudeM)
            textSecPaint.textSize = smallTextSz * 0.75f
            textSecPaint.color = COLOR_TEXT_SEC
            canvas.drawText("$slopeStr  $altStr", x, baseLine, textSecPaint)
        }
    }

    /** Draws 4 ascending bars representing WiFi signal strength. */
    private fun drawWifiBars(canvas: Canvas, cx: Float, cy: Float, totalW: Float, totalH: Float) {
        val barCount = 4
        val barW = totalW / (barCount * 2f - 1f)
        val gap = barW
        val left = cx - totalW / 2f
        for (i in 0 until barCount) {
            val barH = totalH * ((i + 1).toFloat() / barCount)
            val bx = left + i * (barW + gap)
            val by = cy + totalH / 2f
            val isActive = wifiLevel >= i && wifiLevel >= 0
            dotPaint.color = if (isActive) COLOR_PRIMARY else COLOR_INACTIVE
            rectF.set(bx, by - barH, bx + barW, by)
            canvas.drawRoundRect(rectF, 1f, 1f, dotPaint)
        }
    }

    init {
        Timber.tag(LOG_TAG).d("StatusBarView initialised")
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }
}
