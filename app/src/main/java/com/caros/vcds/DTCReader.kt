package com.caros.vcds

// ─────────────────────────────────────────────────────────────────────────────
//  DTCReader.kt — Reads and clears Diagnostic Trouble Codes from all known
//  ECUs on the Seat León 5F via UDS service 0x19 (ReadDTCInformation).
// ─────────────────────────────────────────────────────────────────────────────

import com.caros.can.DTCCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DTCReader @Inject constructor(
    private val obdConnection: OBDConnection
) {
    /**
     * Result of scanning a single ECU.
     *
     * @param ecuAddress  UDS address of the ECU that was queried
     * @param ecuName     Full descriptive name from [ECUDatabase]
     * @param dtcs        List of DTCs found; empty if the ECU is fault-free
     * @param error       Non-null if the scan failed (timeout, no response, etc.)
     */
    data class ScanResult(
        val ecuAddress: Int,
        val ecuName: String,
        val dtcs: List<DTCCode>,
        val error: String? = null
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Scan every ECU in [ECUDatabase.LEON_5F_ECUS] using UDS service 19 02 FF
     * (report DTCs with status mask 0xFF = all).
     *
     * Each ECU is queried sequentially to avoid bus congestion.  Failures are
     * captured per-ECU so a single non-responsive module does not abort the scan.
     *
     * @return One [ScanResult] per ECU, in the same order as [ECUDatabase.LEON_5F_ECUS]
     */
    suspend fun scanAllECUs(): List<ScanResult> = withContext(Dispatchers.IO) {
        ECUDatabase.LEON_5F_ECUS.map { ecu ->
            try {
                val dtcs = readDTCsFromECU(ecu.address)
                ScanResult(ecu.address, ecu.name, dtcs)
            } catch (e: Exception) {
                ScanResult(ecu.address, ecu.name, emptyList(), e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Send UDS ClearDiagnosticInformation (0x14 0xFF 0xFF 0xFF) to [ecuAddress].
     *
     * @return `true` if the ECU returned a positive response, `false` otherwise
     */
    suspend fun clearAllDTCs(ecuAddress: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val cmd = "ATSH%02X\r14FFFFFF\r".format(ecuAddress)
            val response = obdConnection.sendCommand(cmd)
            // Positive response for ClearDTCs is 0x54
            response?.contains("54", ignoreCase = true) == true
        } catch (e: Exception) {
            false
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Address the ELM327 to [address] then request UDS 19 02 FF.
     * Returns the decoded DTC list (may be empty for a clean ECU).
     */
    private suspend fun readDTCsFromECU(address: Int): List<DTCCode> {
        val cmd = "ATSH%02X\r1902FF\r".format(address)
        val response = obdConnection.sendCommand(cmd) ?: return emptyList()
        return parseDTCResponse(response)
    }

    /**
     * Parse the raw ELM327 response for UDS service 0x59 (ReadDTCInformation
     * positive response).
     *
     * Frame layout (after stripping echo):
     * ```
     * 59 02 <status_availability_mask> [<DTC_high> <DTC_low> <DTC_status>] ...
     * ```
     */
    private fun parseDTCResponse(raw: String): List<DTCCode> {
        val dtcs = mutableListOf<DTCCode>()

        for (line in raw.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith(">") || trimmed.startsWith("AT")) continue

            val bytes = trimmed.split(Regex("\\s+"))
                .mapNotNull { token -> token.toIntOrNull(16) }

            // Look for the 0x59 positive-response service ID
            val responseIdx = bytes.indexOf(0x59)
            if (responseIdx < 0) continue

            // DTC records start at offset +3 from the service byte
            var i = responseIdx + 3
            while (i + 2 < bytes.size) {
                val high   = bytes[i]
                val low    = bytes[i + 1]
                val status = bytes[i + 2]
                i += 3

                val code = decodeDTCBytes(high, low)
                val statusText = when {
                    status and 0x01 != 0 -> "Active"
                    status and 0x08 != 0 -> "Pending"
                    else                 -> "Stored"
                }
                dtcs.add(DTCCode(code, getDTCDescription(code), statusText))
            }
        }

        return dtcs
    }

    /**
     * Convert two raw DTC bytes to an SAE-format code string.
     *
     * High byte bits [7:6] encode the system prefix:
     * 0x0 → P (Powertrain), 0x1 → C (Chassis), 0x2 → B (Body), 0x3 → U (Network)
     */
    private fun decodeDTCBytes(high: Int, low: Int): String {
        val prefix = when ((high shr 6) and 0x03) {
            0    -> "P"
            1    -> "C"
            2    -> "B"
            else -> "U"
        }
        val numericPart = ((high and 0x3F) shl 8) or low
        return "$prefix%04X".format(numericPart)
    }

    /**
     * Map well-known DTC codes to human-readable descriptions.
     * Codes not in this map return "Unknown fault code".
     */
    private fun getDTCDescription(code: String): String =
        dtcDescriptions[code.uppercase()] ?: "Unknown fault code"

    // ── DTC description catalogue ─────────────────────────────────────────────

    private val dtcDescriptions: Map<String, String> = mapOf(
        "P0087" to "Fuel Rail/System Pressure — Too Low",
        "P0088" to "Fuel Rail/System Pressure — Too High",
        "P0089" to "Fuel Pressure Regulator Performance",
        "P0100" to "Mass Air Flow Sensor Circuit Malfunction",
        "P0105" to "Manifold Absolute Pressure Circuit Malfunction",
        "P0115" to "Engine Coolant Temperature Circuit Malfunction",
        "P0190" to "Fuel Rail Pressure Sensor Circuit Malfunction",
        "P0234" to "Turbocharger / Supercharger A Overboost Condition",
        "P0299" to "Turbocharger / Supercharger A Underboost Condition",
        "P0300" to "Random / Multiple Cylinder Misfire Detected",
        "P0301" to "Cylinder 1 Misfire Detected",
        "P0302" to "Cylinder 2 Misfire Detected",
        "P0303" to "Cylinder 3 Misfire Detected",
        "P0304" to "Cylinder 4 Misfire Detected",
        "P0380" to "Glow Plug / Heater Circuit A Malfunction",
        "P0381" to "Glow Plug / Heater Indicator Circuit Malfunction",
        "P0401" to "Exhaust Gas Recirculation Flow Insufficient Detected",
        "P0402" to "Exhaust Gas Recirculation Flow Excessive Detected",
        "P0420" to "Catalyst System Efficiency Below Threshold Bank 1",
        "P0470" to "Exhaust Pressure Sensor Malfunction",
        "P0471" to "Exhaust Pressure Sensor Range/Performance",
        "P0480" to "Cooling Fan Relay 1 Control Circuit Malfunction",
        "P0541" to "Intake Air Heater Relay Circuit Low",
        "P0600" to "Serial Communication Link Malfunction",
        "P2002" to "Diesel Particulate Filter Efficiency Below Threshold Bank 1",
        "P2003" to "Diesel Particulate Filter Efficiency Below Threshold Bank 2",
        "P2030" to "Diesel Particulate Filter Heater Control Circuit",
        "P2196" to "O2 Sensor Signal Biased / Stuck Rich Bank 1 Sensor 1",
        "P242F" to "Diesel Particulate Filter Restriction — Ash Accumulation",
        "P2463" to "Diesel Particulate Filter — Soot Accumulation",
        "U0001" to "High Speed CAN Communication Bus",
        "U0100" to "Lost Communication with ECM/PCM",
        "U0121" to "Lost Communication with Anti-Lock Brake System Control Module",
        "U0155" to "Lost Communication with Instrument Panel Cluster (IPC) Control Module"
    )
}
