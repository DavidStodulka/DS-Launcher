package com.caros.service

// ─────────────────────────────────────────────────────────────────────────────
//  OilLifeCalculator.kt — Engine oil degradation model for CLHA 1.6 TDI
//
//  Factors that accelerate oil degradation beyond the base 15 000 km interval:
//    • Cold starts       (coolant < 30 °C at session start) → +0.3 % per event
//    • Idle time         (rpm > 0 && speed == 0) → +0.5 % per idle hour
//    • High-temp time    (oilTemp > 110 °C) → +0.05 % per minute above threshold
//    • Short trips       (session distance < 10 km) → +1 % per trip
//
//  lifePct = 1.0 − (kmTraveled / adjustedInterval), clamped to [0, 1]
// ─────────────────────────────────────────────────────────────────────────────

import com.caros.db.CarOSDatabase
import com.caros.db.TelemetrySessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Result of an oil life calculation.
 *
 * @param lifePct                Remaining oil life as a fraction [0.0, 1.0]. 1.0 = fresh.
 * @param recommendedChangeKm    Odometer reading at which oil should be changed next.
 * @param recommendedChangeDate  Unix epoch millis of the projected change date.
 * @param degradationFactors     Named factors and their individual contribution to interval
 *                               shortening in km.
 */
data class OilLifeResult(
    val lifePct: Float,
    val recommendedChangeKm: Int,
    val recommendedChangeDate: Long,
    val degradationFactors: Map<String, Float>
)

