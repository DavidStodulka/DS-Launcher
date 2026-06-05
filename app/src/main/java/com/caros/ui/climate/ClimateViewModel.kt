package com.caros.ui.climate

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.caros.can.CANWriter
import com.caros.can.ClimateCommand
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ClimateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val canWriter: CANWriter
) : ViewModel() {

    val targetTemp = MutableStateFlow(22.0f)
    val fanSpeed = MutableStateFlow(2)          // 0-7
    val acEnabled = MutableStateFlow(false)
    val recircEnabled = MutableStateFlow(false)
    val defrostEnabled = MutableStateFlow(false)
    val autoMode = MutableStateFlow(false)

    fun tempUp() { targetTemp.value = (targetTemp.value + 0.5f).coerceAtMost(30f); sendCommand() }
    fun tempDown() { targetTemp.value = (targetTemp.value - 0.5f).coerceAtLeast(16f); sendCommand() }
    fun fanUp() { fanSpeed.value = (fanSpeed.value + 1).coerceAtMost(7); sendCommand() }
    fun fanDown() { fanSpeed.value = (fanSpeed.value - 1).coerceAtLeast(0); sendCommand() }
    fun toggleAC() { acEnabled.value = !acEnabled.value; sendCommand() }
    fun toggleRecirc() { recircEnabled.value = !recircEnabled.value; sendCommand() }
    fun toggleDefrost() { defrostEnabled.value = !defrostEnabled.value; sendCommand() }
    fun toggleAuto() {
        autoMode.value = !autoMode.value
        if (autoMode.value) { targetTemp.value = 22f; fanSpeed.value = 3; acEnabled.value = true }
        sendCommand()
    }

    private fun sendCommand() {
        viewModelScope.launch {
            canWriter.sendClimateCommand(ClimateCommand(
                targetTemp = targetTemp.value,
                fanSpeed = fanSpeed.value,
                acOn = acEnabled.value,
                recircOn = recircEnabled.value,
                defrostOn = defrostEnabled.value
            ))
        }
    }
}
