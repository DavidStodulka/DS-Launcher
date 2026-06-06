package com.caros.audio

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
enum class AudioSource { BT_PHONE, USB_MEDIA, FM_RADIO, STREAMING, UNKNOWN }

@Singleton
class AdaptiveEQEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engineManager: AudioEngineManager
) {
    // BANDS: 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz
    private val BASS_PREFERENCE = floatArrayOf(3.0f, 4.0f, 2.5f, 1.0f, 0f, 0f, 0f, 0f, 0f, 0f)

    private val currentGains = FloatArray(10)  // smoothed current state
    private var userOffset = FloatArray(10)     // manual user additions

    private val _isEnabled = MutableStateFlow(true)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()
    fun setEnabled(enabled: Boolean) { _isEnabled.value = enabled }

    var currentSource: AudioSource = AudioSource.UNKNOWN

    // Output flow: emits final 10-band float array every 2 seconds
    val eqFlow: Flow<FloatArray> = flow {
        while (true) {
            if (_isEnabled.value) {
                val target = computeTargetGains()
                // Smooth: newGain = old * 0.85 + target * 0.15
                for (i in 0..9) {
                    currentGains[i] = currentGains[i] * 0.85f + target[i] * 0.15f
                }
                val final = FloatArray(10) { i ->
                    (currentGains[i] + userOffset[i]).coerceIn(-12f, 12f)
                }
                engineManager.applyFullEQ(final)
                emit(final)
            }
            delay(2000)
        }
    }

    // auto-EQ target without user offset and without smoothing
    val autoGainsFlow: Flow<FloatArray> = flow {
        while (true) {
            if (isEnabled) emit(computeTargetGains())
            delay(2000)
        }
    }

    private var lastSpeed = 0f
    private var lastVolume = 7
    private var lastIsParked = true

    fun updateDrivingState(speed: Float, volume: Int, isParked: Boolean) {
        lastSpeed = speed; lastVolume = volume; lastIsParked = isParked
    }

    private fun computeTargetGains(): FloatArray {
        val result = FloatArray(10)
        val speedFactor = (lastSpeed / 130f).coerceIn(0f, 1f)
        val volumeFactor = 1f - (lastVolume / 15f).coerceIn(0f, 1f)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val isNight = hour >= 22 || lastVolume < 5

        // Layer 1: bass preference (always)
        for (i in 0..9) result[i] += BASS_PREFERENCE[i]

        // Layer 2: road noise compensation
        if (!lastIsParked) {
            result[0] -= speedFactor * 2.0f
            result[1] -= speedFactor * 1.0f
            result[2] -= speedFactor * 0.5f
            // result[3] += 0f
            result[4] += speedFactor * 0.5f
            result[5] += speedFactor * 1.5f
            result[6] += speedFactor * 2.0f
            result[7] += speedFactor * 1.0f
            result[8] += speedFactor * 0.5f
            result[9] += speedFactor * 0.5f
        }

        // Layer 3: volume loudness (Fletcher-Munson)
        result[0] += volumeFactor * 3.0f
        result[1] += volumeFactor * 2.0f
        result[2] += volumeFactor * 1.0f
        result[8] += volumeFactor * 1.0f
        result[9] += volumeFactor * 2.0f

        // Layer 4: audio source profile
        when (currentSource) {
            AudioSource.BT_PHONE -> {
                result[3] += 1.5f; result[5] += 2.0f; result[6] += 1.5f; result[7] += 1.0f
            }
            AudioSource.FM_RADIO -> {
                result[1] -= 1.0f; result[2] -= 0.5f; result[7] += 1.0f; result[8] += 1.5f
            }
            AudioSource.STREAMING -> {
                result[0] += 0.5f; result[8] += 0.5f; result[9] += 1.0f
            }
            AudioSource.USB_MEDIA, AudioSource.UNKNOWN -> { /* flat reference */ }
        }

        // Layer 5: night compensation
        if (isNight) {
            result[0] -= 1.0f; result[1] -= 0.5f
            result[5] += 0.5f; result[6] += 1.0f
        }

        // Clamp
        for (i in 0..9) result[i] = result[i].coerceIn(-12f, 12f)
        return result
    }

    fun setUserOffset(band: Int, offsetDb: Float) {
        if (band in 0..9) userOffset[band] = offsetDb.coerceIn(-12f, 12f)
    }

    fun resetUserOffsets() { userOffset = FloatArray(10) }
    fun getUserOffsets(): FloatArray = userOffset.copyOf()
    fun getCurrentAutoGains(): FloatArray = currentGains.copyOf()
}
