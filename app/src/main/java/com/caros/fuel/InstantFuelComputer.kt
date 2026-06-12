package com.caros.fuel

// ─────────────────────────────────────────────────────────────────────────────
//  InstantFuelComputer.kt — Real-time fuel consumption calculator for 1.6 TDI
//
//  Primary path: MAF sensor (g/s) → fuel mass flow via λ correction.
//    Diesel at mixed loads runs λ ≈ 2.5–4.  The effective AFR used here is
//    derived from the stoichiometric AFR (14.5) plus a λ=3 excess air factor
//    giving an effective AFR of ~43 g-air / g-fuel.
//    Fuel mass flow (g/s) = MAF / AFR_eff
//    Fuel volume flow (L/h) = fuel_mass_flow * 3600 / DIESEL_DENSITY
//
//  Fallback: speed + throttle polynomial calibrated for city/highway.
//
//  The computer keeps running trip totals; call resetTrip() on session start.
// ─────────────────────────────────────────────────────────────────────────────

import com.caros.can.CANFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

data class FuelStats(
    /** Instantaneous consumption in L/100 km (99 = engine idling / stopped). */
    val instantLper100km: Float,
    /** Session average consumption in L/100 km. */
    val avgLper100km: Float,
    /** Distance driven this session in km. */
    val tripDistanceKm: Float,
    /** Fuel consumed this session in litres. */
    val tripFuelLitres: Float,
    /** Estimated remaining range in km (based on 55 L tank and avg consumption). */
    val estimatedRangeKm: Int
)

@Singleton
class InstantFuelComputer @Inject constructor() {

    companion object {
        private const val DIESEL_DENSITY_G_PER_L = 832f
        private const val AFR_EFFECTIVE          = 43f    // λ≈3 excess air for diesel
        private const val TANK_LITRES            = 55f    // Seat León 5F
        private const val IDLE_FLOW_LPH          = 0.5f   // idle consumption ~0.5 L/h
    }

    @Volatile private var tripFuelLitres  = 0f
    @Volatile private var tripDistanceKm  = 0f
    @Volatile private var lastTimestampMs = 0L

    private val _stats = MutableStateFlow(
        FuelStats(0f, 0f, 0f, 0f, (TANK_LITRES / 6f * 100f).toInt())
    )
    val stats: StateFlow<FuelStats> = _stats.asStateFlow()

    /**
     * Update the fuel computer from a decoded CAN frame.
     * Should be called once per frame (≈ 2 Hz from CAN, 500 ms intervals).
     */
    @Synchronized
    fun update(frame: CANFrame) {
        val speedKmh = frame.vehicleSpeed?.kmh ?: return
        val now      = frame.timestamp
        val dtSec    = if (lastTimestampMs > 0L) ((now - lastTimestampMs) / 1000f) else 0f
        lastTimestampMs = now

        val fuelLph = calculateFlowLph(frame)

        if (dtSec in 0.05f..5f) {
            val dtH = dtSec / 3600f
            tripFuelLitres += fuelLph * dtH
            tripDistanceKm += speedKmh * dtH
        }

        val instantL100 = if (speedKmh > 1f) (fuelLph / speedKmh) * 100f else 99f
        val avgL100     = if (tripDistanceKm > 0.1f) (tripFuelLitres / tripDistanceKm) * 100f
                          else instantL100.coerceAtMost(30f)

        val usedFraction = tripFuelLitres / TANK_LITRES
        val remaining    = max(0f, TANK_LITRES * (1f - usedFraction))
        val rangeKm      = if (avgL100 > 0f) (remaining / avgL100 * 100f).toInt() else 0

        _stats.value = FuelStats(
            instantLper100km = instantL100.coerceIn(0f, 99f),
            avgLper100km     = avgL100.coerceIn(0f, 30f),
            tripDistanceKm   = tripDistanceKm,
            tripFuelLitres   = tripFuelLitres,
            estimatedRangeKm = rangeKm
        )
    }

    /** Reset trip accumulators at the start of a new driving session. */
    @Synchronized
    fun resetTrip() {
        tripFuelLitres  = 0f
        tripDistanceKm  = 0f
        lastTimestampMs = 0L
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun calculateFlowLph(frame: CANFrame): Float {
        val mafGs = frame.mafRate?.gramsPerSecond
        if (mafGs != null && mafGs > 0.5f) {
            // MAF-based: most accurate for diesel
            val fuelGps = mafGs / AFR_EFFECTIVE
            return (fuelGps * 3600f) / DIESEL_DENSITY_G_PER_L
        }
        // Fallback: speed + load polynomial calibrated to 1.6 TDI
        val speedKmh   = frame.vehicleSpeed?.kmh ?: 0f
        val throttlePct = frame.throttlePosition?.percent ?: 15f
        return estimateFallback(speedKmh, throttlePct)
    }

    /**
     * Empirical model: city ~7 L/100, highway ~5.5 L/100, sport ~9+ L/100.
     * Returns litres per hour.
     */
    private fun estimateFallback(speedKmh: Float, throttlePct: Float): Float {
        if (speedKmh < 1f) return IDLE_FLOW_LPH
        val baseL100      = 4.0f + (speedKmh - 50f).coerceAtLeast(0f) * 0.018f
        val loadAdderL100 = throttlePct * 0.055f
        val l100          = baseL100 + loadAdderL100
        return l100 * speedKmh / 100f
    }
}
