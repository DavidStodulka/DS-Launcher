package com.caros.race

// ─────────────────────────────────────────────────────────────────────────────
//  LapTimer.kt — GPS-based lap timing with sector support for circuit driving.
//  The finish line is defined by a single lat/lon coordinate and a proximity
//  radius.  Each time the vehicle passes within that radius (with a 10-second
//  debounce) a lap is recorded.
// ─────────────────────────────────────────────────────────────────────────────

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Completed lap result.
 *
 * @param lapNumber   Sequential lap number within the session (starts at 1)
 * @param timeMs      Total lap time in milliseconds
 * @param maxSpeedKmh Peak speed recorded during the lap in km/h
 * @param avgSpeedKmh Average speed across all location samples in km/h
 * @param sectors     List of sector split times in milliseconds (may be empty)
 */
data class LapResult(
    val lapNumber: Int,
    val timeMs: Long,
    val maxSpeedKmh: Float,
    val avgSpeedKmh: Float,
    val sectors: List<Long> = emptyList()
)

/** Lifecycle state of the lap timer. */
enum class LapTimerState {
    /** Timer is not active — no finish line set. */
    INACTIVE,
    /** Finish line set, waiting for the first crossing to begin lap 1. */
    ARMED,
    /** Actively timing a lap; no sector points configured or not in a sector. */
    RUNNING,
    /** Actively timing a lap and currently within a sector segment. */
    SECTOR
}

@Singleton
class LapTimer @Inject constructor() {

    // ── Exposed state ─────────────────────────────────────────────────────────

    private val _state = MutableStateFlow(LapTimerState.INACTIVE)
    val state: StateFlow<LapTimerState> = _state

    /** Running elapsed time for the current lap in milliseconds. */
    private val _currentLapMs = MutableStateFlow(0L)
    val currentLapMs: StateFlow<Long> = _currentLapMs

    /** Accumulated list of completed laps in this session, in order. */
    private val _lapResults = MutableStateFlow<List<LapResult>>(emptyList())
    val lapResults: StateFlow<List<LapResult>> = _lapResults

    // ── Configuration ─────────────────────────────────────────────────────────

    private var finishLat = 0.0
    private var finishLon = 0.0

    /**
     * Proximity radius around the finish line coordinate.
     * A crossing is registered when the vehicle is within this many metres.
     */
    private val finishRadius = 50f

    // ── Per-lap accumulators ──────────────────────────────────────────────────

    private var lapStartMs = 0L
    private var lapNumber = 0
    private var maxSpeed = 0f
    private val speedSamples = mutableListOf<Float>()

    /** Debounce: minimum time in ms between two consecutive finish-line crossings. */
    private val crossingDebouncMs = 10_000L
    private var lastCrossed = 0L

    // Sector support
    private val sectors = mutableListOf<Long>()
    private val sectorPoints = mutableListOf<Pair<Double, Double>>()
    private var currentSectorIndex = 0
    private var lastSectorMs = 0L

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Set the finish/start line coordinate and arm the timer.
     * The lap timer transitions to [LapTimerState.ARMED].
     *
     * @param lat Latitude of the finish line in decimal degrees
     * @param lon Longitude of the finish line in decimal degrees
     */
    fun setFinishLine(lat: Double, lon: Double) {
        finishLat = lat
        finishLon = lon
        _state.value = LapTimerState.ARMED
    }

    /**
     * Add a sector checkpoint.  Checkpoints are evaluated in the order they
     * are added.  After all sectors are passed the next crossing becomes a
     * lap completion.
     *
     * @param lat Latitude of the sector boundary in decimal degrees
     * @param lon Longitude of the sector boundary in decimal degrees
     */
    fun addSector(lat: Double, lon: Double) {
        sectorPoints.add(Pair(lat, lon))
    }

