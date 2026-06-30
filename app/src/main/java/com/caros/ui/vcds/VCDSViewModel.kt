package com.caros.ui.vcds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.caros.vcds.CodingPresets
import com.caros.vcds.DTCReader
import com.caros.vcds.ECUDatabase
import com.caros.vcds.PresetState
import com.caros.vcds.VCDSManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class VCDSViewModel @Inject constructor(
    val vcdsManager: VCDSManager
) : ViewModel() {

    // ── Delegated state from VCDSManager ──────────────────────────────────────

    val scanResults: StateFlow<List<DTCReader.ScanResult>> = vcdsManager.scanResults
    val isScanning: StateFlow<Boolean> = vcdsManager.isScanning
    val connectionState: StateFlow<com.caros.vcds.ConnectionType> = vcdsManager.connectionType

    // ── Preset states ─────────────────────────────────────────────────────────

    private val _presetStates = MutableStateFlow<List<PresetState>>(emptyList())
    val presetStates: StateFlow<List<PresetState>> = _presetStates.asStateFlow()

    // ── Freeze Frame ──────────────────────────────────────────────────────────

    private val _freezeFrame = MutableStateFlow<com.caros.vcds.FreezeFrame?>(null)
    val freezeFrame: StateFlow<com.caros.vcds.FreezeFrame?> = _freezeFrame.asStateFlow()

    fun loadFreezeFrame(ecuAddress: Int, dtc: com.caros.can.DTCCode) {
        viewModelScope.launch {
            _freezeFrame.value = vcdsManager.dtcReader.readFreezeFrame(ecuAddress, dtc)
        }
    }

    init {
        loadPresets()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun scanAll() {
        viewModelScope.launch {
            try {
                vcdsManager.scanAll()
            } catch (e: Exception) {
                Timber.e(e, "VCDSViewModel: scanAll failed")
            }
        }
    }

    fun togglePreset(preset: ECUDatabase.CodingPresetDef) {
        viewModelScope.launch {
            try {
                val success = vcdsManager.togglePreset(preset)
                if (success) {
                    loadPresets()
                }
            } catch (e: Exception) {
                Timber.e(e, "VCDSViewModel: togglePreset failed for ${preset.id}")
            }
        }
    }

    fun connect() {
        viewModelScope.launch {
            try {
                vcdsManager.connect()
            } catch (e: Exception) {
                Timber.e(e, "VCDSViewModel: connect failed")
            }
        }
    }

    fun clearAll(ecuAddress: Int) {
        viewModelScope.launch {
            try {
                vcdsManager.clearAndRescan(ecuAddress)
            } catch (e: Exception) {
                Timber.e(e, "VCDSViewModel: clearAll failed for ECU 0x%02X".format(ecuAddress))
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun loadPresets() {
        viewModelScope.launch {
            try {
                _presetStates.value = vcdsManager.codingPresets.getAllPresets()
            } catch (e: Exception) {
                Timber.w(e, "VCDSViewModel: loadPresets failed")
            }
        }
    }
}
