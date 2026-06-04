package com.caros.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class ACControlView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val tvSetTemp: TextView
    private val tvInteriorTemp: TextView
    private val tvFanSpeed: TextView
    private val btnTempUp: ImageButton
    private val btnTempDown: ImageButton
    private val btnAC: ImageButton
    private val btnRecirc: ImageButton
    private val fanBars: List<ImageView>

    private var setTemp = 22f
    private var acOn = false
    private var recircOn = false
    private var fanSpeed = 3

    var onTempChanged: ((Float) -> Unit)? = null
    var onACToggle: ((Boolean) -> Unit)? = null
    var onRecircToggle: ((Boolean) -> Unit)? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFF111111.toInt())
        setPadding(8.dp, 8.dp, 8.dp, 8.dp)

        // Temp row
        val tempRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        btnTempDown = ImageButton(context).apply {
            setImageResource(android.R.drawable.arrow_down_float)
            setBackgroundColor(0xFF1E1E1E.toInt())
            minimumWidth = 64.dp; minimumHeight = 64.dp
            setOnClickListener { adjustTemp(-0.5f) }
        }
        tvSetTemp = TextView(context).apply {
            text = "22.0°C"; textSize = 20f; setTextColor(0xFFFFFFFF.toInt())
            gravity = android.view.Gravity.CENTER; minWidth = 120.dp
        }
        btnTempUp = ImageButton(context).apply {
            setImageResource(android.R.drawable.arrow_up_float)
            setBackgroundColor(0xFF1E1E1E.toInt())
            minimumWidth = 64.dp; minimumHeight = 64.dp
            setOnClickListener { adjustTemp(0.5f) }
        }
        tempRow.addView(btnTempDown); tempRow.addView(tvSetTemp); tempRow.addView(btnTempUp)
        addView(tempRow)

        // Interior temp
        tvInteriorTemp = TextView(context).apply {
            text = "Int: --°C"; textSize = 13f; setTextColor(0xFF757575.toInt()); gravity = android.view.Gravity.CENTER
        }
        addView(tvInteriorTemp)

        // Fan speed row
        val fanRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = android.view.Gravity.CENTER; setPadding(0, 4.dp, 0, 4.dp) }
        tvFanSpeed = TextView(context).apply { text = "Fan: 3"; textSize = 13f; setTextColor(0xFF757575.toInt()); setPadding(0, 0, 8.dp, 0) }
        fanRow.addView(tvFanSpeed)
        val mutableBars = mutableListOf<ImageView>()
        for (i in 1..7) {
            val bar = ImageView(context).apply {
                layoutParams = LayoutParams(8.dp, 16 + i * 3.dp).also { it.setMargins(2.dp, 0, 2.dp, 0) }
                setBackgroundColor(if (i <= fanSpeed) 0xFF1565C0.toInt() else 0xFF2A2A2A.toInt())
            }
            mutableBars.add(bar); fanRow.addView(bar)
        }
        fanBars = mutableBars
        addView(fanRow)

        // AC + Recirc row
        val ctrlRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = android.view.Gravity.CENTER; setPadding(0, 4.dp, 0, 0) }
        btnAC = ImageButton(context).apply {
            text@ setBackgroundColor(0xFF1E1E1E.toInt())
            minimumWidth = 64.dp; minimumHeight = 64.dp
            contentDescription = "A/C"
            setOnClickListener { acOn = !acOn; updateACButton(); onACToggle?.invoke(acOn) }
        }
        btnRecirc = ImageButton(context).apply {
            setBackgroundColor(0xFF1E1E1E.toInt())
            minimumWidth = 64.dp; minimumHeight = 64.dp
            contentDescription = "Recirc"
            setOnClickListener { recircOn = !recircOn; updateRecircButton(); onRecircToggle?.invoke(recircOn) }
        }
        ctrlRow.addView(btnAC); ctrlRow.addView(btnRecirc)
        addView(ctrlRow)
        updateACButton(); updateRecircButton()
    }

    private fun adjustTemp(delta: Float) {
        setTemp = (setTemp + delta).coerceIn(16f, 30f)
        tvSetTemp.text = String.format("%.1f°C", setTemp)
        onTempChanged?.invoke(setTemp)
    }

    private fun updateACButton() {
        btnAC.setBackgroundColor(if (acOn) 0xFF1565C0.toInt() else 0xFF1E1E1E.toInt())
    }
    private fun updateRecircButton() {
        btnRecirc.setBackgroundColor(if (recircOn) 0xFF2E7D32.toInt() else 0xFF1E1E1E.toInt())
    }

    fun update(setTempC: Float, interiorTempC: Float, fanSpd: Int, ac: Boolean, recirc: Boolean) {
        setTemp = setTempC; tvSetTemp.text = String.format("%.1f°C", setTempC)
        tvInteriorTemp.text = String.format("Int: %.1f°C", interiorTempC)
        fanSpeed = fanSpd.coerceIn(0, 7); tvFanSpeed.text = "Fan: $fanSpeed"
        fanBars.forEachIndexed { i, bar -> bar.setBackgroundColor(if (i < fanSpeed) 0xFF1565C0.toInt() else 0xFF2A2A2A.toInt()) }
        acOn = ac; updateACButton()
        recircOn = recirc; updateRecircButton()
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
}

// Extension to avoid "text@" confusion
private fun android.widget.ImageButton.setLabel(text: String) {
    contentDescription = text
}
