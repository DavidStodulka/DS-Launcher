package com.caros.ui.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.caros.audio.AudioProfile
import com.caros.audio.AudioProfileManager
import com.caros.audio.EQController
import com.caros.audio.ViperController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AudioViewModel @Inject constructor(
    private val audioProfileManager: AudioProfileManager,
    private val eqController: EQController,
    private val viperController: ViperController
) : ViewModel() {

    // ── State ─────────────────────────────────────────────────────────────────

    val activeProfile: StateFlow<AudioProfile> = audioProfileManager.activeProfile

    val profiles: StateFlow<List<AudioProfile>> = flowOf(audioProfileManager.loadProfiles())
        .stateIn(viewModelScope, SharingStarted.Eagerly, audioProfileManager.loadProfiles())

    val viperInstalled: Boolean = viperController.checkInstalled()

    // ── Public API ────────────────────────────────────────────────────────────

    fun applyProfile(profile: AudioProfile) {
        viewModelScope.launch {
            audioProfileManager.applyProfile(profile)
        }
    }

    fun setBand(band: Int, db: Float) {
        eqController.setBandLevel(band, db)
    }
}