@Singleton
class OilLifeCalculator @Inject constructor(
    private val db: CarOSDatabase
) {

    companion object {
        private const val TAG = "OilLifeCalculator"

        /** CLHA TDI base oil change interval in km. */
        private const val BASE_INTERVAL_KM = 15_000f

        /** Coolant temperature threshold for a "cold start" in °C. */
        private const val COLD_START_THRESHOLD = 30f

        /** Oil temperature threshold above which oil degrades faster, in °C. */
        private const val HIGH_OIL_TEMP_THRESHOLD = 110f

        /** Minimum session distance to not count as a short trip, in km. */
        private const val SHORT_TRIP_THRESHOLD_KM = 10.0

        /** Degradation per cold start as a fraction of the base interval. */
        private const val COLD_START_DEGRADATION = 0.003f   // 0.3 %

        /** Degradation per idle hour as a fraction of the base interval. */
        private const val IDLE_HOUR_DEGRADATION = 0.005f    // 0.5 %

        /** Degradation per high-temp minute as a fraction of the base interval. */
        private const val HIGH_TEMP_MIN_DEGRADATION = 0.0005f  // 0.05 %

        /** Degradation per short trip as a fraction of the base interval. */
        private const val SHORT_TRIP_DEGRADATION = 0.01f    // 1 %

        /**
         * Assumed frame capture rate: 1 frame per 500 ms = 2 frames/s.
         * 120 frames/min, 7200 frames/hr.
         */
        private const val FRAMES_PER_MINUTE = 120f
        private const val FRAMES_PER_HOUR   = FRAMES_PER_MINUTE * 60f

        /** Minimum adjusted interval to prevent division-by-zero weirdness. */
        private const val MIN_INTERVAL_KM = 5_000f
    }

    /**
     * Calculates remaining engine oil life from mileage and telemetry-derived factors.
     *
     * @param currentKm      Current vehicle odometer reading in km.
     * @param lastChangeKm   Odometer reading at the last oil change.
     * @param lastChangeDate Unix epoch millis of the last oil change.
     * @return [OilLifeResult] with remaining life percentage, recommended change point,
     *         and per-factor degradation breakdown.
     */
    suspend fun calculateOilLife(
        currentKm: Int,
        lastChangeKm: Int,
        lastChangeDate: Long
    ): OilLifeResult = withContext(Dispatchers.IO) {
        Timber.d("$TAG: calculating oil life — currentKm=$currentKm lastChangeKm=$lastChangeKm")

        val kmTraveled = max(0, currentKm - lastChangeKm).toFloat()

        // Collect all sessions recorded after the last oil change date
        val allSessions: List<TelemetrySessionEntity> = db.telemetrySessionDao()
            .getAllSessions()
            .first()
            .filter { it.startTime >= lastChangeDate }

        var coldStarts     = 0
        var idleFrames     = 0L
        var highTempFrames = 0L
        var shortTrips     = 0

        for (session in allSessions) {
            val frames = db.telemetryFrameDao().getFramesForSessionOnce(session.id)
            if (frames.isEmpty()) continue

            // Cold start: first frame of session where coolant is below threshold
            val firstFrame = frames.first()
            if ((firstFrame.coolantTemp ?: 100f) < COLD_START_THRESHOLD) {
                coldStarts++
            }

            // Idle time: engine running (rpm > 0) but vehicle not moving (speed == 0)
            idleFrames += frames.count { f ->
                (f.rpm ?: 0) > 0 && (f.speedKmh ?: 0f) == 0f
            }

            // High oil temperature time: frames above the high-temp threshold
            highTempFrames += frames.count { f ->
                (f.oilTemp ?: 0f) > HIGH_OIL_TEMP_THRESHOLD
            }

            // Short trip: session ended before covering the minimum beneficial distance
            if (session.distanceKm < SHORT_TRIP_THRESHOLD_KM) {
                shortTrips++
            }
        }

        // Convert raw frame counts to time-based units
        val idleHours       = idleFrames.toFloat()     / FRAMES_PER_HOUR
        val highTempMinutes = highTempFrames.toFloat() / FRAMES_PER_MINUTE

        // Calculate how many km each factor shaves off the base interval
        val coldStartReduction  = coldStarts       * COLD_START_DEGRADATION   * BASE_INTERVAL_KM
        val idleReduction       = idleHours        * IDLE_HOUR_DEGRADATION    * BASE_INTERVAL_KM
        val highTempReduction   = highTempMinutes  * HIGH_TEMP_MIN_DEGRADATION * BASE_INTERVAL_KM
        val shortTripReduction  = shortTrips       * SHORT_TRIP_DEGRADATION   * BASE_INTERVAL_KM

        val totalReduction   = coldStartReduction + idleReduction + highTempReduction + shortTripReduction
        val adjustedInterval = (BASE_INTERVAL_KM - totalReduction).coerceAtLeast(MIN_INTERVAL_KM)

        Timber.d(
            "$TAG: coldStarts=%d idleHrs=%.1f highTempMin=%.0f shortTrips=%d " +
            "totalReduction=%.0f adjustedInterval=%.0f kmTraveled=%.0f",
            coldStarts, idleHours, highTempMinutes, shortTrips,
            totalReduction, adjustedInterval, kmTraveled
        )

        val lifePct       = (1f - (kmTraveled / adjustedInterval)).coerceIn(0f, 1f)
        val remainingKm   = (adjustedInterval - kmTraveled).coerceAtLeast(0f)
        val changeAtKm    = currentKm + remainingKm.toInt()

        // Project calendar date based on recent average daily mileage
        val daysSinceChange = max(
            1L,
            (System.currentTimeMillis() - lastChangeDate) / 86_400_000L
        )
        val avgKmPerDay   = if (daysSinceChange > 0) kmTraveled / daysSinceChange else 1f
        val daysUntilDue  = if (avgKmPerDay > 0f) (remainingKm / avgKmPerDay).toLong() else 365L
        val changeByDate  = System.currentTimeMillis() + daysUntilDue * 86_400_000L

        OilLifeResult(
            lifePct               = lifePct,
            recommendedChangeKm   = changeAtKm,
            recommendedChangeDate = changeByDate,
            degradationFactors    = mapOf(
                "cold_starts_km"  to coldStartReduction,
                "idle_time_km"    to idleReduction,
                "high_temp_km"    to highTempReduction,
                "short_trips_km"  to shortTripReduction
            )
        )
    }
}
