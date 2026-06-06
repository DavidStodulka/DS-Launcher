package com.caros.multimedia

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FMRadioViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fmController: FMController
) : ViewModel() {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("fm_prefs", Context.MODE_PRIVATE)

    val frequency: MutableStateFlow<Float> = MutableStateFlow(
        prefs.getFloat("last_frequency", 87.5f)
    )
    val rdsText: MutableStateFlow<String> = MutableStateFlow("")
    val isPlaying: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val presets: MutableStateFlow<List<Float>> = MutableStateFlow(loadPresetsFromPrefs())
    val fanSpeed: MutableStateFlow<Int> = MutableStateFlow(0) // 0=auto, 1=low, 2=medium, 3=high
    val cpuTemp: MutableStateFlow<Float> = MutableStateFlow(0f)

    init {
        // Sync with FMController state
        viewModelScope.launch {
            fmController.currentFreq.collect { freq ->
                frequency.value = freq
                prefs.edit().putFloat("last_frequency", freq).apply()
            }
        }
        viewModelScope.launch {
            fmController.isPlaying.collect { isPlaying.value = it }
        }
        viewModelScope.launch {
            fmController.rdsText.collect { rdsText.value = it }
        }
        startCpuTempPolling()
    }

    fun play() {
        fmController.play()
    }

    fun stop() {
        fmController.stop()
    }

    fun stepUp() {
        fmController.stepUp()
    }

    fun stepDown() {
        fmController.stepDown()
    }

    fun scanUp() {
        fmController.scanUp()
    }

    fun scanDown() {
        fmController.scanDown()
    }

    fun tuneToFreq(freq: Float) {
        fmController.tuneToFreq(freq)
    }

    fun savePreset(slot: Int) {
        if (slot !in 0 until FMController.PRESET_COUNT) return
        fmController.savePreset(slot)
        val updated = presets.value.toMutableList()
        updated[slot] = frequency.value
        presets.value = updated
        savePresetsToPrefs(updated)
    }

    fun loadPreset(slot: Int) {
        if (slot !in 0 until FMController.PRESET_COUNT) return
        fmController.recallPreset(slot)
    }

    fun setFanSpeed(speed: Int) {
        fanSpeed.value = speed
        applyFanSpeed(speed)
    }

    private fun applyFanSpeed(speed: Int) {
        val duty = when (speed) {
            0 -> "auto"
            1 -> "100"
            2 -> "180"
            3 -> "255"
            else -> "auto"
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (duty == "auto") {
                    ProcessBuilder("su", "-c", "echo 0 > /sys/class/hwmon/hwmon0/pwm1_enable").start().waitFor()
                } else {
                    ProcessBuilder("su", "-c", "echo 1 > /sys/class/hwmon/hwmon0/pwm1_enable && echo $duty > /sys/class/hwmon/hwmon0/pwm1").start().waitFor()
                }
            } catch (_: Exception) {}
        }
    }

    private fun startCpuTempPolling() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val raw = java.io.File("/sys/class/thermal/thermal_zone0/temp").readText().trim()
                    val tempC = raw.toLong() / 1000f
                    cpuTemp.value = tempC
                } catch (_: Exception) {}
                delay(10_000L)
            }
        }
    }

    private fun loadPresetsFromPrefs(): List<Float> {
        val controllerPresets = fmController.getPresets()
        return List(FMController.PRESET_COUNT) { i ->
            val saved = prefs.getFloat("preset_$i", 0f)
            if (saved > 0f) saved else controllerPresets[i]
        }
    }

    private fun savePresetsToPrefs(presetList: List<Float>) {
        val editor = prefs.edit()
        presetList.forEachIndexed { i, freq -> editor.putFloat("preset_$i", freq) }
        editor.apply()
    }
}
