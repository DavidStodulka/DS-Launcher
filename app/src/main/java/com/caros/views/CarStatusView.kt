package com.caros.views

// ─────────────────────────────────────────────────────────────────────────────
//  CarStatusView.kt — Top-view car silhouette with door/trunk/seatbelt indicators
//
//  Draws a simplified top-view car body (rectangles + rounded corners).
//  5 door positions (FL, FR, RL, RR, Trunk): green=closed, red=open, orange=ajar.
//  4 seatbelt dot indicators.
// ─────────────────────────────────────────────────────────────────────────────

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.caros.can.DoorState
import com.caros.can.SeatbeltState
import timber.log.Timber

class CarStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val LOG_TAG = "CarStatusView"

        private const val COLOR_BG       = 0xFF080808.toInt()
        private const val COLOR_CAR_BODY = 0xFF1E1E1E.toInt()
        private const val COLOR_CAR_LINE = 0xFF444444.toInt()
        private const val COLOR_CLOSED   = 0xFF2E7D32.toInt()  // green
        private const val COLOR_AJAR     = 0xFFE65100.toInt()  // orange
        private const val COLOR_OPEN     = 0xFFC62828.toInt()  // red
        private const val COLOR_BELT_ON  = 0xFF2E7D32.toInt()
        private const val COLOR_BELT_OFF = 0xFF444444.toInt()
        private const val COLOR_TEXT     = 0xFFAAAAAA.toInt()

        /** Door tri-state: 0=closed, 1=ajar (treated as open here), 2=open */
        private fun doorColor(open: Boolean) = if (open) COLOR_OPEN else COLOR_CLOSED
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private var doorState     = DoorState(false, false, false, false, false)
    private var seatbeltState = SeatbeltState(false, false, false, false)

    // ── Geometry (computed in onSizeChanged) ──────────────────────────────────

    // Car body RectF
    private val bodyRect   = RectF()
    // Roof area
    private val roofRect   = RectF()
    // Window areas
    private val windshield = RectF()
    private val rearScreen = RectF()
    // Door indicator rects: FL, FR, RL, RR, Trunk
    private val doorFL = RectF()
    private val doorFR = RectF()
    private val doorRL = RectF()
    private val doorRR = RectF()
    private val doorTrunk = RectF()
    // Belt dot positions
    private val beltPositions = Array(4) { Pair(0f, 0f) }
    private var beltRadius = 0f

    // ── Paints ────────────────────────────────────────────────────────────────

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_BG
        style = Paint.Style.FILL
    }

    private val carBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_CAR_BODY
        style = Paint.Style.FILL
    }

    private val carLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = COLOR_CAR_LINE
        style       = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val doorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val doorBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = COLOR_BG
        style       = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val beltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = COLOR_TEXT
        textAlign = Paint.Align.CENTER
        typeface  = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun update(doorState: DoorState, seatbeltState: SeatbeltState) {
        this.doorState     = doorState
        this.seatbeltState = seatbeltState
        invalidate()
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        val density = resources.displayMetrics.density
        labelPaint.textSize = 9f * density

        // Car body centered, portrait proportions for a top-view
        val carW = w * 0.38f
        val carH = h * 0.72f
        val carL = (w - carW) / 2f
        val carT = h * 0.06f
        bodyRect.set(carL, carT, carL + carW, carT + carH)

        // Roof is inner rect (slightly inset from body)
        roofRect.set(bodyRect.left + carW * 0.08f, bodyRect.top + carH * 0.22f,
            bodyRect.right - carW * 0.08f, bodyRect.bottom - carH * 0.22f)

        // Windshield / rear screen
        windshield.set(bodyRect.left + carW * 0.06f, bodyRect.top + carH * 0.06f,
            bodyRect.right - carW * 0.06f, bodyRect.top + carH * 0.22f)
        rearScreen.set(bodyRect.left + carW * 0.06f, bodyRect.bottom - carH * 0.22f,
            bodyRect.right - carW * 0.06f, bodyRect.bottom - carH * 0.06f)

        // Door indicator rects — placed on sides of the body
        val doorW  = carW * 0.26f
        val doorH  = carH * 0.20f
        val doorGap = carW * 0.07f

        // FL (front left, left side, upper)
        doorFL.set(bodyRect.left - doorW - doorGap,
            bodyRect.top + carH * 0.20f,
            bodyRect.left - doorGap,
            bodyRect.top + carH * 0.20f + doorH)

        // FR (front right, right side, upper)
        doorFR.set(bodyRect.right + doorGap,
            bodyRect.top + carH * 0.20f,
            bodyRect.right + doorGap + doorW,
            bodyRect.top + carH * 0.20f + doorH)

        // RL (rear left)
        doorRL.set(bodyRect.left - doorW - doorGap,
            bodyRect.top + carH * 0.50f,
            bodyRect.left - doorGap,
            bodyRect.top + carH * 0.50f + doorH)

        // RR (rear right)
        doorRR.set(bodyRect.right + doorGap,
            bodyRect.top + carH * 0.50f,
            bodyRect.right + doorGap + doorW,
            bodyRect.top + carH * 0.50f + doorH)

        // Trunk (bottom center)
        doorTrunk.set(bodyRect.centerX() - carW * 0.25f,
            bodyRect.bottom + doorGap,
            bodyRect.centerX() + carW * 0.25f,
            bodyRect.bottom + doorGap + doorW * 0.55f)

        // Seatbelt positions: FL, FR, RL, RR inside roof rect
        beltRadius = carW * 0.075f
        val midFront = roofRect.top + roofRect.height() * 0.22f
        val midRear  = roofRect.top + roofRect.height() * 0.68f
        val beltInset = roofRect.width() * 0.22f
        beltPositions[0] = Pair(roofRect.left + beltInset, midFront)   // FL
        beltPositions[1] = Pair(roofRect.right - beltInset, midFront)  // FR
        beltPositions[2] = Pair(roofRect.left + beltInset, midRear)    // RL
        beltPositions[3] = Pair(roofRect.right - beltInset, midRear)   // RR

        carLinePaint.strokeWidth = density * 1.5f
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Car body
        canvas.drawRoundRect(bodyRect, bodyRect.width() * 0.18f, bodyRect.width() * 0.18f, carBodyPaint)
        canvas.drawRoundRect(bodyRect, bodyRect.width() * 0.18f, bodyRect.width() * 0.18f, carLinePaint)

        // Windshield
        carLinePaint.style = Paint.Style.FILL_AND_STROKE
        canvas.drawRect(windshield, carLinePaint)
        carLinePaint.style = Paint.Style.STROKE

        // Rear screen
        canvas.drawRect(rearScreen, carLinePaint)

        // Roof
        canvas.drawRoundRect(roofRect, roofRect.width() * 0.08f, roofRect.width() * 0.08f, carLinePaint)

        // Door FL
        drawDoor(canvas, doorFL, doorState.driver, "FL")
        // Door FR
        drawDoor(canvas, doorFR, doorState.passenger, "FR")
        // Door RL
        drawDoor(canvas, doorRL, doorState.rearLeft, "RL")
        // Door RR
        drawDoor(canvas, doorRR, doorState.rearRight, "RR")
        // Trunk
        drawDoor(canvas, doorTrunk, doorState.trunk, "TRK")

        // Seatbelt dots
        val belts = listOf(seatbeltState.driver, seatbeltState.passenger,
            seatbeltState.rearLeft, seatbeltState.rearRight)
        for (i in belts.indices) {
            beltPaint.color = if (belts[i]) COLOR_BELT_ON else COLOR_BELT_OFF
            canvas.drawCircle(beltPositions[i].first, beltPositions[i].second, beltRadius, beltPaint)
        }
    }

    private fun drawDoor(canvas: Canvas, rect: RectF, isOpen: Boolean, label: String) {
        doorPaint.color = doorColor(isOpen)
        canvas.drawRoundRect(rect, 3f, 3f, doorPaint)
        canvas.drawRoundRect(rect, 3f, 3f, doorBorderPaint)
        canvas.drawText(label, rect.centerX(), rect.centerY() + labelPaint.textSize * 0.35f, labelPaint)
    }

    init {
        Timber.tag(LOG_TAG).d("CarStatusView initialised")
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }
}
