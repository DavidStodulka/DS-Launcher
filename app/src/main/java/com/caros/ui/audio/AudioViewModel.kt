package com.caros.ui.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.caros.audio.AdaptiveEQEngine
import com.caros.audio.AudioEngineManager
import com.caros.audio.AudioProfile
import com.caros.audio.AudioProfileManager
import com.caros.audio.EQController
import com.caros.audio.ViperController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AudioViewModel @Inject constructor(
    private val audioProfileManager: AudioProfileManager,
    private val eqController: EQController,
    private val viperController: ViperController,
    private val adaptiveEQEngine: AdaptiveEQEngine,
    val engineManager: AudioEngineManager
) : ViewModel() {

    // ── State ─────────────────────────────────────────────────────────────────

    val activeProfile: StateFlow<AudioProfile> = audioProfileManager.activeProfile

    /** Currently active profile, exposed as MutableStateFlow for UI highlighting. */
    val currentProfile: MutableStateFlow<AudioProfile> = MutableStateFlow(AudioProfile.FLAT)

    val profiles: StateFlow<List<AudioProfile>> = flowOf(audioProfileManager.loadProfiles())
        .stateIn(viewModelScope, SharingStarted.Eagerly, audioProfileManager.loadProfiles())

    val viperInstalled: Boolean = viperController.checkInstalled()

    /** Smoothed auto-EQ gains from AdaptiveEQEngine (10-band FloatArray), emitted every 2 s. */
    val autoEQGains: StateFlow<FloatArray> = adaptiveEQEngine.eqFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FloatArray(10))

    /** Whether adaptive EQ is currently active. */
    private val _autoEQEnabled = MutableStateFlow(adaptiveEQEngine.isEnabled.value)
    val autoEQEnabled: StateFlow<Boolean> = _autoEQEnabled.asStateFlow()

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        // Keep currentProfile in sync with the profile manager's state.
        viewModelScope.launch {
            audioProfileManager.activeProfile.collect { profile ->
                currentProfile.value = profile
            }
        }
        // Drive the adaptive EQ flow collection so it stays alive while the ViewModel is alive.
        viewModelScope.launch {
            adaptiveEQEngine.eqFlow.collect { /* consumption handled by stateIn above */ }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun applyProfile(profile: AudioProfile) {
        viewModelScope.launch {
            audioProfileManager.applyProfile(profile)
            engineManager.setProfile(profile)
            // Sync adaptive EQ enabled state from profile
            adaptiveEQEngine.setEnabled(profile.autoEQEnabled)
            _autoEQEnabled.value = profile.autoEQEnabled
        }
    }

    fun setProfile(profile: AudioProfile) = applyProfile(profile)

    fun setBand(band: Int, db: Float) {
        eqController.setBandLevel(band, db)
        engineManager.setEQBand(band, db)
    }

    fun toggleAutoEQ() {
        val newState = !adaptiveEQEngine.isEnabled.value
        adaptiveEQEngine.setEnabled(newState)
        _autoEQEnabled.value = newState
    }

    fun setUserBandOffset(band: Int, db: Float) {
        adaptiveEQEngine.setUserOffset(band, db)
    }

    fun resetUserOffsets() {
        adaptiveEQEngine.resetUserOffsets()
    }

    fun updateDrivingState(speed: Float, volume: Int, isParked: Boolean) {
        adaptiveEQEngine.updateDrivingState(speed, volume, isParked)
    }

    fun setViperEnabled(enabled: Boolean) {
        viperController.setEnabled(enabled)
    }

    fun getInstallInstructions(): String = viperController.getInstallInstructions()
}
