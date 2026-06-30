package com.caros.vcds

// ─────────────────────────────────────────────────────────────────────────────
//  TDIDiagnostics.kt — Data model for VAG Mode 22 / UDS extended OBD diagnostics
//  Specific to 1.6 TDI EA288 engine (Seat León 5F, 2012–2020)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Per-cylinder injector quantity adaptation (IQA).
 * Values in mg/stroke — deviation from base injection quantity.
 * Typical range: ±4 mg/stroke. Values > ±2 mg/stroke indicate a worn injector.
 */
data class InjectorCorrection(
    val cyl1Mg: Float,
    val cyl2Mg: Float,
    val cyl3Mg: Float,
    val cyl4Mg: Float
) {
    val maxDeviationMg: Float get() = listOf(cyl1Mg, cyl2Mg, cyl3Mg, cyl4Mg)
        .map { kotlin.math.abs(it) }.max()

    val isBalanced: Boolean get() = maxDeviationMg <= 2.0f
}

/**
 * Exhaust gas temperature sensor readings.
 * Sensors 3 and 4 are nullable — present only on 2.0 TDI variants with OPF.
 * Typical operating range: 150–800 °C. Above 850 °C = overtemperature warning.
 */
data class EGTData(
    val sensor1C: Float,
    val sensor2C: Float,
    val sensor3C: Float? = null,
    val sensor4C: Float? = null
) {
    val maxC: Float get() = listOfNotNull(sensor1C, sensor2C, sensor3C, sensor4C).max()
    val isOvertemp: Boolean get() = maxC > 850f
}

/**
 * Variable Nozzle Turbine (VNT) turbocharger position.
 * Both values 0–100 %. A persistent gap between actual and target > 10 %
 * indicates sticking vanes (common EA288 fault).
 */
data class TurboData(
    val vntActualPct: Float,
    val vntTargetPct: Float
) {
    val vntErrorPct: Float get() = kotlin.math.abs(vntActualPct - vntTargetPct)
    val isSticking: Boolean get() = vntErrorPct > 10f
}

/**
 * EGR (Exhaust Gas Recirculation) valve position.
 * A persistent gap between actual and target > 5 % indicates stuck valve.
 */
data class EGRData(
    val actualPct: Float,
    val targetPct: Float
) {
    val errorPct: Float get() = kotlin.math.abs(actualPct - targetPct)
    val isStuck: Boolean get() = errorPct > 5f
}

/**
 * DPF upstream and downstream temperature.
 * A difference < 20 °C during forced regen indicates blocked filter.
 */
data class DPFThermal(
    val upstreamC: Float,
    val downstreamC: Float
) {
    val deltaC: Float get() = downstreamC - upstreamC
}

/**
 * Glow plug resistance per cylinder in mΩ.
 * New glow plug: ~250–350 mΩ. Failed: > 800 mΩ or < 100 mΩ.
 */
data class GlowPlugData(
    val cyl1MOhm: Float,
    val cyl2MOhm: Float,
    val cyl3MOhm: Float,
    val cyl4MOhm: Float
) {
    fun isFailed(resistance: Float): Boolean = resistance > 800f || resistance < 100f
    val anyFailed: Boolean
        get() = isFailed(cyl1MOhm) || isFailed(cyl2MOhm) ||
                isFailed(cyl3MOhm) || isFailed(cyl4MOhm)
}

/**
 * Complete snapshot of TDI-specific extended OBD diagnostics.
 * All fields are nullable — populated as responses arrive from the ECU.
 */
data class TDIDiagnostics(
    val injectorCorrection: InjectorCorrection? = null,
    val egt: EGTData? = null,
    val turbo: TurboData? = null,
    val egr: EGRData? = null,
    /** Swirl flap position [0–100 %]. Null if not equipped / not responding. */
    val swirlPct: Float? = null,
    val dpfThermal: DPFThermal? = null,
    /** Common rail fuel pressure in bar. Idle ~250 bar, full load ~1800 bar. */
    val fuelRailBar: Float? = null,
    val glowPlugs: GlowPlugData? = null,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val hasData: Boolean
        get() = injectorCorrection != null || egt != null || turbo != null ||
                egr != null || dpfThermal != null || fuelRailBar != null
}
