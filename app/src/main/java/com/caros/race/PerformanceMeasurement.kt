package com.caros.race

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
//  PerformanceMeasurement.kt — 0–100, 0–200, 80–120, braking, and custom
//  measurement engine driven by CAN-bus speed data.
// ─────────────────────────────────────────────────────────────────────────────

/** Category of performance run to measure. */
enum class MeasurementType {
    ZERO_TO_100,
    ZERO_TO_200,
    OVERTAKE_80_120,
    BRAKING,
    CUSTOM
}

/** Lifecycle state of a single measurement attempt. */
enum class MeasurementState {
    IDLE,
    WAITING_FOR_START,
    MEASURING,
    COMPLETE,
    FAILED
}

/**
 * Vehicle conditions captured at the instant measurement begins.
 * Used for environmental context in results storage.
 */
data class StartConditions(
    val coolantTemp: Float,
    val gear: String,
    val boostKpa: Float,
    val timestamp: Long
)

/**
 * Completed performance measurement result.
 *
 * @param type              The kind of run that was performed
 * @param startSpeed        Speed at which timing began, km/h
 * @param endSpeed          Speed at which timing ended, km/h
 * @param durationMs        Elapsed time in milliseconds
 * @param maxSpeedKmh       Peak speed observed during the run
 * @param avgAccelerationMs2 Average longitudinal acceleration over the run, m/s²
 * @param startConditions   Engine state snapshot at run start
 * @param timestamp         Wall-clock millis of the completed result
 */
data class Measurement(
    val type: MeasurementType,
    val startSpeed: Float,
    val endSpeed: Float,
    val durationMs: Long,
    val maxSpeedKmh: Float,
    val avgAccelerationMs2: Float,
    val startConditions: StartConditions,
    val timestamp: Long = System.currentTimeMillis()
)

@Singleton
class PerformanceMeasurementEngine @Inject constructor() {

    // ── Exposed state ─────────────────────────────────────────────────────────

    private val _state = MutableStateFlow(MeasurementState.IDLE)
    val state: StateFlow<MeasurementState> = _state

    private val _result = MutableStateFlow<Measurement?>(null)
    val result: StateFlow<Measurement?> = _result

    // ── Internal run state ────────────────────────────────────────────────────

    private var currentType = MeasurementType.ZERO_TO_100
    private var targetStartSpeed = 0f
    private var targetEndSpeed = 100f

    private var measureStart = 0L
    private var startSpeed = 0f
    private var maxSpeed = 0f
    private var startConditions: StartConditions? = null

    // Rolling window used both for speed sampling and braking detection
    private val speedSamples = mutableListOf<Pair<Long, Float>>()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Arm the engine for a new measurement run.
     *
     * @param type        Which run type to perform
     * @param customStart Override start speed for [MeasurementType.CUSTOM] runs (km/h)
     * @param customEnd   Override end speed for [MeasurementType.CUSTOM] runs (km/h)
     */
    fun startMeasurement(
        type: MeasurementType,
        customStart: Float? = null,
        customEnd: Float? = null
    ) {
        currentType = type
        targetStartSpeed = customStart ?: when (type) {
            MeasurementType.ZERO_TO_100  -> 0f
            MeasurementType.ZERO_TO_200  -> 0f
            MeasurementType.OVERTAKE_80_120 -> 80f
            MeasurementType.BRAKING      -> -1f   // sentinel: detect braking event
            MeasurementType.CUSTOM       -> customStart ?: 0f
        }
        targetEndSpeed = customEnd ?: when (type) {
            MeasurementType.ZERO_TO_100  -> 100f
            MeasurementType.ZERO_TO_200  -> 200f
            MeasurementType.OVERTAKE_80_120 -> 120f
            MeasurementType.BRAKING      -> 0f
            MeasurementType.CUSTOM       -> customEnd ?: 100f
        }
        speedSamples.clear()
        startConditions = null
        _state.value = MeasurementState.WAITING_FOR_START
    }

