package com.caros.race

// ─────────────────────────────────────────────────────────────────────────────
//  RaceChronoManager.kt — Facade that ties together PerformanceMeasurementEngine,
//  LapTimer, and GForceCalculator into a single entry point.
//  Also handles persisting completed sessions to Room via CarOSDatabase.
// ─────────────────────────────────────────────────────────────────────────────

import android.content.Context
import com.caros.can.CANFrame
import com.caros.db.CarOSDatabase
import com.caros.db.RaceSessionEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RaceChronoManager @Inject constructor(
    /** Performance measurement engine — 0–100, 0–200, 80–120, braking, custom. */
    val performanceEngine: PerformanceMeasurementEngine,
    /** GPS-based lap timer with sector support. */
    val lapTimer: LapTimer,
    /** GPS/accelerometer G-force calculator. */
    val gForce: GForceCalculator,
    private val db: CarOSDatabase,
    @ApplicationContext private val context: Context
) {
    /**
     * Background scope used for any fire-and-forget coroutine work.
     * Uses [SupervisorJob] so individual child failures don't cancel the manager.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── CAN frame routing ─────────────────────────────────────────────────────

    /**
     * Route an incoming [CANFrame] to all active sub-systems.
     * Call this from the CAN service on every decoded frame.
     *
     * @param frame Decoded CAN snapshot from the vehicle bus
     */
    fun processCANFrame(frame: CANFrame) {
        val speedKmh = frame.vehicleSpeed?.kmh ?: 0f
        val coolantCelsius = frame.coolantTemp?.celsius ?: 0f
        val gear = frame.dsgData?.gear ?: "N"
        val boostKpa = frame.boostPressure?.kPa ?: 0f
        val now = frame.timestamp

        performanceEngine.processCANFrame(
            speedKmh = speedKmh,
            coolantTemp = coolantCelsius,
            gear = gear,
            boostKpa = boostKpa,
            timestampMs = now
        )

        // Feed speed as GPS-derived: no bearing update available from CAN alone,
        // so bearing 0f is used — the accelerometer path picks up lateral G instead.
        gForce.processGPSUpdate(
            speedMs = speedKmh / 3.6f,
            bearing = 0f,
            timestampMs = now
        )
    }

    // ── Performance measurement control ───────────────────────────────────────

    /**
     * Arm the performance engine for the given [type] of run.
     *
     * @param type        Which measurement to perform
     * @param customStart Override start speed (km/h) for [MeasurementType.CUSTOM] runs
     * @param customEnd   Override end speed (km/h) for [MeasurementType.CUSTOM] runs
     */
    fun startPerformanceMeasurement(
        type: MeasurementType,
        customStart: Float? = null,
        customEnd: Float? = null
    ) {
        performanceEngine.startMeasurement(type, customStart, customEnd)
    }

    /** Reset the performance engine back to idle and clear the last result. */
    fun resetPerformance() {
        performanceEngine.reset()
    }

    // ── Lap timer control ─────────────────────────────────────────────────────

    /**
     * Set the finish/start line and arm the lap timer.
     *
     * @param lat Latitude of the finish line in decimal degrees
     * @param lon Longitude of the finish line in decimal degrees
     */
    fun startLapTimer(lat: Double, lon: Double) {
        lapTimer.setFinishLine(lat, lon)
    }

    /**
     * Add a sector checkpoint to the lap timer.
     *
     * @param lat Latitude of the sector boundary in decimal degrees
     * @param lon Longitude of the sector boundary in decimal degrees
     */
    fun addLapSector(lat: Double, lon: Double) {
        lapTimer.addSector(lat, lon)
    }

    /** Stop and reset the lap timer. */
    fun stopLapTimer() {
        lapTimer.stop()
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    /**
     * Persist the most recently completed performance measurement to the database.
     * Does nothing if [PerformanceMeasurementEngine.result] is null.
     *
     * @param location  Optional free-text location description
     * @return The new row ID, or -1 if there was no result to save
     */
    suspend fun saveCurrentMeasurement(location: String = ""): Long {
        val result = performanceEngine.result.value ?: return -1L
        return withContext(Dispatchers.IO) {
            db.raceSessionDao().insert(
                RaceSessionEntity(
                    date = result.timestamp,
                    location = location.ifBlank { null },
                    measurementType = result.type.name,
                    resultSeconds = result.durationMs / 1000.0,
                    maxSpeedKmh = result.maxSpeedKmh,
                    avgAccelerationMs2 = result.avgAccelerationMs2,
                    conditions = "${result.startConditions.coolantTemp}°C coolant, " +
                            "gear ${result.startConditions.gear}, " +
                            "${result.startConditions.boostKpa.toInt()} kPa boost"
                )
            )
        }
    }

    /**
     * Persist the best lap of the current lap-timer session to the database.
     *
     * @param location  Optional free-text location description
     * @return The new row ID, or -1 if no laps were completed
     */
    suspend fun saveBestLap(location: String = ""): Long {
        val best = lapTimer.getBestLap() ?: return -1L
        return withContext(Dispatchers.IO) {
            db.raceSessionDao().insert(
                RaceSessionEntity(
                    date = System.currentTimeMillis(),
                    location = location.ifBlank { null },
                    measurementType = "LAP_TIME",
                    resultSeconds = best.timeMs / 1000.0,
                    maxSpeedKmh = best.maxSpeedKmh,
                    avgAccelerationMs2 = null,
                    conditions = "avg speed ${best.avgSpeedKmh.toInt()} km/h, " +
                            "${best.sectors.size} sectors"
                )
            )
        }
    }

    /**
     * Observe all saved race sessions from the database, ordered newest-first.
     */
    fun getSessions() = db.raceSessionDao().getAllSessions()
}
