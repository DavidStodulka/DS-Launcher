package com.caros.vcds

// ─────────────────────────────────────────────────────────────────────────────
//  CodingPresets.kt — Read, toggle, and undo single-bit/single-channel ECU
//  coding changes on the Seat León 5F.
//
//  Every change is logged to [com.caros.db.CodingHistoryEntity] so it can be
//  reviewed and reversed later.
// ─────────────────────────────────────────────────────────────────────────────

import com.caros.db.CarOSDatabase
import com.caros.db.CodingHistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snapshot of a preset's current state for display in the UI.
 *
 * @param preset    The preset definition from [ECUDatabase]
 * @param isEnabled True if the preset's enable value is currently written to the ECU
 * @param canUndo   True if there is a reversible history entry in the database
 */
data class PresetState(
    val preset: ECUDatabase.CodingPresetDef,
    val isEnabled: Boolean,
    val canUndo: Boolean
)

@Singleton
class CodingPresets @Inject constructor(
    private val obdConnection: OBDConnection,
    private val db: CarOSDatabase
) {
    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Read the current value of every preset from the ECU and return their states.
     *
     * Runs on [Dispatchers.IO] — safe to call from a ViewModel / coroutine.
     */
    suspend fun getAllPresets(): List<PresetState> = withContext(Dispatchers.IO) {
        // Single history query shared by all presets (was one query per preset)
        val recentHistory = db.codingHistoryDao().getRecent(limit = 200)
        ECUDatabase.CODING_PRESETS.map { preset ->
            val currentValue = readCurrentValue(preset)
            val isEnabled = currentValue == preset.enableValue
            val ecuAddrStr = "%02X".format(preset.ecuAddress)
            // History check: was there a coding change recorded for this preset's ECU + channel?
            val canUndo = recentHistory.any { entry ->
                entry.ecuAddress == ecuAddrStr &&
                entry.channel == channelLabel(preset) &&
                entry.canBeReverted
            }
            PresetState(preset, isEnabled, canUndo)
        }
    }

    /**
     * Toggle a preset: if it is currently enabled, disable it; if disabled, enable it.
     *
     * Logs the change to [com.caros.db.CodingHistoryDao] regardless of direction.
     *
     * @return `true` if the ECU returned a positive write response, `false` on failure
     */
    suspend fun togglePreset(preset: ECUDatabase.CodingPresetDef): Boolean =
        withContext(Dispatchers.IO) {
            val currentValue = readCurrentValue(preset)
            val newValue = if (currentValue == preset.enableValue) preset.disableValue else preset.enableValue
            val success = writeValue(preset, newValue)

            if (success) {
                db.codingHistoryDao().insert(
                    CodingHistoryEntity(
                        timestamp = System.currentTimeMillis(),
                        ecuAddress = "%02X".format(preset.ecuAddress),
                        channel = channelLabel(preset),
                        oldValue = currentValue,
                        newValue = newValue,
                        description = preset.nameCZ,
                        canBeReverted = !preset.isRisky
                    )
                )
            }
            success
        }

    /**
     * Undo the most recent coding change for [preset] by reverting to the value
     * recorded in the history entry.
     *
     * @return `true` if the revert was applied successfully, `false` if no history
     *         was found or the write failed
     */
    suspend fun undoPreset(preset: ECUDatabase.CodingPresetDef): Boolean =
        withContext(Dispatchers.IO) {
            val ecuAddrStr = "%02X".format(preset.ecuAddress)
            val channelLabel = channelLabel(preset)

            // Find the most recent revertible history entry for this preset
            val history = db.codingHistoryDao()
                .getRecent(limit = 200)
                .firstOrNull { entry ->
                    entry.ecuAddress == ecuAddrStr &&
                    entry.channel == channelLabel &&
                    entry.canBeReverted
                } ?: return@withContext false

            val cmd = buildWriteCommand(preset, history.oldValue)
            val success = obdConnection.sendCommand(cmd)?.let { resp ->
                resp.contains("68", ignoreCase = true) ||
                resp.contains("6E", ignoreCase = true)
            } == true

            if (success) {
                db.codingHistoryDao().delete(history)
            }
            success
        }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Send UDS ReadDataByIdentifier (0x22) to read the current byte/bit value
     * for [preset]'s channel.  Returns [preset.disableValue] as a safe default
     * if the command fails or returns no data.
     */
    private suspend fun readCurrentValue(preset: ECUDatabase.CodingPresetDef): String {
        val channelHex = "%02X".format(preset.channel ?: 0)
        val cmd = "ATSH%02X\r2200%s\r".format(preset.ecuAddress, channelHex)
        val response = obdConnection.sendCommand(cmd)
        // If the response is non-null and non-empty, return the raw trimmed response
        // as the value.  The caller compares it against preset.enableValue / disableValue.
        return response?.trim()?.takeIf { it.isNotBlank() } ?: preset.disableValue
    }

    /**
     * Write a new value for [preset] using UDS WriteDataByIdentifier (0x2E).
     * Returns true if the ECU acknowledged with a positive response byte (0x68).
     */
    private suspend fun writeValue(preset: ECUDatabase.CodingPresetDef, value: String): Boolean {
        val cmd = buildWriteCommand(preset, value)
        return obdConnection.sendCommand(cmd)?.let { resp ->
            resp.contains("68", ignoreCase = true) ||
            resp.contains("6E", ignoreCase = true) // 0x6E = positive response for 0x2E
        } == true
    }

    /**
     * Build a `2E` (WriteDataByIdentifier) command string for the ELM327.
     */
    private fun buildWriteCommand(preset: ECUDatabase.CodingPresetDef, value: String): String {
        val channelHex = "%02X".format(preset.channel ?: 0)
        return "ATSH%02X\r2E00%s%s\r".format(preset.ecuAddress, channelHex, value)
    }

    /**
     * Derive a stable, human-readable channel label for database storage.
     * Format: "CH<nn>" when a numeric channel is set, or "Bit<nn>" for bit-only presets.
     */
    private fun channelLabel(preset: ECUDatabase.CodingPresetDef): String =
        when {
            preset.channel != null     -> "CH%02X".format(preset.channel)
            preset.bitPosition != null -> "Bit%02d".format(preset.bitPosition)
            else                       -> preset.id
        }
}