    /**
     * Feed a GPS location update to the lap timer.  Should be called at every
     * location fix (typically once per second from GPS).
     *
     * @param lat         Current latitude in decimal degrees
     * @param lon         Current longitude in decimal degrees
     * @param speedKmh    Current speed in km/h
     * @param timestampMs Epoch millis of the GPS fix
     */
    fun processLocation(lat: Double, lon: Double, speedKmh: Float, timestampMs: Long) {
        if (_state.value == LapTimerState.INACTIVE) return

        // Accumulate speed samples for average calculation (bounded — extremely
        // long laps would otherwise grow this list without limit)
        if (speedSamples.size >= 100_000) speedSamples.removeAt(0)
        speedSamples.add(speedKmh)
        if (speedKmh > maxSpeed) maxSpeed = speedKmh

        // Check sector crossings while a lap is in progress
        if ((_state.value == LapTimerState.RUNNING || _state.value == LapTimerState.SECTOR) &&
            sectorPoints.isNotEmpty() &&
            currentSectorIndex < sectorPoints.size
        ) {
            val sector = sectorPoints[currentSectorIndex]
            val distToSector = haversine(lat, lon, sector.first, sector.second)
            if (distToSector < finishRadius) {
                val sectorTime = timestampMs - (if (currentSectorIndex == 0) lapStartMs else lastSectorMs)
                sectors.add(sectorTime)
                lastSectorMs = timestampMs
                currentSectorIndex++
                _state.value = LapTimerState.SECTOR
            }
        }

        // Check finish-line crossing
        val distToFinish = haversine(lat, lon, finishLat, finishLon)
        if (distToFinish < finishRadius && timestampMs - lastCrossed > crossingDebouncMs) {
            when (_state.value) {
                LapTimerState.ARMED -> {
                    // First crossing — begin lap 1
                    lapStartMs = timestampMs
                    lapNumber = 0
                    lastCrossed = timestampMs
                    _state.value = LapTimerState.RUNNING
                }
                LapTimerState.RUNNING, LapTimerState.SECTOR -> {
                    // Lap completed
                    val lapTimeMs = timestampMs - lapStartMs
                    val avgSpeed = if (speedSamples.isNotEmpty()) speedSamples.average().toFloat() else 0f
                    val result = LapResult(
                        lapNumber = ++lapNumber,
                        timeMs = lapTimeMs,
                        maxSpeedKmh = maxSpeed,
                        avgSpeedKmh = avgSpeed,
                        sectors = sectors.toList()
                    )
                    _lapResults.value = _lapResults.value + result

                    // Reset per-lap accumulators
                    maxSpeed = 0f
                    speedSamples.clear()
                    sectors.clear()
                    currentSectorIndex = 0
                    lastSectorMs = timestampMs
                    lapStartMs = timestampMs
                    lastCrossed = timestampMs
                    _state.value = LapTimerState.RUNNING
                }
                else -> { /* INACTIVE — cannot reach here */ }
            }
        }

        // Update rolling lap timer display
        if (_state.value == LapTimerState.RUNNING || _state.value == LapTimerState.SECTOR) {
            _currentLapMs.value = timestampMs - lapStartMs
        }
    }

    // ── Session helpers ───────────────────────────────────────────────────────

    /** Stop the lap timer and return to [LapTimerState.INACTIVE]. */
    fun stop() {
        _state.value = LapTimerState.INACTIVE
        _currentLapMs.value = 0L
    }

    /** Reset the full session — clear all laps and return to [LapTimerState.INACTIVE]. */
    fun reset() {
        stop()
        _lapResults.value = emptyList()
        lapNumber = 0
        maxSpeed = 0f
        speedSamples.clear()
        sectors.clear()
        sectorPoints.clear()
        currentSectorIndex = 0
        lastCrossed = 0L
        lapStartMs = 0L
    }

    /**
     * Return the fastest lap recorded in this session, or `null` if no laps
     * have been completed yet.
     */
    fun getBestLap(): LapResult? = _lapResults.value.minByOrNull { it.timeMs }

    // ── Haversine helper ──────────────────────────────────────────────────────

    /**
     * Calculate the great-circle distance in metres between two WGS-84 coordinates
     * using the Haversine formula.
     */
    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val earthRadiusM = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return (earthRadiusM * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))).toFloat()
    }
}
