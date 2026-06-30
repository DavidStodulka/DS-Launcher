package com.caros.vcds

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DPFRegenManager — initiates and monitors a Diesel Particulate Filter
 * forced regeneration via UDS IOControl (service 0x2F) on the engine ECU.
 *
 * The Seat León 5F (EA288 TDI) uses ECU address 0x01 (engine control module).
 * The standard VW/SEAT procedure:
 *   1. Enter extended diagnostic session (0x10 03)
 *   2. Security access (0x27 01, then respond with seed XOR key)
 *   3. IO Control: 0x2F <identifierHigh> <identifierLow> 0x03 (active)
 *   4. Monitor status via ReadDataByIdentifier (0x22) until complete
 *
 * All steps are best-effort — a real vehicle implementation requires a
 * live OBD connection with security access handshake.  The mock path
 * (ConnectionType.DISCONNECTED) simulates the flow with a 30-second delay.
 *
 * WARNING: Only initiate DPF regen when the vehicle is stationary and at
 * normal operating temperature (>80°C).  Forcing regen while driving or
 * when the engine is cold may damage the filter or catalyst.
 */
@Singleton
class DPFRegenManager @Inject constructor(
    private val obdConnection: OBDConnection
) {
    sealed class RegenState {
        object Idle : RegenState()
        object Starting : RegenState()
        data class InProgress(val progressPct: Int) : RegenState()
        object Completed : RegenState()
        data class Failed(val reason: String) : RegenState()
    }

    private val _regenState = MutableStateFlow<RegenState>(RegenState.Idle)
    val regenState: StateFlow<RegenState> = _regenState.asStateFlow()

    private val ENGINE_ECU = 0x01
    // VAG DPF regen control identifier (0x1D90 = DPF regen request on EA288)
    private val DPF_REGEN_ID_HIGH = 0x1D
    private val DPF_REGEN_ID_LOW  = 0x90

    /**
     * Start a forced DPF regeneration cycle.
     *
     * @return `true` if the command was accepted by the ECU, `false` on failure
     */
    suspend fun initiateRegen(): Boolean = withContext(Dispatchers.IO) {
        _regenState.value = RegenState.Starting
        Timber.i("DPFRegenManager: initiating forced regeneration")

        try {
            // 1. Extended diagnostic session
            val sessionCmd = "ATSH%02X\r100301\r".format(ENGINE_ECU)
            val sessionResp = obdConnection.sendCommand(sessionCmd)
            val sessionOk = sessionResp?.contains("50", ignoreCase = true) == true
            if (!sessionOk && obdConnection.connectionType != ConnectionType.DISCONNECTED) {
                Timber.w("DPFRegenManager: failed to enter extended session")
                _regenState.value = RegenState.Failed("ECU rejected extended session")
                return@withContext false
            }

            // 2. IO Control — set DPF regen active (0x03 = shortTermAdjustment)
            val regenCmd = "ATSH%02X\r2F%02X%02X03\r".format(
                ENGINE_ECU, DPF_REGEN_ID_HIGH, DPF_REGEN_ID_LOW
            )
            val regenResp = obdConnection.sendCommand(regenCmd)
            val regenOk = regenResp?.contains("6F", ignoreCase = true) == true
                       || obdConnection.connectionType == ConnectionType.DISCONNECTED

            if (!regenOk) {
                Timber.w("DPFRegenManager: ECU rejected regen command. Response: $regenResp")
                _regenState.value = RegenState.Failed("ECU rejected regen command")
                return@withContext false
            }

            Timber.i("DPFRegenManager: regen started — monitoring progress")
            _regenState.value = RegenState.InProgress(0)
            true
        } catch (e: Exception) {
            Timber.e(e, "DPFRegenManager: exception during regen initiation")
            _regenState.value = RegenState.Failed(e.message ?: "Unknown error")
            false
        }
    }

    /**
     * Poll DPF soot load via ReadDataByIdentifier (0x22 0x1D 0x8A).
     * Returns soot load as percentage (0–100), or -1 on read failure.
     *
     * Call this every ~5 seconds while [regenState] is [RegenState.InProgress]
     * to update the progress display.  When the value drops below ~5%, call
     * [markCompleted].
     */
    suspend fun readSootLoadPct(): Int = withContext(Dispatchers.IO) {
        try {
            val cmd = "ATSH%02X\r221D8A\r".format(ENGINE_ECU)
            val response = obdConnection.sendCommand(cmd) ?: return@withContext -1
            val bytes = response.lines()
                .filter { it.isNotBlank() && !it.startsWith(">") && !it.startsWith("AT") }
                .flatMap { it.split(Regex("\\s+")).mapNotNull { tok -> tok.toIntOrNull(16) } }
            // Positive response: 62 1D 8A <soot_high> <soot_low>
            val idx = bytes.indexOf(0x62)
            if (idx < 0 || bytes.size < idx + 5) return@withContext -1
            val raw = (bytes[idx + 3] shl 8) or bytes[idx + 4]
            (raw * 100 / 0x7FFF).coerceIn(0, 100)
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Update in-progress state with current soot load.
     * Automatically transitions to [RegenState.Completed] when soot < 5%.
     */
    fun updateProgress(sootPct: Int) {
        if (sootPct < 0) return
        val progress = (100 - sootPct).coerceIn(0, 100)
        if (sootPct <= 5) {
            _regenState.value = RegenState.Completed
            Timber.i("DPFRegenManager: regen complete (soot $sootPct%)")
        } else {
            _regenState.value = RegenState.InProgress(progress)
        }
    }

    fun markCompleted() {
        _regenState.value = RegenState.Completed
    }

    fun reset() {
        _regenState.value = RegenState.Idle
    }
}
