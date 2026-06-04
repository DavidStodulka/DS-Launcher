package com.caros.vcds

// ─────────────────────────────────────────────────────────────────────────────
//  VCDSManager.kt — Top-level façade for the VCDS module.
//  Aggregates OBDConnection, DTCReader, CodingPresets, and CodingHistory
//  and exposes observable StateFlows that the UI layer consumes.
// ─────────────────────────────────────────────────────────────────────────────

import com.caros.can.DTCCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VCDSManager @Inject constructor(
    /** Raw OBD/UDS connection — exposed so callers can send custom commands. */
    val obdConnection: OBDConnection,
    /** Reads and clears DTCs from all ECUs. */
    val dtcReader: DTCReader,
    /** Reads, toggles, and undoes coding presets. */
    val codingPresets: CodingPresets,
    /** Read-only view of the coding change history. */
    val codingHistory: CodingHistory
) {
    // ── Exposed state ─────────────────────────────────────────────────────────

    private val _scanResults = MutableStateFlow<List<DTCReader.ScanResult>>(emptyList())
    /** Most recent full-vehicle DTC scan result. */
    val scanResults: StateFlow<List<DTCReader.ScanResult>> = _scanResults

    private val _isScanning = MutableStateFlow(false)
    /** True while a full-vehicle scan is in progress. */
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _connectionType = MutableStateFlow(ConnectionType.DISCONNECTED)
    /** Current OBD adapter connection type. */
    val connectionType: StateFlow<ConnectionType> = _connectionType

    private val _presetStates = MutableStateFlow<List<PresetState>>(emptyList())
    /** Current enabled/disabled state of every coding preset. */
    val presetStates: StateFlow<List<PresetState>> = _presetStates

    // ── Connection ────────────────────────────────────────────────────────────

    /**
     * Open the OBD connection.  Call once on module startup or when the user
     * presses "Connect" in the VCDS screen.
     */
    suspend fun connect() {
        obdConnection.connect()
        _connectionType.value = obdConnection.connectionType
    }

    /** Close the OBD connection and reset state. */
    fun disconnect() {
        obdConnection.disconnect()
        _connectionType.value = ConnectionType.DISCONNECTED
    }

    // ── DTC scanning ──────────────────────────────────────────────────────────

    /**
     * Scan all ECUs for DTCs and update [scanResults].
     * Sets [isScanning] to true for the duration of the scan.
     */
    suspend fun scanAll() {
        _isScanning.value = true
        try {
            _scanResults.value = dtcReader.scanAllECUs()
        } finally {
            _isScanning.value = false
        }
    }

    /**
     * Clear all DTCs on [ecuAddress] and re-scan that ECU.
     *
     * @return `true` if the clear command was acknowledged by the ECU
     */
    suspend fun clearAndRescan(ecuAddress: Int): Boolean {
        val cleared = dtcReader.clearAllDTCs(ecuAddress)
        if (cleared) {
            // Refresh the entry for this ECU in the scan results list
            val ecus = ECUDatabase.LEON_5F_ECUS
            val ecu = ecus.firstOrNull { it.address == ecuAddress }
            if (ecu != null) {
                val fresh = try {
                    val dtcs = dtcReader.scanAllECUs()
                        .firstOrNull { it.ecuAddress == ecuAddress }
                        ?.dtcs ?: emptyList()
                    DTCReader.ScanResult(ecuAddress, ecu.name, dtcs)
                } catch (e: Exception) {
                    DTCReader.ScanResult(ecuAddress, ecu.name, emptyList(), e.message)
                }
                _scanResults.value = _scanResults.value
                    .map { if (it.ecuAddress == ecuAddress) fresh else it }
            }
        }
        return cleared
    }

    // ── Coding presets ────────────────────────────────────────────────────────

    /**
     * Load the current state of all coding presets from the ECU.
     * Updates [presetStates].
     */
    suspend fun loadPresetStates() {
        _presetStates.value = codingPresets.getAllPresets()
    }

    /**
     * Toggle [preset] (enable ↔ disable) and refresh [presetStates].
     *
     * @return `true` if the write succeeded
     */
    suspend fun togglePreset(preset: ECUDatabase.CodingPresetDef): Boolean {
        val success = codingPresets.togglePreset(preset)
        if (success) loadPresetStates()
        return success
    }

    /**
     * Undo the most recent change for [preset] and refresh [presetStates].
     *
     * @return `true` if the revert succeeded
     */
    suspend fun undoPreset(preset: ECUDatabase.CodingPresetDef): Boolean {
        val success = codingPresets.undoPreset(preset)
        if (success) loadPresetStates()
        return success
    }

    // ── Convenience accessors ─────────────────────────────────────────────────

    /** Total DTC count across all scanned ECUs. */
    val totalDTCCount: Int
        get() = _scanResults.value.sumOf { it.dtcs.size }

    /** All DTCs from all ECUs in a single flat list. */
    val allDTCs: List<DTCCode>
        get() = _scanResults.value.flatMap { it.dtcs }

    /** True if any scanned ECU has at least one active DTC. */
    val hasActiveDTCs: Boolean
        get() = _scanResults.value.any { result ->
            result.dtcs.any { dtc -> dtc.status.equals("Active", ignoreCase = true) }
        }
}
