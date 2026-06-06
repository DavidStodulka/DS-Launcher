package com.caros.vcds

import com.caros.can.DTCCode

/**
 * Snapshot of sensor values captured at the moment a DTC was set.
 *
 * @param dtcCode    The fault code this freeze frame belongs to
 * @param parameters Map of parameter name → formatted value string
 *                   e.g. {"RPM" → "1200 rpm", "Speed" → "45 km/h", "Coolant" → "88 °C"}
 */
data class FreezeFrame(
    val dtcCode: DTCCode,
    val parameters: Map<String, String>
)
