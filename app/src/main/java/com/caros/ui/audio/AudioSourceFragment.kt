package com.caros.ui.audio

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class AudioSourceFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF080808.toInt())
            setPadding(16.dp, 16.dp, 16.dp, 16.dp)
            addView(TextView(context).apply {
                text = "Audio zdroj"; textSize = 18f; setTextColor(0xFFFFFFFF.toInt())
                setPadding(0, 0, 0, 16.dp)
            })
            listOf("Bluetooth", "USB", "FM Rádio", "Streamování").forEach { source ->
                addView(Button(context).apply {
                    text = source
                    setBackgroundColor(0xFF111111.toInt())
                    setTextColor(0xFFFFFFFF.toInt())
                    layoutParams = LinearLayout.LayoutParams(-1, 64.dp).also { it.setMargins(0, 4.dp, 0, 4.dp) }
                })
            }
        }
    }
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