    /**
     * Feed a decoded CAN snapshot into the engine.  Call this every time a new
     * speed sample is available (typically 10–20 Hz from CAN).
     *
     * @param speedKmh    Current vehicle speed in km/h
     * @param coolantTemp Coolant temperature in °C
     * @param gear        Current DSG gear string, e.g. "1", "2", "D"
     * @param boostKpa    Turbo boost pressure in kPa
     * @param timestampMs Epoch millis for this sample
     */
    fun processCANFrame(
        speedKmh: Float,
        coolantTemp: Float,
        gear: String,
        boostKpa: Float,
        timestampMs: Long
    ) {
        when (_state.value) {
            MeasurementState.WAITING_FOR_START -> {
                if (currentType == MeasurementType.BRAKING) {
                    // For braking runs we need at least 2 samples to detect sudden decel
                    if (speedSamples.size >= 1) {
                        val prev = speedSamples.last()
                        val dtSec = (timestampMs - prev.first) / 1000f
                        if (dtSec > 0f) {
                            val decelRateKmhS = (prev.second - speedKmh) / dtSec
                            if (decelRateKmhS > 5f) {
                                // More than 5 km/h/s deceleration — braking event started
                                startMeasuringNow(speedKmh, coolantTemp, gear, boostKpa, timestampMs)
                                return
                            }
                        }
                    }
                    // Keep a short rolling window for braking detection
                    speedSamples.add(Pair(timestampMs, speedKmh))
                    if (speedSamples.size > 10) speedSamples.removeAt(0)
                } else {
                    val startThreshold = targetStartSpeed.coerceAtLeast(0f)
                    // Capture start conditions while the vehicle is near the start speed
                    if (speedKmh <= startThreshold + 2f) {
                        startConditions = StartConditions(coolantTemp, gear, boostKpa, timestampMs)
                    }
                    if (speedKmh >= startThreshold && startConditions != null) {
                        startMeasuringNow(speedKmh, coolantTemp, gear, boostKpa, timestampMs)
                    }
                }
            }

            MeasurementState.MEASURING -> {
                speedSamples.add(Pair(timestampMs, speedKmh))
                if (speedKmh > maxSpeed) maxSpeed = speedKmh

                val reachedEnd = speedKmh >= targetEndSpeed
                val brakedToStop = currentType == MeasurementType.BRAKING && speedKmh <= 1f
                if (reachedEnd || brakedToStop) {
                    finishMeasurement(timestampMs)
                }
            }

            else -> { /* IDLE / COMPLETE / FAILED — do nothing */ }
        }
    }

    /** Reset the engine back to [MeasurementState.IDLE] and clear the last result. */
    fun reset() {
        _state.value = MeasurementState.IDLE
        _result.value = null
        speedSamples.clear()
        startConditions = null
        maxSpeed = 0f
        startSpeed = 0f
        measureStart = 0L
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun startMeasuringNow(
        speed: Float,
        coolant: Float,
        gear: String,
        boost: Float,
        ts: Long
    ) {
        measureStart = ts
        startSpeed = speed
        maxSpeed = speed
        startConditions = StartConditions(coolant, gear, boost, ts)
        speedSamples.clear()
        _state.value = MeasurementState.MEASURING
    }

    private fun finishMeasurement(endTs: Long) {
        val durationMs = endTs - measureStart
        // Average acceleration: Δv (in m/s) over Δt (in seconds)
        val deltaVms = ((targetEndSpeed - startSpeed) / 3.6f)
        val avgAccel = if (durationMs > 0L) deltaVms / (durationMs / 1000f) else 0f

        _result.value = Measurement(
            type = currentType,
            startSpeed = startSpeed,
            endSpeed = targetEndSpeed,
            durationMs = durationMs,
            maxSpeedKmh = maxSpeed,
            avgAccelerationMs2 = avgAccel,
            startConditions = startConditions!!
        )
        _state.value = MeasurementState.COMPLETE
    }
}
