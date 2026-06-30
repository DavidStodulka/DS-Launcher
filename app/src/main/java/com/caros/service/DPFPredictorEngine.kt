package com.caros.service

// ─────────────────────────────────────────────────────────────────────────────
//  DPFPredictorEngine.kt — Estimates kilometres remaining until the engine
//  triggers an active DPF regeneration on the 1.6 TDI CLHA.
//
//  Active regen is initiated when soot load exceeds ~75 %.
//  Estimate uses the DPFMonitor trend (% / hr) converted to % / km via average
//  urban speed.  When no trend data is available, a default of 0.25 % / km
//  (typical city driving) is used as a conservative fallback.
// ─────────────────────────────────────────────────────────────────────────────

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class DPFPrediction(
    val currentLoadPct: Float,
    /** Estimated km until active regen is triggered. -1 = unknown. 0 = regen imminent. */
    val estimatedKmToRegen: Int,
    /** Human-readable range string, e.g. "~150–200 km". */
    val regenRangeStr: String,
    val recommendation: DPFRecommendation
)

@Singleton
class DPFPredictorEngine @Inject constructor(
    private val dpfMonitor: DPFMonitor
) {
    companion object {
        private const val REGEN_TRIGGER_PCT      = 75f
        /** Conservative city-driving soot rate in % per km. */
        private const val DEFAULT_SOOT_RATE      = 0.25f
        /** Average urban speed used to convert %/hr trend to %/km. */
        private const val AVG_URBAN_SPEED_KMH    = 25f
    }

    suspend fun getPrediction(): DPFPrediction = withContext(Dispatchers.IO) {
        val status    = dpfMonitor.getDPFStatus()
        val remaining = REGEN_TRIGGER_PCT - status.currentLoad

        val sootRatePctPerKm: Float = if (status.trend > 0.01f) {
            status.trend / AVG_URBAN_SPEED_KMH
        } else {
            DEFAULT_SOOT_RATE
        }

        val kmToRegen: Int = when {
            remaining <= 0f -> 0
            sootRatePctPerKm > 0.01f -> (remaining / sootRatePctPerKm).toInt()
            else -> -1
        }

        val rangeStr = when {
            kmToRegen < 0  -> "neznámo"
            kmToRegen == 0 -> "nyní"
            kmToRegen < 30 -> "< 30 km"
            kmToRegen < 100 -> {
                val lo = (kmToRegen / 10) * 10
                "~$lo–${lo + 30} km"
            }
            else -> {
                val lo = (kmToRegen / 50) * 50
                "~$lo–${lo + 50} km"
            }
        }

        Timber.d(
            "DPFPredictorEngine: load=%.1f%% trend=%.2f%%/hr → %d km to regen (%s)",
            status.currentLoad, status.trend, kmToRegen, rangeStr
        )

        DPFPrediction(
            currentLoadPct     = status.currentLoad,
            estimatedKmToRegen = kmToRegen,
            regenRangeStr      = rangeStr,
            recommendation     = status.recommendation
        )
    }
}
